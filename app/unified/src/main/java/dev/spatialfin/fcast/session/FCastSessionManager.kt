package dev.spatialfin.fcast.session

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jdtech.jellyfin.fcast.discovery.FCastDiscovery
import dev.jdtech.jellyfin.fcast.protocol.PlayMessage
import dev.jdtech.jellyfin.fcast.sender.FCastCastingController
import dev.jdtech.jellyfin.fcast.sender.FCastReceiver
import dev.jdtech.jellyfin.fcast.sender.PlayMessageBuilder
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

    /** True iff there is a chosen receiver — connection or not. */
    fun hasCastIntent(): Boolean = _pickedReceiver.value != null

    /** True iff a TCP connection is currently established and streaming. */
    fun isCasting(): Boolean = status.value == FCastCastingController.Status.Casting ||
        status.value == FCastCastingController.Status.Connecting

    fun showPicker() {
        _pickerVisible.value = true
        // Pre-warm cache on open if it's stale; the picker will also kick its own scan.
        scope.launch { refreshReceivers(maxAgeMs = DISCOVERY_FRESH_MS) }
    }

    fun hidePicker() {
        _pickerVisible.value = false
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
