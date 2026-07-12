package dev.jdtech.jellyfin.sendspin.receiver.remote

import org.json.JSONArray
import org.json.JSONObject

/**
 * Messages exchanged with the Music Assistant WebRTC signaling server
 * (`wss://signaling.music-assistant.io/ws`). The server brokers WebRTC peer
 * connections between this client and a Music Assistant gateway addressed by Remote ID.
 *
 * Modelled on the official MA mobile app's `SignalingMessage`. We only encode the
 * subset we send (connect-request / offer / ice-candidate / pong) and decode the
 * subset we receive (connected / answer / ice-candidate / error / peer-disconnected /
 * ping). [SignalingCodec] handles these small wire shapes directly so they remain stable
 * under whole-program optimization without reflection rules.
 */

/** STUN/TURN server entry handed to us in the `connected` message. */
data class IceServerConfig(
    val urls: List<String> = emptyList(),
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

/**
 * Serialises outbound and parses inbound signaling JSON. Stateless; safe to share.
 */
class SignalingCodec {
    fun encodeConnectRequest(remoteId: RemoteId): String =
        JSONObject()
            .put("type", "connect-request")
            .put("remoteId", remoteId.rawId)
            .toString()

    fun encodeOffer(remoteId: RemoteId, sessionId: String, sdp: SessionDescriptionData): String =
        JSONObject()
            .put("type", "offer")
            .put("remoteId", remoteId.rawId)
            .put("sessionId", sessionId)
            .put("data", sdp.toJson())
            .toString()

    fun encodeIceCandidate(
        remoteId: RemoteId,
        sessionId: String,
        candidate: IceCandidateData,
    ): String =
        JSONObject()
            .put("type", "ice-candidate")
            .put("remoteId", remoteId.rawId)
            .put("sessionId", sessionId)
            .put("data", candidate.toJson())
            .toString()

    fun encodePong(): String = JSONObject().put("type", "pong").toString()

    /** Parse an inbound frame; returns [SignalingInbound.Unknown] on unrecognised/garbled input. */
    fun decode(json: String): SignalingInbound {
        val envelope = runCatching { JSONObject(json) }.getOrNull()
        val type = envelope?.stringOrNull("type")
        return runCatching {
            when (type) {
                "connected" ->
                    SignalingInbound.Connected(
                        sessionId = envelope.stringOrNull("sessionId"),
                        iceServers = envelope.optJSONArray("iceServers").toIceServers(),
                    )
                "answer" ->
                    SignalingInbound.Answer(
                        sessionId = envelope.stringOrNull("sessionId"),
                        data = envelope.getJSONObject("data").toSessionDescription(),
                    )
                "ice-candidate" ->
                    SignalingInbound.RemoteIce(
                        sessionId = envelope.stringOrNull("sessionId"),
                        data = envelope.getJSONObject("data").toIceCandidate(),
                    )
                "error" ->
                    SignalingInbound.Error(
                        error = envelope.stringOrNull("error"),
                        sessionId = envelope.stringOrNull("sessionId"),
                    )
                "peer-disconnected" -> SignalingInbound.PeerDisconnected
                "ping" -> SignalingInbound.Ping
                else -> SignalingInbound.Unknown(type)
            }
        }.getOrElse { SignalingInbound.Unknown(type) }
    }

    private fun SessionDescriptionData.toJson(): JSONObject =
        JSONObject().put("sdp", sdp).put("type", type)

    private fun IceCandidateData.toJson(): JSONObject =
        JSONObject().put("candidate", candidate).apply {
            sdpMid?.let { put("sdpMid", it) }
            sdpMLineIndex?.let { put("sdpMLineIndex", it) }
        }

    private fun JSONObject.toSessionDescription(): SessionDescriptionData =
        SessionDescriptionData(sdp = getString("sdp"), type = getString("type"))

    private fun JSONObject.toIceCandidate(): IceCandidateData =
        IceCandidateData(
            candidate = getString("candidate"),
            sdpMid = stringOrNull("sdpMid"),
            sdpMLineIndex = intOrNull("sdpMLineIndex"),
        )

    private fun JSONArray?.toIceServers(): List<IceServerConfig> =
        if (this == null) {
            emptyList()
        } else {
            buildList {
                for (index in 0 until length()) {
                    val server = optJSONObject(index) ?: continue
                    add(
                        IceServerConfig(
                            urls = server.stringList("urls"),
                            username = server.stringOrNull("username"),
                            credential = server.stringOrNull("credential"),
                        )
                    )
                }
            }
        }

    private fun JSONObject.stringList(key: String): List<String> =
        when (val value = opt(key)) {
            is String -> listOf(value)
            is JSONArray -> buildList {
                for (index in 0 until value.length()) {
                    value.optString(index).takeIf { it.isNotEmpty() }?.let(::add)
                }
            }
            else -> emptyList()
        }

    private fun JSONObject.stringOrNull(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

    private fun JSONObject.intOrNull(key: String): Int? =
        if (!has(key) || isNull(key)) null else optInt(key)
}
