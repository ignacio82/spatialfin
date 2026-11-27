package dev.spatialfin.fcast.session

import dev.jdtech.jellyfin.cast.CastProtocol
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.SendspinAvrTransport
import dev.jdtech.jellyfin.fcast.protocol.PlayMessage
import dev.jdtech.jellyfin.fcast.sender.FCastCastingController
import dev.jdtech.jellyfin.fcast.sender.FCastReceiver
import dev.jdtech.jellyfin.network.UrlTargetPolicy
import dev.jdtech.jellyfin.network.newBuilderWithSsrfGuard
import dev.jdtech.jellyfin.player.core.splitav.SplitAvTheaterBridge
import dev.jdtech.jellyfin.player.core.splitav.TheaterClientEvent
import dev.jdtech.jellyfin.player.core.splitav.TheaterSessionSnapshot
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.settings.domain.TheaterAvrTokenStore
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * Thin `@Singleton` Split A/V dispatcher routing between legacy FCast ([FCastSplitAvEngine])
 * and Spec 0 Theater Audio ([SendspinAvrSplitAvEngine]) behind a narrow [SplitAvSessionEngine]
 * boundary while keeping all existing FCast caller signatures unchanged.
 */
@Singleton
class SplitAvController @Inject constructor(
    castingController: FCastCastingController,
    rememberedReceiversStore: RememberedReceiversStore,
    okHttpClient: OkHttpClient,
    private val appPreferences: AppPreferences,
    private val theaterAvrTokenStore: TheaterAvrTokenStore,
) {
    enum class State {
        Idle,
        Connecting,
        AwaitingMaster,
        Playing,
        Degraded,
        Stopped,
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val sendspinOkHttpClient: OkHttpClient = okHttpClient
        .newBuilderWithSsrfGuard(
            policy = UrlTargetPolicy.LAN_ONLY,
            allowCrossOriginRedirects = false,
        )
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val fcastEngine = FCastSplitAvEngine(castingController, rememberedReceiversStore)

    private val sendspinEngine = SendspinAvrSplitAvEngine(
        transportFactory = { config ->
            val allowedHost = runCatching { URI(config.streamUrl).host }.getOrNull()
            SendspinAvrTransport(
                okHttpClient = sendspinOkHttpClient,
                allowedJellyfinHost = allowedHost,
            )
        },
        onAuthInvalid = { serverId -> theaterAvrTokenStore.removeToken(serverId) },
        onLcrScreenLockChanged = { enabled ->
            appPreferences.setValue(appPreferences.xrTheaterLcrScreenLock, enabled)
        },
    )

    private val _activeProtocol = MutableStateFlow(CastProtocol.FCast)
    private val _state = MutableStateFlow(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _lastDriftMs = MutableStateFlow<Long?>(null)
    val lastDriftMs: StateFlow<Long?> = _lastDriftMs.asStateFlow()

    private val _theaterSnapshot = MutableStateFlow<TheaterSessionSnapshot?>(null)
    val theaterSnapshot: StateFlow<TheaterSessionSnapshot?> = _theaterSnapshot.asStateFlow()

    var clock: () -> Long
        get() = fcastEngine.clock
        set(value) {
            fcastEngine.clock = value
        }

    var traceSink: SplitAvTraceSink
        get() = fcastEngine.traceSink
        set(value) {
            fcastEngine.traceSink = value
        }

    init {
        scope.launch {
            combine(_activeProtocol, fcastEngine.state, sendspinEngine.state) { protocol, fc, sa ->
                if (protocol == CastProtocol.SendspinAvr) sa else fc
            }.collect { _state.value = it }
        }
        scope.launch {
            combine(_activeProtocol, fcastEngine.lastDriftMs, sendspinEngine.lastDriftMs) { protocol, fc, sa ->
                if (protocol == CastProtocol.SendspinAvr) sa else fc
            }.collect { _lastDriftMs.value = it }
        }
        scope.launch {
            combine(_activeProtocol, fcastEngine.theaterSnapshot, sendspinEngine.theaterSnapshot) { protocol, fc, sa ->
                if (protocol == CastProtocol.SendspinAvr) sa else fc
            }.collect { snap ->
                _theaterSnapshot.value = snap
                SplitAvTheaterBridge.updateSnapshot(snap)
            }
        }
        scope.launch {
            SplitAvTheaterBridge.clientEvents.collect { event ->
                if (_activeProtocol.value == CastProtocol.SendspinAvr) {
                    sendspinEngine.handleTheaterClientEvent(event)
                }
            }
        }
    }

    private fun currentEngine(): SplitAvSessionEngine =
        if (_activeProtocol.value == CastProtocol.SendspinAvr) sendspinEngine else fcastEngine

    suspend fun start(
        receiver: FCastReceiver,
        play: PlayMessage,
        audioLatencyMs: Int = 0,
        mediaStartOffsetMs: Long = 0L,
        manualVideoOffsetMs: Int = 0,
        learnedCodecCorrections: Map<String, Int> = emptyMap(),
        routeFingerprint: String? = null,
    ) {
        if (_activeProtocol.value == CastProtocol.SendspinAvr) {
            sendspinEngine.stop("switched_to_fcast")
        }
        _activeProtocol.value = CastProtocol.FCast
        fcastEngine.start(
            receiver = receiver,
            play = play,
            audioLatencyMs = audioLatencyMs,
            mediaStartOffsetMs = mediaStartOffsetMs,
            manualVideoOffsetMs = manualVideoOffsetMs,
            learnedCodecCorrections = learnedCodecCorrections,
            routeFingerprint = routeFingerprint,
        )
    }

    suspend fun startSendspinAvr(config: SendspinAvrSessionConfig): Boolean {
        if (_activeProtocol.value == CastProtocol.FCast && fcastEngine.state.value != State.Idle) {
            fcastEngine.stop("switched_to_sendspin_avr")
        }
        _activeProtocol.value = CastProtocol.SendspinAvr
        return sendspinEngine.start(config)
    }

    fun setManualVideoOffset(offsetMs: Int) {
        fcastEngine.setManualVideoOffset(offsetMs)
    }

    fun updateRouteLatencyConfig(
        baseLatencyMs: Int?,
        manualVideoOffsetMs: Int?,
        learnedCodecCorrections: Map<String, Int>?,
        routeFingerprint: String?,
    ) {
        fcastEngine.updateRouteLatencyConfig(
            baseLatencyMs = baseLatencyMs,
            manualVideoOffsetMs = manualVideoOffsetMs,
            learnedCodecCorrections = learnedCodecCorrections,
            routeFingerprint = routeFingerprint,
        )
    }

    suspend fun pause() = currentEngine().pause()

    suspend fun resume() = currentEngine().resume()

    suspend fun seekTo(positionMs: Long) = currentEngine().seekTo(positionMs)

    fun currentMasterPositionMs(): Long? = currentEngine().currentMasterPositionMs()

    suspend fun stop() {
        currentEngine().stop("user_stop")
    }

    suspend fun stop(reason: String) {
        currentEngine().stop(reason)
    }

    suspend fun endFromMaster() = currentEngine().endFromMaster()

    suspend fun endByReceiver() = currentEngine().endByReceiver()

    fun onMasterDisconnected(userExit: Boolean) {
        currentEngine().onMasterDisconnected(userExit)
    }

    suspend fun handleTheaterClientEvent(event: TheaterClientEvent) {
        currentEngine().handleTheaterClientEvent(event)
    }

    fun shutdown() {
        fcastEngine.shutdown()
        sendspinEngine.shutdown()
        scope.cancel()
    }

    fun tagAsAudio(play: PlayMessage): PlayMessage = fcastEngine.tagAsAudio(play)
}
