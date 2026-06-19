package dev.jdtech.jellyfin.sendspin.receiver.remote

import com.squareup.moshi.FromJson
import com.squareup.moshi.JsonQualifier
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import com.squareup.moshi.ToJson
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

/**
 * Messages exchanged with the Music Assistant WebRTC signaling server
 * (`wss://signaling.music-assistant.io/ws`). The server brokers WebRTC peer
 * connections between this client and a Music Assistant gateway addressed by Remote ID.
 *
 * Modelled on the official MA mobile app's `SignalingMessage`. We only encode the
 * subset we send (connect-request / offer / ice-candidate / pong) and decode the
 * subset we receive (connected / answer / ice-candidate / error / peer-disconnected /
 * ping). [SignalingCodec] does the JSON, using the module's reflection-based Moshi.
 */

/** STUN/TURN server entry handed to us in the `connected` message. */
data class IceServerConfig(
    @FlexibleStringList val urls: List<String> = emptyList(),
    val username: String? = null,
    val credential: String? = null,
)

/** SDP offer/answer payload. */
data class SessionDescriptionData(
    val sdp: String,
    val type: String, // "offer" | "answer"
)

/** ICE candidate payload for NAT traversal. */
data class IceCandidateData(
    val candidate: String,
    val sdpMid: String? = null,
    val sdpMLineIndex: Int? = null,
)

/** Inbound signaling messages we act on. */
sealed interface SignalingInbound {
    /** Connection accepted: gives the session id and the ICE servers to use. */
    data class Connected(
        val sessionId: String?,
        val iceServers: List<IceServerConfig>,
    ) : SignalingInbound

    /** SDP answer from the gateway. */
    data class Answer(val sessionId: String?, val data: SessionDescriptionData) : SignalingInbound

    /** Remote ICE candidate from the gateway. */
    data class RemoteIce(val sessionId: String?, val data: IceCandidateData) : SignalingInbound

    /** Signaling-level error. */
    data class Error(val error: String?, val sessionId: String?) : SignalingInbound

    /** The gateway peer disconnected. */
    data object PeerDisconnected : SignalingInbound

    /** Keepalive ping; the codec answers it transparently, callers rarely see it. */
    data object Ping : SignalingInbound

    /** A type we don't recognise — ignored, kept for forward compatibility. */
    data class Unknown(val type: String?) : SignalingInbound
}

/** `urls` may arrive as a single string (public server) or an array (Nabu Casa). */
@Retention(AnnotationRetention.RUNTIME)
@JsonQualifier
annotation class FlexibleStringList

private object FlexibleStringListAdapter {
    @FromJson
    @FlexibleStringList
    fun fromJson(reader: JsonReader): List<String> =
        when (reader.peek()) {
            JsonReader.Token.BEGIN_ARRAY -> buildList {
                reader.beginArray()
                while (reader.hasNext()) add(reader.nextString())
                reader.endArray()
            }
            JsonReader.Token.STRING -> listOf(reader.nextString())
            JsonReader.Token.NULL -> {
                reader.nextNull<Unit>()
                emptyList()
            }
            else -> {
                reader.skipValue()
                emptyList()
            }
        }

    @ToJson
    fun toJson(writer: JsonWriter, @FlexibleStringList value: List<String>) {
        writer.beginArray()
        value.forEach { writer.value(it) }
        writer.endArray()
    }
}

/**
 * Serialises outbound and parses inbound signaling JSON. Stateless; safe to share.
 */
class SignalingCodec {
    private val moshi: Moshi =
        Moshi.Builder()
            .add(FlexibleStringListAdapter)
            .addLast(KotlinJsonAdapterFactory())
            .build()

    private data class TypeEnvelope(val type: String? = null)

    // Outbound wire shapes (only the fields the server reads).
    private data class ConnectRequestWire(val type: String, val remoteId: String)
    private data class OfferWire(
        val type: String,
        val remoteId: String,
        val sessionId: String,
        val data: SessionDescriptionData,
    )
    private data class IceCandidateWire(
        val type: String,
        val remoteId: String?,
        val sessionId: String,
        val data: IceCandidateData,
    )
    private data class PongWire(val type: String)

    // Inbound wire shapes.
    private data class ConnectedWire(
        val sessionId: String? = null,
        val iceServers: List<IceServerConfig> = emptyList(),
    )
    private data class AnswerWire(val sessionId: String? = null, val data: SessionDescriptionData)
    private data class IceCandidateInWire(val sessionId: String? = null, val data: IceCandidateData)
    private data class ErrorWire(val error: String? = null, val sessionId: String? = null)

    private val envelopeAdapter = moshi.adapter(TypeEnvelope::class.java)

    fun encodeConnectRequest(remoteId: RemoteId): String =
        moshi.adapter(ConnectRequestWire::class.java)
            .toJson(ConnectRequestWire("connect-request", remoteId.rawId))

    fun encodeOffer(remoteId: RemoteId, sessionId: String, sdp: SessionDescriptionData): String =
        moshi.adapter(OfferWire::class.java)
            .toJson(OfferWire("offer", remoteId.rawId, sessionId, sdp))

    fun encodeIceCandidate(
        remoteId: RemoteId,
        sessionId: String,
        candidate: IceCandidateData,
    ): String =
        moshi.adapter(IceCandidateWire::class.java)
            .toJson(IceCandidateWire("ice-candidate", remoteId.rawId, sessionId, candidate))

    fun encodePong(): String = moshi.adapter(PongWire::class.java).toJson(PongWire("pong"))

    /** Parse an inbound frame; returns [SignalingInbound.Unknown] on unrecognised/garbled input. */
    fun decode(json: String): SignalingInbound {
        val type = runCatching { envelopeAdapter.fromJson(json)?.type }.getOrNull()
        return runCatching {
            when (type) {
                "connected" -> moshi.adapter(ConnectedWire::class.java).fromJson(json)
                    ?.let { SignalingInbound.Connected(it.sessionId, it.iceServers) }
                "answer" -> moshi.adapter(AnswerWire::class.java).fromJson(json)
                    ?.let { SignalingInbound.Answer(it.sessionId, it.data) }
                "ice-candidate" -> moshi.adapter(IceCandidateInWire::class.java).fromJson(json)
                    ?.let { SignalingInbound.RemoteIce(it.sessionId, it.data) }
                "error" -> moshi.adapter(ErrorWire::class.java).fromJson(json)
                    ?.let { SignalingInbound.Error(it.error, it.sessionId) }
                "peer-disconnected" -> SignalingInbound.PeerDisconnected
                "ping" -> SignalingInbound.Ping
                else -> SignalingInbound.Unknown(type)
            } ?: SignalingInbound.Unknown(type)
        }.getOrElse { SignalingInbound.Unknown(type) }
    }
}
