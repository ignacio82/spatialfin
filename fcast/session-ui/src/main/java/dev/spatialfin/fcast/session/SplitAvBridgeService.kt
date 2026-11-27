package dev.spatialfin.fcast.session

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Messenger
import dagger.hilt.android.AndroidEntryPoint
import dev.jdtech.jellyfin.cast.CastCapability
import dev.jdtech.jellyfin.player.core.splitav.ProxyVideoMaster
import dev.jdtech.jellyfin.player.core.splitav.SplitAvBridgeIpcMessage
import dev.jdtech.jellyfin.player.core.splitav.SplitAvVideoBridge
import dev.jdtech.jellyfin.player.core.splitav.SplitAvVideoTiming
import dev.jdtech.jellyfin.player.core.splitav.TheaterClientEvent
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Cross-process bridge endpoint for split-A/V (`:xrplayer` <-> `:main`).
 *
 * Dispatches incoming Messenger IPC onto a dedicated `HandlerThread`, attaches an
 * `IBinder.DeathRecipient` so abrupt `:xrplayer` process termination immediately pauses room
 * audio and enters bounded grace teardown, and forwards `T_play` barriers + Theater snapshots/events.
 */
@AndroidEntryPoint
class SplitAvBridgeService : Service() {

    @Inject lateinit var splitAvController: SplitAvController

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val proxy = ProxyVideoMaster()

    private lateinit var handlerThread: HandlerThread
    private lateinit var incomingHandler: Handler
    private lateinit var messenger: Messenger

    @Volatile
    private var linkedClientBinder: IBinder? = null

    private val clientDeathRecipient = IBinder.DeathRecipient {
        if (!::incomingHandler.isInitialized) return@DeathRecipient
        incomingHandler.post {
            Timber.tag(TAG).w("xrplayer client binder died — detaching master and pausing room audio")
            unlinkClientDeathLocked()
            SplitAvVideoBridge.unbind(proxy)
            proxy.detachClient()
            splitAvController.onMasterDisconnected(userExit = false)
        }
    }

    override fun onCreate() {
        super.onCreate()
        handlerThread = HandlerThread("SplitAvBridgeIpc").apply { start() }
        incomingHandler = Handler(handlerThread.looper) { msg ->
            handleIncomingMessage(msg)
        }
        messenger = Messenger(incomingHandler)

        serviceScope.launch {
            splitAvController.theaterSnapshot.collect { snapshot ->
                if (snapshot != null) {
                    proxy.sendTheaterSnapshot(snapshot)
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        unlinkClientDeathLocked()
        SplitAvVideoBridge.unbind(proxy)
        proxy.detachClient()
        serviceScope.cancel()
        if (::handlerThread.isInitialized) {
            handlerThread.quitSafely()
        }
        super.onDestroy()
    }

    private fun handleIncomingMessage(msg: android.os.Message): Boolean {
        when (msg.what) {
            SplitAvBridgeIpcMessage.MSG_REGISTER -> {
                val client = msg.replyTo
                if (client == null) {
                    Timber.tag(TAG).w("REGISTER without replyTo — ignoring")
                    return true
                }
                val activeReceiver = SplitAvSessionRegistry.activeReceiver.value
                if (activeReceiver == null || CastCapability.SplitAv !in activeReceiver.capabilities) {
                    Timber.tag(TAG).w(
                        "REGISTER rejected — no SplitAv-capable session (active=%s)",
                        activeReceiver?.protocol?.displayName ?: "none",
                    )
                    return true
                }
                unlinkClientDeathLocked()
                linkClientDeathLocked(client.binder)
                proxy.attachClient(client)
                SplitAvVideoBridge.bind(proxy)
                splitAvController.theaterSnapshot.value?.let { proxy.sendTheaterSnapshot(it) }
                Timber.tag(TAG).i("SplitAvBridgeService: client registered, proxy bound to bridge")
                return true
            }
            SplitAvBridgeIpcMessage.MSG_UNREGISTER -> {
                val userExit = msg.arg1 == SplitAvBridgeIpcMessage.UNREGISTER_REASON_USER_EXIT
                unlinkClientDeathLocked()
                SplitAvVideoBridge.unbind(proxy)
                proxy.detachClient()
                splitAvController.onMasterDisconnected(userExit = userExit)
                Timber.tag(TAG).i("SplitAvBridgeService: client unregistered userExit=%b", userExit)
                return true
            }
            SplitAvBridgeIpcMessage.MSG_STATE_UPDATE -> {
                val data = msg.data ?: return true
                val timing =
                    if (data.containsKey(SplitAvBridgeIpcMessage.KEY_VIDEO_TIMING_POSITION_MS) &&
                        data.containsKey(SplitAvBridgeIpcMessage.KEY_VIDEO_TIMING_RELEASE_MS) &&
                        data.containsKey(SplitAvBridgeIpcMessage.KEY_VIDEO_TIMING_SAMPLE_MS)
                    ) {
                        val posMs = data.getLong(SplitAvBridgeIpcMessage.KEY_VIDEO_TIMING_POSITION_MS)
                        val relMs = data.getLong(SplitAvBridgeIpcMessage.KEY_VIDEO_TIMING_RELEASE_MS)
                        val sampleMs = data.getLong(SplitAvBridgeIpcMessage.KEY_VIDEO_TIMING_SAMPLE_MS)
                        val posUs = if (data.containsKey(SplitAvBridgeIpcMessage.KEY_VIDEO_TIMING_POSITION_US)) {
                            data.getLong(SplitAvBridgeIpcMessage.KEY_VIDEO_TIMING_POSITION_US)
                        } else {
                            posMs * 1_000L
                        }
                        val relUs = if (data.containsKey(SplitAvBridgeIpcMessage.KEY_VIDEO_TIMING_RELEASE_BOOTTIME_US)) {
                            data.getLong(SplitAvBridgeIpcMessage.KEY_VIDEO_TIMING_RELEASE_BOOTTIME_US)
                        } else {
                            relMs * 1_000L
                        }
                        SplitAvVideoTiming(
                            mediaPositionMs = posMs,
                            releaseMonotonicMs = relMs,
                            sampledMonotonicMs = sampleMs,
                            mediaPositionUs = posUs,
                            releaseBoottimeUs = relUs,
                        )
                    } else {
                        null
                    }
                proxy.applyStateUpdate(
                    positionMs = data.getLong(SplitAvBridgeIpcMessage.KEY_POSITION_MS),
                    playing = data.getBoolean(SplitAvBridgeIpcMessage.KEY_PLAYING),
                    videoTiming = timing,
                )
                return true
            }
            SplitAvBridgeIpcMessage.MSG_USER_SEEK -> {
                val data = msg.data ?: return true
                proxy.applyUserSeek(data.getLong(SplitAvBridgeIpcMessage.KEY_POSITION_MS))
                return true
            }
            SplitAvBridgeIpcMessage.MSG_END_FROM_MASTER -> {
                Timber.tag(TAG).i("MSG_END_FROM_MASTER — folding audio back to headset")
                serviceScope.launch {
                    runCatching { splitAvController.endFromMaster() }
                        .onFailure { Timber.tag(TAG).w(it, "endFromMaster failed") }
                }
                return true
            }
            SplitAvBridgeIpcMessage.MSG_FIRST_FRAME_READY -> {
                val data = msg.data ?: return true
                val seq = data.getLong(SplitAvBridgeIpcMessage.KEY_BARRIER_SEQ)
                val ptsUs = data.getLong(SplitAvBridgeIpcMessage.KEY_ACTUAL_PTS_US)
                proxy.applyFirstFrameReady(seq, ptsUs)
                return true
            }
            SplitAvBridgeIpcMessage.MSG_THEATER_EVENT -> {
                val raw = msg.data?.getString(SplitAvBridgeIpcMessage.KEY_JSON_PAYLOAD) ?: return true
                runCatching { TheaterClientEvent.fromJsonString(raw) }
                    .onSuccess { proxy.applyTheaterEvent(it) }
                    .onFailure { Timber.tag(TAG).w(it, "Malformed MSG_THEATER_EVENT payload") }
                return true
            }
            else -> return false
        }
    }

    private fun linkClientDeathLocked(binder: IBinder) {
        runCatching {
            binder.linkToDeath(clientDeathRecipient, 0)
            linkedClientBinder = binder
        }.onFailure { Timber.tag(TAG).w(it, "Failed to linkToDeath on xrplayer binder") }
    }

    private fun unlinkClientDeathLocked() {
        val prev = linkedClientBinder ?: return
        linkedClientBinder = null
        runCatching { prev.unlinkToDeath(clientDeathRecipient, 0) }
    }

    private companion object {
        const val TAG = "SplitAvBridgeService"
    }
}
