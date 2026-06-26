package dev.spatialfin.fcast

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import dev.jdtech.jellyfin.fcast.receiver.ExternalStreamPlayer
import dev.jdtech.jellyfin.fcast.receiver.ExternalStreamRequest
import dev.jdtech.jellyfin.fcast.receiver.FCastInboundBridgeIpcMessage
import dev.jdtech.jellyfin.fcast.receiver.FCastInboundSession
import timber.log.Timber

/**
 * Main-process end of immersive inbound playback. The FCast socket remains in this process;
 * a short-lived proxy relays its commands to the isolated Full Space player process.
 */
class FCastInboundBridgeService : Service() {
    private data class Registration(
        val sessionId: String,
        val proxy: ProxyExternalStreamPlayer,
    )

    private var current: Registration? = null
    private val handler = Handler(Looper.getMainLooper()) { message ->
        when (message.what) {
            FCastInboundBridgeIpcMessage.MSG_REGISTER -> {
                val sessionId = message.data.getString(FCastInboundBridgeIpcMessage.KEY_SESSION_ID)
                val callback = message.replyTo
                if (sessionId.isNullOrBlank() || callback == null) return@Handler true
                val proxy = ProxyExternalStreamPlayer(callback)
                current = Registration(sessionId, proxy)
                FCastInboundSession.bindControl(proxy)
                Timber.tag(TAG).i("Immersive inbound player registered")
                true
            }
            FCastInboundBridgeIpcMessage.MSG_UNREGISTER -> {
                val id = message.data.getString(FCastInboundBridgeIpcMessage.KEY_SESSION_ID)
                current?.takeIf { it.sessionId == id }?.let {
                    FCastInboundSession.unbindControl(it.proxy)
                    current = null
                }
                true
            }
            FCastInboundBridgeIpcMessage.MSG_PLAYBACK_UPDATE -> {
                message.data.getString(FCastInboundBridgeIpcMessage.KEY_JSON)?.let {
                    runCatching { FCastInboundBridgeIpcMessage.decodePlayback(it) }
                        .onSuccess(FCastInboundSession::pushPlaybackUpdate)
                }
                true
            }
            FCastInboundBridgeIpcMessage.MSG_VOLUME_UPDATE -> {
                message.data.getString(FCastInboundBridgeIpcMessage.KEY_JSON)?.let {
                    runCatching { FCastInboundBridgeIpcMessage.decodeVolume(it) }
                        .onSuccess(FCastInboundSession::pushVolumeUpdate)
                }
                true
            }
            FCastInboundBridgeIpcMessage.MSG_TRACKS_UPDATE -> {
                message.data.getString(FCastInboundBridgeIpcMessage.KEY_JSON)?.let {
                    runCatching { FCastInboundBridgeIpcMessage.decodeTracks(it) }
                        .onSuccess(FCastInboundSession::pushTracksUpdate)
                }
                true
            }
            else -> false
        }
    }
    private val messenger = Messenger(handler)

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onUnbind(intent: Intent?): Boolean {
        current?.let { FCastInboundSession.unbindControl(it.proxy) }
        current = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        current?.let { FCastInboundSession.unbindControl(it.proxy) }
        current = null
        super.onDestroy()
    }

    private class ProxyExternalStreamPlayer(private val remote: Messenger) : ExternalStreamPlayer {
        override fun play(request: ExternalStreamRequest): ExternalStreamPlayer.PlayResult =
            ExternalStreamPlayer.PlayResult.Rejected("Play is launched by the inbound router")
        override fun pause() = send(FCastInboundBridgeIpcMessage.MSG_PAUSE)
        override fun resume() = send(FCastInboundBridgeIpcMessage.MSG_RESUME)
        override fun resumeAt(atReceiverMonotonicMs: Long) =
            send(FCastInboundBridgeIpcMessage.MSG_RESUME_AT) {
                putLong(FCastInboundBridgeIpcMessage.KEY_LONG, atReceiverMonotonicMs)
            }
        override fun stop() = send(FCastInboundBridgeIpcMessage.MSG_STOP)
        override fun seek(seconds: Double) = send(FCastInboundBridgeIpcMessage.MSG_SEEK) {
            putDouble(FCastInboundBridgeIpcMessage.KEY_DOUBLE, seconds)
        }
        override fun setVolume(volume: Double) = send(FCastInboundBridgeIpcMessage.MSG_SET_VOLUME) {
            putDouble(FCastInboundBridgeIpcMessage.KEY_DOUBLE, volume)
        }
        override fun setSpeed(speed: Double) = send(FCastInboundBridgeIpcMessage.MSG_SET_SPEED) {
            putDouble(FCastInboundBridgeIpcMessage.KEY_DOUBLE, speed)
        }
        override fun setTrack(type: Int, trackId: String) = send(FCastInboundBridgeIpcMessage.MSG_SET_TRACK) {
            putInt(FCastInboundBridgeIpcMessage.KEY_TRACK_TYPE, type)
            putString(FCastInboundBridgeIpcMessage.KEY_TRACK_ID, trackId)
        }

        private fun send(what: Int, extras: Bundle.() -> Unit = {}) {
            try {
                remote.send(Message.obtain(null, what).apply { data = Bundle().apply(extras) })
            } catch (error: RemoteException) {
                Timber.tag(TAG).w(error, "Immersive inbound command delivery failed")
            }
        }
    }

    private companion object {
        const val TAG = "FCastInboundBridge"
    }
}
