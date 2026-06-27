package dev.jdtech.jellyfin.sendspin.receiver.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalingCodecTest {
    private val codec = SignalingCodec()
    private val remoteId = RemoteId("PGSVXKGZJCFA6MOH4UPBH5Q9HY")

    // --- decode ---

    @Test
    fun `decodes connected with an array of ICE servers`() {
        val json = """
            {"type":"connected","sessionId":"s-1","iceServers":[
              {"urls":["stun:stun.l.google.com:19302"]},
              {"urls":["turn:relay.example:3478"],"username":"u","credential":"c"}
            ]}
        """.trimIndent()

        val msg = codec.decode(json)
        assertTrue(msg is SignalingInbound.Connected)
        msg as SignalingInbound.Connected
        assertEquals("s-1", msg.sessionId)
        assertEquals(2, msg.iceServers.size)
        assertEquals(listOf("stun:stun.l.google.com:19302"), msg.iceServers[0].urls)
        assertEquals("u", msg.iceServers[1].username)
        assertEquals("c", msg.iceServers[1].credential)
    }

    @Test
    fun `decodes connected when urls is a single string`() {
        // Public signaling servers send `urls` as a bare string rather than an array;
        // the FlexibleStringList adapter must normalise both into a List.
        val json = """{"type":"connected","iceServers":[{"urls":"stun:single.example:3478"}]}"""

        val msg = codec.decode(json) as SignalingInbound.Connected
        assertEquals(listOf("stun:single.example:3478"), msg.iceServers.single().urls)
    }

    @Test
    fun `decodes an SDP answer`() {
        val json = """{"type":"answer","sessionId":"s-2","data":{"sdp":"v=0...","type":"answer"}}"""

        val msg = codec.decode(json) as SignalingInbound.Answer
        assertEquals("s-2", msg.sessionId)
        assertEquals("v=0...", msg.data.sdp)
        assertEquals("answer", msg.data.type)
    }

    @Test
    fun `decodes a remote ICE candidate`() {
        val json = """
            {"type":"ice-candidate","sessionId":"s-3",
             "data":{"candidate":"candidate:1 1 udp ...","sdpMid":"0","sdpMLineIndex":0}}
        """.trimIndent()

        val msg = codec.decode(json) as SignalingInbound.RemoteIce
        assertEquals("s-3", msg.sessionId)
        assertEquals("candidate:1 1 udp ...", msg.data.candidate)
        assertEquals("0", msg.data.sdpMid)
        assertEquals(0, msg.data.sdpMLineIndex)
    }

    @Test
    fun `decodes an error frame`() {
        val msg = codec.decode("""{"type":"error","error":"nope","sessionId":"s-4"}""")
            as SignalingInbound.Error
        assertEquals("nope", msg.error)
        assertEquals("s-4", msg.sessionId)
    }

    @Test
    fun `decodes peer-disconnected and ping singletons`() {
        assertEquals(SignalingInbound.PeerDisconnected, codec.decode("""{"type":"peer-disconnected"}"""))
        assertEquals(SignalingInbound.Ping, codec.decode("""{"type":"ping"}"""))
    }

    @Test
    fun `decodes an unrecognised type as Unknown for forward compatibility`() {
        val msg = codec.decode("""{"type":"some-future-thing"}""") as SignalingInbound.Unknown
        assertEquals("some-future-thing", msg.type)
    }

    @Test
    fun `decodes garbage as Unknown without throwing`() {
        assertEquals(SignalingInbound.Unknown(null), codec.decode("not json at all"))
        assertEquals(SignalingInbound.Unknown(null), codec.decode("{"))
    }

    @Test
    fun `decode tolerates a well-formed frame missing its data payload`() {
        // type=answer but no `data`: must not throw, falls back to Unknown.
        val msg = codec.decode("""{"type":"answer","sessionId":"s-5"}""")
        assertEquals(SignalingInbound.Unknown("answer"), msg)
    }

    // --- encode ---

    @Test
    fun `encodes a connect-request with the remote id`() {
        val json = codec.encodeConnectRequest(remoteId)
        assertTrue(json.contains(""""type":"connect-request""""))
        assertTrue(json.contains(""""remoteId":"PGSVXKGZJCFA6MOH4UPBH5Q9HY""""))
    }

    @Test
    fun `encodes an offer carrying the session and SDP`() {
        val json = codec.encodeOffer(
            remoteId,
            "s-6",
            SessionDescriptionData(sdp = "v=0 offer", type = "offer"),
        )
        assertTrue(json.contains(""""type":"offer""""))
        assertTrue(json.contains(""""sessionId":"s-6""""))
        assertTrue(json.contains(""""sdp":"v=0 offer""""))
    }

    @Test
    fun `encodes an ice candidate`() {
        val json = codec.encodeIceCandidate(
            remoteId,
            "s-7",
            IceCandidateData(candidate = "candidate:xyz", sdpMid = "0", sdpMLineIndex = 0),
        )
        assertTrue(json.contains(""""type":"ice-candidate""""))
        assertTrue(json.contains(""""candidate":"candidate:xyz""""))
    }

    @Test
    fun `encodes a pong`() {
        assertEquals("""{"type":"pong"}""", codec.encodePong())
    }
}
