package dev.jdtech.jellyfin.player.core.splitav

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Cross-process IPC constants for split-A/V (`:main` <-> `:xrplayer`).
 */
object SplitAvBridgeIpcMessage {
    /** Boolean Intent extra: hold local player paused on target keyframe until T_play barrier fires. */
    const val EXTRA_SPLIT_AV_HOLD_AT_START: String = "splitAv.holdAtStart"

    /** Client → service. `Message.replyTo` carries the client Messenger. */
    const val MSG_REGISTER: Int = 1

    /** Client → service. `msg.arg1` carries [UNREGISTER_REASON_HANDOFF] or [UNREGISTER_REASON_USER_EXIT]. */
    const val MSG_UNREGISTER: Int = 2

    /**
     * Client → service, sent ~20 Hz while playing. Payload:
     *  - long "positionMs", bool "playing"
     *  - legacy ms timing keys + optional µs keys ([KEY_VIDEO_TIMING_POSITION_US], [KEY_VIDEO_TIMING_RELEASE_BOOTTIME_US])
     */
    const val MSG_STATE_UPDATE: Int = 3

    /** Service → client. Payload: bool "muted". */
    const val MSG_SET_AUDIO_MUTED: Int = 10

    /** Service → client. Payload: float "factor". */
    const val MSG_SET_PLAYBACK_SPEED: Int = 11

    /** Service → client. Payload: long "positionMs". */
    const val MSG_SEEK_TO: Int = 12

    /** Service → client. No payload. */
    const val MSG_PAUSE_FROM_MASTER: Int = 13

    /** Service → client. No payload. */
    const val MSG_RESUME_FROM_MASTER: Int = 14

    /** Service → client. No payload. */
    const val MSG_STOP_FROM_MASTER: Int = 15

    /** Client → service. Payload: [KEY_POSITION_MS]. */
    const val MSG_USER_SEEK: Int = 16

    /** Client → service. Fold audio back to headset without stopping playback. */
    const val MSG_END_FROM_MASTER: Int = 17

    /** Service → client. Fold split-A/V audio back to the headset. */
    const val MSG_FOLD_BACK: Int = 18

    /** Service → client. Hold paused at target keyframe. Payload: [KEY_BARRIER_SEQ], [KEY_POSITION_MS]. */
    const val MSG_PREPARE_HOLD: Int = 20

    /** Client → service. Held frame ready. Payload: [KEY_BARRIER_SEQ], [KEY_ACTUAL_PTS_US]. */
    const val MSG_FIRST_FRAME_READY: Int = 21

    /** Service → client. Release held frame at `t_play_client_us`. Payload: [KEY_BARRIER_SEQ], [KEY_T_PLAY_CLIENT_US]. */
    const val MSG_START_AT_BOOTTIME_US: Int = 22

    /** Service → client. JSON snapshot payload ([KEY_JSON_PAYLOAD]) of [TheaterSessionSnapshot]. */
    const val MSG_THEATER_SNAPSHOT: Int = 23

    /** Client → service. JSON event payload ([KEY_JSON_PAYLOAD]) of [TheaterClientEvent]. */
    const val MSG_THEATER_EVENT: Int = 24

    const val UNREGISTER_REASON_HANDOFF: Int = 0
    const val UNREGISTER_REASON_USER_EXIT: Int = 1

    const val KEY_POSITION_MS: String = "positionMs"
    const val KEY_PLAYING: String = "playing"
    const val KEY_MUTED: String = "muted"
    const val KEY_FACTOR: String = "factor"
    const val KEY_VIDEO_TIMING_POSITION_MS: String = "videoTimingPositionMs"
    const val KEY_VIDEO_TIMING_RELEASE_MS: String = "videoTimingReleaseMs"
    const val KEY_VIDEO_TIMING_SAMPLE_MS: String = "videoTimingSampleMs"
    const val KEY_VIDEO_TIMING_POSITION_US: String = "videoTimingPositionUs"
    const val KEY_VIDEO_TIMING_RELEASE_BOOTTIME_US: String = "videoTimingReleaseBoottimeUs"
    const val KEY_BARRIER_SEQ: String = "barrierSeq"
    const val KEY_ACTUAL_PTS_US: String = "actualPtsUs"
    const val KEY_T_PLAY_CLIENT_US: String = "tPlayClientUs"
    const val KEY_JSON_PAYLOAD: String = "jsonPayload"
}

/**
 * Service-side proxy implementing [SplitAvVideoMaster] so the main-process controller drives
 * `:xrplayer` seamlessly across Messenger IPC.
 */
class ProxyVideoMaster(
    private val onStopFromMaster: () -> Unit = {},
) : SplitAvVideoMaster {

    private val _isPlaying = MutableStateFlow(false)
    override val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _userSeeks = MutableSharedFlow<Long>(replay = 0, extraBufferCapacity = 8)
    override val userSeeks: SharedFlow<Long> = _userSeeks.asSharedFlow()

    private val _firstFrameReady = MutableSharedFlow<SplitAvFirstFrameReady>(
        replay = 0,
        extraBufferCapacity = 8,
    )
    override val firstFrameReady: SharedFlow<SplitAvFirstFrameReady> =
        _firstFrameReady.asSharedFlow()

    private val _isBufferingStalled = MutableStateFlow(false)
    override val isBufferingStalled: StateFlow<Boolean> = _isBufferingStalled.asStateFlow()

    private val _trackSwitches = MutableSharedFlow<SplitAvTrackSwitchRequest>(
        replay = 0,
        extraBufferCapacity = 8,
    )
    override val trackSwitches: SharedFlow<SplitAvTrackSwitchRequest> =
        _trackSwitches.asSharedFlow()

    private val _mediaItemTransitions = MutableSharedFlow<SplitAvMediaItemTransition>(
        replay = 0,
        extraBufferCapacity = 8,
    )
    override val mediaItemTransitions: SharedFlow<SplitAvMediaItemTransition> =
        _mediaItemTransitions.asSharedFlow()

    @Volatile
    private var lastPositionMs: Long = 0L

    @Volatile
    private var lastVideoTiming: SplitAvVideoTiming? = null

    @Volatile
    private var client: Messenger? = null

    fun attachClient(c: Messenger) {
        client = c
    }

    fun detachClient() {
        client = null
        _isPlaying.value = false
        _isBufferingStalled.value = false
    }

    fun applyStateUpdate(
        positionMs: Long,
        playing: Boolean,
        videoTiming: SplitAvVideoTiming? = null,
    ) {
        lastPositionMs = positionMs
        if (videoTiming != null) lastVideoTiming = videoTiming
        _isPlaying.value = playing
    }

    fun applyUserSeek(positionMs: Long) {
        lastPositionMs = positionMs
        _userSeeks.tryEmit(positionMs)
    }

    fun applyFirstFrameReady(barrierSeq: Long, actualPtsUs: Long) {
        _firstFrameReady.tryEmit(SplitAvFirstFrameReady(barrierSeq, actualPtsUs))
    }

    fun applyTheaterEvent(event: TheaterClientEvent) {
        when (event) {
            is TheaterClientEvent.BufferingStall -> _isBufferingStalled.value = event.stalled
            is TheaterClientEvent.AudioTrackChanged -> _trackSwitches.tryEmit(
                SplitAvTrackSwitchRequest(event.mediaStreamIndex, event.codec),
            )
            is TheaterClientEvent.MediaItemChanged -> _mediaItemTransitions.tryEmit(
                SplitAvMediaItemTransition(
                    itemId = event.itemId,
                    mediaSourceId = event.mediaSourceId,
                    audioStreamIndex = event.audioStreamIndex,
                ),
            )
            else -> Unit
        }
        SplitAvTheaterBridge.emitClientEvent(event)
    }

    fun sendTheaterSnapshot(snapshot: TheaterSessionSnapshot) {
        send(SplitAvBridgeIpcMessage.MSG_THEATER_SNAPSHOT) {
            putString(SplitAvBridgeIpcMessage.KEY_JSON_PAYLOAD, snapshot.toJsonString())
        }
    }

    override fun currentPositionMs(): Long = lastPositionMs

    override fun renderedVideoTiming(): SplitAvVideoTiming? = lastVideoTiming

    override fun setPlaybackSpeed(factor: Float) {
        send(SplitAvBridgeIpcMessage.MSG_SET_PLAYBACK_SPEED) {
            putFloat(SplitAvBridgeIpcMessage.KEY_FACTOR, factor)
        }
    }

    override fun seekTo(positionMs: Long) {
        send(SplitAvBridgeIpcMessage.MSG_SEEK_TO) {
            putLong(SplitAvBridgeIpcMessage.KEY_POSITION_MS, positionMs)
        }
    }

    override fun pauseFromMaster() = sendNoData(SplitAvBridgeIpcMessage.MSG_PAUSE_FROM_MASTER)
    override fun resumeFromMaster() = sendNoData(SplitAvBridgeIpcMessage.MSG_RESUME_FROM_MASTER)

    override fun stopFromMaster() {
        sendNoData(SplitAvBridgeIpcMessage.MSG_STOP_FROM_MASTER)
        runCatching { onStopFromMaster() }
    }

    override fun setAudioMuted(muted: Boolean) {
        send(SplitAvBridgeIpcMessage.MSG_SET_AUDIO_MUTED) {
            putBoolean(SplitAvBridgeIpcMessage.KEY_MUTED, muted)
        }
    }

    override fun foldBackToLocal() = sendNoData(SplitAvBridgeIpcMessage.MSG_FOLD_BACK)

    override fun prepareAndHoldAt(barrierSeq: Long, targetPositionMs: Long) {
        send(SplitAvBridgeIpcMessage.MSG_PREPARE_HOLD) {
            putLong(SplitAvBridgeIpcMessage.KEY_BARRIER_SEQ, barrierSeq)
            putLong(SplitAvBridgeIpcMessage.KEY_POSITION_MS, targetPositionMs)
        }
    }

    override fun startAtBoottimeUs(barrierSeq: Long, tPlayClientUs: Long) {
        send(SplitAvBridgeIpcMessage.MSG_START_AT_BOOTTIME_US) {
            putLong(SplitAvBridgeIpcMessage.KEY_BARRIER_SEQ, barrierSeq)
            putLong(SplitAvBridgeIpcMessage.KEY_T_PLAY_CLIENT_US, tPlayClientUs)
        }
    }

    private fun sendNoData(what: Int) = send(what) { }

    private fun send(what: Int, dataBuilder: Bundle.() -> Unit) {
        val c = client ?: return
        val msg = Message.obtain(null, what)
        msg.data = Bundle().apply(dataBuilder)
        try {
            c.send(msg)
        } catch (e: RemoteException) {
            Timber.tag(TAG).w(e, "ProxyVideoMaster: client died while sending %d", what)
            detachClient()
        }
    }

    companion object {
        private const val TAG = "SplitAvBridgeIpc"
    }
}

/**
 * Client-side IPC adapter used in `:xrplayer`. Binds to `SplitAvBridgeService` in `:main`,
 * pushes player state + `T_play` / theater events, and receives commands + snapshots.
 */
class SplitAvBridgeIpcClient(
    private val context: Context,
    private val adapter: PlayerSplitAvAdapter,
) {
    private var serviceMessenger: Messenger? = null
    private var ipcScope: CoroutineScope? = null

    @Volatile
    private var lastBarrierSeq: Long = 0L

    private val incomingHandler = Handler(Looper.getMainLooper()) { msg ->
        when (msg.what) {
            SplitAvBridgeIpcMessage.MSG_SET_AUDIO_MUTED ->
                adapter.setAudioMuted(msg.data.getBoolean(SplitAvBridgeIpcMessage.KEY_MUTED))
            SplitAvBridgeIpcMessage.MSG_SET_PLAYBACK_SPEED ->
                adapter.setPlaybackSpeed(msg.data.getFloat(SplitAvBridgeIpcMessage.KEY_FACTOR))
            SplitAvBridgeIpcMessage.MSG_SEEK_TO ->
                adapter.seekTo(msg.data.getLong(SplitAvBridgeIpcMessage.KEY_POSITION_MS))
            SplitAvBridgeIpcMessage.MSG_PAUSE_FROM_MASTER -> adapter.pauseFromMaster()
            SplitAvBridgeIpcMessage.MSG_RESUME_FROM_MASTER -> adapter.resumeFromMaster()
            SplitAvBridgeIpcMessage.MSG_STOP_FROM_MASTER -> adapter.stopFromMaster()
            SplitAvBridgeIpcMessage.MSG_FOLD_BACK -> adapter.foldBackToLocal()
            SplitAvBridgeIpcMessage.MSG_PREPARE_HOLD -> {
                val seq = msg.data.getLong(SplitAvBridgeIpcMessage.KEY_BARRIER_SEQ)
                lastBarrierSeq = seq
                val posMs = msg.data.getLong(SplitAvBridgeIpcMessage.KEY_POSITION_MS)
                adapter.prepareAndHoldAt(seq, posMs)
            }
            SplitAvBridgeIpcMessage.MSG_START_AT_BOOTTIME_US -> {
                val seq = msg.data.getLong(SplitAvBridgeIpcMessage.KEY_BARRIER_SEQ)
                val tPlayUs = msg.data.getLong(SplitAvBridgeIpcMessage.KEY_T_PLAY_CLIENT_US)
                adapter.startAtBoottimeUs(seq, tPlayUs)
            }
            SplitAvBridgeIpcMessage.MSG_THEATER_SNAPSHOT -> {
                val payload = msg.data.getString(SplitAvBridgeIpcMessage.KEY_JSON_PAYLOAD)
                    ?: return@Handler true
                runCatching { TheaterSessionSnapshot.fromJsonString(payload) }
                    .onSuccess { SplitAvTheaterBridge.updateSnapshot(it) }
                    .onFailure { Timber.tag(TAG).w(it, "Invalid MSG_THEATER_SNAPSHOT JSON") }
            }
            else -> return@Handler false
        }
        true
    }
    private val incomingMessenger = Messenger(incomingHandler)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val outbound = Messenger(service)
            serviceMessenger = outbound
            val register = Message.obtain(null, SplitAvBridgeIpcMessage.MSG_REGISTER)
            register.replyTo = incomingMessenger
            try {
                outbound.send(register)
                Timber.tag(TAG).i("SplitAvBridgeIpcClient: registered with service")
            } catch (e: RemoteException) {
                Timber.tag(TAG).w(e, "REGISTER failed")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceMessenger = null
            Timber.tag(TAG).w("SplitAvBridgeIpcClient: service disconnected")
        }
    }

    private var bound: Boolean = false

    fun connect(): Boolean {
        if (bound) return true
        startForwardingFlows()
        val intent = Intent().apply {
            component = ComponentName(context.packageName, SPLIT_AV_BRIDGE_SERVICE_CLASS)
        }
        bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        if (!bound) {
            stopForwardingFlows()
            Timber.tag(TAG).w("bindService returned false — service not found?")
        }
        return bound
    }

    fun pushState(
        positionMs: Long,
        playing: Boolean,
        videoTiming: SplitAvVideoTiming? = null,
    ) {
        val sm = serviceMessenger ?: return
        val msg = Message.obtain(null, SplitAvBridgeIpcMessage.MSG_STATE_UPDATE)
        msg.data = Bundle().apply {
            putLong(SplitAvBridgeIpcMessage.KEY_POSITION_MS, positionMs)
            putBoolean(SplitAvBridgeIpcMessage.KEY_PLAYING, playing)
            videoTiming?.let {
                putLong(SplitAvBridgeIpcMessage.KEY_VIDEO_TIMING_POSITION_MS, it.mediaPositionMs)
                putLong(SplitAvBridgeIpcMessage.KEY_VIDEO_TIMING_RELEASE_MS, it.releaseMonotonicMs)
                putLong(SplitAvBridgeIpcMessage.KEY_VIDEO_TIMING_SAMPLE_MS, it.sampledMonotonicMs)
                putLong(SplitAvBridgeIpcMessage.KEY_VIDEO_TIMING_POSITION_US, it.mediaPositionUs)
                putLong(SplitAvBridgeIpcMessage.KEY_VIDEO_TIMING_RELEASE_BOOTTIME_US, it.releaseBoottimeUs)
            }
        }
        sendSafe(sm, msg)
    }

    fun pushUserSeek(positionMs: Long) {
        val sm = serviceMessenger ?: return
        val msg = Message.obtain(null, SplitAvBridgeIpcMessage.MSG_USER_SEEK)
        msg.data = Bundle().apply {
            putLong(SplitAvBridgeIpcMessage.KEY_POSITION_MS, positionMs)
        }
        sendSafe(sm, msg)
    }

    fun pushFirstFrameReady(barrierSeq: Long, actualPtsUs: Long) {
        val sm = serviceMessenger ?: return
        val msg = Message.obtain(null, SplitAvBridgeIpcMessage.MSG_FIRST_FRAME_READY)
        msg.data = Bundle().apply {
            putLong(SplitAvBridgeIpcMessage.KEY_BARRIER_SEQ, barrierSeq)
            putLong(SplitAvBridgeIpcMessage.KEY_ACTUAL_PTS_US, actualPtsUs)
        }
        sendSafe(sm, msg)
    }

    fun pushTheaterEvent(event: TheaterClientEvent) {
        val sm = serviceMessenger ?: return
        val msg = Message.obtain(null, SplitAvBridgeIpcMessage.MSG_THEATER_EVENT)
        msg.data = Bundle().apply {
            putString(SplitAvBridgeIpcMessage.KEY_JSON_PAYLOAD, event.toJsonString())
        }
        sendSafe(sm, msg)
    }

    fun requestEndFromMaster() {
        val sm = serviceMessenger ?: return
        sendSafe(sm, Message.obtain(null, SplitAvBridgeIpcMessage.MSG_END_FROM_MASTER))
    }

    fun disconnect(reason: Int = SplitAvBridgeIpcMessage.UNREGISTER_REASON_HANDOFF) {
        stopForwardingFlows()
        if (!bound) return
        serviceMessenger?.let { sm ->
            runCatching {
                val unregister = Message.obtain(null, SplitAvBridgeIpcMessage.MSG_UNREGISTER)
                unregister.arg1 = reason
                sm.send(unregister)
            }
        }
        runCatching { context.unbindService(connection) }
        serviceMessenger = null
        bound = false
    }

    private fun startForwardingFlows() {
        stopForwardingFlows()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        ipcScope = scope
        SplitAvTheaterBridge.setOutboundEventForwarder(::pushTheaterEvent)

        scope.launch {
            var lastPlaying: Boolean? = null
            adapter.isPlaying.collect { playing ->
                if (playing != lastPlaying) {
                    lastPlaying = playing
                    pushState(
                        positionMs = adapter.currentPositionMs(),
                        playing = playing,
                        videoTiming = adapter.renderedVideoTiming(),
                    )
                }
            }
        }
        scope.launch {
            adapter.firstFrameReady.collect { ready ->
                pushFirstFrameReady(ready.barrierSeq, ready.actualPtsUs)
            }
        }
        scope.launch {
            var lastStalled = false
            adapter.isBufferingStalled.collect { stalled ->
                if (stalled != lastStalled) {
                    lastStalled = stalled
                    pushTheaterEvent(TheaterClientEvent.BufferingStall(lastBarrierSeq, stalled))
                }
            }
        }
        scope.launch {
            adapter.trackSwitches.collect { switch ->
                pushTheaterEvent(TheaterClientEvent.AudioTrackChanged(switch.audioStreamIndex, switch.codec))
            }
        }
        scope.launch {
            adapter.mediaItemTransitions.collect { transition ->
                pushTheaterEvent(
                    TheaterClientEvent.MediaItemChanged(
                        itemId = transition.itemId,
                        mediaSourceId = transition.mediaSourceId,
                        audioStreamIndex = transition.audioStreamIndex,
                    ),
                )
            }
        }
    }

    private fun stopForwardingFlows() {
        SplitAvTheaterBridge.setOutboundEventForwarder(null)
        ipcScope?.cancel()
        ipcScope = null
    }

    private fun sendSafe(messenger: Messenger, message: Message) {
        try {
            messenger.send(message)
        } catch (_: RemoteException) {
            // Service died — onServiceDisconnected will clear reference.
        }
    }

    companion object {
        private const val TAG = "SplitAvBridgeIpc"
        const val SPLIT_AV_BRIDGE_SERVICE_CLASS: String =
            "dev.spatialfin.fcast.session.SplitAvBridgeService"
    }
}
