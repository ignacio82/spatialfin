package dev.spatialfin.fcast.session

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jdtech.jellyfin.fcast.discovery.FCastDiscovery
import dev.jdtech.jellyfin.fcast.protocol.PlayMessage
import dev.jdtech.jellyfin.fcast.sender.FCastCastingController
import dev.jdtech.jellyfin.fcast.sender.FCastReceiver
import dev.jdtech.jellyfin.fcast.sender.PickerEntry
import dev.jdtech.jellyfin.fcast.sender.PlayMessageBuilder
import dev.jdtech.jellyfin.fcast.sender.probeFCastReceiver
import dev.jdtech.jellyfin.models.SpatialFinEpisode
import dev.jdtech.jellyfin.models.SpatialFinItem
import dev.jdtech.jellyfin.models.SpatialFinMovie
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Process-singleton that owns the active FCast sender session. Wraps [FCastCastingController]
 * with three responsibilities the controller alone doesn't cover:
 *
 *  1. **Receiver intent persistence** — once the user picks a receiver via the global cast icon,
 *     [pickedReceiver] stays set so subsequent play taps route to FCast even though no TCP
 *     connection has been opened yet (FCast can't do a connection-only handshake — first
 *     PlayMessage establishes the stream).
 *  2. **Stream URL resolution off the player thread** — [castSpatialItem] does the same
 *     `getMediaSources` + `getStreamUrl` round-trip the local player would have done, so
 *     library/home surfaces can route a play tap to the receiver without spinning up the local
 *     ExoPlayer Activity.
 *  3. **Discovery cache with a freshness window** — receivers are scanned opportunistically when
 *     a cast affordance enters composition, cached for [DISCOVERY_FRESH_MS], and re-used by the
 *     picker so opening it doesn't always block on a 4-second mDNS round-trip.
 *
 * Lifecycle: this is a Hilt @Singleton; [FCastCastingController.shutdown] is *only* called at
 * process shutdown (the controller's own coroutine scope is process-scoped). Per-screen
 * surfaces must call [stopCast] (idempotent), never [shutdown].
 */
@Singleton
class FCastSessionManager @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val controller: FCastCastingController,
    private val repository: JellyfinRepository,
    private val appPreferences: AppPreferences,
    private val rememberedReceiversStore: RememberedReceiversStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val discoveryMutex = Mutex()

    /** Status passthrough — Idle / Connecting / Casting / Failed. */
    val status: StateFlow<FCastCastingController.Status> = controller.status

    /** Active connection target (set only while a TCP connection exists). */
    val activeReceiver: StateFlow<FCastReceiver?> = controller.activeReceiver

    /** Live remote playback state from the receiver. Null if no cast is active. */
    val remoteState = controller.remoteState

    /**
     * The user-chosen receiver — set as soon as the picker emits a receiver. May be set even
     * when [status] is Idle (the user picked, but no playback has started yet). This is the
     * primary "is the app intent-to-cast?" signal — UI uses it to tint the cast icon and decide
     * whether to show the mini-controller.
     */
    private val _pickedReceiver = MutableStateFlow<FCastReceiver?>(null)
    val pickedReceiver: StateFlow<FCastReceiver?> = _pickedReceiver.asStateFlow()

    /** Picker dialog visibility — single source of truth so any surface can open it. */
    private val _pickerVisible = MutableStateFlow(false)
    val pickerVisible: StateFlow<Boolean> = _pickerVisible.asStateFlow()

    /** Last cached discovery sweep (host:port -> FCastReceiver). */
    private val _cachedReceivers = MutableStateFlow<List<FCastReceiver>>(emptyList())
    val cachedReceivers: StateFlow<List<FCastReceiver>> = _cachedReceivers.asStateFlow()
    private var lastDiscoveryAtMs: Long = 0L
    private var inflightDiscovery: Job? = null

    /**
     * The picker reads this to render the unified list — remembered receivers up front (with
     * Probing → Online/Offline state) plus anything the in-flight mDNS scan adds. Updated by
     * [showPicker].
     */
    private val _pickerEntries = MutableStateFlow<List<PickerEntry>>(emptyList())
    val pickerEntries: StateFlow<List<PickerEntry>> = _pickerEntries.asStateFlow()

    /** True while an mDNS scan is in flight from [showPicker]. */
    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private var pickerJob: Job? = null

    /** True iff there is a chosen receiver — connection or not. */
    fun hasCastIntent(): Boolean = _pickedReceiver.value != null

    /** True iff a TCP connection is currently established and streaming. */
    fun isCasting(): Boolean = status.value == FCastCastingController.Status.Casting ||
        status.value == FCastCastingController.Status.Connecting

    fun showPicker() {
        _pickerVisible.value = true
        pickerJob?.cancel()
        pickerJob = scope.launch { driveDiscoveryAndProbing() }
    }

    fun hidePicker() {
        _pickerVisible.value = false
        pickerJob?.cancel()
        pickerJob = null
    }

    /**
     * Forget a remembered receiver. UI exposes this via long-press / swipe. The picker drops
     * the entry from its current view immediately and the store stops surfacing it next open.
     */
    fun forgetReceiver(host: String, port: Int) {
        scope.launch {
            rememberedReceiversStore.forget(host, port)
            _pickerEntries.update { entries ->
                entries.filterNot { it.receiver.host == host && it.receiver.port == port }
            }
        }
    }

    /**
     * Show remembered receivers immediately, probe each in parallel, and run an mDNS scan in
     * parallel. Each completion path updates [_pickerEntries] in place — so the user sees
     * known names instantly and the status badges flip from Probing to Online/Offline as
     * results arrive without forcing the user to wait for the full 1.5–4s mDNS round-trip.
     */
    private suspend fun driveDiscoveryAndProbing() {
        val remembered = rememberedReceiversStore.load()
        // Snapshot remembered as Probing so the user sees them immediately.
        _pickerEntries.value = remembered.map { r ->
            PickerEntry(
                receiver = FCastReceiver(
                    host = r.host,
                    port = r.port,
                    name = r.name,
                    source = FCastReceiver.Source.Manual,
                ),
                state = PickerEntry.State.Probing,
                lastSeenMs = r.lastSeenMs,
            )
        }
        _isScanning.value = true

        // Probe each remembered entry in its own coroutine so a slow / dead host doesn't block
        // the others. Each updates its row when it completes.
        val probeJobs = remembered.map { r ->
            scope.launch {
                val online = probeFCastReceiver(r.host, r.port)
                _pickerEntries.update { entries ->
                    entries.map { e ->
                        if (e.receiver.host == r.host && e.receiver.port == r.port) {
                            e.copy(
                                state = if (online) PickerEntry.State.Online
                                else PickerEntry.State.Offline,
                                lastSeenMs = if (online) System.currentTimeMillis() else e.lastSeenMs,
                            )
                        } else e
                    }
                }
                if (online) {
                    rememberedReceiversStore.upsert(
                        FCastReceiver(host = r.host, port = r.port, name = r.name),
                    )
                }
            }
        }

        // mDNS scan in parallel. Successful results override probe outcomes (mDNS proves
        // online with the up-to-date display name) and add never-before-seen receivers.
        val scanJob = scope.launch {
            val discovery = FCastDiscovery(appContext)
            val found = runCatching { discovery.browse(DISCOVERY_QUICK_MS) }
                .getOrDefault(emptyList())
            val now = System.currentTimeMillis()
            _cachedReceivers.value = found
            lastDiscoveryAtMs = now
            if (found.isEmpty()) return@launch

            // Persist freshly-seen receivers.
            found.forEach { rememberedReceiversStore.upsert(it, now) }

            _pickerEntries.update { entries ->
                val byKey = entries.associateBy { "${it.receiver.host}:${it.receiver.port}" }.toMutableMap()
                found.forEach { rec ->
                    val key = "${rec.host}:${rec.port}"
                    byKey[key] = PickerEntry(
                        receiver = rec,
                        state = PickerEntry.State.Online,
                        lastSeenMs = now,
                    )
                }
                byKey.values.sortedWith(
                    // Online first, then Probing, then Offline. Within a bucket, freshest first.
                    compareBy<PickerEntry> {
                        when (it.state) {
                            PickerEntry.State.Online -> 0
                            PickerEntry.State.Probing -> 1
                            PickerEntry.State.Offline -> 2
                        }
                    }.thenByDescending { it.lastSeenMs ?: 0L },
                )
            }
        }

        scanJob.join()
        probeJobs.forEach { it.join() }
        _isScanning.value = false
    }

    /**
     * Browse mDNS if the cache is older than [maxAgeMs]. Returns the (possibly cached) list.
     * Held under a mutex so concurrent surfaces don't fan out into multiple multicast scans.
     */
    suspend fun refreshReceivers(maxAgeMs: Long = DISCOVERY_FRESH_MS): List<FCastReceiver> =
        discoveryMutex.withLock {
            val now = System.currentTimeMillis()
            if (now - lastDiscoveryAtMs <= maxAgeMs && _cachedReceivers.value.isNotEmpty()) {
                return@withLock _cachedReceivers.value
            }
            val discovery = FCastDiscovery(appContext)
            val results = runCatching { discovery.browse(DISCOVERY_QUICK_MS) }
                .getOrDefault(emptyList())
            if (results.isNotEmpty() || lastDiscoveryAtMs == 0L) {
                _cachedReceivers.value = results
                lastDiscoveryAtMs = now
            }
            results
        }

    /**
     * Mark [receiver] as the user's chosen target and persist its host:port so the next picker
     * opening can pre-select it. Does not open a TCP connection — that happens on the next
     * [castSpatialItem] / [castFromActivePlayer] call.
     */
    fun pickReceiver(receiver: FCastReceiver) {
        _pickedReceiver.value = receiver
        appPreferences.setValue(
            appPreferences.lastUsedFCastReceiverHostPort,
            "${receiver.host}:${receiver.port}",
        )
        // Manual host:port entries also get remembered so the next picker open shows them
        // up front without re-typing.
        scope.launch { rememberedReceiversStore.upsert(receiver) }
    }

    /** Pre-select hint for the picker — host:port string of the last user pick, if any. */
    fun lastUsedReceiverHostPort(): String? =
        appPreferences.getValue(appPreferences.lastUsedFCastReceiverHostPort)?.takeIf { it.isNotBlank() }

    /**
     * Resolve the stream URL for [item] via the same JellyfinRepository path the local player
     * would have used, then send a PlayMessage to [pickedReceiver]. Returns true if the cast
     * started (or is starting), false if the item isn't castable or no receiver is picked.
     *
     * Bitrate / quality follow Jellyfin Web's "cast = server default transcode" convention:
     * we pass `maxBitrate = null` and pick the first media source.
     */
    suspend fun castSpatialItem(
        item: SpatialFinItem,
        startPositionMs: Long? = null,
    ): Boolean {
        val receiver = _pickedReceiver.value ?: return false
        val itemId = when (item) {
            is SpatialFinMovie -> item.id
            is SpatialFinEpisode -> item.id
            else -> return false
        }
        val title = when (item) {
            is SpatialFinMovie -> item.name
            is SpatialFinEpisode -> item.name
            else -> null
        }
        return withContext(Dispatchers.IO) {
            try {
                val sources = repository.getMediaSources(itemId = itemId, includePath = false)
                val source = sources.firstOrNull() ?: run {
                    Timber.tag(TAG).w("castSpatialItem: no media sources for %s", itemId)
                    return@withContext false
                }
                val url = repository.getStreamUrl(itemId, source.id)
                val container = PlayMessageBuilder.guessContainer(url) ?: "video/mp4"
                val play = PlayMessageBuilder.build(
                    url = url,
                    container = container,
                    positionSeconds = (startPositionMs ?: 0L).coerceAtLeast(0L) / 1000.0,
                    title = title,
                )
                controller.startCast(receiver, play)
                true
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "castSpatialItem failed for %s", itemId)
                false
            }
        }
    }

    /**
     * Send an already-resolved Play message to [receiver]. Used by the in-player cast button
     * which has the URL/container/position from the active ExoPlayer media item.
     */
    suspend fun castFromActivePlayer(
        receiver: FCastReceiver,
        play: PlayMessage,
    ) {
        pickReceiver(receiver)
        controller.startCast(receiver, play)
    }

    suspend fun pause() = controller.pause()
    suspend fun resume() = controller.resume()
    suspend fun seek(seconds: Double) = controller.seek(seconds)

    /**
     * Seek by a relative offset, computed against the last known [remoteState] time. Skips on
     * the receiver's clock — accurate to the most recent PlaybackUpdate we got. No-op if we
     * don't have a known time yet (the receiver hasn't pushed its first update).
     */
    suspend fun seekBy(deltaSeconds: Double) {
        val currentTime = remoteState.value?.time ?: return
        controller.seek((currentTime + deltaSeconds).coerceAtLeast(0.0))
    }

    /**
     * Local intent for what the FCast/ExoPlayer gain should be, 0.0–1.0. We keep a local
     * StateFlow because the spec's VolumeUpdate echo isn't reliably emitted across all receiver
     * implementations. The user nudges this with [adjustVolume]; the receiver's *system* volume
     * (TV remote) is independent and unaffected.
     */
    private val _currentVolume = MutableStateFlow(1.0)
    val currentVolume: StateFlow<Double> = _currentVolume.asStateFlow()

    /** Set absolute FCast/ExoPlayer gain on the receiver. Clamped to 0.0–1.0. */
    suspend fun setVolume(volume: Double) {
        val clamped = volume.coerceIn(0.0, 1.0)
        _currentVolume.value = clamped
        controller.setVolume(clamped)
    }

    /** Bump volume by [delta] (e.g. ±0.1). */
    suspend fun adjustVolume(delta: Double) = setVolume(_currentVolume.value + delta)

    suspend fun setSpeed(speed: Double) = controller.setSpeed(speed)

    /** Tear down the connection AND clear the picked-receiver intent. Idempotent. */
    suspend fun stopCast() {
        controller.stopCast()
        _pickedReceiver.value = null
    }

    private companion object {
        const val TAG = "FCastSession"
        const val DISCOVERY_QUICK_MS = 1_500L
        const val DISCOVERY_FRESH_MS = 30_000L
    }
}
