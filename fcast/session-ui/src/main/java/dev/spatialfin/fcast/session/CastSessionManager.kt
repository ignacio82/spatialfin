package dev.spatialfin.fcast.session

import android.content.Context
import android.content.Intent
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jdtech.jellyfin.network.newBuilderWithSsrfGuard
import dev.jdtech.jellyfin.cast.CastAdapterFactory
import dev.jdtech.jellyfin.cast.CastCapability
import dev.jdtech.jellyfin.cast.CastMedia
import dev.jdtech.jellyfin.cast.CastProtocol
import dev.jdtech.jellyfin.cast.CastReceiver
import dev.jdtech.jellyfin.cast.ProtocolAdapter
import dev.jdtech.jellyfin.cast.SubtitleFidelity
import dev.jdtech.jellyfin.cast.adapter.airplay.AirPlayDiscovery
import dev.jdtech.jellyfin.cast.adapter.googlecast.GoogleCastDiscovery
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.PairClaimRequest
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.PairPinResponse
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.SendspinAvrDiscoveryBrowser
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.SendspinAvrHttpClient
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.SendspinAvrRequestValidator
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.TheaterAudioProtocol
import dev.jdtech.jellyfin.cast.splitav.AudioCodecTokens
import dev.jdtech.jellyfin.cast.subtitle.AssStyleDetector
import dev.jdtech.jellyfin.cast.subtitle.SubtitlePolicy
import dev.jdtech.jellyfin.cast.subtitle.SubtitleWarningTracker
import dev.jdtech.jellyfin.cast.toCastReceiver
import dev.jdtech.jellyfin.fcast.discovery.FCastDiscovery
import dev.jdtech.jellyfin.fcast.protocol.AudioRouteInfo
import dev.jdtech.jellyfin.fcast.protocol.FCAST_DEFAULT_PORT
import dev.jdtech.jellyfin.fcast.protocol.PlayMessage
import dev.jdtech.jellyfin.fcast.protocol.PlaybackUpdateMessage
import dev.jdtech.jellyfin.fcast.sender.FCastCastingController
import dev.jdtech.jellyfin.fcast.sender.FCastReceiver
import dev.jdtech.jellyfin.fcast.sender.PickerEntry
import dev.jdtech.jellyfin.fcast.sender.PlayMessageBuilder
import dev.jdtech.jellyfin.fcast.sender.probeFCastReceiverDetails
import dev.jdtech.jellyfin.models.MediaStreamLanguage
import dev.jdtech.jellyfin.models.SpatialFinMediaStream
import dev.jdtech.jellyfin.models.SpatialFinEpisode
import dev.jdtech.jellyfin.models.SpatialFinItem
import dev.jdtech.jellyfin.models.SpatialFinMovie
import dev.jdtech.jellyfin.models.SpatialFinSource
import dev.jdtech.jellyfin.models.SpatialFinSourceType
import dev.jdtech.jellyfin.models.companion.CompanionEndpoint
import dev.jdtech.jellyfin.core.util.XrPlayerSessionClient
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.session.ActiveSessionObserver
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.settings.domain.TheaterAvrTokenStore
import dev.jdtech.jellyfin.player.core.splitav.ReceiverAudioCodecs
import dev.jdtech.jellyfin.player.core.splitav.RoomGeometryContract
import dev.jdtech.jellyfin.player.core.splitav.SplitAvBridgeIpcMessage
import dev.jdtech.jellyfin.player.core.splitav.SplitAvVideoBridge
import dev.jdtech.jellyfin.player.core.splitav.TheaterClientEvent
import dev.jdtech.jellyfin.player.core.splitav.TheaterRoomSummaryContract
import dev.jdtech.jellyfin.player.core.splitav.TheaterScreenPlaneContract
import dev.jdtech.jellyfin.player.core.splitav.TheaterSessionSnapshot
import dev.jdtech.jellyfin.player.core.splitav.TheaterSpeakerContract
import dev.spatialfin.fcast.session.calibration.CalibrationOrchestrator
import dev.spatialfin.unified.applock.AppLockManager
import dev.spatialfin.unified.applock.AppLockObserver
import java.net.URI
import java.net.URLDecoder
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.jellyfin.sdk.model.api.MediaStreamType
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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
    private val okHttpClient: okhttp3.OkHttpClient,
    private val appLockObserver: AppLockObserver,
    private val activeSessionObserver: ActiveSessionObserver,
    private val theaterAvrTokenStore: TheaterAvrTokenStore,
    private val sendspinAvrHttpClient: SendspinAvrHttpClient,
) {
    data class TheaterPickerEntry(
        val receiver: CastReceiver,
        val state: PickerEntry.State,
        val isPaired: Boolean,
        val supportedAudioCodecs: List<String> = emptyList(),
        val lastSeenMs: Long? = null,
    )
    private fun isLocked(): Boolean =
        appLockObserver.lockState.value == AppLockManager.LockState.LOCKED

    private fun isCastSessionAllowed(): Boolean =
        !isLocked() && activeSessionObserver.activeSessionState.value.isAuthenticated
    private val subtitleSampleClient: okhttp3.OkHttpClient =
        okHttpClient
            .newBuilderWithSsrfGuard(
                policy = dev.jdtech.jellyfin.network.UrlTargetPolicy.PUBLIC_OR_LAN,
                allowCrossOriginRedirects = false,
            )
            .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    private val companionJson = Json { ignoreUnknownKeys = true }
    private val companionDiscoveryClient: okhttp3.OkHttpClient =
        okHttpClient
            .newBuilderWithSsrfGuard(
                policy = dev.jdtech.jellyfin.network.UrlTargetPolicy.LAN_ONLY,
                allowCrossOriginRedirects = false,
            )
            .connectTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val discoveryMutex = Mutex()



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
    private val scanGeneration = java.util.concurrent.atomic.AtomicLong(0L)

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
        CastProtocol.SendspinAvr -> true
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

    private val _activeReceiverAudioRoute = MutableStateFlow<AudioRouteInfo?>(null)
    val activeReceiverAudioRoute: StateFlow<AudioRouteInfo?> =
        _activeReceiverAudioRoute.asStateFlow()

    private val _manualVideoOffsetMs = MutableStateFlow(0)
    val manualVideoOffsetMs: StateFlow<Int> = _manualVideoOffsetMs.asStateFlow()

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

    private data class ActiveTheaterPlaybackContext(
        val item: SpatialFinItem,
        val requestedMediaSourceIndex: Int?,
        val requestedAudioStreamIndex: Int?,
        val formFactor: String,
    )

    @Volatile
    private var lastTheaterPlaybackContext: ActiveTheaterPlaybackContext? = null

    private val roomSwitchMutex = Mutex()
    private val roomSwitchGeneration = java.util.concurrent.atomic.AtomicLong(0L)

    @Volatile
    private var recastDone: Boolean = false

    @Volatile
    private var activeRoutePersistenceKey: String? = null

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

    private val _theaterReceivers = MutableStateFlow<List<TheaterPickerEntry>>(emptyList())
    val theaterReceivers: StateFlow<List<TheaterPickerEntry>> = _theaterReceivers.asStateFlow()

    private val _pendingTheaterPairing = MutableStateFlow<TheaterPairingRequest?>(null)
    val pendingTheaterPairing: StateFlow<TheaterPairingRequest?> = _pendingTheaterPairing.asStateFlow()

    val theaterSnapshot: StateFlow<TheaterSessionSnapshot?> = splitAvController.theaterSnapshot

    fun requestTheaterPairing(request: TheaterPairingRequest = TheaterPairingRequest()) {
        _pendingTheaterPairing.value = request
    }

    fun dismissTheaterPairing() {
        _pendingTheaterPairing.value = null
    }

    fun parseTheaterPairQrUri(rawUri: String): TheaterPairingRequest? {
        val uri = runCatching { URI(rawUri.trim()) }.getOrNull() ?: return null
        if (!uri.scheme.equals("spatialfin-avr", ignoreCase = true)) return null
        val authority = uri.authority?.lowercase().orEmpty()
        val path = uri.path?.trim('/')?.lowercase().orEmpty()
        if (authority != "pair" && path != "pair") return null

        val params = uri.rawQuery.orEmpty()
            .split('&')
            .filter { it.isNotBlank() }
            .associate { part ->
                val idx = part.indexOf('=')
                if (idx < 0) {
                    URLDecoder.decode(part, "UTF-8").lowercase() to ""
                } else {
                    URLDecoder.decode(part.substring(0, idx), "UTF-8").lowercase() to
                        URLDecoder.decode(part.substring(idx + 1), "UTF-8")
                }
            }

        val host = params["host"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val port = params["port"]?.trim()?.toIntOrNull()
            ?: SendspinAvrDiscoveryBrowser.SENDSPIN_AVR_DEFAULT_PORT
        if (port !in 1..65535) return null
        val useTls = params["tls"]?.trim() in setOf("1", "true", "yes")
        val serverId = params["id"]?.trim()?.takeIf { TheaterAudioProtocol.SERVER_ID_REGEX.matches(it) }
        val pin = params["pin"]?.filter(Char::isDigit)?.take(6).orEmpty()
        val nameHint = params["name"]?.trim()?.takeIf { it.isNotEmpty() }
        val spki = params["spki"]?.trim()?.takeIf { it.isNotEmpty() && useTls }
        return TheaterPairingRequest(
            host = host,
            port = port,
            useTls = useTls,
            initialPin = pin,
            serverIdHint = serverId,
            roomNameHint = nameHint,
            tlsSpkiSha256 = spki,
        )
    }

    /**
     * Performs a handshake-only TLS certificate probe so Android TV / manual pairing can display
     * the server's 8-character SAS for user confirmation before pinning [tlsSpkiSha256].
     */
    suspend fun probeTheaterTlsSas(host: String, port: Int): Result<Pair<String, String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                dev.jdtech.jellyfin.cast.adapter.sendspinavr.SendspinAvrTlsPinning
                    .probeLeafCertificatePinAndSas(host.trim(), port)
            }
        }

    suspend fun pairTheaterRoom(
        host: String,
        port: Int,
        useTls: Boolean,
        pin: String,
        roomNameHint: String? = null,
        autoSelect: Boolean = true,
        tlsSpkiSha256: String? = null,
    ): Result<CastReceiver> = withContext(Dispatchers.IO) {
        val parsedEndpoint = SendspinAvrHttpClient.Endpoint.parse(
            rawHost = host,
            fallbackPort = port,
            fallbackUseTls = useTls,
        )
        val endpoint = parsedEndpoint.copy(
            tlsSpkiSha256 = tlsSpkiSha256?.trim()?.takeIf { it.isNotEmpty() && parsedEndpoint.useTls },
        )
        if (endpoint.host.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Enter a valid host or IP address."))
        }
        val clientId = theaterAvrTokenStore.getOrCreateClientId()
        val clientName = Build.MODEL?.takeIf { it.isNotBlank() } ?: "SpatialFin"
        val claim = sendspinAvrHttpClient.pairClaim(
            endpoint = endpoint,
            request = PairClaimRequest(
                pin = pin.trim(),
                clientId = clientId,
                clientName = clientName,
            ),
        )
        when (claim) {
            is SendspinAvrHttpClient.HttpResult.Error -> {
                val friendly = when (claim.errorCode) {
                    "pin_expired" -> "Pairing PIN expired — request a fresh PIN on spatialfin-avr."
                    "rate_limited" -> "Too many attempts — wait a moment and try again."
                    "pairing_disabled" -> "Pairing is disabled on this spatialfin-avr room."
                    "auth_invalid" -> "Invalid pairing PIN."
                    else -> claim.message.ifBlank { "Pairing failed (${claim.errorCode})." }
                }
                Result.failure(IllegalStateException(friendly))
            }
            is SendspinAvrHttpClient.HttpResult.Success -> {
                val resp = claim.value
                theaterAvrTokenStore.saveToken(
                    serverId = resp.serverId,
                    avrToken = resp.avrToken,
                    host = endpoint.host,
                    port = endpoint.port,
                    useTls = endpoint.useTls,
                    tlsSpkiSha256 = endpoint.tlsSpkiSha256,
                    requireTls = endpoint.useTls,
                )
                val info = (sendspinAvrHttpClient.getInfo(endpoint, resp.avrToken) as? SendspinAvrHttpClient.HttpResult.Success)?.value
                val receiverId = "SendspinAvr:${resp.serverId}"
                val receiver = CastReceiver(
                    id = receiverId,
                    name = info?.serverName?.takeIf { it.isNotBlank() }
                        ?: roomNameHint?.takeIf { it.isNotBlank() }
                        ?: endpoint.host,
                    host = endpoint.host,
                    port = endpoint.port,
                    protocol = CastProtocol.SendspinAvr,
                    modelName = info?.layout,
                    appName = "SpatialFin AVR",
                    appVersion = info?.softwareVersion,
                    capabilities = setOf(
                        CastCapability.Audio,
                        CastCapability.Volume,
                        CastCapability.SplitAv,
                        CastCapability.TheaterAudioV1,
                    ),
                    source = CastReceiver.Source.Manual,
                    useTls = endpoint.useTls,
                )
                val now = System.currentTimeMillis()
                rememberedReceiversStore.upsertCastReceiver(receiver, now)
                val codecs = info?.supportedAudioCodecs.orEmpty()
                if (codecs.isNotEmpty()) {
                    rememberedReceiversStore.setSupportedAudioCodecsById(receiverId, codecs)
                    receiverAudioCaps[receiverId] = codecs
                }
                // Immediately expand all rooms already configured on this spatialfin-avr server
                // so a newly paired SpatialFin client inherits every room right away.
                val expandedRooms = mergeDiscoveredTheaterRooms(listOf(receiver))
                val primaryReceiver = expandedRooms.firstOrNull { it.id == receiverId }
                    ?: expandedRooms.firstOrNull()
                    ?: receiver
                _pendingTheaterPairing.value = null
                if (autoSelect) {
                    pickCastReceiver(primaryReceiver)
                }
                Result.success(primaryReceiver)
            }
        }
    }

    suspend fun armTheaterPairPin(receiver: CastReceiver): SendspinAvrHttpClient.HttpResult<PairPinResponse> {
        val stored = theaterAvrTokenStore.getToken(receiver.id)
            ?: return SendspinAvrHttpClient.HttpResult.Error(
                httpStatus = 401,
                errorCode = "auth_invalid",
                message = "Room is not paired on this device.",
            )
        val endpoint = SendspinAvrHttpClient.Endpoint(
            host = receiver.host,
            port = receiver.port,
            useTls = receiver.useTls || stored.requireTls,
            tlsSpkiSha256 = stored.tlsSpkiSha256,
        )
        return sendspinAvrHttpClient.armPairPin(endpoint, stored.avrToken)
    }

    suspend fun unpairTheaterRoom(receiver: CastReceiver) = withContext(Dispatchers.IO) {
        val stored = theaterAvrTokenStore.getToken(receiver.id)
        if (stored != null) {
            val endpoint = SendspinAvrHttpClient.Endpoint(
                host = receiver.host,
                port = receiver.port,
                useTls = receiver.useTls || stored.requireTls,
                tlsSpkiSha256 = stored.tlsSpkiSha256,
            )
            runCatching {
                sendspinAvrHttpClient.revokeClient(
                    endpoint = endpoint,
                    bearerToken = stored.avrToken,
                    clientId = theaterAvrTokenStore.getOrCreateClientId(),
                    force = true,
                )
            }
        }
        val baseServerId = receiver.id.substringBefore("::")
        if (_pickedTarget.value?.id?.substringBefore("::") == baseServerId) {
            stopCast()
        }
        theaterAvrTokenStore.removeToken(receiver.id)
        rememberedReceiversStore.forgetServerTheaterRooms(baseServerId)
        receiverAudioCaps.keys.filter { it == baseServerId || it.startsWith("$baseServerId::") }
            .forEach { receiverAudioCaps.remove(it) }
        _theaterReceivers.update { entries ->
            entries.mapNotNull { entry ->
                val matchesServer = entry.receiver.id == baseServerId ||
                    entry.receiver.id.startsWith("$baseServerId::")
                when {
                    !matchesServer -> entry
                    entry.receiver.id == baseServerId && entry.state == PickerEntry.State.Online ->
                        entry.copy(isPaired = false)
                    else -> null
                }
            }
        }
    }

    suspend fun confirmTheaterTakeover(confirm: Boolean) {
        splitAvController.handleTheaterClientEvent(TheaterClientEvent.ConfirmTakeover(confirm))
    }

    suspend fun setMuted(muted: Boolean) {
        if (_pickedTarget.value?.protocol == CastProtocol.SendspinAvr) {
            splitAvController.handleTheaterClientEvent(TheaterClientEvent.SetMuted(muted))
        }
    }

    suspend fun setEqBypass(bypass: Boolean) {
        if (_pickedTarget.value?.protocol == CastProtocol.SendspinAvr) {
            splitAvController.handleTheaterClientEvent(TheaterClientEvent.SetEqBypass(bypass))
        }
    }

    suspend fun setUpmixMode(mode: String) {
        if (_pickedTarget.value?.protocol == CastProtocol.SendspinAvr) {
            splitAvController.handleTheaterClientEvent(TheaterClientEvent.SetUpmixMode(mode))
        }
    }

    fun hasPairedTheaterRooms(): Boolean =
        _theaterReceivers.value.any { it.isPaired } || theaterAvrTokenStore.listServerIds().isNotEmpty()
    private var peerCapabilityObserverStarted = false

    private fun ensurePeerCapabilityObserver() {
        if (peerCapabilityObserverStarted) return
        peerCapabilityObserverStarted = true
        scope.launch {
            controller.peerInitial.collect { initial ->
                val receiver = controller.activeReceiver.value ?: return@collect
                val key = "${receiver.host}:${receiver.port}"
                val appName = initial.appName?.trim().orEmpty()
                val isSpatialFinPeer = appName.startsWith("SpatialFin", ignoreCase = true) && !appName.contains("Web", ignoreCase = true)
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
            // External ASS: pin to active Jellyfin base URL and fetch a slice with X-Emby-Token
            // header (never ?api_key=). Failure → conservative null.
            runCatching { fetchTextSample(externalPath, 8 * 1024) }
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
     * Fetch up to [maxBytes] of UTF-8 text from [urlString], pinned to the active Jellyfin
     * server's `(scheme, host, port, basePath)` prefix and authenticated via `X-Emby-Token`.
     */
    internal suspend fun fetchTextSample(urlString: String, maxBytes: Int): String =
        withContext(Dispatchers.IO) {
            val baseUrl = repository.getBaseUrl()
            if (!dev.jdtech.jellyfin.network.SafeUrlPolicy.matchesNormalizedBaseUrlPrefix(urlString, baseUrl)) {
                throw dev.jdtech.jellyfin.network.SafeUrlBlockedException(
                    "Subtitle sample URL does not match active Jellyfin server origin/basePath",
                )
            }
            val requestBuilder = okhttp3.Request.Builder()
                .url(urlString)
                .header("Range", "bytes=0-${maxBytes - 1}")
                .header("Accept", "text/plain, text/x-ssa, application/x-subrip")
            repository.getAccessToken()?.takeIf { it.isNotBlank() }?.let { token ->
                requestBuilder.header("X-Emby-Token", token)
            }
            subtitleSampleClient.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    throw java.io.IOException("Subtitle sample HTTP ${response.code}")
                }
                response.body.byteStream().use { input ->
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
        }

    fun setSplitAvMode(enabled: Boolean) {
        Timber.tag(TAG).i("setSplitAvMode: enabled=%b (was %b)", enabled, _splitAvMode.value)
        _splitAvMode.value = enabled
        if (_pickerVisible.value && !_isScanning.value) {
            if (enabled) {
                val hasOnlineSplitAv = _pickerEntries.value.any { entry ->
                    entry.state == PickerEntry.State.Online &&
                        SplitAvPolicy.isSplitAvPickerCandidate(entry.receiver)
                }
                if (!hasOnlineSplitAv) {
                    rescanReceivers()
                }
            } else {
                rescanReceivers()
            }
        }
    }

    fun rescanReceivers() {
        Timber.tag(TAG).i("rescanReceivers: restarting picker discovery")
        pickerJob?.cancel()
        pickerJob = scope.launch { driveDiscoveryAndProbing() }
    }

    fun setManualVideoOffset(offsetMs: Int) {
        val clamped = offsetMs.coerceIn(-200, 200)
        _manualVideoOffsetMs.value = clamped
        splitAvController.setManualVideoOffset(clamped)
        val receiver = _pickedReceiver.value ?: controller.activeReceiver.value ?: return
        val routeFingerprint = _activeReceiverAudioRoute.value?.fingerprint ?: return
        scope.launch {
            rememberedReceiversStore.setManualVideoOffset(
                host = receiver.host,
                port = receiver.port,
                routeFingerprint = routeFingerprint,
                offsetMs = clamped,
            )
        }
    }

    fun adjustManualVideoOffset(deltaMs: Int) {
        setManualVideoOffset(_manualVideoOffsetMs.value + deltaMs)
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
            val routeLatency = r.routes.firstOrNull { it.baseLatencyMs != null }?.baseLatencyMs
            (routeLatency ?: r.audioLatencyMs)?.let { "${r.host}:${r.port}" to it }
        }.toMap()
        receivers.forEach { r ->
            r.supportedAudioCodecs?.takeIf { it.isNotEmpty() }?.let { codecs ->
                receiverAudioCaps["${r.host}:${r.port}"] = codecs
                receiverAudioCaps[r.resolvedId] = codecs
            }
        }
    }

    /** True iff there is a chosen receiver of any protocol — connection or not. */
    fun hasCastIntent(): Boolean = _pickedTarget.value != null

    /** True iff the currently picked receiver is a Spec 0 Theater Audio (`SendspinAvr`) room. */
    fun isTheaterAvrPicked(): Boolean = _pickedTarget.value?.protocol == CastProtocol.SendspinAvr

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
        scanGeneration.incrementAndGet()
        _isScanning.value = false
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
     * Show remembered receivers immediately, probe each in parallel, and run an mDNS scan +
     * Companion directory lookup in parallel. Each completion path updates [_pickerEntries] in
     * place — so the user sees known names instantly and the status badges flip from Probing to
     * Online/Offline as results arrive without forcing the user to wait for the full scan.
     */
    private suspend fun driveDiscoveryAndProbing() {
        val myGen = scanGeneration.incrementAndGet()
        _isScanning.value = true
        try {
            val remembered = rememberedReceiversStore.load()
            refreshAudioLatenciesFrom(remembered)
            val rememberedFCast = remembered.filter { it.castProtocol == CastProtocol.FCast }
            val rememberedTheater = remembered.filter { it.castProtocol == CastProtocol.SendspinAvr }
            // Snapshot remembered as Probing so the user sees them immediately.
            _pickerEntries.value = rememberedFCast.map { r ->
                PickerEntry(
                    receiver = FCastReceiver(
                        host = r.host,
                        port = r.port,
                        name = r.name,
                        appName = r.appName,
                        appVersion = r.appVersion,
                        source = FCastReceiver.Source.Manual,
                    ),
                    state = PickerEntry.State.Probing,
                    lastSeenMs = r.lastSeenMs,
                )
            }
            _theaterReceivers.value = rememberedTheater.map { r ->
                val inferredRoomId = r.roomId
                    ?: r.resolvedId.substringAfter("::", "").takeIf { it.isNotBlank() }
                TheaterPickerEntry(
                    receiver = CastReceiver(
                        id = r.resolvedId,
                        name = r.name,
                        host = r.host,
                        port = r.port,
                        protocol = CastProtocol.SendspinAvr,
                        modelName = r.modelName,
                        appName = r.appName ?: "SpatialFin AVR",
                        appVersion = r.appVersion,
                        capabilities = setOf(
                            CastCapability.Audio,
                            CastCapability.Volume,
                            CastCapability.SplitAv,
                            CastCapability.TheaterAudioV1,
                        ),
                        source = CastReceiver.Source.Manual,
                        useTls = r.useTls,
                        roomId = inferredRoomId,
                    ),
                    state = PickerEntry.State.Probing,
                    isPaired = theaterAvrTokenStore.hasToken(r.resolvedId),
                    supportedAudioCodecs = r.supportedAudioCodecs.orEmpty(),
                    lastSeenMs = r.lastSeenMs,
                )
            }

            val discoveredByKey = java.util.concurrent.ConcurrentHashMap<String, FCastReceiver>()

            fun sortPickerEntries(entries: Collection<PickerEntry>): List<PickerEntry> =
                entries.sortedWith(
                    // Online first, then Probing, then Offline. Within a bucket, freshest first.
                    compareBy<PickerEntry> {
                        when (it.state) {
                            PickerEntry.State.Online -> 0
                            PickerEntry.State.Probing -> 1
                            PickerEntry.State.Offline -> 2
                        }
                    }.thenByDescending { it.lastSeenMs ?: 0L },
                )

            suspend fun recordDiscovered(discovered: List<FCastReceiver>, seenAtMs: Long) {
                if (discovered.isEmpty()) return
                val mergedBatch = mutableListOf<FCastReceiver>()
                discovered.forEach { rec ->
                    val key = "${rec.host}:${rec.port}"
                    val merged = discoveredByKey.compute(key) { _, existing ->
                        mergeFCastReceiver(existing = existing, incoming = rec)
                    }!!
                    mergedBatch += merged
                }
                _cachedReceivers.value = discoveredByKey.values.toList()
                lastDiscoveryAtMs = seenAtMs
                mergedBatch.forEach { rememberedReceiversStore.upsert(it, seenAtMs) }

                _pickerEntries.update { entries ->
                    val byKey = entries.associateBy { "${it.receiver.host}:${it.receiver.port}" }.toMutableMap()
                    mergedBatch.forEach { rec ->
                        val key = "${rec.host}:${rec.port}"
                        val prev = byKey[key]
                        val mergedRec = mergeFCastReceiver(existing = prev?.receiver, incoming = rec)
                        byKey[key] = PickerEntry(
                            receiver = mergedRec,
                            state = PickerEntry.State.Online,
                            lastSeenMs = seenAtMs,
                        )
                    }
                    sortPickerEntries(byKey.values)
                }
            }

            // Structured child scope so cancelling pickerJob (hidePicker / rescanReceivers) cancels
            // every mDNS socket, TCP probe, and HTTP call immediately without leaking sockets or locks.
            coroutineScope {
                rememberedFCast.forEach { r ->
                    launch {
                        val probe = probeFCastReceiverDetails(r.host, r.port)
                        val now = System.currentTimeMillis()
                        val probedReceiver = FCastReceiver(
                            host = r.host,
                            port = r.port,
                            name = probe.displayName ?: r.name,
                            appName = probe.appName ?: r.appName,
                            appVersion = probe.appVersion ?: r.appVersion,
                            source = FCastReceiver.Source.Manual,
                        )
                        _pickerEntries.update { entries ->
                            sortPickerEntries(
                                entries.map { e ->
                                    if (e.receiver.host == r.host && e.receiver.port == r.port) {
                                        e.copy(
                                            receiver = if (probe.reachable) {
                                                mergeFCastReceiver(existing = e.receiver, incoming = probedReceiver)
                                            } else {
                                                e.receiver
                                            },
                                            state = when {
                                                probe.reachable -> PickerEntry.State.Online
                                                e.state == PickerEntry.State.Online -> PickerEntry.State.Online
                                                else -> PickerEntry.State.Offline
                                            },
                                            lastSeenMs = if (probe.reachable) now else e.lastSeenMs,
                                        )
                                    } else {
                                        e
                                    }
                                },
                            )
                        }
                        if (probe.reachable) {
                            discoveredByKey.compute("${r.host}:${r.port}") { _, existing ->
                                mergeFCastReceiver(existing = existing, incoming = probedReceiver)
                            }
                            rememberedReceiversStore.upsert(probedReceiver, now)
                        }
                    }
                }

                // Probe every unique remembered OR paired spatialfin-avr host directly over unicast
                // HTTP (/health + /api/v1/info + /api/v1/rooms) so rooms created, renamed, or
                // deleted on another SpatialFin client sync immediately even if mDNS is dropped.
                val theaterHostsByBaseId = linkedMapOf<String, CastReceiver>()
                rememberedTheater.forEach { r ->
                    val baseId = r.resolvedId.substringBefore("::")
                    theaterHostsByBaseId.putIfAbsent(
                        baseId,
                        CastReceiver(
                            id = baseId,
                            name = r.name,
                            host = r.host,
                            port = r.port,
                            protocol = CastProtocol.SendspinAvr,
                            modelName = r.modelName,
                            appName = r.appName ?: "SpatialFin AVR",
                            appVersion = r.appVersion,
                            capabilities = setOf(
                                CastCapability.Audio,
                                CastCapability.Volume,
                                CastCapability.SplitAv,
                                CastCapability.TheaterAudioV1,
                            ),
                            source = CastReceiver.Source.Manual,
                            useTls = r.useTls,
                        ),
                    )
                }
                theaterAvrTokenStore.listServerIds().forEach { serverId ->
                    val baseId = "SendspinAvr:$serverId"
                    if (!theaterHostsByBaseId.containsKey(baseId)) {
                        val stored = theaterAvrTokenStore.getToken(serverId)
                        if (stored != null && stored.host.isNotBlank()) {
                            theaterHostsByBaseId[baseId] = CastReceiver(
                                id = baseId,
                                name = stored.host,
                                host = stored.host,
                                port = stored.port,
                                protocol = CastProtocol.SendspinAvr,
                                appName = "SpatialFin AVR",
                                capabilities = setOf(
                                    CastCapability.Audio,
                                    CastCapability.Volume,
                                    CastCapability.SplitAv,
                                    CastCapability.TheaterAudioV1,
                                ),
                                source = CastReceiver.Source.Manual,
                                useTls = stored.useTls,
                            )
                        }
                    }
                }
                theaterHostsByBaseId.forEach { (baseId, hostReceiver) ->
                    launch {
                        val endpoint = SendspinAvrHttpClient.Endpoint(
                            host = hostReceiver.host,
                            port = hostReceiver.port,
                            useTls = hostReceiver.useTls,
                        )
                        val healthOk = sendspinAvrHttpClient.health(endpoint) is SendspinAvrHttpClient.HttpResult.Success
                        if (healthOk) {
                            mergeDiscoveredTheaterRooms(listOf(hostReceiver))
                        } else {
                            val isPaired = theaterAvrTokenStore.hasToken(baseId)
                            _theaterReceivers.update { entries ->
                                entries.map { entry ->
                                    if (entry.receiver.id == baseId || entry.receiver.id.startsWith("$baseId::")) {
                                        entry.copy(
                                            state = if (entry.state == PickerEntry.State.Online) {
                                                PickerEntry.State.Online
                                            } else {
                                                PickerEntry.State.Offline
                                            },
                                            isPaired = isPaired,
                                        )
                                    } else {
                                        entry
                                    }
                                }
                            }
                        }
                    }
                }

                // Browse _spatialfin-avr._tcp.local. rooms via JmDNS and update DHCP host/port in place.
                if (shouldShowProtocol(CastProtocol.SendspinAvr)) {
                    launch {
                        val found = try {
                            withTimeoutOrNull(DISCOVERY_QUICK_MS + 500L) {
                                SendspinAvrDiscoveryBrowser(appContext).browse(DISCOVERY_QUICK_MS)
                            }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Timber.tag(TAG).w(e, "SendspinAvr browse failed")
                            null
                        }
                        if (!found.isNullOrEmpty()) {
                            mergeDiscoveredTheaterRooms(found)
                        }
                    }
                    // Also query paired SpatialFin Companion for configured / mDNS-discovered
                    // spatialfin-avr rooms (unicast fallback + automatic zero-touch per-device
                    // pairing when Companion itself is paired to the room).
                    launch {
                        syncCompanionTheaterRooms()
                    }
                }

                // Stream FCast mDNS results as soon as each device resolves so entries appear live rather
                // than waiting for Google Cast + AirPlay timeouts.
                launch {
                    try {
                        withTimeoutOrNull(DISCOVERY_QUICK_MS) {
                            FCastDiscovery(appContext).browseFlow().collect { found ->
                                recordDiscovered(found, System.currentTimeMillis())
                            }
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.tag(TAG).w(e, "FCast streaming mDNS browse failed")
                    }
                }

                // Query paired Companion's receiver directory as a unicast LAN fallback (and TCP-probe
                // each entry so appName="SpatialFin" is known even when multicast mDNS is dropped).
                launch {
                    try {
                        val fromCompanion = fetchCompanionReceivers()
                        if (fromCompanion.isNotEmpty()) {
                            recordDiscovered(fromCompanion, System.currentTimeMillis())
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.tag(TAG).w(e, "Companion FCast discovery failed")
                    }
                }

                if (!_splitAvMode.value && shouldShowProtocol(CastProtocol.GoogleCast)) {
                    launch {
                        val found = try {
                            withTimeoutOrNull(DISCOVERY_QUICK_MS + 500L) {
                                GoogleCastDiscovery(appContext).browse(DISCOVERY_QUICK_MS)
                            }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Timber.tag(TAG).w(e, "Google Cast browse failed")
                            null
                        }
                        if (found != null) {
                            _googleCastReceivers.value = found
                        }
                    }
                }

                if (!_splitAvMode.value && shouldShowProtocol(CastProtocol.AirPlay)) {
                    launch {
                        val found = try {
                            withTimeoutOrNull(DISCOVERY_QUICK_MS + 500L) {
                                AirPlayDiscovery(appContext).browse(DISCOVERY_QUICK_MS)
                            }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Timber.tag(TAG).w(e, "AirPlay browse failed")
                            null
                        }
                        if (found != null) {
                            _airPlayReceivers.value = found
                        }
                    }
                }
            }

            Timber.tag(TAG).i(
                "driveDiscoveryAndProbing complete: splitAv=%b fcast=%d (%s) cast=%d airplay=%d",
                _splitAvMode.value,
                _pickerEntries.value.size,
                _pickerEntries.value.joinToString {
                    "${it.receiver.name}@${it.receiver.host}:${it.receiver.port}[${it.state}/${it.receiver.appName}]"
                },
                _googleCastReceivers.value.size,
                _airPlayReceivers.value.size,
            )
        } finally {
            if (scanGeneration.get() == myGen) {
                _isScanning.value = false
            }
        }
    }

    private fun mergeFCastReceiver(existing: FCastReceiver?, incoming: FCastReceiver): FCastReceiver {
        if (existing == null) return incoming
        return existing.copy(
            name = incoming.name.ifBlank { existing.name },
            appName = incoming.appName?.takeIf { it.isNotBlank() } ?: existing.appName,
            appVersion = incoming.appVersion?.takeIf { it.isNotBlank() } ?: existing.appVersion,
            source = if (incoming.source == FCastReceiver.Source.Mdns) {
                FCastReceiver.Source.Mdns
            } else {
                existing.source
            },
        )
    }

    @Serializable
    private data class CompanionReceiverEntry(
        val ip: String,
        val port: Int = FCAST_DEFAULT_PORT,
        val name: String = "",
        val lastSeen: Long? = null,
    )

    @Serializable
    private data class CompanionTheaterRoomEntry(
        @kotlinx.serialization.SerialName("server_id") val serverId: String,
        val name: String = "",
        val host: String,
        val port: Int = SendspinAvrDiscoveryBrowser.SENDSPIN_AVR_DEFAULT_PORT,
        @kotlinx.serialization.SerialName("use_tls") val useTls: Boolean = false,
        val layout: String? = null,
        @kotlinx.serialization.SerialName("software_version") val softwareVersion: String? = null,
        @kotlinx.serialization.SerialName("companion_paired") val companionPaired: Boolean = false,
    )

    @Serializable
    private data class CompanionTheaterPinResponse(
        @kotlinx.serialization.SerialName("server_id") val serverId: String,
        val name: String = "",
        val host: String,
        val port: Int = SendspinAvrDiscoveryBrowser.SENDSPIN_AVR_DEFAULT_PORT,
        @kotlinx.serialization.SerialName("use_tls") val useTls: Boolean = false,
        val pin: String,
    )

    private suspend fun mergeDiscoveredTheaterRooms(found: List<CastReceiver>): List<CastReceiver> {
        if (found.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()
        val expanded = mutableListOf<CastReceiver>()
        val replacedServerPrefixes = mutableSetOf<String>()

        found.distinctBy { it.id.substringBefore("::") }.forEach { rec ->
            val stored = theaterAvrTokenStore.getToken(rec.id)
            if (stored != null) {
                if (stored.requireTls && !rec.useTls) {
                    Timber.tag(TAG).w(
                        "Ignoring plaintext mDNS downgrade for TLS-paired Theater AVR %s (%s:%d)",
                        stored.serverId,
                        rec.host,
                        rec.port,
                    )
                }
                val effectiveUseTls = stored.requireTls || rec.useTls
                val effectivePort = if (stored.requireTls && !rec.useTls) stored.port else rec.port
                val effectiveRec = rec.copy(useTls = effectiveUseTls, port = effectivePort)
                if (stored.host != effectiveRec.host || stored.port != effectiveRec.port || stored.useTls != effectiveUseTls) {
                    theaterAvrTokenStore.saveToken(
                        serverId = stored.serverId,
                        avrToken = stored.avrToken,
                        host = effectiveRec.host,
                        port = effectiveRec.port,
                        useTls = effectiveUseTls,
                        tlsSpkiSha256 = stored.tlsSpkiSha256,
                        requireTls = stored.requireTls,
                    )
                }
                val endpoint = SendspinAvrHttpClient.Endpoint(
                    host = effectiveRec.host,
                    port = effectiveRec.port,
                    useTls = effectiveUseTls,
                    tlsSpkiSha256 = stored.tlsSpkiSha256,
                )
                val info = (sendspinAvrHttpClient.getInfo(endpoint, stored.avrToken) as? SendspinAvrHttpClient.HttpResult.Success)?.value
                val codecs = info?.supportedAudioCodecs.orEmpty()
                val roomsResp = (sendspinAvrHttpClient.listRooms(endpoint, stored.avrToken) as? SendspinAvrHttpClient.HttpResult.Success)?.value
                if (roomsResp != null && roomsResp.rooms.isNotEmpty()) {
                    val baseId = "SendspinAvr:${stored.serverId}"
                    replacedServerPrefixes += baseId
                    val serverRooms = roomsResp.rooms.map { room ->
                        val roomRecId = if (room.isDefault) baseId else "$baseId::${room.roomId}"
                        rec.copy(
                            id = roomRecId,
                            name = room.roomName,
                            modelName = room.layout,
                            appVersion = info?.softwareVersion ?: rec.appVersion,
                            roomId = room.roomId,
                        )
                    }
                    expanded += serverRooms
                    rememberedReceiversStore.replaceServerTheaterRooms(
                        serverBaseId = baseId,
                        rooms = serverRooms,
                        supportedAudioCodecs = codecs,
                        lastSeenMs = now,
                    )
                    if (codecs.isNotEmpty()) {
                        serverRooms.forEach { r -> receiverAudioCaps[r.id] = codecs }
                    }
                } else {
                    expanded += rec
                    rememberedReceiversStore.upsertCastReceiver(rec, now)
                    if (codecs.isNotEmpty()) {
                        receiverAudioCaps[rec.id] = codecs
                        rememberedReceiversStore.setSupportedAudioCodecsById(rec.id, codecs)
                    }
                }
            } else {
                expanded += rec
            }
        }

        _theaterReceivers.update { existing ->
            val byId = existing.filterNot { entry ->
                replacedServerPrefixes.any { prefix ->
                    entry.receiver.id == prefix || entry.receiver.id.startsWith("$prefix::")
                }
            }.associateBy { it.receiver.id }.toMutableMap()
            expanded.forEach { rec ->
                val prev = byId[rec.id]
                val isPaired = theaterAvrTokenStore.hasToken(rec.id)
                byId[rec.id] = TheaterPickerEntry(
                    receiver = rec,
                    state = PickerEntry.State.Online,
                    isPaired = isPaired,
                    supportedAudioCodecs = receiverAudioCaps[rec.id]
                        ?: prev?.supportedAudioCodecs.orEmpty(),
                    lastSeenMs = now,
                )
            }
            byId.values.sortedWith(
                compareBy<TheaterPickerEntry> {
                    when (it.state) {
                        PickerEntry.State.Online -> 0
                        PickerEntry.State.Probing -> 1
                        PickerEntry.State.Offline -> 2
                    }
                }.thenByDescending { it.isPaired }
                    .thenBy { it.receiver.name.lowercase() },
            )
        }

        // Keep currently picked Theater target synced if another client renamed or deleted it.
        val currentPicked = _pickedTarget.value
        if (currentPicked != null && currentPicked.protocol == CastProtocol.SendspinAvr) {
            val pickedPrefix = currentPicked.id.substringBefore("::")
            if (pickedPrefix in replacedServerPrefixes) {
                val updated = expanded.firstOrNull {
                    it.id == currentPicked.id ||
                        (currentPicked.roomId != null && it.roomId == currentPicked.roomId)
                }
                if (updated != null) {
                    _pickedTarget.value = updated
                } else if (splitAvController.state.value == SplitAvController.State.Idle) {
                    _pickedTarget.value = null
                }
            }
        }
        return expanded
    }

    /** Re-queries `GET /api/v1/rooms` for [receiver]'s AVR host so newly created/renamed/deleted rooms appear immediately in the Cast Picker. */
    fun refreshTheaterRoomsForHost(receiver: CastReceiver) {
        scope.launch {
            mergeDiscoveredTheaterRooms(listOf(receiver))
        }
    }

    /**
     * Fetches configured & mDNS-discovered `spatialfin-avr` rooms from a paired SpatialFin
     * Companion server (`GET /api/theater-avr/rooms`). For any room where Companion itself is
     * paired (`companion_paired == true`) and this device does not yet hold its own revocable
     * `avr_token`, requests a fresh single-use PIN from Companion
     * (`POST /api/theater-avr/rooms/{serverId}/pair-pin`) and claims a per-device token
     * automatically — zero manual IP/port/PIN entry required.
     */
    suspend fun syncCompanionTheaterRooms(): List<CastReceiver> = withContext(Dispatchers.IO) {
        val endpoint = runCatching { CompanionEndpoint.fromPreferences(appPreferences) }.getOrNull()
            ?: return@withContext emptyList()
        val request = endpoint.authorizedRequest(endpoint.theaterAvrRoomsUrl).get().build()
        val body = runCatching {
            companionDiscoveryClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.tag(TAG).d("Companion theater rooms HTTP %d", response.code)
                    return@withContext emptyList()
                }
                response.body.string()
            }
        }.getOrNull() ?: return@withContext emptyList()

        val entries = runCatching {
            companionJson.decodeFromString<List<CompanionTheaterRoomEntry>>(body)
        }.onFailure {
            Timber.tag(TAG).w(it, "Failed to decode Companion theater rooms response")
        }.getOrDefault(emptyList())

        if (entries.isEmpty()) return@withContext emptyList()

        val receivers = entries.mapNotNull { entry ->
            val cleanHost = entry.host.trim()
            val cleanId = entry.serverId.trim()
            if (cleanHost.isEmpty() || cleanId.isEmpty() || entry.port !in 1..65535) return@mapNotNull null
            CastReceiver(
                id = "SendspinAvr:$cleanId",
                name = entry.name.ifBlank { cleanHost },
                host = cleanHost,
                port = entry.port,
                protocol = CastProtocol.SendspinAvr,
                modelName = entry.layout,
                appName = "SpatialFin AVR",
                appVersion = entry.softwareVersion,
                capabilities = setOf(
                    CastCapability.Audio,
                    CastCapability.Volume,
                    CastCapability.SplitAv,
                    CastCapability.TheaterAudioV1,
                ),
                source = CastReceiver.Source.Manual,
                useTls = entry.useTls,
            )
        }
        mergeDiscoveredTheaterRooms(receivers)

        // Zero-touch per-device pairing for Companion-managed rooms this device hasn't paired yet.
        entries.filter { it.companionPaired }.forEach { entry ->
            val receiverId = "SendspinAvr:${entry.serverId.trim()}"
            if (theaterAvrTokenStore.hasToken(receiverId)) return@forEach
            val pinReq = endpoint.authorizedRequest(endpoint.theaterAvrPairPinUrl(entry.serverId.trim()))
                .post("{}".toRequestBody("application/json".toMediaType()))
                .build()
            val pinBody = runCatching {
                companionDiscoveryClient.newCall(pinReq).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Timber.tag(TAG).d("Companion theater pair-pin HTTP %d for %s", resp.code, entry.serverId)
                        return@runCatching null
                    }
                    resp.body.string()
                }
            }.getOrNull() ?: return@forEach

            val pinResp = runCatching {
                companionJson.decodeFromString<CompanionTheaterPinResponse>(pinBody)
            }.getOrNull() ?: return@forEach

            if (pinResp.pin.length == 6) {
                val claim = pairTheaterRoom(
                    host = pinResp.host,
                    port = pinResp.port,
                    useTls = pinResp.useTls,
                    pin = pinResp.pin,
                    roomNameHint = pinResp.name.ifBlank { entry.name },
                    autoSelect = false,
                )
                claim.onSuccess { paired ->
                    Timber.tag(TAG).i("Auto-paired Theater Audio room %s via Companion", paired.name)
                }.onFailure { err ->
                    Timber.tag(TAG).w(err, "Companion auto-pair failed for %s", entry.serverId)
                }
            }
        }
        receivers
    }

    private suspend fun fetchCompanionReceivers(): List<FCastReceiver> = withContext(Dispatchers.IO) {
        val endpoint = runCatching { CompanionEndpoint.fromPreferences(appPreferences) }.getOrNull()
            ?: return@withContext emptyList()
        val request = endpoint.authorizedRequest(endpoint.fcastReceiversUrl).get().build()
        val body = runCatching {
            companionDiscoveryClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.tag(TAG).d("Companion receivers HTTP %d", response.code)
                    return@withContext emptyList()
                }
                response.body.string()
            }
        }.getOrNull() ?: return@withContext emptyList()

        val entries = runCatching {
            companionJson.decodeFromString<List<CompanionReceiverEntry>>(body)
        }.onFailure {
            Timber.tag(TAG).w(it, "Failed to decode Companion receivers response")
        }.getOrDefault(emptyList())

        val localIps = FCastDiscovery.localIpAddresses()
        val candidates = entries
            .filter { it.ip.isNotBlank() && it.port in 1..65535 && it.ip !in localIps }
            .distinctBy { "${it.ip}:${it.port}" }
        if (candidates.isEmpty()) return@withContext emptyList()

        val found = coroutineScope {
            candidates.map { entry ->
                async {
                    val probe = probeFCastReceiverDetails(entry.ip, entry.port)
                    if (!probe.reachable) return@async null
                    FCastReceiver(
                        host = entry.ip,
                        port = entry.port,
                        name = probe.displayName ?: entry.name.ifBlank { "${entry.ip}:${entry.port}" },
                        appName = probe.appName,
                        appVersion = probe.appVersion,
                        source = FCastReceiver.Source.Manual,
                    )
                }
            }.mapNotNull { it.await() }
        }
        Timber.tag(TAG).i(
            "Companion directory yielded %d/%d reachable FCast receivers",
            found.size,
            candidates.size,
        )
        found
    }

    /**
     * Browse mDNS + Companion directory if the cache is older than [maxAgeMs]. Returns the
     * (possibly cached) list. Held under a mutex so concurrent surfaces don't fan out into
     * multiple multicast scans.
     */
    suspend fun refreshReceivers(maxAgeMs: Long = DISCOVERY_FRESH_MS): List<FCastReceiver> =
        discoveryMutex.withLock {
            val now = System.currentTimeMillis()
            if (now - lastDiscoveryAtMs <= maxAgeMs && _cachedReceivers.value.isNotEmpty()) {
                return@withLock _cachedReceivers.value
            }
            val (mdnsResults, companionResults) = coroutineScope {
                val mdnsDeferred = async {
                    runCatching { FCastDiscovery(appContext).browse(DISCOVERY_QUICK_MS) }
                        .getOrDefault(emptyList())
                }
                val companionDeferred = async {
                    runCatching { fetchCompanionReceivers() }
                        .getOrDefault(emptyList())
                }
                mdnsDeferred.await() to companionDeferred.await()
            }
            val merged = linkedMapOf<String, FCastReceiver>()
            (mdnsResults + companionResults).forEach { rec ->
                val key = "${rec.host}:${rec.port}"
                merged[key] = mergeFCastReceiver(existing = merged[key], incoming = rec)
            }
            val results = merged.values.toList()
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
            "pickReceiver: receiver=%s:%d name=%s appName=%s",
            receiver.host, receiver.port, receiver.name, receiver.appName,
        )
        _pickedReceiver.value = receiver
        _pickedTarget.value = receiver.toCastReceiver()
        appPreferences.setValue(
            appPreferences.lastUsedFCastReceiverHostPort,
            "${receiver.host}:${receiver.port}",
        )
        // Manual host:port entries also get remembered so the next picker open shows them
        // up front without re-typing; probe in background to backfill appName if missing.
        scope.launch {
            rememberedReceiversStore.upsert(receiver)
            if (receiver.appName.isNullOrBlank()) {
                val probe = probeFCastReceiverDetails(receiver.host, receiver.port)
                if (probe.reachable && (!probe.appName.isNullOrBlank() || !probe.displayName.isNullOrBlank())) {
                    val enriched = receiver.copy(
                        name = probe.displayName ?: receiver.name,
                        appName = probe.appName ?: receiver.appName,
                        appVersion = probe.appVersion ?: receiver.appVersion,
                    )
                    if (_pickedReceiver.compareAndSet(receiver, enriched)) {
                        _pickedTarget.value = enriched.toCastReceiver()
                    }
                    rememberedReceiversStore.upsert(enriched)
                }
            }
        }
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
        if (receiver.protocol == CastProtocol.SendspinAvr && !theaterAvrTokenStore.hasToken(receiver.id)) {
            _pendingTheaterPairing.value = TheaterPairingRequest(
                host = receiver.host,
                port = receiver.port,
                useTls = receiver.useTls,
                serverIdHint = receiver.id.removePrefix("SendspinAvr:"),
                roomNameHint = receiver.name,
            )
            return
        }
        // If the user is switching from FCast → Cast/SendspinAvr, tear down any active
        // FCast cast first. Otherwise we'd leave the FCast TV playing alongside the new pick.
        if (_pickedReceiver.value != null && receiver.protocol != CastProtocol.FCast) {
            _pickedReceiver.value = null
            scope.launch { runCatching { controller.stopCast() } }
        }
        _pendingCastError.value = null
        // Do not let delayed teardown close an adapter just opened by an immediate Play tap.
        scope.launch { releaseActiveCastAdapterExcept(receiver.id) }
        _pickedTarget.value = receiver
        if (receiver.protocol == CastProtocol.SendspinAvr) {
            _splitAvMode.value = true
            scope.launch {
                rememberedReceiversStore.upsertCastReceiver(receiver)
            }
        }
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
        if (!isCastSessionAllowed()) {
            Timber.tag(TAG).w("castSpatialItem: blocked (locked or unauthenticated)")
            return false
        }
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
            CastProtocol.AirPlay -> castViaAdapter(target, item, startPositionMs)
            // SendspinAvr execution routes through SplitAvSessionEngine in the next slice.
            CastProtocol.SendspinAvr -> false
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

                Timber.tag(TAG).e(
                    "castViaAdapter: final URL=%s contentType=%s startMs=%d",
                    SplitAvStreamUrlPolicy.scrubUrlForLog(url),
                    guessMediaContentType(url, container),
                    finalStartMs,
                )
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
                update.audioRoute?.let { route ->
                    _activeReceiverAudioRoute.value = route
                    val rcv = controller.activeReceiver.value ?: return@let
                    val routeFingerprint = route.fingerprint?.takeIf { it.isNotBlank() } ?: return@let
                    val routeKey = "${rcv.host}:${rcv.port}:$routeFingerprint"
                    if (activeRoutePersistenceKey != routeKey) {
                        activeRoutePersistenceKey = routeKey
                        val remembered = rememberedReceiversStore.load()
                            .firstOrNull { it.host == rcv.host && it.port == rcv.port }
                        val priorRoute = remembered?.routes
                            ?.firstOrNull { it.fingerprint == routeFingerprint }
                        val fallbackLatency = priorRoute?.baseLatencyMs ?: remembered?.audioLatencyMs
                        val fallbackManual = priorRoute?.manualVideoOffsetMs
                            ?: _manualVideoOffsetMs.value
                        rememberedReceiversStore.ensureRouteRecord(
                            host = rcv.host,
                            port = rcv.port,
                            route = route,
                            fallbackBaseLatencyMs = fallbackLatency,
                            fallbackManualVideoOffsetMs = fallbackManual,
                            fallbackSupportedAudioCodecs = route.supportedAudioCodecs
                                ?: remembered?.supportedAudioCodecs,
                        )
                        if (_splitAvMode.value) {
                            _manualVideoOffsetMs.value = fallbackManual.coerceIn(-200, 200)
                            splitAvController.updateRouteLatencyConfig(
                                baseLatencyMs = fallbackLatency,
                                manualVideoOffsetMs = fallbackManual,
                                learnedCodecCorrections = priorRoute?.learnedCodecCorrections,
                                routeFingerprint = routeFingerprint,
                            )
                        }
                    }
                }
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
     * request. Keep SpatialFin's outgoing URL self-authenticating because receiver capabilities
     * vary by protocol and implementation; FCast now transports headers, but Google Cast,
     * AirPlay, and older FCast targets cannot be assumed to do so. Append the access token as
     * `api_key=` (Jellyfin recognises it as an alternative to the header).
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

    private data class SplitAvAudioSelection(
        val source: SpatialFinSource?,
        val sourceIndex: Int?,
        val audioStream: SpatialFinMediaStream?,
        val preferredAudioLanguage: String?,
    )

    private suspend fun resolveSplitAvAudioSelection(
        item: SpatialFinItem,
        sources: List<SpatialFinSource>,
        forTheaterAvr: Boolean = false,
        receiverAudioCodecs: List<String>? = null,
        requestedMediaSourceIndex: Int? = null,
        requestedAudioStreamIndex: Int? = null,
    ): SplitAvAudioSelection {
        if (sources.isEmpty()) return SplitAvAudioSelection(null, null, null, null)

        val eligiblePairs = sources.mapIndexedNotNull { idx, src ->
            if (forTheaterAvr && src.type != SpatialFinSourceType.REMOTE) null else idx to src
        }
        if (eligiblePairs.isEmpty()) return SplitAvAudioSelection(null, null, null, null)

        val genres = when (item) {
            is SpatialFinMovie -> item.genres
            is SpatialFinEpisode -> runCatching { repository.getShow(item.seriesId).genres }
                .getOrDefault(emptyList())
            else -> emptyList()
        }
        val isAnime = isSplitAvAnimeItem(item, genres, eligiblePairs.map { it.second })
        val seriesOverride = (item as? SpatialFinEpisode)
            ?.seriesId
            ?.toString()
            ?.let { appPreferences.getSeriesLanguageOverride(it) }
        val preferredAudioLanguage = seriesOverride?.audioLanguageCode
            ?: if (isAnime) {
                appPreferences.getValue(appPreferences.animeAudioLanguage)
            } else {
                appPreferences.getValue(appPreferences.nonAnimeAudioLanguage)
                    ?: appPreferences.getValue(appPreferences.preferredAudioLanguage)
            }

        val explicitSourcePair = requestedMediaSourceIndex?.let { idx ->
            eligiblePairs.firstOrNull { it.first == idx }
        }
        val selectedPair = explicitSourcePair ?: chooseSplitAvMediaSource(
            sources = eligiblePairs,
            preferredAudioLanguage = preferredAudioLanguage,
            preferStyledSubtitles = isAnime,
            forTheaterAvr = forTheaterAvr,
            receiverAudioCodecs = receiverAudioCodecs,
        ) ?: eligiblePairs.firstOrNull()

        val source = selectedPair?.second
        val audioStream = source?.let {
            chooseSplitAvAudioStream(
                source = it,
                preferredAudioLanguage = preferredAudioLanguage,
                requestedAudioStreamIndex = requestedAudioStreamIndex,
                forTheaterAvr = forTheaterAvr,
                receiverAudioCodecs = receiverAudioCodecs,
            )
        }
        Timber.tag(TAG).i(
            "split-A/V source selection theaterAvr=%b source=%s(idx=%s) preferredAudio=%s selectedAudioIndex=%s codec=%s language=%s",
            forTheaterAvr,
            source?.name?.ifBlank { source.id } ?: "none",
            selectedPair?.first?.toString() ?: "none",
            preferredAudioLanguage ?: "default",
            audioStream?.index?.toString() ?: "default",
            audioStream?.codec ?: "unknown",
            audioStream?.language ?: "",
        )
        return SplitAvAudioSelection(
            source = source,
            sourceIndex = selectedPair?.first,
            audioStream = audioStream,
            preferredAudioLanguage = preferredAudioLanguage,
        )
    }

    private fun chooseSplitAvMediaSource(
        sources: List<Pair<Int, SpatialFinSource>>,
        preferredAudioLanguage: String?,
        preferStyledSubtitles: Boolean,
        forTheaterAvr: Boolean,
        receiverAudioCodecs: List<String>?,
    ): Pair<Int, SpatialFinSource>? =
        sources.maxByOrNull { (_, source) ->
            var score = 0
            if (!forTheaterAvr && source.type == SpatialFinSourceType.LOCAL) {
                score += 500
            }

            val audioStreams = source.mediaStreams.filter { it.type == MediaStreamType.AUDIO }
            val matchingStreams = if (preferredAudioLanguage.isNullOrBlank()) {
                audioStreams
            } else {
                audioStreams.filter { MediaStreamLanguage.matchesLanguage(it, preferredAudioLanguage) }
            }
            if (!preferredAudioLanguage.isNullOrBlank() && matchingStreams.isNotEmpty()) {
                score += 400
            }
            val bestAudio = (matchingStreams.ifEmpty { audioStreams })
                .maxByOrNull { stream ->
                    if (forTheaterAvr) {
                        theaterAvrCodecScore(stream.codec, receiverAudioCodecs)
                    } else if (isSplitAvFriendlyAudioCodec(stream.codec)) {
                        120
                    } else {
                        -80
                    }
                }
            if (bestAudio != null) {
                score += if (forTheaterAvr) {
                    theaterAvrCodecScore(bestAudio.codec, receiverAudioCodecs)
                } else if (!preferredAudioLanguage.isNullOrBlank() && matchingStreams.isNotEmpty()) {
                    if (isSplitAvFriendlyAudioCodec(bestAudio.codec)) 120 else -80
                } else {
                    0
                }
            }

            if (preferStyledSubtitles && source.mediaStreams.any { stream ->
                    stream.type == MediaStreamType.SUBTITLE &&
                        (stream.codec.equals("ass", ignoreCase = true) ||
                            stream.codec.equals("ssa", ignoreCase = true))
                }
            ) {
                score += 70
            }

            score += audioStreams.size * 10
            score
        }

    private fun chooseSplitAvAudioStream(
        source: SpatialFinSource,
        preferredAudioLanguage: String?,
        requestedAudioStreamIndex: Int? = null,
        forTheaterAvr: Boolean = false,
        receiverAudioCodecs: List<String>? = null,
    ): SpatialFinMediaStream? {
        val audioStreams = source.mediaStreams.filter { it.type == MediaStreamType.AUDIO }
        if (audioStreams.isEmpty()) return null
        if (requestedAudioStreamIndex != null) {
            audioStreams.firstOrNull { it.index == requestedAudioStreamIndex }?.let { return it }
        }
        val candidates = if (preferredAudioLanguage.isNullOrBlank()) {
            audioStreams
        } else {
            audioStreams.filter { MediaStreamLanguage.matchesLanguage(it, preferredAudioLanguage) }
                .ifEmpty { audioStreams }
        }
        return if (forTheaterAvr) {
            candidates.maxByOrNull { stream ->
                theaterAvrCodecScore(stream.codec, receiverAudioCodecs) +
                    theaterChannelLayoutScore(stream.channelLayout)
            }
        } else {
            candidates.first()
        }
    }

    private fun theaterChannelLayoutScore(channelLayout: String?): Int {
        val layout = channelLayout?.lowercase()?.trim().orEmpty()
        return when {
            "7.1" in layout || "atmos" in layout -> 80
            "5.1" in layout -> 60
            "quad" in layout || "4.0" in layout -> 40
            "stereo" in layout || "2.0" in layout -> 20
            else -> 0
        }
    }

    private fun theaterAvrCodecScore(codec: String?, receiverAudioCodecs: List<String>?): Int {
        val normalized = AudioCodecTokens.normalize(codec)
        val supportedByAvr = receiverAudioCodecs.isNullOrEmpty() ||
            ReceiverAudioCodecs.canRenderDirect(normalized, receiverAudioCodecs)
        val fidelityRank = when (normalized) {
            "truehd" -> 300
            "dts-hd" -> 280
            "eac3-joc" -> 260
            "flac", "alac", "pcm", "pcm_s24le", "pcm_s32le", "pcm_f32le", "pcm_s16le" -> 240
            "eac3" -> 200
            "dts" -> 190
            "ac3" -> 150
            "opus" -> 120
            "aac", "vorbis", "mp3" -> 100
            else -> 50
        }
        return if (supportedByAvr) fidelityRank else fidelityRank - 150
    }

    private fun isSplitAvAnimeItem(
        item: SpatialFinItem,
        genres: List<String>,
        mediaSources: List<SpatialFinSource>,
    ): Boolean {
        if (genres.any { it.contains("anime", ignoreCase = true) }) return true
        val hasJapaneseAudio = mediaSources.any { source ->
            source.mediaStreams.any { stream ->
                stream.type == MediaStreamType.AUDIO &&
                    MediaStreamLanguage.matchesLanguage(stream, "jpn")
            }
        }
        val hasStyledSubtitles = mediaSources.any { source ->
            source.mediaStreams.any { stream ->
                stream.type == MediaStreamType.SUBTITLE &&
                    (stream.codec.equals("ass", ignoreCase = true) ||
                        stream.codec.equals("ssa", ignoreCase = true))
            }
        }
        if (hasJapaneseAudio && hasStyledSubtitles) return true

        val titleText = listOf(item.name, item.originalTitle.orEmpty()).joinToString(" ")
        return titleText.any { char ->
            char.code in 0x3040..0x30FF || char.code in 0x4E00..0x9FFF
        }
    }

    private fun isSplitAvFriendlyAudioCodec(codec: String): Boolean =
        when (codec.lowercase()) {
            "aac", "mp4a-latm", "opus", "vorbis", "flac", "mp3" -> true
            else -> false
        }


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
        requestedMediaSourceIndex: Int? = null,
        requestedAudioStreamIndex: Int? = null,
        formFactor: String = "xr_full_space",
        localPlayerIntentBuilder: () -> Intent? = { null },
        localPlayerIntentBuilderForPlan: ((SplitAvLaunchPlan) -> Intent?)? = null,
        localFallbackIntentBuilder: (() -> Intent?)? = null,
        audioRouteOverride: SplitAvAudioRouteInfo.Route? = null,
    ): Boolean {
        val target = _pickedTarget.value ?: _pickedReceiver.value?.toCastReceiver() ?: run {
            Timber.tag(TAG).w("castSpatialItemSplitAv: no receiver picked")
            return false
        }
        if (isLocked()) {
            Timber.tag(TAG).w("castSpatialItemSplitAv: blocked (app locked)")
            return false
        }
        if (!activeSessionObserver.activeSessionState.value.isAuthenticated) {
            if (target.protocol == CastProtocol.SendspinAvr && localFallbackIntentBuilder != null) {
                Timber.tag(TAG).i("castSpatialItemSplitAv: offline while Theater Audio paired; bypassing AVR locally")
                _pendingCastError.value = "Offline playback — using local audio (room pairing kept)."
                launchLocalPlayerIntent(localFallbackIntentBuilder())
                return true
            }
            Timber.tag(TAG).w("castSpatialItemSplitAv: blocked (unauthenticated)")
            return false
        }
        // Refuse the SplitAv code path for receivers that don't advertise the capability.
        if (!SplitAvPolicy.isAvailable(target)) {
            Timber.tag(TAG).w(
                "castSpatialItemSplitAv: receiver %s lacks SplitAv capability — caller should fall back to castSpatialItem()",
                target.id,
            )
            return false
        }
        if (target.protocol == CastProtocol.SendspinAvr) {
            return castSpatialItemSendspinAvr(
                target = target,
                item = item,
                startPositionMs = startPositionMs,
                requestedMediaSourceIndex = requestedMediaSourceIndex,
                requestedAudioStreamIndex = requestedAudioStreamIndex,
                formFactor = formFactor,
                localPlayerIntentBuilder = localPlayerIntentBuilder,
                localPlayerIntentBuilderForPlan = localPlayerIntentBuilderForPlan,
                localFallbackIntentBuilder = localFallbackIntentBuilder,
            )
        }
        // Fresh user-initiated cast re-arms the one-shot self-correcting re-cast guard; a
        // re-cast triggered from a beacon must not (it already set recastDone = true).
        if (!recast) {
            recastDone = false
            lastSplitAvRequest = null
            _pendingAudioTranscodeNotice.value = null
            _activeAudioRoute.value = null
            _activeReceiverAudioRoute.value = null
            _manualVideoOffsetMs.value = 0
            activeRoutePersistenceKey = null
        }
        val receiver = _pickedReceiver.value ?: FCastReceiver(
            host = target.host,
            port = target.port,
            name = target.name,
            appName = target.appName,
            appVersion = target.appVersion,
            source = FCastReceiver.Source.Manual,
        )
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
                val splitAudioSelection = resolveSplitAvAudioSelection(
                    item = item,
                    sources = sources,
                    forTheaterAvr = false,
                    requestedMediaSourceIndex = requestedMediaSourceIndex,
                    requestedAudioStreamIndex = requestedAudioStreamIndex,
                )
                val source = splitAudioSelection.source ?: run {
                    Timber.tag(TAG).w("castSpatialItemSplitAv: no media sources for %s", itemId)
                    return@withContext false
                }
                val selectedAudioStream = splitAudioSelection.audioStream
                val audioCodec = selectedAudioStream?.codec
                val startMs = (startPositionMs ?: 0L).coerceAtLeast(0L)
                val recvKey = "${receiver.host}:${receiver.port}"
                val rememberedReceiver = rememberedReceiversStore.load()
                    .firstOrNull { it.host == receiver.host && it.port == receiver.port }
                val rememberedRoute = rememberedReceiver?.routes
                    ?.maxByOrNull { it.lastSeenMs ?: it.calibratedAtMs ?: 0L }
                rememberedReceiver?.supportedAudioCodecs?.takeIf { it.isNotEmpty() }?.let { codecs ->
                    receiverAudioCaps[recvKey] = codecs
                }
                rememberedRoute?.supportedAudioCodecs?.takeIf { it.isNotEmpty() }?.let { codecs ->
                    receiverAudioCaps[recvKey] = codecs
                }
                val audioDecision = audioCodecDecision(receiver)
                val caps = receiverAudioCaps[recvKey]
                val canDirect = SplitAvAudioRoutePolicy.canDirect(
                    sourceAudioCodec = audioCodec,
                    receiverAudioCodecs = caps,
                    fallbackMode = audioDecision.mode,
                )
                val url: String
                val mediaStartOffsetMs: Long
                var transcoded = false
                var transcodeTargetCodec: String? = null
                if (canDirect) {
                    url = repository.getStreamUrl(
                        itemId = itemId,
                        mediaSourceId = source.id,
                        audioStreamIndex = selectedAudioStream?.index,
                    ).withJellyfinAuth()
                    mediaStartOffsetMs = 0L
                } else {
                    val targets = SplitAvAudioRoutePolicy.preferredTranscodeCodecs(caps)
                    val hls = repository.getAudioTranscodeStreamUrl(
                        itemId,
                        source.id,
                        targets,
                        startMs,
                        selectedAudioStream?.index,
                    )
                    if (hls.isBlank()) {
                        url = repository.getStreamUrl(
                            itemId = itemId,
                            mediaSourceId = source.id,
                            audioStreamIndex = selectedAudioStream?.index,
                        ).withJellyfinAuth()
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

                // 1. Calibrate audio latency if we don't have a cached value for this FCast receiver.
                var audioLatencyMs = rememberedRoute?.baseLatencyMs ?: rememberedReceiver?.audioLatencyMs
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
                val manualVideoOffsetMs = rememberedRoute?.manualVideoOffsetMs ?: 0
                val learnedCodecCorrections = rememberedRoute?.learnedCodecCorrections.orEmpty()
                _manualVideoOffsetMs.value = manualVideoOffsetMs.coerceIn(-200, 200)

                val play = PlayMessageBuilder.build(
                    url = url,
                    container = container,
                    positionSeconds = 0.0,
                    title = title,
                    sourceAudioCodec = audioCodec,
                    audioTranscoded = transcoded,
                    preferredAudioLanguage = splitAudioSelection.preferredAudioLanguage,
                )

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

                splitAvController.start(
                    receiver = receiver,
                    play = play,
                    audioLatencyMs = audioLatencyMs,
                    mediaStartOffsetMs = mediaStartOffsetMs,
                    manualVideoOffsetMs = manualVideoOffsetMs,
                    learnedCodecCorrections = learnedCodecCorrections,
                    routeFingerprint = rememberedRoute?.fingerprint,
                )

                val launchPlan = SplitAvLaunchPlan(
                    mediaSourceIndex = splitAudioSelection.sourceIndex,
                    audioStreamIndex = selectedAudioStream?.index,
                    holdAtStart = false,
                    startPositionMs = startMs.takeIf { it > 0L },
                )
                val intent = localPlayerIntentBuilderForPlan?.invoke(launchPlan)
                    ?: localPlayerIntentBuilder()
                launchLocalPlayerIntent(intent)
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

    private data class BuiltTheaterSession(
        val config: SendspinAvrSessionConfig,
        val launchPlan: SplitAvLaunchPlan,
        val resolvedInitial: ResolvedAudioStream,
    )

    private suspend fun buildSendspinAvrSessionConfig(
        target: CastReceiver,
        item: SpatialFinItem,
        startMs: Long,
        requestedMediaSourceIndex: Int?,
        requestedAudioStreamIndex: Int?,
        formFactor: String,
        startPaused: Boolean = false,
    ): BuiltTheaterSession? {
        val itemId = when (item) {
            is SpatialFinMovie -> item.id
            is SpatialFinEpisode -> item.id
            else -> return null
        }
        val storedToken = theaterAvrTokenStore.getToken(target.id) ?: return null
        val allowedJellyfinHost = runCatching { URI(repository.getBaseUrl()).host }.getOrNull()
        val sources = repository.getMediaSources(itemId = itemId, includePath = false)
        val remoteSources = sources.filter { it.type == SpatialFinSourceType.REMOTE }
        if (remoteSources.isEmpty()) return null

        val remembered = rememberedReceiversStore.load()
            .firstOrNull { it.resolvedId == target.id }
        val cachedCodecs = receiverAudioCaps[target.id] ?: remembered?.supportedAudioCodecs
        val selection = resolveSplitAvAudioSelection(
            item = item,
            sources = sources,
            forTheaterAvr = true,
            receiverAudioCodecs = cachedCodecs,
            requestedMediaSourceIndex = requestedMediaSourceIndex,
            requestedAudioStreamIndex = requestedAudioStreamIndex,
        )
        val source = selection.source ?: return null
        val selectedAudioStream = selection.audioStream
        val audioStreamIndex = selectedAudioStream?.index ?: 0
        val normalizedCodec = AudioCodecTokens.normalize(selectedAudioStream?.codec)
            .ifBlank { "aac" }

        val resolvedInitial = resolveSanitizedTheaterAudioStream(
            itemId = itemId,
            sourceId = source.id,
            audioStreamIndex = audioStreamIndex,
            sourceCodec = normalizedCodec,
            startMs = startMs,
            cachedCodecs = cachedCodecs,
            allowedHost = allowedJellyfinHost,
            target = target,
        ) ?: return null

        val effectiveUseTls = target.useTls || storedToken.requireTls
        val effectiveTarget = if (effectiveUseTls != target.useTls) {
            target.copy(useTls = effectiveUseTls, port = if (storedToken.requireTls && !target.useTls) storedToken.port else target.port)
        } else {
            target
        }
        val endpoint = SendspinAvrHttpClient.Endpoint(
            host = effectiveTarget.host,
            port = effectiveTarget.port,
            useTls = effectiveTarget.useTls,
            roomId = effectiveTarget.roomId,
            tlsSpkiSha256 = storedToken.tlsSpkiSha256,
        )
        val roomGeometry = withTimeoutOrNull(1_200L) {
            (sendspinAvrHttpClient.getRoomProfile(endpoint, storedToken.avrToken) as? SendspinAvrHttpClient.HttpResult.Success)
                ?.value
                ?.let { profile ->
                    RoomGeometryContract(
                        roomProfileVersion = profile.roomProfileVersion,
                        roomName = profile.roomName,
                        layout = profile.layout,
                        lcrScreenPlane = profile.lcrScreenPlane?.let { plane ->
                            TheaterScreenPlaneContract(
                                centerM = plane.centerM,
                                widthM = plane.widthM,
                                heightM = plane.heightM,
                                normalVec = plane.normalVec,
                            )
                        },
                        speakers = profile.speakers.map { sp ->
                            TheaterSpeakerContract(
                                role = sp.role,
                                clientId = sp.clientId,
                                channelIndex = sp.channelIndex,
                                transportTier = sp.transportTier,
                                coordM = sp.coordM,
                            )
                        },
                    )
                }
        }

        val manualOffsetUs = (remembered?.routes?.firstOrNull()?.manualVideoOffsetMs ?: 0) * 1_000L
        val lcrLockEnabled = appPreferences.getValue(appPreferences.xrTheaterLcrScreenLock)
        val clientId = theaterAvrTokenStore.getOrCreateClientId()
        val clientName = Build.MODEL?.takeIf { it.isNotBlank() } ?: "SpatialFin"
        val appVersion = runCatching {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "2.7.74"

        val config = SendspinAvrSessionConfig(
            receiver = effectiveTarget,
            bearerToken = storedToken.avrToken,
            clientId = clientId,
            clientName = clientName,
            formFactor = formFactor,
            appVersion = appVersion,
            streamUrl = resolvedInitial.streamUrl,
            streamLayout = resolvedInitial.streamLayout,
            mediaSourceId = resolvedInitial.mediaSourceId,
            audioStreamIndex = resolvedInitial.audioStreamIndex,
            sourceCodec = resolvedInitial.sourceCodec,
            jellyfinToken = resolvedInitial.jellyfinToken,
            tlsSpkiSha256 = storedToken.tlsSpkiSha256,
            initialTargetPtsUs = startMs * 1_000L,
            clientDisplayOffsetUs = manualOffsetUs,
            initialLcrScreenLock = lcrLockEnabled,
            initialAnchorUuid = remembered?.anchorUuid,
            initialAnchorToMlpTransform = remembered?.anchorToMlpTransform,
            initialRoomGeometry = roomGeometry,
            startPaused = startPaused,
            resolveTrackSwitchStream = { newAudioIndex, newCodec ->
                resolveSanitizedTheaterAudioStream(
                    itemId = itemId,
                    sourceId = source.id,
                    audioStreamIndex = newAudioIndex,
                    sourceCodec = AudioCodecTokens.normalize(newCodec).ifBlank { normalizedCodec },
                    startMs = 0L,
                    cachedCodecs = receiverAudioCaps[target.id] ?: cachedCodecs,
                    allowedHost = allowedJellyfinHost,
                    target = target,
                )
            },
            resolveMediaItemStream = { nextItemIdRaw, nextMediaSourceId, nextAudioIndex ->
                val nextUuid = runCatching { java.util.UUID.fromString(nextItemIdRaw) }.getOrNull()
                    ?: return@SendspinAvrSessionConfig null
                val nextSources = runCatching {
                    repository.getMediaSources(itemId = nextUuid, includePath = false)
                }.getOrDefault(emptyList())
                val nextRemote = nextSources.firstOrNull { src ->
                    src.type == SpatialFinSourceType.REMOTE &&
                        (nextMediaSourceId == null || src.id == nextMediaSourceId)
                } ?: nextSources.firstOrNull { it.type == SpatialFinSourceType.REMOTE }
                    ?: return@SendspinAvrSessionConfig null
                val nextStream = nextRemote.mediaStreams.firstOrNull {
                    it.type == MediaStreamType.AUDIO && it.index == nextAudioIndex
                } ?: nextRemote.mediaStreams.firstOrNull { it.type == MediaStreamType.AUDIO }
                resolveSanitizedTheaterAudioStream(
                    itemId = nextUuid,
                    sourceId = nextRemote.id,
                    audioStreamIndex = nextStream?.index ?: nextAudioIndex,
                    sourceCodec = AudioCodecTokens.normalize(nextStream?.codec).ifBlank { "aac" },
                    startMs = 0L,
                    cachedCodecs = receiverAudioCaps[target.id] ?: cachedCodecs,
                    allowedHost = allowedJellyfinHost,
                    target = target,
                )
            },
        )
        val launchPlan = SplitAvLaunchPlan(
            mediaSourceIndex = selection.sourceIndex,
            audioStreamIndex = resolvedInitial.audioStreamIndex,
            holdAtStart = true,
            startPositionMs = startMs.takeIf { it > 0L },
        )
        return BuiltTheaterSession(
            config = config,
            launchPlan = launchPlan,
            resolvedInitial = resolvedInitial,
        )
    }

    private suspend fun castSpatialItemSendspinAvr(
        target: CastReceiver,
        item: SpatialFinItem,
        startPositionMs: Long?,
        requestedMediaSourceIndex: Int?,
        requestedAudioStreamIndex: Int?,
        formFactor: String,
        localPlayerIntentBuilder: () -> Intent?,
        localPlayerIntentBuilderForPlan: ((SplitAvLaunchPlan) -> Intent?)?,
        localFallbackIntentBuilder: (() -> Intent?)?,
    ): Boolean {
        val itemId = when (item) {
            is SpatialFinMovie -> item.id
            is SpatialFinEpisode -> item.id
            else -> {
                Timber.tag(TAG).w("castSpatialItemSendspinAvr: ineligible item type %s", item::class.simpleName)
                return false
            }
        }
        val storedToken = theaterAvrTokenStore.getToken(target.id)
        if (storedToken == null) {
            Timber.tag(TAG).w("castSpatialItemSendspinAvr: missing token for %s; opening pairing", target.id)
            _pendingTheaterPairing.value = TheaterPairingRequest(
                host = target.host,
                port = target.port,
                useTls = target.useTls,
                serverIdHint = target.id.removePrefix("SendspinAvr:"),
                roomNameHint = target.name,
                repairReason = "Room credentials missing or revoked — please pair again.",
            )
            return false
        }
        val jellyfinToken = repository.getAccessToken()?.takeIf { it.isNotBlank() } ?: run {
            _pendingCastError.value = "Jellyfin session token unavailable."
            return false
        }
        if (SendspinAvrRequestValidator.validateToken(jellyfinToken, "jellyfin_token")
            !is SendspinAvrRequestValidator.ValidationResult.Valid
        ) {
            Timber.tag(TAG).w("castSpatialItemSendspinAvr: invalid Jellyfin token characters")
            _pendingCastError.value = "Jellyfin session token rejected by safety check."
            return false
        }

        return withContext(Dispatchers.IO) {
            try {
                val sources = repository.getMediaSources(itemId = itemId, includePath = false)
                val remoteSources = sources.filter { it.type == SpatialFinSourceType.REMOTE }
                if (remoteSources.isEmpty()) {
                    Timber.tag(TAG).i("castSpatialItemSendspinAvr: offline-only item %s; playing locally", itemId)
                    _pendingCastError.value = "Offline media — playing locally on this device (room pairing kept)."
                    launchLocalPlayerIntent(localFallbackIntentBuilder?.invoke())
                    return@withContext true
                }

                val startMs = (startPositionMs ?: 0L).coerceAtLeast(0L)
                val built = buildSendspinAvrSessionConfig(
                    target = target,
                    item = item,
                    startMs = startMs,
                    requestedMediaSourceIndex = requestedMediaSourceIndex,
                    requestedAudioStreamIndex = requestedAudioStreamIndex,
                    formFactor = formFactor,
                    startPaused = false,
                ) ?: run {
                    _pendingCastError.value = "Could not resolve a valid Jellyfin stream URL for ${target.name}."
                    return@withContext false
                }
                val config = built.config
                val launchPlan = built.launchPlan
                val resolvedInitial = built.resolvedInitial

                lastTheaterPlaybackContext = ActiveTheaterPlaybackContext(
                    item = item,
                    requestedMediaSourceIndex = requestedMediaSourceIndex,
                    requestedAudioStreamIndex = requestedAudioStreamIndex,
                    formFactor = formFactor,
                )

                _activeAudioRoute.value = SplitAvAudioRouteInfo(
                    route = SplitAvAudioRouteInfo.Route.Direct,
                    sourceAudioCodec = resolvedInitial.sourceCodec,
                    targetAudioCodec = null,
                )
                Timber.tag(TAG).i(
                    "castSpatialItemSendspinAvr: starting sessionId=%s target=%s codec=%s sourceIdx=%s audioIdx=%d startMs=%d",
                    config.sessionId,
                    target.id,
                    resolvedInitial.sourceCodec,
                    launchPlan.mediaSourceIndex?.toString() ?: "none",
                    resolvedInitial.audioStreamIndex,
                    startMs,
                )

                val started = splitAvController.startSendspinAvr(config)
                if (!started) {
                    val banner = splitAvController.theaterSnapshot.value?.banner
                    if (banner?.requiresTakeoverConfirm == true || banner?.requiresRepair == true) {
                        Timber.tag(TAG).i(
                            "castSpatialItemSendspinAvr: awaiting user action (%s) before launch",
                            banner.code,
                        )
                        return@withContext false
                    }
                    val reason = banner?.message?.takeIf { it.isNotBlank() }
                        ?: "Theater Audio unavailable — playing locally."
                    Timber.tag(TAG).w(
                        "castSpatialItemSendspinAvr: start failed (%s); falling back to local playback",
                        banner?.code ?: "unknown",
                    )
                    _pendingCastError.value = reason
                    val fallback = localFallbackIntentBuilder?.invoke()
                    if (fallback != null) {
                        launchLocalPlayerIntent(fallback)
                        return@withContext true
                    }
                    return@withContext false
                }

                val intent = (localPlayerIntentBuilderForPlan?.invoke(launchPlan)
                    ?: localPlayerIntentBuilder())?.apply {
                    putExtra(SplitAvBridgeIpcMessage.EXTRA_SPLIT_AV_HOLD_AT_START, true)
                    launchPlan.mediaSourceIndex?.let { idx ->
                        if (!hasExtra("mediaSourceIndex")) putExtra("mediaSourceIndex", idx)
                    }
                    launchPlan.audioStreamIndex?.let { idx ->
                        if (!hasExtra("audioStreamIndex")) putExtra("audioStreamIndex", idx)
                    }
                }
                launchLocalPlayerIntent(intent)
                true
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "castSpatialItemSendspinAvr failed for %s", itemId)
                _pendingCastError.value = e.message ?: "Could not start Theater Audio playback."
                false
            }
        }
    }

    /**
     * Serialized break-before-make in-player audio room switch (Room A -> Room B,
     * Room A -> Local Device, or Local Device -> Room B) without restarting the player Activity.
     */
    internal suspend fun switchTheaterRoomInPlayer(targetReceiverId: String) {
        val gen = roomSwitchGeneration.incrementAndGet()
        roomSwitchMutex.withLock {
            if (gen != roomSwitchGeneration.get()) return
            val activeState = splitAvController.state.value
            if (targetReceiverId == TheaterClientEvent.LOCAL_AUDIO_RECEIVER_ID &&
                activeState == SplitAvController.State.Idle
            ) {
                return
            }
            if (targetReceiverId == _pickedTarget.value?.id &&
                activeState != SplitAvController.State.Idle
            ) {
                return
            }

            val master = SplitAvVideoBridge.activeMaster.value
            if (master == null) {
                Timber.tag(TAG).w("switchTheaterRoomInPlayer: no active SplitAvVideoMaster")
                return
            }

            val wasPlaying = master.isPlaying.value
            master.pauseFromMaster()
            withTimeoutOrNull(600L) { master.isPlaying.first { !it } }
            val posMs = master.currentPositionMs().coerceAtLeast(0L)
            if (gen != roomSwitchGeneration.get()) return

            Timber.tag(TAG).i(
                "switchTheaterRoomInPlayer: target=%s posMs=%d wasPlaying=%b gen=%d",
                targetReceiverId,
                posMs,
                wasPlaying,
                gen,
            )

            splitAvController.stopActiveEngine("room_switch")

            if (targetReceiverId == TheaterClientEvent.LOCAL_AUDIO_RECEIVER_ID) {
                master.setPlaybackSpeed(1.0f)
                master.setAudioMuted(false)
                master.foldBackToLocal()
                if (wasPlaying) {
                    master.resumeFromMaster()
                }
                splitAvController.emitTheaterToast("Switched audio to This Device")
                return
            }

            val target = _theaterReceivers.value
                .firstOrNull { it.isPaired && it.receiver.id == targetReceiverId }
                ?.receiver
                ?: rememberedReceiversStore.load()
                    .firstOrNull { it.resolvedId == targetReceiverId && it.castProtocol == CastProtocol.SendspinAvr }
                    ?.takeIf { theaterAvrTokenStore.hasToken(it.resolvedId) }
                    ?.let { remembered ->
                        CastReceiver(
                            id = remembered.resolvedId,
                            name = remembered.name,
                            host = remembered.host,
                            port = remembered.port,
                            protocol = CastProtocol.SendspinAvr,
                            modelName = remembered.modelName,
                            appName = remembered.appName ?: "SpatialFin AVR",
                            appVersion = remembered.appVersion,
                            capabilities = setOf(
                                CastCapability.Audio,
                                CastCapability.Volume,
                                CastCapability.SplitAv,
                                CastCapability.TheaterAudioV1,
                            ),
                            source = CastReceiver.Source.Manual,
                            useTls = remembered.useTls,
                            roomId = remembered.roomId,
                        )
                    }

            val ctx = lastTheaterPlaybackContext
            if (target == null || ctx == null) {
                Timber.tag(TAG).w(
                    "switchTheaterRoomInPlayer: missing target (%s) or playback context",
                    targetReceiverId,
                )
                master.setPlaybackSpeed(1.0f)
                master.setAudioMuted(false)
                master.foldBackToLocal()
                if (wasPlaying) master.resumeFromMaster()
                splitAvController.emitTheaterToast("Room switch unavailable — using local audio")
                return
            }

            val built = runCatching {
                buildSendspinAvrSessionConfig(
                    target = target,
                    item = ctx.item,
                    startMs = posMs,
                    requestedMediaSourceIndex = ctx.requestedMediaSourceIndex,
                    requestedAudioStreamIndex = ctx.requestedAudioStreamIndex,
                    formFactor = ctx.formFactor,
                    startPaused = !wasPlaying,
                )
            }.onFailure { err ->
                Timber.tag(TAG).w(err, "switchTheaterRoomInPlayer: config build failed for %s", target.id)
            }.getOrNull()

            if (built == null || gen != roomSwitchGeneration.get()) {
                master.setPlaybackSpeed(1.0f)
                master.setAudioMuted(false)
                master.foldBackToLocal()
                if (wasPlaying) master.resumeFromMaster()
                splitAvController.emitTheaterToast("Failed to switch to ${target.name} — using local audio")
                return
            }

            _activeAudioRoute.value = SplitAvAudioRouteInfo(
                route = SplitAvAudioRouteInfo.Route.Direct,
                sourceAudioCodec = built.resolvedInitial.sourceCodec,
                targetAudioCodec = null,
            )

            val started = splitAvController.startSendspinAvr(built.config)
            if (started && gen == roomSwitchGeneration.get()) {
                pickCastReceiver(target)
                splitAvController.emitTheaterToast("Switched audio to ${target.name}")
            } else {
                if (started) {
                    splitAvController.stopActiveEngine("room_switch_superseded")
                }
                master.setPlaybackSpeed(1.0f)
                master.setAudioMuted(false)
                master.foldBackToLocal()
                if (wasPlaying) master.resumeFromMaster()
                splitAvController.emitTheaterToast("Failed to switch to ${target.name} — using local audio")
            }
        }
    }

    private suspend fun resolveSanitizedTheaterAudioStream(
        itemId: java.util.UUID,
        sourceId: String,
        audioStreamIndex: Int,
        sourceCodec: String,
        startMs: Long,
        cachedCodecs: List<String>?,
        allowedHost: String?,
        target: CastReceiver,
    ): ResolvedAudioStream? {
        val freshToken = repository.getAccessToken()?.takeIf { it.isNotBlank() } ?: return null
        if (SendspinAvrRequestValidator.validateToken(freshToken, "jellyfin_token")
            !is SendspinAvrRequestValidator.ValidationResult.Valid
        ) {
            return null
        }
        val audioDecision = audioCodecDecision(target.host, target.port)
        val canDirect = cachedCodecs.isNullOrEmpty() || SplitAvAudioRoutePolicy.canDirect(
            sourceAudioCodec = sourceCodec,
            receiverAudioCodecs = cachedCodecs,
            fallbackMode = audioDecision.mode,
        )
        val rawUrl: String
        val layout: String
        if (canDirect) {
            rawUrl = repository.getStreamUrl(
                itemId = itemId,
                mediaSourceId = sourceId,
                audioStreamIndex = audioStreamIndex,
            )
            layout = "multi_stream_container"
        } else {
            val targets = SplitAvAudioRoutePolicy.preferredTranscodeCodecs(cachedCodecs)
            val transcodeUrl = repository.getAudioTranscodeStreamUrl(
                itemId,
                sourceId,
                targets,
                startMs,
                audioStreamIndex,
            )
            if (transcodeUrl.isNotBlank()) {
                rawUrl = transcodeUrl
                layout = "single_audio_stream"
            } else {
                rawUrl = repository.getStreamUrl(
                    itemId = itemId,
                    mediaSourceId = sourceId,
                    audioStreamIndex = audioStreamIndex,
                )
                layout = "multi_stream_container"
            }
        }
        val sanitizedUrl = SplitAvStreamUrlPolicy.stripAndValidateTheaterStreamUrl(
            rawUrl = rawUrl,
            allowedHost = allowedHost,
        ).getOrElse { err ->
            Timber.tag(TAG).w(err, "Rejected unsafe Theater Audio stream URL")
            return null
        }
        return ResolvedAudioStream(
            streamUrl = sanitizedUrl,
            streamLayout = layout,
            mediaSourceId = sourceId,
            audioStreamIndex = audioStreamIndex,
            sourceCodec = sourceCodec,
            jellyfinToken = freshToken,
        )
    }

    private suspend fun launchLocalPlayerIntent(intent: Intent?) {
        withContext(Dispatchers.Main) {
            val readyIntent = intent?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (readyIntent != null) {
                runCatching { XrPlayerSessionClient.notifyPendingLaunch(readyIntent) }
                runCatching { appContext.startActivity(readyIntent) }
                    .onFailure { Timber.tag(TAG).w(it, "split-A/V local launch failed") }
            } else {
                Timber.tag(TAG).w("split-A/V local intent builder returned null")
            }
        }
    }

    suspend fun pause() {
        if (isSplitAvTransportActive()) {
            splitAvController.pause()
            return
        }
        when (_pickedTarget.value?.protocol) {
            CastProtocol.FCast -> controller.pause()
            CastProtocol.GoogleCast, CastProtocol.AirPlay -> activeCastAdapter?.pause()
            CastProtocol.SendspinAvr, null -> Unit
        }
    }

    suspend fun resume() {
        if (isSplitAvTransportActive()) {
            splitAvController.resume()
            return
        }
        when (_pickedTarget.value?.protocol) {
            CastProtocol.FCast -> controller.resume()
            CastProtocol.GoogleCast, CastProtocol.AirPlay -> activeCastAdapter?.play()
            CastProtocol.SendspinAvr, null -> Unit
        }
    }

    suspend fun seek(seconds: Double) {
        if (isSplitAvTransportActive()) {
            splitAvController.seekTo((seconds * 1000.0).toLong())
            return
        }
        when (_pickedTarget.value?.protocol) {
            CastProtocol.FCast -> controller.seek(seconds)
            CastProtocol.GoogleCast, CastProtocol.AirPlay -> activeCastAdapter?.seek((seconds * 1000.0).toLong())
            CastProtocol.SendspinAvr, null -> Unit
        }
    }

    private fun isSplitAvTransportActive(): Boolean =
        _pickedTarget.value?.protocol in setOf(CastProtocol.FCast, CastProtocol.SendspinAvr) &&
            splitAvController.state.value in setOf(
                SplitAvController.State.Connecting,
                SplitAvController.State.AwaitingMaster,
                SplitAvController.State.Playing,
                SplitAvController.State.Degraded,
            )

    /**
     * Seek by a relative offset, computed against the last known position. Accurate to the
     * most recent state update we got from whichever protocol is active. No-op if we don't
     * have a known position yet (the receiver hasn't pushed its first update).
     */
    suspend fun seekBy(deltaSeconds: Double) {
        val currentMs = if (isSplitAvTransportActive()) {
            splitAvController.currentMasterPositionMs() ?: _activePositionMs.value
        } else {
            _activePositionMs.value
        }
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
            CastProtocol.GoogleCast, CastProtocol.AirPlay -> activeCastAdapter?.setVolume(clamped.toFloat())
            CastProtocol.SendspinAvr -> splitAvController.handleTheaterClientEvent(
                TheaterClientEvent.SetVolume(
                    volume = clamped,
                    muted = splitAvController.theaterSnapshot.value?.muted ?: false,
                ),
            )
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
            CastProtocol.SendspinAvr, null -> Unit
        }
    }

    /** Tear down the connection AND clear the picked-receiver intent. Idempotent. */
    suspend fun stopCast() {
        lastSplitAvRequest = null
        lastTheaterPlaybackContext = null
        splitAvController.stop()
        controller.stopCast()
        releaseActiveCastAdapter()
        _pickedReceiver.value = null
        _pickedTarget.value = null
        _subtitleFidelity.value = SubtitleFidelity.None
        _activeAudioFormat.value = null
        _activeAudioRoute.value = null
        _activeReceiverAudioRoute.value = null
        _manualVideoOffsetMs.value = 0
        activeRoutePersistenceKey = null
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
        _activeReceiverAudioRoute.value = null
        activeRoutePersistenceKey = null
    }

    /** True while a split-A/V session is active (between start and end). */
    val splitAvActive: StateFlow<SplitAvController.State> = splitAvController.state

    init {
        startFCastStateBridge()
        startTheaterStateBridge()
        observeLockAndSessionTeardown()
        scope.launch { runCatching { syncCompanionTheaterRooms() } }
    }

    private fun startTheaterStateBridge() {
        scope.launch {
            splitAvController.state.collect { splitState ->
                if (_pickedTarget.value?.protocol != CastProtocol.SendspinAvr) return@collect
                _activeMediaState.value = when (splitState) {
                    SplitAvController.State.Playing -> dev.jdtech.jellyfin.cast.CastMediaState.Playing
                    SplitAvController.State.Connecting,
                    SplitAvController.State.AwaitingMaster,
                    -> dev.jdtech.jellyfin.cast.CastMediaState.Buffering
                    SplitAvController.State.Degraded -> dev.jdtech.jellyfin.cast.CastMediaState.Paused
                    SplitAvController.State.Idle,
                    SplitAvController.State.Stopped,
                    -> dev.jdtech.jellyfin.cast.CastMediaState.Idle
                }
            }
        }
        scope.launch {
            splitAvController.theaterSnapshot.collect { snap ->
                if (snap == null) return@collect
                if (_pickedTarget.value?.protocol == CastProtocol.SendspinAvr) {
                    _currentVolume.value = snap.masterVolume.coerceIn(0.0, 1.0)
                    _activeVolume.value = snap.masterVolume.toFloat().coerceIn(0f, 1f)
                }
                val banner = snap.banner
                if (banner?.requiresRepair == true) {
                    val target = _pickedTarget.value
                    _pendingTheaterPairing.value = TheaterPairingRequest(
                        host = target?.host.orEmpty(),
                        port = target?.port ?: SendspinAvrDiscoveryBrowser.SENDSPIN_AVR_DEFAULT_PORT,
                        useTls = target?.useTls ?: false,
                        serverIdHint = snap.serverId,
                        roomNameHint = snap.serverName ?: target?.name,
                        repairReason = banner.message.ifBlank { "Room authorization expired — please pair again." },
                    )
                }
            }
        }
        scope.launch {
            combine(
                _theaterReceivers,
                _pickedTarget,
                splitAvController.state,
                SplitAvVideoBridge.activeMaster,
            ) { receivers, picked, splitState, activeMaster ->
                val canSwitchInPlayer = splitState != SplitAvController.State.Idle ||
                    (activeMaster != null && lastTheaterPlaybackContext != null)
                if (!canSwitchInPlayer) {
                    emptyList()
                } else {
                    receivers
                        .filter { it.isPaired }
                        .map { entry ->
                            TheaterRoomSummaryContract(
                                receiverId = entry.receiver.id,
                                roomId = entry.receiver.roomId ?: "room-default",
                                roomName = entry.receiver.name,
                                layout = entry.receiver.modelName.orEmpty(),
                                isCurrent = splitState != SplitAvController.State.Idle &&
                                    picked?.id == entry.receiver.id,
                            )
                        }
                }
            }.distinctUntilChanged().collect { rooms ->
                splitAvController.setAvailableTheaterRooms(rooms)
            }
        }
        scope.launch {
            splitAvController.roomSwitchRequests.collect { targetReceiverId ->
                runCatching { switchTheaterRoomInPlayer(targetReceiverId) }
                    .onFailure { err ->
                        Timber.tag(TAG).w(err, "roomSwitchRequests handler failed for %s", targetReceiverId)
                    }
            }
        }
    }

    private fun observeLockAndSessionTeardown() {
        scope.launch {
            var lastSessionKey: Triple<String?, java.util.UUID?, Boolean>? = null
            combine(
                appLockObserver.lockState,
                activeSessionObserver.activeSessionState,
            ) { lock, session ->
                Triple(
                    lock == AppLockManager.LockState.LOCKED,
                    session.isAuthenticated,
                    Triple(session.serverId, session.userId, session.isAuthenticated),
                )
            }.distinctUntilChanged().collect { (locked, authenticated, sessionKey) ->
                val switchedOrDropped =
                    lastSessionKey != null &&
                        lastSessionKey != sessionKey &&
                        (lastSessionKey!!.third || !authenticated)
                lastSessionKey = sessionKey
                if (locked || !authenticated || switchedOrDropped) {
                    if (isCasting() || hasCastIntent() || isSplitAvTransportActive() || lastSplitAvRequest != null) {
                        val stopReason = if (locked) "app_lock" else "user_switch"
                        Timber.tag(TAG).i(
                            "CastSessionManager: tearing down active cast/Split-A/V (locked=%b authed=%b switched=%b reason=%s)",
                            locked,
                            authenticated,
                            switchedOrDropped,
                            stopReason,
                        )
                        lastSplitAvRequest = null
                        lastTheaterPlaybackContext = null
                        _pendingTheaterPairing.value = null
                        runCatching { splitAvController.stop(stopReason) }
                        runCatching { stopCast() }
                    }
                }
            }
        }
    }

    private companion object {
        const val TAG = "FCastSession"
        const val DISCOVERY_QUICK_MS = 4_000L
        const val DISCOVERY_FRESH_MS = 30_000L
    }
}
