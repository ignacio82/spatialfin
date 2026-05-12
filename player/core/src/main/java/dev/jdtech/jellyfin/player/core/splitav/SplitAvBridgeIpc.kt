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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * Cross-process IPC for split-A/V.
 *
 * `XrPlayerActivity` runs in the `:xrplayer` process (to work around a Filament-on-`SurfaceEntity`
 * teardown crash), but `SplitAvController` lives in the main process alongside the FCast sender.
 * The original [SplitAvVideoBridge] is a per-process Kotlin object, so binding a master in
 * `:xrplayer` is invisible to the controller in main — pause/seek/drift cascade can't work.
 *
 * This protocol fixes it with [android.os.Messenger]-based IPC:
 *
 *  1. The local-player Activity in `:xrplayer` creates a [PlayerSplitAvAdapter] against its
 *     ExoPlayer (unchanged) and exposes it to a [SplitAvBridgeIpcClient] running in the same
 *     process.
 *  2. The client binds to a `SplitAvBridgeService` (running in main process), sends REGISTER
 *     with its callback [Messenger], and pushes [SplitAvBridgeIpcMessage.MSG_STATE_UPDATE]
 *     frames roughly every 50 ms with the player's current position + isPlaying state.
 *  3. The service hosts a [ProxyVideoMaster] that implements [SplitAvVideoMaster] and reflects
 *     those pushed updates. It registers itself with the main-process [SplitAvVideoBridge] so
 *     [dev.spatialfin.fcast.session.SplitAvController] sees a master and starts drift
 *     correction + the pause-mirror coroutine.
 *  4. Commands from the controller (`pauseFromMaster`, `seekTo`, `setPlaybackSpeed`, ...)
 *     turn into outbound Messenger messages from the service back to the `:xrplayer` client,
 *     which applies them to its local ExoPlayer via the original adapter.
 *
 * `currentPositionMs()` is served from the latest pushed value (≤ 50 ms stale, well inside the
 * policy's 20 ms-equivalent precision when paired with networkOneWayMs). Synchronous binder
 * calls are deliberately avoided to keep the controller's beacon loop non-blocking.
 */

/** Message ids for the split-A/V IPC protocol. Kept off in their own namespace so the */
/** SplitAvBridgeService is forward-compatible with future protocol bumps. */
object SplitAvBridgeIpcMessage {
    /** Client → service. `Message.replyTo` carries the client Messenger. */
    const val MSG_REGISTER: Int = 1

    /** Client → service. No payload — service will drop the proxy from the bridge. */
    const val MSG_UNREGISTER: Int = 2

    /**
     * Client → service, sent ~20 Hz while playing. Payload:
     *  - long  "positionMs"
     *  - bool  "playing"
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

    const val KEY_POSITION_MS: String = "positionMs"
    const val KEY_PLAYING: String = "playing"
    const val KEY_MUTED: String = "muted"
    const val KEY_FACTOR: String = "factor"
}

/**
 * Service-side proxy. Implements [SplitAvVideoMaster] so the main-process
 * [SplitAvController] can drive it as if it were the real local player. Translates each
 * call into a Messenger message and dispatches it to the connected `:xrplayer` client.
 *
 * Position is served from the latest STATE_UPDATE push. `isPlaying` is a [StateFlow] backed
 * by the same pushes, so the controller's pause-mirror coroutine sees user-driven
 * play/pause transitions cross-process.
 */
class ProxyVideoMaster(
    private val onStopFromMaster: () -> Unit = {},
) : SplitAvVideoMaster {

    private val _isPlaying = MutableStateFlow(false)
    override val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    @Volatile
    private var lastPositionMs: Long = 0L

    @Volatile
    private var client: Messenger? = null

    /** Attach the client Messenger received on MSG_REGISTER. */
    fun attachClient(c: Messenger) {
        client = c
    }

    /** Detach on MSG_UNREGISTER or when the client dies. */
    fun detachClient() {
        client = null
        _isPlaying.value = false
    }

    /** Apply a STATE_UPDATE push from the client. */
    fun applyStateUpdate(positionMs: Long, playing: Boolean) {
        lastPositionMs = positionMs
        _isPlaying.value = playing
    }

    override fun currentPositionMs(): Long = lastPositionMs

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
        // The controller's flow also calls onStopFromMaster locally; mirror the contract.
        runCatching { onStopFromMaster() }
    }

    override fun setAudioMuted(muted: Boolean) {
        send(SplitAvBridgeIpcMessage.MSG_SET_AUDIO_MUTED) {
            putBoolean(SplitAvBridgeIpcMessage.KEY_MUTED, muted)
        }
    }

    private fun sendNoData(what: Int) = send(what) { /* empty */ }

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
 * Client-side wrapper that an Activity in `:xrplayer` uses. Binds to `SplitAvBridgeService`
 * in the main process, pushes player state updates, and dispatches inbound commands to the
 * supplied [PlayerSplitAvAdapter].
 *
 * Lifecycle:
 *  - [connect] binds the Service and starts pushing state. Idempotent.
 *  - [pushState] should be called from the player's main thread when isPlaying changes or
 *    on a periodic ticker (recommend ~20 Hz so position never lags more than 50 ms).
 *  - [disconnect] sends UNREGISTER and unbinds. Call from `onDestroy`.
 *
 * Thread-safety: all IPC operations marshal to the binder thread internally; callers can
 * invoke from the main thread freely.
 */
class SplitAvBridgeIpcClient(
    private val context: Context,
    private val adapter: PlayerSplitAvAdapter,
) {

    private var serviceMessenger: Messenger? = null
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

    /** Bind to the service if not already bound. Returns true if binding initiated. */
    fun connect(): Boolean {
        if (bound) return true
        val intent = Intent().apply {
            // Service lives in the main app process. Both processes share the application
            // package, so we resolve by class name + package name.
            component = ComponentName(context.packageName, SPLIT_AV_BRIDGE_SERVICE_CLASS)
        }
        bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        if (!bound) Timber.tag(TAG).w("bindService returned false — service not found?")
        return bound
    }

    /** Push the current player state to the service. Cheap; safe to call at 20 Hz. */
    fun pushState(positionMs: Long, playing: Boolean) {
        val sm = serviceMessenger ?: return
        val msg = Message.obtain(null, SplitAvBridgeIpcMessage.MSG_STATE_UPDATE)
        msg.data = Bundle().apply {
            putLong(SplitAvBridgeIpcMessage.KEY_POSITION_MS, positionMs)
            putBoolean(SplitAvBridgeIpcMessage.KEY_PLAYING, playing)
        }
        try {
            sm.send(msg)
        } catch (e: RemoteException) {
            // Service died — onServiceDisconnected will re-null it.
        }
    }

    fun disconnect() {
        if (!bound) return
        serviceMessenger?.let { sm ->
            runCatching {
                val unregister = Message.obtain(null, SplitAvBridgeIpcMessage.MSG_UNREGISTER)
                sm.send(unregister)
            }
        }
        runCatching { context.unbindService(connection) }
        serviceMessenger = null
        bound = false
    }

    companion object {
        private const val TAG = "SplitAvBridgeIpc"

        /**
         * Fully-qualified class name of the Service implementation. Lives in `:app:unified`
         * but we resolve it by name to keep `:player:core` from depending on `:app:unified`.
         */
        const val SPLIT_AV_BRIDGE_SERVICE_CLASS: String =
            "dev.spatialfin.fcast.session.SplitAvBridgeService"
    }
}
