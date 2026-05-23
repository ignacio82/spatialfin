package dev.spatialfin.fcast.session

import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jdtech.jellyfin.cast.CastAdapterFactory
import dev.jdtech.jellyfin.cast.CastCapability
import dev.jdtech.jellyfin.cast.CastMedia
import dev.jdtech.jellyfin.cast.CastProtocol
import dev.jdtech.jellyfin.cast.CastReceiver
import dev.jdtech.jellyfin.cast.ProtocolAdapter
import dev.jdtech.jellyfin.cast.SubtitleFidelity
import dev.jdtech.jellyfin.cast.discovery.MultiProtocolDiscovery
import dev.jdtech.jellyfin.cast.subtitle.AssStyleDetector
import dev.jdtech.jellyfin.cast.subtitle.SubtitlePolicy
import dev.jdtech.jellyfin.cast.subtitle.SubtitleWarningTracker
import dev.jdtech.jellyfin.cast.toCastReceiver
import dev.jdtech.jellyfin.cast.toFCastReceiver
import dev.jdtech.jellyfin.fcast.discovery.FCastDiscovery
import dev.jdtech.jellyfin.fcast.protocol.PlayMessage
import dev.jdtech.jellyfin.fcast.sender.FCastCastingController
import dev.jdtech.jellyfin.fcast.sender.FCastReceiver
import dev.jdtech.jellyfin.fcast.sender.PickerEntry
import dev.jdtech.jellyfin.fcast.sender.PlayMessageBuilder
import dev.jdtech.jellyfin.fcast.sender.probeFCastReceiver
import dev.jdtech.jellyfin.models.SpatialFinMediaStream
import dev.jdtech.jellyfin.models.SpatialFinEpisode
import dev.jdtech.jellyfin.models.SpatialFinItem
import dev.jdtech.jellyfin.models.SpatialFinMovie
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.spatialfin.fcast.ReceiverAudioCodecs
import dev.spatialfin.fcast.session.calibration.CalibrationOrchestrator
import org.jellyfin.sdk.model.api.MediaStreamType
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
class CastSessionManager @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val controller: FCastCastingController,
    private val repository: JellyfinRepository,
    private val appPreferences: AppPreferences,
    private val rememberedReceiversStore: RememberedReceiversStore,
    private val splitAvController: SplitAvController,
    private val calibrationOrchestrator: CalibrationOrchestrator,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val discoveryMutex = Mutex()

    init {
        startFCastStateBridge()
    }

    /** Status passthrough — Idle / Connecting / Casting / Failed. */
    val status: StateFlow<FCastCastingController.Status> = controller.status

    /** Active connection target (set only while a TCP connection exists). */
    val activeReceiver: StateFlow<FCastReceiver?> = controller.activeReceiver

    /** Live remote playback state from the receiver. Null if no cast is active. */
    val remoteState = controller.remoteState

    val tracksState = controller.tracksState

    /**
     * The user-chosen FCast receiver — set when the picker emits an FCast device. Read by the
     * existing FCast-only paths (`SplitAvController.start`, the inline cast button on the Beam
     * player, etc.) that haven't been migrated to the protocol-agnostic API yet. Stays null
     * when the user picks a Chromecast — those go through [pickedTarget] only.
     */
    private val _pickedReceiver = MutableStateFlow<FCastReceiver?>(null)
    val pickedReceiver: StateFlow<FCastReceiver?> = _pickedReceiver.asStateFlow()

    private val _activeItemTitle = MutableStateFlow<String?>(null)
    val activeItemTitle: StateFlow<String?> = _activeItemTitle.asStateFlow()

    private val _activeItemArtworkUrl = MutableStateFlow<String?>(null)
    val activeItemArtworkUrl: StateFlow<String?> = _activeItemArtworkUrl.asStateFlow()

    /**
     * Protocol-agnostic version of the picked target. Set for *any* receiver the user picks —
     * FCast or Google Cast — and the primary "is the app intent-to-cast?" signal for the UI.
     * The mini-controller and cast icon tint read this so they react to a Chromecast pick
     * exactly the way they react to an FCast pick.
     */
    private val _pickedTarget = MutableStateFlow<CastReceiver?>(null)
    val pickedTarget: StateFlow<CastReceiver?> = _pickedTarget.asStateFlow()

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

    /**
     * Whether the next playback should run in split-A/V mode (XR plays video, picked TV plays
     * audio). Set by the picker when the user toggles "Split A/V"; cleared when the cast ends
     * or the user dismisses split mode. Independent of [pickedReceiver] so both flags can be
     * read together.
     */
    private val _splitAvMode = MutableStateFlow(false)
    val splitAvMode: StateFlow<Boolean> = _splitAvMode.asStateFlow()

    sealed interface CalibrationState {
        data object Idle : CalibrationState
        data class Running(val receiverName: String) : CalibrationState
        data class Success(val latencyMs: Int) : CalibrationState
        data class Failed(val reason: String) : CalibrationState
    }

    private val _calibrationState = MutableStateFlow<CalibrationState>(CalibrationState.Idle)
    val calibrationState: StateFlow<CalibrationState> = _calibrationState.asStateFlow()

    /**
     * Per-receiver calibrated audio latency, keyed by `host:port`. Refreshed from
     * [RememberedReceiversStore] when the picker scans, and after a successful (re)calibration.
     * Used by the picker row to show "Calibrated: 73 ms" + Recalibrate.
     */
    private val _audioLatencies = MutableStateFlow<Map<String, Int>>(emptyMap())
    val audioLatencies: StateFlow<Map<String, Int>> = _audioLatencies.asStateFlow()

    /**
     * Subtitle fidelity for the active cast. Emits at session start (when [castSpatialItem]
     * applies the subtitle policy) and any time the policy reruns. UI surfaces the
     * "Transcoding for subtitle compatibility" chip whenever this is [SubtitleFidelity.Transcoding]
     * and the user hasn't hidden the indicator in settings.
     *
     * Defaults to [SubtitleFidelity.None] when no cast is active so observers can read it
     * unconditionally without a null check.
     */
    private val _subtitleFidelity = MutableStateFlow(SubtitleFidelity.None)
    val subtitleFidelity: StateFlow<SubtitleFidelity> = _subtitleFidelity.asStateFlow()

    /**
     * Tracks which receivers have already been warned about subtitle-fidelity degradation in
     * the current process. Pure Kotlin helper so the dedup rule is independently testable —
     * see `SubtitleWarningTrackerTest`. Reset on process death by design (mDNS may have placed
     * a different physical device at the same host:port).
     */
    private val subtitleWarningTracker = SubtitleWarningTracker()

    /**
     * Per-session subtitle warning the UI surfaces as a one-time toast. Set to a non-null
     * receiver display name when a styled ASS track was about to be cast to a receiver that
     * lacks libass and the user picked "Faster start" in settings. UI consumes via
     * [consumeSubtitleDegradationWarning] so the value clears on read.
     */
    private val _pendingSubtitleDegradationWarning = MutableStateFlow<String?>(null)
    val pendingSubtitleDegradationWarning: StateFlow<String?> =
        _pendingSubtitleDegradationWarning.asStateFlow()

    /** UI calls this after rendering the warning toast so it doesn't fire again this session. */
    fun consumeSubtitleDegradationWarning() {
        _pendingSubtitleDegradationWarning.value = null
    }

    private val _pendingCastError = MutableStateFlow<String?>(null)
    val pendingCastError: StateFlow<String?> = _pendingCastError.asStateFlow()

    fun consumeCastError() {
        _pendingCastError.value = null
    }

    /**
     * Whether the "Transcoding for subtitle compatibility" chip should be shown when
     * [subtitleFidelity] is [SubtitleFidelity.Transcoding]. Wraps the [AppPreferences] lookup so
     * Compose callers don't need to import the preferences module.
     */
    fun shouldShowTranscodingIndicator(): Boolean =
        appPreferences.getValue(appPreferences.castShowTranscodingIndicator)

    /** Per-protocol visibility flags read by the picker UI. PR 6. */
    fun shouldShowProtocol(protocol: CastProtocol): Boolean = when (protocol) {
        CastProtocol.FCast -> appPreferences.getValue(appPreferences.castShowFCast)
        CastProtocol.GoogleCast -> appPreferences.getValue(appPreferences.castShowGoogleCast)
        CastProtocol.AirPlay -> appPreferences.getValue(appPreferences.castShowAirPlay)
    }

    /**
     * Per-host:port post-handshake capability cache. Populated by [observePeerCapabilities]
     * the first time a real cast completes its FCast Initial handshake with that receiver, and
     * read by [castSpatialItem] on subsequent casts to decide whether to burn in styled ASS
     * subtitles. Process-scoped: forgets on app restart (mDNS may have placed a different
     * device at the same host:port).
     */
    private val peerCapabilityCache = mutableMapOf<String, Set<CastCapability>>()

    /**
     * Active non-FCast adapter slot. Lazily created when [castSpatialItem] is invoked on a
     * picked Google Cast / AirPlay receiver, torn down by [releaseActiveCastAdapter] on pick
     * switch or [stopCast]. FCast still runs through [controller] for now — the FCast adapter
     * is a wrapper around the same controller, so wiring two paths to one controller would
     * fight over its connection.
     */
    private var activeCastAdapter: ProtocolAdapter? = null
    private val castAdapterMutex = Mutex()
    private var castAdapterEventJob: Job? = null

    /**
     * Protocol-agnostic playback state for the active session. Reads from FCast's
     * [remoteState] when an FCast session is active, and from the [ProtocolAdapter] event
     * stream when a Cast/AirPlay session is active. The mini-controller observes these so its
     * play/pause/scrub/volume work uniformly regardless of which protocol is driving the cast.
     */
    private val _activeMediaState = MutableStateFlow(
        dev.jdtech.jellyfin.cast.CastMediaState.Idle,
    )
    val activeMediaState: StateFlow<dev.jdtech.jellyfin.cast.CastMediaState> =
        _activeMediaState.asStateFlow()

    private val _activePositionMs = MutableStateFlow(0L)
    val activePositionMs: StateFlow<Long> = _activePositionMs.asStateFlow()

    private val _activeDurationMs = MutableStateFlow<Long?>(null)
    val activeDurationMs: StateFlow<Long?> = _activeDurationMs.asStateFlow()

    private val _activeVolume = MutableStateFlow(1f)
    val activeVolume: StateFlow<Float> = _activeVolume.asStateFlow()

    /**
     * Audio-format the receiver reports it's actually decoding right now (Dolby Atmos / EAC3 /
     * AAC / PCM / etc.). SpatialFin extension on the FCast beacon — null for non-SpatialFin
     * receivers and for the brief window after Play before ExoPlayer's tracks resolve. The
     * mini-controller renders this as a one-line "Audio · <label>" under the receiver name.
     */
    private val _activeAudioFormat =
        MutableStateFlow<dev.jdtech.jellyfin.fcast.protocol.AudioFormatInfo?>(null)
    val activeAudioFormat: StateFlow<dev.jdtech.jellyfin.fcast.protocol.AudioFormatInfo?> =
        _activeAudioFormat.asStateFlow()

    /**
     * Sender-side split-A/V audio route. This tells the mini-controller whether we preserved
     * the original bitstream or asked Jellyfin for a compatible audio transcode.
     */
    private val _activeAudioRoute = MutableStateFlow<SplitAvAudioRouteInfo?>(null)
    val activeAudioRoute: StateFlow<SplitAvAudioRouteInfo?> =
        _activeAudioRoute.asStateFlow()

    /**
     * Process-scoped set of `host:port` receivers that have reported "not supported" for an
     * audio codec we tried to direct-play. The URL builder reads this to auto-fallback to
     * AAC transcode on the *next* cast to that receiver — so the user gets best-quality
     * passthrough on a Dolby-capable setup AND working audio on a non-passthrough setup
     * without having to flip any settings.
     *
     * Memory-only by design: receiver capabilities can change (cable swap, soundbar firmware
     * update, HDMI audio mode toggled in OS settings) so we re-learn on every process start.
     */
    private val unsupportedAudioReceivers = java.util.Collections.synchronizedSet(
        mutableSetOf<String>(),
    )

    /**
     * Per-`host:port` cache of the receiver's *authoritative* passthrough-capable audio codec
     * tokens, latched from the SpatialFin `supportedAudioCodecs` beacon extension. This — not
     * a hardcoded codec table — is what drives the split-A/V direct-stream vs transcode
     * decision, so one build is correct on a TrueHD AVR, a DD+/Atmos soundbar, and a PCM-only
     * TV. Survives across casts within a process (a receiver's chain doesn't change without a
     * reconfigure); seeds the *next* split-A/V cast's decision before any beacon and lets the
     * current one self-correct once the first beacon lands.
     */
    private val receiverAudioCaps = java.util.Collections.synchronizedMap(
        mutableMapOf<String, List<String>>(),
    )

    /**
     * One-shot user notice (mini-controller / toast) emitted when split-A/V had to fall back
     * to a server audio transcode because the picked receiver's chain can't render the source
     * codec (e.g. TrueHD on a DD+ soundbar). Replaces the old silent failure. Consumed by the
     * UI then cleared via [consumeAudioTranscodeNotice].
     */
    private val _pendingAudioTranscodeNotice = MutableStateFlow<String?>(null)
    val pendingAudioTranscodeNotice: StateFlow<String?> =
        _pendingAudioTranscodeNotice.asStateFlow()

    fun consumeAudioTranscodeNotice() {
        _pendingAudioTranscodeNotice.value = null
    }

    /**
     * The most recent split-A/V request, stashed so a beacon that resolves the receiver's audio
     * capabilities can transparently re-cast to the best route: transcode if the direct stream
     * is unsupported, or direct stream if an initially-conservative transcode was unnecessary.
     * [recastDone] guards against a re-cast loop and is reset on every fresh user-initiated cast.
     */
    private data class SplitAvRequest(
        val item: dev.jdtech.jellyfin.models.SpatialFinItem,
        val localPlayerIntentBuilder: () -> Intent?,
        val sourceAudioCodec: String?,
        val wasDirectStream: Boolean,
        val receiverKey: String,
        val receiverMediaStartOffsetMs: Long,
        val fallbackMode: SplitAvAudioRoutePolicy.FallbackMode,
    )

    @Volatile
    private var lastSplitAvRequest: SplitAvRequest? = null

    @Volatile
    private var recastDone: Boolean = false

    /**
     * Most-recent latest cached Google Cast results. Surfaced to the picker UI so users can
     * pick Chromecasts. Populated by [driveDiscoveryAndProbing]; cleared along with the FCast
     * cache when discovery starts a fresh sweep.
     */
    private val _googleCastReceivers = MutableStateFlow<List<CastReceiver>>(emptyList())
    val googleCastReceivers: StateFlow<List<CastReceiver>> = _googleCastReceivers.asStateFlow()

    /**
     * Most-recent cached AirPlay results. Includes AirPlay-1 video receivers (playable) and
     * AirPlay-2-only audio devices (surfaced for visibility but disabled in the picker until
     * the SRP-6a pairing handshake ships in PR 6).
     */
    private val _airPlayReceivers = MutableStateFlow<List<CastReceiver>>(emptyList())
    val airPlayReceivers: StateFlow<List<CastReceiver>> = _airPlayReceivers.asStateFlow()
    private var peerCapabilityObserverStarted = false

    private fun ensurePeerCapabilityObserver() {
        if (peerCapabilityObserverStarted) return
        peerCapabilityObserverStarted = true
        scope.launch {
            controller.peerInitial.collect { initial ->
                val receiver = controller.activeReceiver.value ?: return@collect
                val key = "${receiver.host}:${receiver.port}"
                val appName = initial.appName?.trim().orEmpty()
                val isSpatialFinPeer = appName.startsWith("SpatialFin", ignoreCase = true)
                peerCapabilityCache[key] = buildSet {
                    if (isSpatialFinPeer) {
                        add(CastCapability.NativeAss)
                        add(CastCapability.EmbeddedFonts)
                    }
                }
                Timber.tag(TAG).i(
                    "peer initial: %s appName=%s spatialFinPeer=%b",
                    key, appName, isSpatialFinPeer,
                )
            }
        }
    }

    /**
     * What we believe the receiver at [host]:[port] supports. Reads the post-handshake cache if
     * we've cast to this host before; otherwise assumes the SpatialFin path (NativeAss +
     * EmbeddedFonts). The optimistic default is the common case — every FCast receiver currently
     * shipping in the wild that talks to SpatialFin *is* SpatialFin. If the assumption is wrong
     * (third-party FCast receiver), the *first* cast renders styled subs as plain dialogue and
     * the cache then corrects course for subsequent casts.
     */
    private fun capabilitiesFor(host: String, port: Int): Set<CastCapability> {
        val key = "$host:$port"
        return peerCapabilityCache[key]
            ?: setOf(CastCapability.NativeAss, CastCapability.EmbeddedFonts)
    }

    /** Read the user's `castSubtitleHandling` setting and map it to [SubtitlePolicy.UserPreference]. */
    private fun subtitleUserPreference(): SubtitlePolicy.UserPreference =
        when (appPreferences.getValue(appPreferences.castSubtitleHandling)) {
            "faster_start" -> SubtitlePolicy.UserPreference.FasterStart
            "off" -> SubtitlePolicy.UserPreference.Off
            else -> SubtitlePolicy.UserPreference.BestFidelity
        }

    /**
     * Convert a Jellyfin subtitle [SpatialFinMediaStream] into the policy's track shape. For
     * external (sideloaded) ASS tracks the JF endpoint URL gives us the .ass file contents — we
     * fetch the first ~8 KB and run [AssStyleDetector] on it. Embedded ASS tracks have no public
     * fetch URL, so we treat them as "potentially styled" and let the policy decide
     * conservatively. Network failure on the fetch also falls back to the conservative path.
     */
    private suspend fun toPolicyTrack(stream: SpatialFinMediaStream): SubtitlePolicy.SubtitleTrack? {
        val index = stream.index ?: return null
        val codec = stream.codec.lowercase().trim()
        val isAss = codec == "ass" || codec == "ssa"
        val externalPath = stream.path
        val isStyled: Boolean? = if (isAss && stream.isExternal && !externalPath.isNullOrBlank()) {
            // External ASS: fetch a slice and run the detector. 8 KB is enough to see ~50
            // dialogue lines on a typical fansub release. Failure → conservative null.
            runCatching { fetchTextSample(externalPath.withJellyfinAuth(), 8 * 1024) }
                .map(AssStyleDetector::isStyled)
                .getOrNull()
        } else {
            null
        }
        return SubtitlePolicy.SubtitleTrack(
            streamIndex = index,
            codec = codec,
            language = stream.language.takeIf { it.isNotBlank() },
            isExternal = stream.isExternal,
            isStyled = isStyled,
        )
    }

    /**
     * Fetch up to [maxBytes] of UTF-8 text from [urlString]. Used by [toPolicyTrack] to sample
     * a sideloaded .ass file so [AssStyleDetector] can classify it. Times out at 5s — if the
     * server is too slow the policy falls back to the conservative "potentially styled" path
     * instead of holding up the cast indefinitely.
     */
    private suspend fun fetchTextSample(urlString: String, maxBytes: Int): String =
        withContext(Dispatchers.IO) {
            val connection = java.net.URL(urlString).openConnection().apply {
                connectTimeout = 5_000
                readTimeout = 5_000
                addRequestProperty("Range", "bytes=0-${maxBytes - 1}")
                addRequestProperty("Accept", "text/plain, text/x-ssa, application/x-subrip")
            }
            connection.getInputStream().use { input ->
                val buffer = ByteArray(maxBytes)
                var read = 0
                while (read < maxBytes) {
                    val n = input.read(buffer, read, maxBytes - read)
                    if (n <= 0) break
                    read += n
                }
                String(buffer, 0, read, Charsets.UTF_8)
            }
        }

    fun setSplitAvMode(enabled: Boolean) {
        Timber.tag(TAG).i("setSplitAvMode: enabled=%b (was %b)", enabled, _splitAvMode.value)
        _splitAvMode.value = enabled
    }

    fun dismissCalibrationResult() {
        _calibrationState.value = CalibrationState.Idle
    }

    /**
     * Manually run calibration for [receiver] and persist the result. Used by the picker's
     * "Recalibrate" affordance after a setup change (new soundbar, different room). The
     * calibration dialog ([SplitAvCalibrationDialog]) renders state transitions while this
     * runs.
     */
    fun recalibrateReceiver(receiver: FCastReceiver) {
        Timber.tag(TAG).i("recalibrateReceiver: receiver=%s:%d", receiver.host, receiver.port)
        scope.launch {
            _calibrationState.value = CalibrationState.Running(receiver.name)
            when (val result = calibrationOrchestrator.calibrate(receiver)) {
                is CalibrationOrchestrator.Result.Success -> {
                    _audioLatencies.update { it + ("${receiver.host}:${receiver.port}" to result.audioLatencyMs) }
                    _calibrationState.value = CalibrationState.Success(result.audioLatencyMs)
                }
                is CalibrationOrchestrator.Result.Failure -> {
                    _calibrationState.value = CalibrationState.Failed(result.reason)
                }
            }
        }
    }

    private fun refreshAudioLatenciesFrom(receivers: List<RememberedReceiver>) {
        _audioLatencies.value = receivers.mapNotNull { r ->
            r.audioLatencyMs?.let { "${r.host}:${r.port}" to it }
        }.toMap()
        receivers.forEach { r ->
            r.supportedAudioCodecs?.takeIf { it.isNotEmpty() }?.let { codecs ->
                receiverAudioCaps["${r.host}:${r.port}"] = codecs
            }
        }
    }

    /** True iff there is a chosen receiver of any protocol — connection or not. */
    fun hasCastIntent(): Boolean = _pickedTarget.value != null

    /** True iff a TCP connection is currently established and streaming. */
    fun isCasting(): Boolean = status.value == FCastCastingController.Status.Casting ||
        status.value == FCastCastingController.Status.Connecting

    fun showPicker() {
        Timber.tag(TAG).i("showPicker: opening cast picker (was visible=%b)", _pickerVisible.value)
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
        refreshAudioLatenciesFrom(remembered)
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
        // Multi-protocol: we now scan FCast + Google Cast in parallel inside a single
        // MultiProtocolDiscovery call; FCast results feed the existing picker entries and
        // Google Cast results land in the parallel _googleCastReceivers flow.
        val scanJob = scope.launch {
            val multi = MultiProtocolDiscovery(appContext)
            val result = runCatching { multi.browse(DISCOVERY_QUICK_MS) }
                .getOrDefault(MultiProtocolDiscovery.Result(emptyList(), emptyList()))
            val now = System.currentTimeMillis()
            // Translate the protocol-agnostic FCast receivers back to the wire type the
            // existing picker code expects.
            val fcastFound = result.fcast.map { it.toFCastReceiver() }
            _cachedReceivers.value = fcastFound
            _googleCastReceivers.value = result.googleCast
            _airPlayReceivers.value = result.airPlay
            lastDiscoveryAtMs = now
            if (fcastFound.isEmpty() && result.googleCast.isEmpty() &&
                result.airPlay.isEmpty()) return@launch

            // Persist freshly-seen FCast receivers (Cast receivers aren't persisted yet — PR 5
            // brings the unified remembered store).
            fcastFound.forEach { rememberedReceiversStore.upsert(it, now) }

            _pickerEntries.update { entries ->
                val byKey = entries.associateBy { "${it.receiver.host}:${it.receiver.port}" }.toMutableMap()
                fcastFound.forEach { rec ->
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
        Timber.tag(TAG).i(
            "pickReceiver: receiver=%s:%d name=%s",
            receiver.host, receiver.port, receiver.name,
        )
        _pickedReceiver.value = receiver
        _pickedTarget.value = receiver.toCastReceiver()
        appPreferences.setValue(
            appPreferences.lastUsedFCastReceiverHostPort,
            "${receiver.host}:${receiver.port}",
        )
        // Manual host:port entries also get remembered so the next picker open shows them
        // up front without re-typing.
        scope.launch { rememberedReceiversStore.upsert(receiver) }
    }

    /**
     * Mark a Google Cast (or future AirPlay) receiver as the chosen target. Same "is the app
     * intent-to-cast?" semantics as [pickReceiver] but routes through the protocol-agnostic
     * adapter pipeline instead of the FCast-specific [controller]. The FCast slot is cleared
     * so the existing FCast call sites don't accidentally try to send to a Chromecast.
     *
     * Does not open the TLS connection here — that happens on the next [castSpatialItem],
     * matching FCast's intent-vs-connection separation.
     */
    fun pickCastReceiver(receiver: CastReceiver) {
        Timber.tag(TAG).i(
            "pickCastReceiver: receiver=%s name=%s protocol=%s",
            receiver.id, receiver.name, receiver.protocol,
        )
        // If the user is switching from FCast → Cast (or Cast → Cast), tear down any active
        // FCast cast first. Otherwise we'd leave the FCast TV playing alongside the new pick.
        if (_pickedReceiver.value != null && receiver.protocol != CastProtocol.FCast) {
            _pickedReceiver.value = null
            scope.launch { runCatching { controller.stopCast() } }
        }
        _pendingCastError.value = null
        // Do not let delayed teardown close an adapter just opened by an immediate Play tap.
        scope.launch { releaseActiveCastAdapterExcept(receiver.id) }
        _pickedTarget.value = receiver
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
        _activeItemTitle.value = item.name
        _activeItemArtworkUrl.value = item.images.primary?.toString() ?: item.images.backdrop?.toString()
        val target = _pickedTarget.value ?: return false
        _pendingCastError.value = null
        _activeAudioRoute.value = null
        // Route by protocol. FCast keeps its existing path (controller + Play wire message);
        // Google Cast goes through the ProtocolAdapter API. Subtitle policy applies to both.
        val started = when (target.protocol) {
            CastProtocol.FCast -> castFCastItem(item, startPositionMs)
            // Cast and AirPlay share the protocol-agnostic adapter pipeline. The subtitle
            // policy inside castViaAdapter does the right thing for each — neither receiver
            // carries NativeAss, so styled ASS routes to BurnIn or Degraded uniformly.
            CastProtocol.GoogleCast,
            CastProtocol.AirPlay,
            -> castViaAdapter(target, item, startPositionMs)
        }
        if (!started && _pendingCastError.value == null) {
            _pendingCastError.value = "Couldn't cast to ${target.name}."
        }
        return started
    }

    private suspend fun castFCastItem(
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
        ensurePeerCapabilityObserver()
        return withContext(Dispatchers.IO) {
            try {
                val sources = repository.getMediaSources(itemId = itemId, includePath = false)
                val source = sources.firstOrNull() ?: run {
                    Timber.tag(TAG).w("castSpatialItem: no media sources for %s", itemId)
                    return@withContext false
                }
                val audioCodec = source.mediaStreams
                    .firstOrNull { it.type == MediaStreamType.AUDIO }?.codec
                val startMs = (startPositionMs ?: 0L).coerceAtLeast(0L)

                // Apply the subtitle policy before building the URL. The policy reads the
                // post-handshake capability cache for this host:port (optimistic SpatialFin
                // default on first cast); on a subsequent cast we get the real answer.
                val subtitleStreams = source.mediaStreams
                    .filter { it.type == MediaStreamType.SUBTITLE }
                val policyTracks = subtitleStreams.mapNotNull { toPolicyTrack(it) }
                val decision = SubtitlePolicy.decide(
                    tracks = policyTracks,
                    receiverCapabilities = capabilitiesFor(receiver.host, receiver.port),
                    userPreference = subtitleUserPreference(),
                )
                applySubtitleFidelityAndWarn(decision, receiver)

                val audioDecision = audioCodecDecision(receiver)
                val url = repository.getStreamUrl(itemId, source.id)
                    .withCastCompatibleCodecs(audioCodec, audioDecision)
                    .withSubtitleBurnIn(decision)
                    .withStartTimeTicks(startMs)
                    .withJellyfinAuth()
                val transcoded = url.contains("audioCodec=", ignoreCase = true) ||
                    url.contains("static=false", ignoreCase = true)
                val container = PlayMessageBuilder.guessContainer(url) ?: "video/mp4"
                val play = PlayMessageBuilder.build(
                    url = url,
                    container = container,
                    positionSeconds = startMs / 1000.0,
                    title = title,
                    sourceAudioCodec = audioCodec,
                    audioTranscoded = transcoded,
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
     * Apply [decision] to the in-session UI state — emit [SubtitleFidelity] for the chip, and
     * queue the one-time warning toast if the user picked "Faster start" and the receiver
     * can't render styled ASS. Reset on each new cast so subsequent receivers re-warn.
     */
    private fun applySubtitleFidelityAndWarn(
        decision: SubtitlePolicy.Decision,
        receiver: FCastReceiver,
    ) {
        val fidelity = SubtitlePolicy.fidelityFor(decision)
        _subtitleFidelity.value = fidelity
        if (decision is SubtitlePolicy.Decision.Degraded &&
            appPreferences.getValue(appPreferences.castShowSubtitleFidelityWarning)) {
            if (subtitleWarningTracker.shouldWarn(receiver.host, receiver.port)) {
                _pendingSubtitleDegradationWarning.value = receiver.name
            }
        }
        Timber.tag(TAG).i(
            "subtitle policy: decision=%s receiver=%s:%d fidelity=%s",
            decision::class.simpleName, receiver.host, receiver.port, fidelity,
        )
    }

    /**
     * Rewrite the stream URL to request a Jellyfin server-side burn-in transcode of the chosen
     * subtitle stream when the policy demands it. Forces `static=false` (so JF spins up the HLS
     * transcoder rather than serving the raw container) and appends `SubtitleStreamIndex=` +
     * `SubtitleMethod=Encode`. For non-burn decisions the URL is passed through unchanged.
     *
     * `SubtitleMethod=Encode` is the Jellyfin server option that bakes the subtitle track into
     * the video pixels — full libass-accurate rendering on the server with no client-side
     * support needed. The trade-off is server CPU and a small startup delay; the
     * SubtitleFidelity.Transcoding chip in the UI tells the user why.
     */
    private fun String.withSubtitleBurnIn(decision: SubtitlePolicy.Decision): String {
        if (decision !is SubtitlePolicy.Decision.BurnIn) return this
        val streamIndex = decision.track.streamIndex
        val withoutStatic = if (contains("static=true", ignoreCase = true)) {
            replace("static=true", "static=false")
        } else {
            this
        }
        val params = buildList {
            if (!withoutStatic.contains("SubtitleStreamIndex=", ignoreCase = true)) {
                add("SubtitleStreamIndex=$streamIndex")
            }
            if (!withoutStatic.contains("SubtitleMethod=", ignoreCase = true)) {
                add("SubtitleMethod=Encode")
            }
        }
        if (params.isEmpty()) return withoutStatic
        val sep = if (withoutStatic.contains('?')) '&' else '?'
        return "$withoutStatic${sep}${params.joinToString("&")}"
    }

    /**
     * Cast a Jellyfin item to a non-FCast receiver via the protocol-agnostic [ProtocolAdapter]
     * API. Same subtitle-policy logic as the FCast path, except Cast receivers never carry
     * [CastCapability.NativeAss] so styled ASS always either burns in (best-fidelity) or
     * degrades (faster-start) — never streams natively.
     */
    private suspend fun castViaAdapter(
        target: CastReceiver,
        item: SpatialFinItem,
        startPositionMs: Long?,
    ): Boolean {
        Timber.tag(TAG).e(
            "castViaAdapter: ENTRY name=%s target=%s:%d proto=%s item=%s",
            target.name, target.host, target.port, target.protocol, item.name,
        )
        val itemId = when (item) {
            is SpatialFinMovie -> item.id
            is SpatialFinEpisode -> item.id
            else -> {
                Timber.tag(TAG).e("castViaAdapter: item type not castable: %s", item::class.simpleName)
                return false
            }
        }
        val title = when (item) {
            is SpatialFinMovie -> item.name
            is SpatialFinEpisode -> item.name
            else -> null
        }
        return withContext(Dispatchers.IO) {
            try {
                Timber.tag(TAG).e("castViaAdapter: fetching media sources for %s", itemId)
                val sources = repository.getMediaSources(itemId = itemId, includePath = false)
                val source = sources.firstOrNull() ?: run {
                    Timber.tag(TAG).e("castViaAdapter: NO media sources for %s", itemId)
                    return@withContext false
                }
                Timber.tag(TAG).e("castViaAdapter: got source path=%s", source.path)
                val audioCodec = source.mediaStreams
                    .firstOrNull { it.type == MediaStreamType.AUDIO }?.codec
                val startMs = (startPositionMs ?: 0L).coerceAtLeast(0L)
                Timber.tag(TAG).e("castViaAdapter: activating adapter for %s:%d", target.host, target.port)
                val adapter = activeCastAdapterFor(target)
                Timber.tag(TAG).e("castViaAdapter: adapter activated OK, capabilities=%s", adapter.currentCapabilities.value)
                // Subtitle policy uses the receiver's *declared* capabilities — Cast receivers
                // never carry NativeAss, so styled ASS lands on BurnIn or Degraded depending
                // on the user's preference.
                val subtitleStreams = source.mediaStreams
                    .filter { it.type == MediaStreamType.SUBTITLE }
                val policyTracks = subtitleStreams.mapNotNull { toPolicyTrack(it) }
                val decision = SubtitlePolicy.decide(
                    tracks = policyTracks,
                    receiverCapabilities = adapter.currentCapabilities.value,
                    userPreference = subtitleUserPreference(),
                )
                applySubtitleFidelityAndWarnFor(decision, target)

                val audioDecision = audioCodecDecision(target.host, target.port)
                val container = source.path.substringAfterLast('.', "").lowercase()
                val isCastOrAirPlay = target.protocol in listOf(CastProtocol.GoogleCast, CastProtocol.AirPlay)
                val receiverSafeAudio = audioCodec?.lowercase()?.trim() in setOf("aac", "mp3")
                val requiresHlsRemux = isCastOrAirPlay && (
                    !container.matches(Regex(".*(mp4|m4v|mov|webm).*")) || !receiverSafeAudio
                )
                Timber.tag(TAG).e("castViaAdapter: container=%s isCastOrAirPlay=%b requiresHlsRemux=%b", container, isCastOrAirPlay, requiresHlsRemux)

                val url: String
                val finalStartMs: Long

                if (requiresHlsRemux) {
                    // This path feeds external receivers, not the FCast/SpatialFin app where
                    // audio passthrough is negotiated. AAC HLS is the common reliable profile.
                    val hlsUrl = repository.getAudioTranscodeStreamUrl(
                        itemId,
                        source.id,
                        listOf("aac"),
                        startMs,
                    )
                    Timber.tag(TAG).e("castViaAdapter: HLS remux URL blank=%b len=%d", hlsUrl.isBlank(), hlsUrl.length)
                    if (hlsUrl.isNotBlank()) {
                        url = hlsUrl.withSubtitleBurnIn(decision).withJellyfinAuth()
                        finalStartMs = 0L // baked into HLS, so receiver should start at 0
                    } else {
                        url = repository.getStreamUrl(itemId, source.id)
                            .withCastCompatibleCodecs(audioCodec, audioDecision)
                            .withSubtitleBurnIn(decision)
                            .withStartTimeTicks(startMs)
                            .withJellyfinAuth()
                        finalStartMs = startMs
                    }
                } else {
                    url = repository.getStreamUrl(itemId, source.id)
                        .withCastCompatibleCodecs(audioCodec, audioDecision)
                        .withSubtitleBurnIn(decision)
                        .withStartTimeTicks(startMs)
                        .withJellyfinAuth()
                    finalStartMs = startMs
                }

                Timber.tag(TAG).e("castViaAdapter: final URL=%s contentType=%s startMs=%d", url.take(200), guessMediaContentType(url, container), finalStartMs)
                val media = CastMedia(
                    url = url,
                    contentType = guessMediaContentType(url, container),
                    title = title,
                    startPositionMs = finalStartMs,
                )
                Timber.tag(TAG).e("castViaAdapter: calling adapter.load()")
                adapter.load(media).onFailure {
                    Timber.tag(TAG).e(it, "castViaAdapter: load() FAILED for %s", target.id)
                    _pendingCastError.value = "Couldn't cast to ${target.name}: ${it.message ?: "load failed"}"
                    return@withContext false
                }
                Timber.tag(TAG).e("castViaAdapter: load() SUCCESS")
                true
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "castViaAdapter: EXCEPTION for %s", itemId)
                _pendingCastError.value = "Couldn't cast to ${target.name}: ${e.message ?: "connection failed"}"
                false
            }
        }
    }

    /**
     * Return the active [ProtocolAdapter] for [target], creating + connecting one if needed.
     * Held under [castAdapterMutex] so concurrent play taps don't race two adapters for the
     * same TLS endpoint.
     */
    private suspend fun activeCastAdapterFor(target: CastReceiver): ProtocolAdapter =
        castAdapterMutex.withLock {
            activeCastAdapter?.takeIf { it.receiver.id == target.id }?.let {
                Timber.tag(TAG).e("activeCastAdapterFor: reusing existing adapter for %s", target.id)
                return@withLock it
            }
            // Receiver changed — tear the old adapter down before opening a new socket.
            Timber.tag(TAG).e("activeCastAdapterFor: creating NEW adapter for %s:%d proto=%s", target.host, target.port, target.protocol)
            activeCastAdapter?.disconnect()
            castAdapterEventJob?.cancel()
            val adapter = CastAdapterFactory.create(target)
            Timber.tag(TAG).e("activeCastAdapterFor: calling adapter.connect() to %s:%d", target.host, target.port)
            adapter.connect().getOrThrow()
            Timber.tag(TAG).e("activeCastAdapterFor: connect() SUCCESS for %s:%d", target.host, target.port)
            activeCastAdapter = adapter
            castAdapterEventJob = scope.launch { bridgeAdapterEvents(adapter) }
            adapter
        }

    /** Drop any active non-FCast adapter. Safe to call when no adapter is active. */
    private suspend fun releaseActiveCastAdapter() {
        castAdapterMutex.withLock {
            castAdapterEventJob?.cancel()
            castAdapterEventJob = null
            activeCastAdapter?.disconnect()
            activeCastAdapter = null
            _activeMediaState.value = dev.jdtech.jellyfin.cast.CastMediaState.Idle
            _activePositionMs.value = 0L
            _activeDurationMs.value = null
        }
    }

    private suspend fun releaseActiveCastAdapterExcept(targetId: String) {
        castAdapterMutex.withLock {
            if (activeCastAdapter?.receiver?.id == targetId) return@withLock
            castAdapterEventJob?.cancel()
            castAdapterEventJob = null
            activeCastAdapter?.disconnect()
            activeCastAdapter = null
            _activeMediaState.value = dev.jdtech.jellyfin.cast.CastMediaState.Idle
            _activePositionMs.value = 0L
            _activeDurationMs.value = null
        }
    }

    suspend fun setTrack(type: Int, trackId: String) {
        if (activeMediaState.value == dev.jdtech.jellyfin.cast.CastMediaState.Idle) return
        // We currently only support FCast for track selection
        if (pickedReceiver.value != null) {
            controller.setTrack(type, trackId)
        }
    }

    /**
     * Push protocol-agnostic events from [adapter] into the unified `active*` flows. Used by
     * the mini-controller to render play/pause/seek/volume uniformly across FCast / Cast /
     * AirPlay. Cancelled by [releaseActiveCastAdapter] when the adapter is replaced.
     */
    private suspend fun bridgeAdapterEvents(adapter: ProtocolAdapter) {
        adapter.events.collect { event ->
            when (event) {
                is dev.jdtech.jellyfin.cast.CastSessionEvent.MediaStateChanged ->
                    _activeMediaState.value = event.state
                is dev.jdtech.jellyfin.cast.CastSessionEvent.PositionChanged ->
                    _activePositionMs.value = event.positionMs
                is dev.jdtech.jellyfin.cast.CastSessionEvent.DurationChanged ->
                    _activeDurationMs.value = event.durationMs
                is dev.jdtech.jellyfin.cast.CastSessionEvent.VolumeChanged ->
                    _activeVolume.value = event.volume
                is dev.jdtech.jellyfin.cast.CastSessionEvent.Ended -> {
                    _activeMediaState.value = dev.jdtech.jellyfin.cast.CastMediaState.Ended
                }
                is dev.jdtech.jellyfin.cast.CastSessionEvent.Error ->
                    _pendingCastError.value =
                        "Couldn't cast to ${adapter.receiver.name}: ${event.reason}"
                else -> Unit
            }
        }
    }

    /**
     * Mirror the FCast [remoteState] into the protocol-agnostic active* flows whenever an
     * FCast session is active. Started once on construction; observes for the lifetime of
     * the manager. Cast/AirPlay sessions go through [bridgeAdapterEvents] instead.
     */
    private fun startFCastStateBridge() {
        scope.launch {
            controller.remoteState.collect { update ->
                if (_pickedTarget.value?.protocol != CastProtocol.FCast) return@collect
                if (update == null) return@collect
                _activeMediaState.value = when (update.playbackState) {
                    dev.jdtech.jellyfin.fcast.protocol.PlaybackState.Playing ->
                        dev.jdtech.jellyfin.cast.CastMediaState.Playing
                    dev.jdtech.jellyfin.fcast.protocol.PlaybackState.Paused ->
                        dev.jdtech.jellyfin.cast.CastMediaState.Paused
                    dev.jdtech.jellyfin.fcast.protocol.PlaybackState.Idle,
                    null,
                    -> dev.jdtech.jellyfin.cast.CastMediaState.Idle
                }
                update.time?.let { _activePositionMs.value = (it * 1000.0).toLong() }
                update.duration?.let { _activeDurationMs.value = (it * 1000.0).toLong() }
                // SpatialFin → SpatialFin: the receiver populates audioFormat on every beacon
                // once ExoPlayer's tracks resolve. Latch the latest non-null value — beacons
                // may transiently omit it during track-change windows, and the mini-controller
                // should keep showing the previously-known codec while that resolves.
                update.audioFormat?.let { format ->
                    _activeAudioFormat.value = format
                    // Auto-fallback: if the receiver's currently-decoded label says "not
                    // supported", remember it. The URL builder reads this on the *next* cast
                    // to that receiver and forces an AAC transcode. The current cast keeps
                    // playing (silently); a future PR could trigger an in-flight re-cast for
                    // a better UX, but the current scope is "make sure the second tap works."
                    if (format.label?.contains("not supported", ignoreCase = true) == true) {
                        controller.activeReceiver.value?.let { rcv ->
                            val key = "${rcv.host}:${rcv.port}"
                            if (unsupportedAudioReceivers.add(key)) {
                                Timber.tag(TAG).w(
                                    "auto-fallback armed for %s — next cast forces AAC transcode " +
                                        "(receiver reported: %s)",
                                    key, format.label,
                                )
                            }
                        }
                    }
                }
                // Latch the receiver's authoritative codec capability and self-correct in
                // either direction. If we direct-streamed something unsupported, downgrade to
                // a compatible transcode. If we initially transcoded because caps were unknown
                // and the beacon proves the receiver can render the source, upgrade back to
                // the original bitstream for the best possible audio quality.
                update.supportedAudioCodecs?.let { codecs ->
                    val rcv = controller.activeReceiver.value ?: return@let
                    val key = "${rcv.host}:${rcv.port}"
                    receiverAudioCaps[key] = codecs
                    scope.launch {
                        rememberedReceiversStore.setSupportedAudioCodecs(rcv.host, rcv.port, codecs)
                    }
                    val req = lastSplitAvRequest
                    if (recastDone || req == null || req.receiverKey != key || !_splitAvMode.value) {
                        return@let
                    }
                    val action = SplitAvAudioRoutePolicy.recastForResolvedCapabilities(
                        wasDirectStream = req.wasDirectStream,
                        sourceAudioCodec = req.sourceAudioCodec,
                        receiverAudioCodecs = codecs,
                        fallbackMode = req.fallbackMode,
                    )
                    if (action == SplitAvAudioRoutePolicy.RecastAction.None) return@let

                    recastDone = true
                    val absolutePositionMs = (_activePositionMs.value + req.receiverMediaStartOffsetMs)
                        .coerceAtLeast(0L)
                    when (action) {
                        SplitAvAudioRoutePolicy.RecastAction.DowngradeToTranscode -> {
                            Timber.tag(TAG).w(
                                "split-A/V self-correct: %s can't render %s (caps=%s) — " +
                                    "re-casting as transcode",
                                key, req.sourceAudioCodec, codecs,
                            )
                        }
                        SplitAvAudioRoutePolicy.RecastAction.UpgradeToDirect -> {
                            _pendingAudioTranscodeNotice.value = null
                            Timber.tag(TAG).i(
                                "split-A/V quality upgrade: %s can render %s (caps=%s) — " +
                                    "re-casting direct",
                                key, req.sourceAudioCodec, codecs,
                            )
                        }
                        SplitAvAudioRoutePolicy.RecastAction.None -> Unit
                    }
                    scope.launch {
                        castSpatialItemSplitAv(
                            item = req.item,
                            startPositionMs = absolutePositionMs,
                            recast = true,
                            localPlayerIntentBuilder = req.localPlayerIntentBuilder,
                            audioRouteOverride = when (action) {
                                SplitAvAudioRoutePolicy.RecastAction.UpgradeToDirect ->
                                    SplitAvAudioRouteInfo.Route.UpgradedToDirect
                                SplitAvAudioRoutePolicy.RecastAction.DowngradeToTranscode ->
                                    SplitAvAudioRouteInfo.Route.DowngradedToTranscode
                                SplitAvAudioRoutePolicy.RecastAction.None -> null
                            },
                        )
                    }
                }
            }
        }
    }

    /**
     * Same as [applySubtitleFidelityAndWarn] but keyed on a protocol-agnostic [CastReceiver]
     * (the FCast variant takes [FCastReceiver]). Kept as a sibling rather than overloading so
     * the FCast call sites keep their typed receiver and the warning tracker dedups on the
     * canonical `host:port` key in both paths.
     */
    private fun applySubtitleFidelityAndWarnFor(
        decision: SubtitlePolicy.Decision,
        receiver: CastReceiver,
    ) {
        val fidelity = SubtitlePolicy.fidelityFor(decision)
        _subtitleFidelity.value = fidelity
        if (decision is SubtitlePolicy.Decision.Degraded &&
            appPreferences.getValue(appPreferences.castShowSubtitleFidelityWarning)) {
            if (subtitleWarningTracker.shouldWarn(receiver.host, receiver.port)) {
                _pendingSubtitleDegradationWarning.value = receiver.name
            }
        }
        Timber.tag(TAG).i(
            "subtitle policy (adapter): decision=%s receiver=%s fidelity=%s",
            decision::class.simpleName, receiver.id, fidelity,
        )
    }

    /** Best-effort container guess from the URL extension or source container. */
    private fun guessMediaContentType(url: String, container: String? = null): String {
        if (url.contains(".m3u8", ignoreCase = true)) return "application/vnd.apple.mpegurl"
        if (container != null) {
            when {
                container.contains("mp4", ignoreCase = true) || container.contains("m4v", ignoreCase = true) -> return "video/mp4"
                container.contains("mkv", ignoreCase = true) || container.contains("matroska", ignoreCase = true) -> return "video/x-matroska"
                container.contains("webm", ignoreCase = true) -> return "video/webm"
                container.contains("ts", ignoreCase = true) -> return "video/mp2t"
                container.contains("mov", ignoreCase = true) -> return "video/quicktime"
                container.contains("avi", ignoreCase = true) -> return "video/x-msvideo"
                container.contains("wmv", ignoreCase = true) -> return "video/x-ms-wmv"
            }
        }
        return when {
            url.contains(".mpd", ignoreCase = true) -> "application/dash+xml"
            url.contains(".mkv", ignoreCase = true) -> "video/x-matroska"
            url.contains(".webm", ignoreCase = true) -> "video/webm"
            url.contains(".ts", ignoreCase = true) -> "video/mp2t"
            else -> "video/mp4"
        }
    }

    /**
     * The Jellyfin SDK's [JellyfinRepository.getStreamUrl] returns a `/Videos/{id}/stream` URL
     * with no embedded authentication — the SDK normally adds an `X-Emby-Token` header on each
     * request. FCast receivers play the URL via plain ExoPlayer / HTTP without those headers,
     * so a stock `getStreamUrl` returns 401 Unauthorized at fetch time and the receiver
     * silently fails to play. Append the access token as `api_key=` (Jellyfin recognises it
     * as an alternative to the header) so the URL is self-authenticating end-to-end.
     */
    private fun String.withJellyfinAuth(): String {
        val token = repository.getAccessToken().takeIf { !it.isNullOrBlank() } ?: return this
        if (contains("api_key=", ignoreCase = true) ||
            contains("ApiKey=", ignoreCase = true)) return this
        val sep = if (contains('?')) '&' else '?'
        return "$this${sep}api_key=$token"
    }

    /**
     * Decide whether the FCast receiver can direct-play [sourceAudioCodec] and rewrite the
     * stream URL accordingly. Direct play preserves quality and minimises CPU/network load on
     * the Jellyfin server; transcoding is only used when we know the codec is not universally
     * supported by Android MediaCodec.
     *
     * Direct-play codecs (Android baseline): aac, mp3, opus, vorbis, flac, ac3, wav, pcm_*.
     * Transcoded to AAC:
     *  - `eac3` / `eac3-joc` — Dolby Digital Plus / Atmos. Android's `c2.dolby.eac3.decoder.eac3`
     *    fails with `MediaCodec$CodecException: Error 0xe` on certain Pixel/Tensor configs even
     *    though the codec is nominally supported. AAC fallback is the only reliable path.
     *  - `dts`, `dts-hd`, `truehd` — patent-encumbered, no Android Open Source decoder, fails
     *    on every consumer phone.
     *  - Anything else we haven't whitelisted (conservative default for unknown codecs is to
     *    transcode rather than serve a file the receiver can't decode).
     */
    /**
     * Per-receiver context used by [withCastCompatibleCodecs] to decide direct-play vs AAC
     * transcode. The user preference is the global override; the receiver host:port lets us
     * check the auto-fallback cache (filled when a previous beacon reported the codec as
     * unsupported on that receiver).
     */
    private data class AudioCodecDecision(
        val mode: SplitAvAudioRoutePolicy.FallbackMode,
        val receiverKey: String,
    )


    private fun audioCodecDecision(receiver: FCastReceiver): AudioCodecDecision =
        audioCodecDecision(receiver.host, receiver.port)

    private fun audioCodecDecision(host: String, port: Int): AudioCodecDecision {
        val mode = when (appPreferences.getValue(appPreferences.castAudioFallback)) {
            "passthrough" -> SplitAvAudioRoutePolicy.FallbackMode.Passthrough
            "transcode_aac" -> SplitAvAudioRoutePolicy.FallbackMode.TranscodeAac
            else -> SplitAvAudioRoutePolicy.FallbackMode.Auto
        }
        return AudioCodecDecision(mode, "$host:$port")
    }

    private fun String.withCastCompatibleCodecs(
        sourceAudioCodec: String?,
        decision: AudioCodecDecision,
    ): String {
        if (!contains("static=true", ignoreCase = true)) return this
        if (contains("audioCodec=", ignoreCase = true)) return this
        // Hard overrides first — settings preference takes precedence over codec heuristics.
        // The "transcode_aac" branch always rewrites; the "passthrough" branch always
        // direct-plays even for codecs we'd normally rewrite (PCM 7.1 etc.) so users with
        // explicit Dolby setups get full fidelity. "auto" falls through to the codec table
        // and the per-receiver auto-fallback cache.
        when (decision.mode) {
            SplitAvAudioRoutePolicy.FallbackMode.TranscodeAac ->
                return replace("static=true", "static=false") + "&audioCodec=aac"
            SplitAvAudioRoutePolicy.FallbackMode.Passthrough -> return this
            SplitAvAudioRoutePolicy.FallbackMode.Auto -> Unit // fall through to codec table + cache check
        }
        // Auto-fallback cache: if a previous cast to this receiver hit an unsupported codec,
        // force AAC for every subsequent cast in this process. The cache is process-scoped so
        // capability changes (cable swap, OS audio setting flip) re-learn on next launch.
        if (decision.receiverKey in unsupportedAudioReceivers) {
            return replace("static=true", "static=false") + "&audioCodec=aac"
        }
        val codec = sourceAudioCodec?.lowercase()?.trim().orEmpty()
        // Direct-play codecs: ones the receiver can either software-decode OR pass through
        // to an HDMI-connected AVR/soundbar.
        //
        // Includes the full Dolby family: AC-3, E-AC-3, E-AC-3 with Atmos (eac3-joc), and
        // TrueHD (TrueHD Atmos shares the same MIME on Media3). Plus DTS. These all
        // passthrough cleanly on the receivers SpatialFin users actually have — Google TV
        // Streamer / Apple TV / typical AVR setups — because the receiver's ExoPlayer hands
        // the encoded bitstream to `DefaultAudioSink`, which routes via HDMI to the soundbar
        // when `AudioCapabilities.supportsEncoding(...)` returns true for the format.
        //
        // The previous list rewrote E-AC-3 / TrueHD / DTS to `audioCodec=aac` (server-side
        // transcode), which both downgraded Atmos to stereo AAC *and* broke the resulting
        // MKV live-transcode stream entirely on some receivers (Streamer reported
        // `groups=0` for the duration). Direct-play here means: the file's original audio
        // bitstream goes straight from Jellyfin to the soundbar.
        //
        // **Compatibility note**: receivers that lack passthrough for these codecs (Pixel
        // speakers, headset audio, Google TV in PCM-only audio mode) will play silent. The
        // `AudioFormatProbe` on the receiver reports `<codec> · not supported` to the
        // sender's mini-controller in that case, so the user has a diagnosis. A future
        // pref will toggle "force AAC transcode" for users without a Dolby chain — for now
        // the default optimizes for the typical user (soundbar / AVR) per the explicit
        // request ("I have a soundbar and want the best possible audio").
        val directPlay = codec in setOf(
            "aac", "mp3", "opus", "vorbis", "flac", "wav",
            "pcm", "pcm_s16le", "pcm_s24le", "pcm_s32le", "pcm_f32le", "pcm_mulaw", "pcm_alaw",
            "ac3", "eac3", "eac3-joc", "ec3", "truehd", "mlp",
            "dts", "dts-hd", "dts-hd-ma", "dts-express", "dca",
        )
        if (directPlay) return this
        return replace("static=true", "static=false") + "&audioCodec=aac"
    }

    /**
     * Append `startTimeTicks=` to a Jellyfin stream URL so the server starts the byte stream
     * at the resume position rather than from byte 0. The receiver-side `EXTRA_START_MS` only
     * issues a client-side `ExoPlayer.setMediaItem(..., startMs)` seek, which on a transcoded
     * stream either fails (segment not yet produced) or forces the receiver to buffer N
     * minutes of unwanted audio. Pushing the offset into the URL makes Jellyfin produce the
     * stream from the right point. Jellyfin tick is 100 ns, so `positionMs * 10_000`.
     */
    private fun String.withStartTimeTicks(positionMs: Long): String {
        if (positionMs <= 0L) return this
        if (contains("startTimeTicks=", ignoreCase = true)) return this
        val sep = if (contains('?')) '&' else '?'
        return "$this${sep}startTimeTicks=${positionMs * 10_000L}"
    }

    /**
     * Send an already-resolved Play message to [receiver]. Used by the in-player cast button
     * which has the URL/container/position from the active ExoPlayer media item.
     */
    suspend fun castFromActivePlayer(
        receiver: FCastReceiver,
        play: PlayMessage,
    ) {
        _activeItemTitle.value = play.metadata?.title
        _activeItemArtworkUrl.value = play.metadata?.thumbnailUrl
        pickReceiver(receiver)
        controller.startCast(receiver, play)
    }

    /**
     * Split-A/V version of [castSpatialItem]. Runs auto-calibration when the picked receiver
     * has no cached audioLatencyMs, then starts the [SplitAvController] (which sends Play with
     * `splitAv.role=AUDIO` to the TV) and launches the local video-master Activity supplied by
     * [localPlayerIntentBuilder].
     *
     * The intent builder is the caller's responsibility because it depends on the form factor —
     * `XrPlayerActivity.createIntent(...splitAvVideoRole = true)` for headset, the equivalent
     * Beam intent for phone. Both must end up with the same `itemId` as this call so the URL
     * resolved by the local player and the URL FCast-Play'd to the TV come from the same
     * Jellyfin source.
     *
     * Returns true on success (split session started, both ends instructed), false otherwise.
     */
    suspend fun castSpatialItemSplitAv(
        item: SpatialFinItem,
        startPositionMs: Long? = null,
        recast: Boolean = false,
        localPlayerIntentBuilder: () -> Intent?,
        audioRouteOverride: SplitAvAudioRouteInfo.Route? = null,
    ): Boolean {
        // Fresh user-initiated cast re-arms the one-shot self-correcting re-cast guard; a
        // re-cast triggered from a beacon must not (it already set recastDone = true).
        if (!recast) {
            recastDone = false
            lastSplitAvRequest = null
            _pendingAudioTranscodeNotice.value = null
            _activeAudioRoute.value = null
        }
        val receiver = _pickedReceiver.value ?: run {
            Timber.tag(TAG).w("castSpatialItemSplitAv: no receiver picked")
            return false
        }
        // Refuse the SplitAv code path for receivers that don't advertise the capability. Today
        // every picked receiver is FCast (the picker is FCast-only), so this never fires; the
        // guard is here so PR 2 / PR 3 (which add Google Cast and AirPlay to the picker) inherit
        // the right behavior without re-deriving it from scratch.
        if (!SplitAvPolicy.isAvailable(receiver.toCastReceiver())) {
            Timber.tag(TAG).w(
                "castSpatialItemSplitAv: receiver %s:%d lacks SplitAv capability — caller should fall back to castSpatialItem()",
                receiver.host, receiver.port,
            )
            return false
        }
        Timber.tag(TAG).i(
            "castSpatialItemSplitAv: ENTRY receiver=%s:%d splitAvMode=%b",
            receiver.host, receiver.port, _splitAvMode.value,
        )
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
                    Timber.tag(TAG).w("castSpatialItemSplitAv: no media sources for %s", itemId)
                    return@withContext false
                }
                val audioCodec = source.mediaStreams
                    .firstOrNull { it.type == MediaStreamType.AUDIO }?.codec
                val startMs = (startPositionMs ?: 0L).coerceAtLeast(0L)
                val recvKey = "${receiver.host}:${receiver.port}"
                val rememberedReceiver = rememberedReceiversStore.load()
                    .firstOrNull { it.host == receiver.host && it.port == receiver.port }
                rememberedReceiver?.supportedAudioCodecs?.takeIf { it.isNotEmpty() }?.let { codecs ->
                    receiverAudioCaps[recvKey] = codecs
                }
                val audioDecision = audioCodecDecision(receiver)
                // Capability-driven decision — never a hardcoded codec table. The receiver
                // advertises exactly what its HDMI/SPDIF chain can render via the
                // `supportedAudioCodecs` beacon extension; we cache it per host:port. We
                // direct-stream (best quality — lossless / Atmos preserved) whenever the chain
                // can render the source codec, and only server-transcode when it genuinely
                // can't. This is correct on a TrueHD AVR, a DD+/Atmos soundbar, and a
                // PCM-only TV with the same build. Settings override the heuristic: "Always
                // direct play" forces direct (full-fidelity Dolby setups), "Always transcode"
                // forces transcode (no-Dolby setups). `caps` is null on the first cast to a
                // never-seen receiver → conservative (only universally-software-decodable
                // codecs direct-stream); the first beacon then self-corrects via the re-cast
                // in [startFCastStateBridge].
                val caps = receiverAudioCaps[recvKey]
                val canDirect = SplitAvAudioRoutePolicy.canDirect(
                    sourceAudioCodec = audioCodec,
                    receiverAudioCodecs = caps,
                    fallbackMode = audioDecision.mode,
                )
                // Behavior of startTimeTicks per path:
                //   - Direct-play (static=true raw container): startTimeTicks is IGNORED — the
                //     file timeline IS absolute, so receiver stream-time 0 == media-time 0;
                //     mediaStartOffsetMs = 0 and the controller's first-play seek aligns it.
                //   - HLS transcode: Jellyfin re-encodes from the PlaybackInfo start offset,
                //     so receiver stream-time 0 == media-time startMs ⇒ mediaStartOffsetMs =
                //     startMs. Do not append startTimeTicks to the returned playlist URL:
                //     ExoPlayer propagates query params to segment requests and Jellyfin
                //     rejects `/hls/.../*.ts?startTimeTicks=...` with HTTP 400.
                val url: String
                val mediaStartOffsetMs: Long
                var transcoded = false
                var transcodeTargetCodec: String? = null
                if (canDirect) {
                    url = repository.getStreamUrl(itemId, source.id).withJellyfinAuth()
                    mediaStartOffsetMs = 0L
                } else {
                    // Best codec the chain CAN render (E-AC-3 → AC-3 → AAC). HLS path mirrors
                    // the proven direct-play stream (master.m3u8 + TS); the legacy
                    // `static=false&audioCodec=` raw-/stream rewrite produced an unreadable
                    // live-transcode container (receiver `groups=0`) and is gone. Bitrate is
                    // unbounded so the only transcode reason is the codec, never bandwidth.
                    val targets = SplitAvAudioRoutePolicy.preferredTranscodeCodecs(caps)
                    val hls = repository.getAudioTranscodeStreamUrl(
                        itemId,
                        source.id,
                        targets,
                        startMs,
                    )
                    if (hls.isBlank()) {
                        url = repository.getStreamUrl(itemId, source.id).withJellyfinAuth()
                        mediaStartOffsetMs = 0L
                        Timber.tag(TAG).w(
                            "split-A/V: transcode URL empty for %s — direct-stream fallback",
                            recvKey,
                        )
                    } else {
                        url = hls.withJellyfinAuth()
                        mediaStartOffsetMs =
                            SplitAvStreamUrlPolicy.receiverMediaStartOffsetMs(hls, startMs)
                        transcoded = true
                        transcodeTargetCodec = targets.firstOrNull() ?: "aac"
                        _pendingAudioTranscodeNotice.value =
                            "${receiver.name} can't play " +
                                "${ReceiverAudioCodecs.normalize(audioCodec).ifEmpty { "this" }} " +
                                "audio — streaming a compatible track " +
                                "($transcodeTargetCodec)."
                    }
                }
                Timber.tag(TAG).i(
                    "castSpatialItemSplitAv: source audio codec=%s transcoded=%b caps=%s startMs=%d mediaStartOffsetMs=%d",
                    audioCodec, transcoded, caps, startMs, mediaStartOffsetMs,
                )
                val container = PlayMessageBuilder.guessContainer(url) ?: "video/mp4"

                // 1. Calibrate audio latency if we don't have a cached value for this receiver.
                var audioLatencyMs = rememberedReceiver?.audioLatencyMs
                if (audioLatencyMs == null) {
                    _calibrationState.value = CalibrationState.Running(receiver.name)
                    when (val result = calibrationOrchestrator.calibrate(receiver)) {
                        is CalibrationOrchestrator.Result.Success -> {
                            audioLatencyMs = result.audioLatencyMs
                            _calibrationState.value = CalibrationState.Success(result.audioLatencyMs)
                            _audioLatencies.update {
                                it + ("${receiver.host}:${receiver.port}" to result.audioLatencyMs)
                            }
                        }
                        is CalibrationOrchestrator.Result.Failure -> {
                            Timber.tag(TAG).w("castSpatialItemSplitAv: calibration failed: %s", result.reason)
                            _calibrationState.value = CalibrationState.Failed(result.reason)
                            return@withContext false
                        }
                    }
                }

                val play = PlayMessageBuilder.build(
                    url = url,
                    container = container,
                    // HLS transcode URLs already start the byte stream at startMs because the
                    // PlaybackInfo request included startTimeTicks. The returned playlist URL
                    // itself must stay clean, otherwise ExoPlayer propagates that query to TS
                    // segments and Jellyfin returns HTTP 400. Sending position 0 avoids
                    // double-skipping inside the receiver's offset stream.
                    positionSeconds = 0.0,
                    title = title,
                    sourceAudioCodec = audioCodec,
                    audioTranscoded = transcoded,
                )

                // Stash for the self-correcting re-cast only after calibration is done.
                // Calibration uses the same FCast controller and emits beacons too; setting
                // this earlier could make a chirp-session beacon trigger a movie re-cast.
                lastSplitAvRequest = SplitAvRequest(
                    item = item,
                    localPlayerIntentBuilder = localPlayerIntentBuilder,
                    sourceAudioCodec = audioCodec,
                    wasDirectStream = canDirect,
                    receiverKey = recvKey,
                    receiverMediaStartOffsetMs = mediaStartOffsetMs,
                    fallbackMode = audioDecision.mode,
                )
                _activeAudioRoute.value = SplitAvAudioRouteInfo(
                    route = audioRouteOverride ?: if (transcoded) {
                        SplitAvAudioRouteInfo.Route.Transcoded
                    } else {
                        SplitAvAudioRouteInfo.Route.Direct
                    },
                    sourceAudioCodec = audioCodec,
                    targetAudioCodec = transcodeTargetCodec,
                )

                // 2. Tell the TV to start audio-only playback. audioLatencyMs has been
                // smart-cast to non-null by the calibration branch above. mediaStartOffsetMs
                // is the absolute media-time at which the receiver's stream-time 0 begins —
                // computed above based on direct-play vs transcoded path. The controller uses
                // it both to seek-align the receiver on first master-play and to translate
                // beacon stream-times back to absolute for the drift policy.
                splitAvController.start(receiver, play, audioLatencyMs, mediaStartOffsetMs)

                // 3. Launch the local video-master Activity. Drift correction in the controller
                //    will idle until that Activity binds itself via SplitAvVideoBridge.
                //    appContext is the Application context, so FLAG_ACTIVITY_NEW_TASK is required
                //    — otherwise startActivity throws AndroidRuntimeException.
                withContext(Dispatchers.Main) {
                    val intent = localPlayerIntentBuilder()?.apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    if (intent != null) {
                        runCatching { appContext.startActivity(intent) }
                            .onFailure { Timber.tag(TAG).w(it, "split-A/V local launch failed") }
                    } else {
                        Timber.tag(TAG).w("split-A/V local intent builder returned null")
                    }
                }
                true
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "castSpatialItemSplitAv failed for %s", itemId)
                _calibrationState.value = CalibrationState.Failed(
                    e.message ?: "Could not start split-A/V playback"
                )
                false
            }
        }
    }

    suspend fun pause() {
        when (_pickedTarget.value?.protocol) {
            CastProtocol.FCast -> controller.pause()
            CastProtocol.GoogleCast, CastProtocol.AirPlay -> activeCastAdapter?.pause()
            null -> Unit
        }
    }

    suspend fun resume() {
        when (_pickedTarget.value?.protocol) {
            CastProtocol.FCast -> controller.resume()
            CastProtocol.GoogleCast, CastProtocol.AirPlay -> activeCastAdapter?.play()
            null -> Unit
        }
    }

    suspend fun seek(seconds: Double) {
        when (_pickedTarget.value?.protocol) {
            CastProtocol.FCast -> controller.seek(seconds)
            CastProtocol.GoogleCast, CastProtocol.AirPlay ->
                activeCastAdapter?.seek((seconds * 1000.0).toLong())
            null -> Unit
        }
    }

    /**
     * Seek by a relative offset, computed against the last known position. Accurate to the
     * most recent state update we got from whichever protocol is active. No-op if we don't
     * have a known position yet (the receiver hasn't pushed its first update).
     */
    suspend fun seekBy(deltaSeconds: Double) {
        val currentMs = _activePositionMs.value
        val targetSeconds = (currentMs / 1000.0 + deltaSeconds).coerceAtLeast(0.0)
        seek(targetSeconds)
    }

    /**
     * Local intent for what the receiver's gain should be, 0.0–1.0. Kept as a StateFlow because
     * the FCast `VolumeUpdate` echo isn't reliably emitted across receiver implementations and
     * the AirPlay/Cast event streams update [activeVolume] asynchronously. UI binds to this for
     * the slider thumb; we also mirror updates into [activeVolume] for the unified observers.
     */
    private val _currentVolume = MutableStateFlow(1.0)
    val currentVolume: StateFlow<Double> = _currentVolume.asStateFlow()

    /** Set absolute receiver gain. Clamped to 0.0–1.0. */
    suspend fun setVolume(volume: Double) {
        val clamped = volume.coerceIn(0.0, 1.0)
        _currentVolume.value = clamped
        _activeVolume.value = clamped.toFloat()
        when (_pickedTarget.value?.protocol) {
            CastProtocol.FCast -> controller.setVolume(clamped)
            CastProtocol.GoogleCast, CastProtocol.AirPlay ->
                activeCastAdapter?.setVolume(clamped.toFloat())
            null -> Unit
        }
    }

    /** Bump volume by [delta] (e.g. ±0.1). */
    suspend fun adjustVolume(delta: Double) = setVolume(_currentVolume.value + delta)

    suspend fun setSpeed(speed: Double) {
        when (_pickedTarget.value?.protocol) {
            CastProtocol.FCast -> controller.setSpeed(speed)
            CastProtocol.GoogleCast, CastProtocol.AirPlay -> {
                // Both Cast and AirPlay v1 return failure for setSpeed; we surface the result
                // silently rather than throwing.
                activeCastAdapter?.setSpeed(speed.toFloat())
            }
            null -> Unit
        }
    }

    /** Tear down the connection AND clear the picked-receiver intent. Idempotent. */
    suspend fun stopCast() {
        controller.stopCast()
        releaseActiveCastAdapter()
        _pickedReceiver.value = null
        _pickedTarget.value = null
        _subtitleFidelity.value = SubtitleFidelity.None
        _activeAudioFormat.value = null
        _activeAudioRoute.value = null
    }

    /**
     * Fold split-A/V audio back to the headset without stopping playback. Tells the picked
     * receiver to stop its audio overlay and unmutes the local master so the XR keeps
     * playing the movie with sound. Picked receiver and split-mode preference are preserved
     * — re-entering split-A/V is just another play tap away.
     */
    suspend fun endSplitAv() {
        splitAvController.endFromMaster()
        _activeAudioFormat.value = null
        _activeAudioRoute.value = null
    }

    /** True while a split-A/V session is active (between start and end). */
    val splitAvActive: StateFlow<SplitAvController.State> = splitAvController.state

    private companion object {
        const val TAG = "FCastSession"
        const val DISCOVERY_QUICK_MS = 1_500L
        const val DISCOVERY_FRESH_MS = 30_000L
    }
}
