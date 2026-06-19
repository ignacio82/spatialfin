package dev.jdtech.jellyfin.sendspin.receiver.remote

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import dev.jdtech.jellyfin.sendspin.receiver.SendspinReceiverService.Companion.TAG
import timber.log.Timber

/**
 * Thin OkHttp WebSocket client for the Music Assistant WebRTC signaling server.
 *
 * It only transports JSON frames between [MaWebRtcConnection] and the cloud server
 * (`wss://signaling.music-assistant.io/ws`); all WebRTC logic lives in the connection.
 * Keepalive `ping` frames are answered with `pong` here and not forwarded.
 *
 * Reuses the receiver's OkHttp stack so it inherits the 5 s ping keepalive.
 */
class MaSignalingClient(
    private val httpClient: OkHttpClient,
    private val codec: SignalingCodec,
    private val listener: Listener,
    private val url: String = DEFAULT_SIGNALING_URL,
) {
    interface Listener {
        fun onSignalingOpen()
        fun onSignalingMessage(message: SignalingInbound)
        fun onSignalingClosed(reason: String)
        fun onSignalingFailure(error: Throwable)
    }

    @Volatile
    private var ws: WebSocket? = null

    @Volatile
    private var closed = false

    fun connect() {
        if (ws != null) return
        closed = false
        Timber.tag(TAG).i("Signaling: connecting to %s", url)
        ws = httpClient.newWebSocket(Request.Builder().url(url).build(), wsListener)
    }

    /** Send a pre-encoded signaling JSON frame. No-op if the socket is gone. */
    fun send(json: String): Boolean = ws?.send(json) ?: false

    fun close(reason: String = "client_close") {
        closed = true
        ws?.close(NORMAL_CLOSE, reason)
        ws = null
    }

    private val wsListener =
        object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (webSocket !== ws) return
                Timber.tag(TAG).i("Signaling: open")
                listener.onSignalingOpen()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (webSocket !== ws) return
                when (val msg = codec.decode(text)) {
                    is SignalingInbound.Ping -> webSocket.send(codec.encodePong())
                    else -> listener.onSignalingMessage(msg)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (webSocket !== ws) return
                webSocket.close(NORMAL_CLOSE, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (webSocket !== ws) return
                ws = null
                if (!closed) listener.onSignalingClosed(reason.ifBlank { "code=$code" })
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (webSocket !== ws) return
                ws = null
                if (!closed) {
                    Timber.tag(TAG).w(t, "Signaling: failure")
                    listener.onSignalingFailure(t)
                }
            }
        }

    companion object {
        const val DEFAULT_SIGNALING_URL = "wss://signaling.music-assistant.io/ws"
        private const val NORMAL_CLOSE = 1000
    }
}
