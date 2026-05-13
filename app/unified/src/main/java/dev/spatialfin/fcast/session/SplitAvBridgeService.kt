package dev.spatialfin.fcast.session

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Messenger
import dev.jdtech.jellyfin.player.core.splitav.ProxyVideoMaster
import dev.jdtech.jellyfin.player.core.splitav.SplitAvBridgeIpcMessage
import dev.jdtech.jellyfin.cast.CastCapability
import dev.jdtech.jellyfin.player.core.splitav.SplitAvVideoBridge
import timber.log.Timber

/**
 * Cross-process bridge endpoint for split-A/V. Hosts a [Messenger] that the `:xrplayer`-process
 * Activity ([dev.jdtech.jellyfin.player.xr.XrPlayerActivity]) binds to. Maintains a single
 * [ProxyVideoMaster] that mirrors the remote player's state and is registered with the
 * main-process [SplitAvVideoBridge] so [SplitAvController] sees a master and can drive it.
 *
 * Why this is a separate Service (not just a binder on an existing component):
 *
 *  - The client lives in `:xrplayer` and is short-lived (one playback session). A Service
 *    gives us a `bindService()` ↔ `ServiceConnection` lifecycle that auto-unbinds when the
 *    client process dies and re-bindable on the next session.
 *  - Keeping the IPC entry point off the Application class keeps cross-process state out of
 *    `UnifiedApplication.onCreate`, where a transient bind error could brick app startup.
 *
 * Process scope: this Service runs in the **main** SpatialFin process (no `android:process`
 * override in the manifest). [SplitAvVideoBridge] and [SplitAvController] are singletons in
 * the same process, so once the proxy is bound everything below is in-process again.
 */
class SplitAvBridgeService : Service() {

    private val proxy = ProxyVideoMaster()

    private val incomingHandler = Handler(Looper.getMainLooper()) { msg ->
        when (msg.what) {
            SplitAvBridgeIpcMessage.MSG_REGISTER -> {
                val client = msg.replyTo
                if (client == null) {
                    Timber.tag(TAG).w("REGISTER without replyTo — ignoring")
                    return@Handler true
                }
                // Defense in depth: the proxy should only attach when a SplitAv-capable session
                // is active. The session manager already filters non-FCast receivers before
                // calling SplitAvController.start, but if a stray bind reaches us with no active
                // session (or a non-SplitAv-capable one), refuse rather than half-bind the
                // master. This is unreachable in production today; the check exists so a future
                // protocol that wires into this Service can't silently break split mode.
                val activeReceiver = SplitAvSessionRegistry.activeReceiver.value
                if (activeReceiver == null || CastCapability.SplitAv !in activeReceiver.capabilities) {
                    Timber.tag(TAG).w(
                        "REGISTER rejected — no SplitAv-capable session (active=%s)",
                        activeReceiver?.protocol?.displayName ?: "none",
                    )
                    return@Handler true
                }
                proxy.attachClient(client)
                SplitAvVideoBridge.bind(proxy)
                Timber.tag(TAG).i("SplitAvBridgeService: client registered, proxy bound to bridge")
                true
            }
            SplitAvBridgeIpcMessage.MSG_UNREGISTER -> {
                SplitAvVideoBridge.unbind(proxy)
                proxy.detachClient()
                Timber.tag(TAG).i("SplitAvBridgeService: client unregistered")
                true
            }
            SplitAvBridgeIpcMessage.MSG_STATE_UPDATE -> {
                val data = msg.data ?: return@Handler true
                proxy.applyStateUpdate(
                    positionMs = data.getLong(SplitAvBridgeIpcMessage.KEY_POSITION_MS),
                    playing = data.getBoolean(SplitAvBridgeIpcMessage.KEY_PLAYING),
                )
                true
            }
            else -> false
        }
    }

    private val messenger = Messenger(incomingHandler)

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        super.onDestroy()
        SplitAvVideoBridge.unbind(proxy)
        proxy.detachClient()
    }

    private companion object {
        const val TAG = "SplitAvBridgeService"
    }
}
