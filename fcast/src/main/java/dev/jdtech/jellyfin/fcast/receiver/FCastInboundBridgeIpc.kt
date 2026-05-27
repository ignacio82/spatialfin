package dev.jdtech.jellyfin.fcast.receiver

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
import dev.jdtech.jellyfin.fcast.protocol.PlaybackUpdateMessage
import dev.jdtech.jellyfin.fcast.protocol.SpatialFinTracksUpdateMessage
import dev.jdtech.jellyfin.fcast.protocol.VolumeUpdateMessage
import java.util.UUID
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber

/** Messenger wire constants and JSON codecs for an immersive inbound player in `:xrplayer`. */
object FCastInboundBridgeIpcMessage {
    const val MSG_REGISTER = 1
    const val MSG_UNREGISTER = 2
    const val MSG_PLAYBACK_UPDATE = 3
    const val MSG_VOLUME_UPDATE = 4
    const val MSG_TRACKS_UPDATE = 5

    const val MSG_PAUSE = 10
    const val MSG_RESUME = 11
    const val MSG_RESUME_AT = 12
    const val MSG_STOP = 13
    const val MSG_SEEK = 14
    const val MSG_SET_VOLUME = 15
    const val MSG_SET_SPEED = 16
    const val MSG_SET_TRACK = 17

    const val KEY_SESSION_ID = "sessionId"
    const val KEY_JSON = "json"
    const val KEY_LONG = "long"
    const val KEY_DOUBLE = "double"
    const val KEY_TRACK_TYPE = "trackType"
    const val KEY_TRACK_ID = "trackId"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    fun encode(update: PlaybackUpdateMessage): String = json.encodeToString(update)
    fun encode(update: VolumeUpdateMessage): String = json.encodeToString(update)
    fun encode(update: SpatialFinTracksUpdateMessage): String = json.encodeToString(update)
    fun decodePlayback(encoded: String): PlaybackUpdateMessage = json.decodeFromString(encoded)
    fun decodeVolume(encoded: String): VolumeUpdateMessage = json.decodeFromString(encoded)
    fun decodeTracks(encoded: String): SpatialFinTracksUpdateMessage = json.decodeFromString(encoded)
}

/**
 * XR-process endpoint: receives sender commands from the main-process bridge and publishes
 * player state using the existing FCast JSON payload shapes.
 */
class FCastInboundBridgeIpcClient(
    private val context: Context,
    private val control: ExternalStreamPlayer,
) {
    private val sessionId = UUID.randomUUID().toString()
    private var service: Messenger? = null
    private var bound = false

    private val callback = Messenger(Handler(Looper.getMainLooper()) { msg ->
        when (msg.what) {
            FCastInboundBridgeIpcMessage.MSG_PAUSE -> control.pause()
            FCastInboundBridgeIpcMessage.MSG_RESUME -> control.resume()
            FCastInboundBridgeIpcMessage.MSG_RESUME_AT ->
                control.resumeAt(msg.data.getLong(FCastInboundBridgeIpcMessage.KEY_LONG))
            FCastInboundBridgeIpcMessage.MSG_STOP -> control.stop()
            FCastInboundBridgeIpcMessage.MSG_SEEK ->
                control.seek(msg.data.getDouble(FCastInboundBridgeIpcMessage.KEY_DOUBLE))
            FCastInboundBridgeIpcMessage.MSG_SET_VOLUME ->
                control.setVolume(msg.data.getDouble(FCastInboundBridgeIpcMessage.KEY_DOUBLE))
            FCastInboundBridgeIpcMessage.MSG_SET_SPEED ->
                control.setSpeed(msg.data.getDouble(FCastInboundBridgeIpcMessage.KEY_DOUBLE))
            FCastInboundBridgeIpcMessage.MSG_SET_TRACK ->
                control.setTrack(
                    msg.data.getInt(FCastInboundBridgeIpcMessage.KEY_TRACK_TYPE),
                    msg.data.getString(FCastInboundBridgeIpcMessage.KEY_TRACK_ID).orEmpty(),
                )
            else -> return@Handler false
        }
        true
    })

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val target = Messenger(binder)
            service = target
            val register = Message.obtain(null, FCastInboundBridgeIpcMessage.MSG_REGISTER).apply {
                data = Bundle().apply {
                    putString(FCastInboundBridgeIpcMessage.KEY_SESSION_ID, sessionId)
                }
                replyTo = this@FCastInboundBridgeIpcClient.callback
            }
            try {
                target.send(register)
            } catch (error: RemoteException) {
                Timber.tag(TAG).w(error, "Immersive FCast bridge registration failed")
                this@FCastInboundBridgeIpcClient.service = null
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    fun connect() {
        if (bound) return
        val intent = Intent().setClassName(context, "dev.spatialfin.fcast.FCastInboundBridgeService")
        bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        if (!bound) Timber.tag(TAG).w("Could not bind immersive FCast bridge service")
    }

    fun publish(update: PlaybackUpdateMessage) =
        sendJson(FCastInboundBridgeIpcMessage.MSG_PLAYBACK_UPDATE, FCastInboundBridgeIpcMessage.encode(update))

    fun publish(update: VolumeUpdateMessage) =
        sendJson(FCastInboundBridgeIpcMessage.MSG_VOLUME_UPDATE, FCastInboundBridgeIpcMessage.encode(update))

    fun publish(update: SpatialFinTracksUpdateMessage) =
        sendJson(FCastInboundBridgeIpcMessage.MSG_TRACKS_UPDATE, FCastInboundBridgeIpcMessage.encode(update))

    fun disconnect() {
        if (!bound) return
        send(FCastInboundBridgeIpcMessage.MSG_UNREGISTER) {
            putString(FCastInboundBridgeIpcMessage.KEY_SESSION_ID, sessionId)
        }
        runCatching { context.unbindService(connection) }
        bound = false
        service = null
    }

    private fun sendJson(what: Int, encoded: String) {
        send(what) { putString(FCastInboundBridgeIpcMessage.KEY_JSON, encoded) }
    }

    private fun send(what: Int, build: Bundle.() -> Unit) {
        val message = Message.obtain(null, what).apply { data = Bundle().apply(build) }
        try {
            service?.send(message)
        } catch (error: RemoteException) {
            Timber.tag(TAG).w(error, "Immersive FCast bridge send failed for %d", what)
            service = null
        }
    }

    private companion object {
        const val TAG = "FCastInboundBridge"
    }
}
