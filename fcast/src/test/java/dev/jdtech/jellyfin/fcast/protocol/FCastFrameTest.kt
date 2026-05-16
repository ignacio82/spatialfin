package dev.jdtech.jellyfin.fcast.protocol

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FCastFrameTest {

    @Test
    fun `body-less message encodes as 4-byte size 1 plus opcode`() {
        val bytes = FCastFrame.encode(FCastMessage.Pause)
        assertEquals(5, bytes.size)
        // size = 1, little-endian
        assertEquals(1, bytes[0].toInt() and 0xFF)
        assertEquals(0, bytes[1].toInt() and 0xFF)
        assertEquals(0, bytes[2].toInt() and 0xFF)
        assertEquals(0, bytes[3].toInt() and 0xFF)
        assertEquals(FCastOpcode.Pause.code, bytes[4].toInt() and 0xFF)
    }

    @Test
    fun `Play message round-trips through encode and read`() {
        val play = FCastMessage.Play(
            PlayMessage(
                container = "video/mp4",
                url = "https://example.org/movie.mp4",
                time = 42.5,
                volume = 0.8,
                speed = 1.0,
                headers = mapOf("X-Token" to "abc"),
                metadata = MetadataObject(type = 1, title = "Test"),
            ),
        )
        val decoded = roundTrip(play)
        assertTrue(decoded is FCastMessage.Play)
        val payload = (decoded as FCastMessage.Play).payload
        assertEquals("video/mp4", payload.container)
        assertEquals("https://example.org/movie.mp4", payload.url)
        assertEquals(42.5, payload.time!!, 0.0)
        assertEquals(0.8, payload.volume!!, 0.0)
        assertEquals("abc", payload.headers!!["X-Token"])
        assertEquals("Test", payload.metadata!!.title)
    }

    @Test
    fun `every opcode that carries a body round-trips`() {
        val cases: List<FCastMessage> = listOf(
            FCastMessage.Seek(SeekMessage(time = 10.0)),
            FCastMessage.PlaybackUpdate(
                PlaybackUpdateMessage(generationTime = 1L, state = 1, time = 5.0, duration = 100.0, speed = 1.5),
            ),
            FCastMessage.VolumeUpdate(VolumeUpdateMessage(generationTime = 2L, volume = 0.5)),
            FCastMessage.SetVolume(SetVolumeMessage(volume = 0.25)),
            FCastMessage.PlaybackError(PlaybackErrorMessage(message = "boom")),
            FCastMessage.SetSpeed(SetSpeedMessage(speed = 2.0)),
            FCastMessage.Version(VersionMessage(version = 3)),
            FCastMessage.Initial(InitialReceiverMessage(displayName = "TV", appName = "FCastReceiver")),
            FCastMessage.PlayUpdate(
                PlayUpdateMessage(generationTime = 3L, playData = PlayMessage(container = "video/mp4", url = "u")),
            ),
            FCastMessage.SetPlaylistItem(SetPlaylistItemMessage(itemIndex = 4)),
            FCastMessage.SubscribeEvent(SubscribeEventMessage(EventSubscribeObject(type = 0))),
            FCastMessage.UnsubscribeEvent(UnsubscribeEventMessage(EventSubscribeObject(type = 1))),
            FCastMessage.Event(EventMessage(generationTime = 4L, event = EventObject(type = 0))),
        )
        for (msg in cases) {
            val decoded = roundTrip(msg)
            assertEquals("opcode mismatch for $msg", msg.opcode, decoded?.opcode)
            // Equality is structural for data classes, but `data object` cases never appear here.
            assertEquals("payload mismatch for $msg", msg, decoded)
        }
    }

    @Test
    fun `body-less control opcodes round-trip`() {
        for (
            msg in listOf(
                FCastMessage.None, FCastMessage.Pause, FCastMessage.Resume(), FCastMessage.Stop,
                FCastMessage.Ping(), FCastMessage.Pong(),
            )
        ) {
            val decoded = roundTrip(msg)
            assertEquals(msg, decoded)
        }
    }

    @Test
    fun `v4 Ping-Pong-Resume bodies survive a round-trip`() {
        assertEquals(
            FCastMessage.Ping(PingMessage(t1 = 123_456L)),
            roundTrip(FCastMessage.Ping(PingMessage(t1 = 123_456L))),
        )
        assertEquals(
            FCastMessage.Pong(PongMessage(t1 = 1L, t2 = 2L, t3 = 3L)),
            roundTrip(FCastMessage.Pong(PongMessage(t1 = 1L, t2 = 2L, t3 = 3L))),
        )
        assertEquals(
            FCastMessage.Resume(ResumeMessage(atReceiverMonotonicMs = 9_999L)),
            roundTrip(FCastMessage.Resume(ResumeMessage(atReceiverMonotonicMs = 9_999L))),
        )
    }

    @Test
    fun `a v4 body-less message is byte-identical to v3 (back-compat guarantee)`() {
        // The whole reason the version bump is safe: no payload ⇒ size=1, no body bytes.
        for (m in listOf(FCastMessage.Ping(), FCastMessage.Pong(), FCastMessage.Resume())) {
            val bytes = FCastFrame.encode(m)
            assertEquals("size header must be 1 (body-less)", 1, bytes[0].toInt())
            assertEquals("frame is header(4)+opcode(1) only", 5, bytes.size)
        }
    }

    @Test
    fun `PlaybackUpdate carries the v4 monotonicSampleMs extension`() {
        val msg = FCastMessage.PlaybackUpdate(
            PlaybackUpdateMessage(
                generationTime = 1L, state = 1, time = 12.5, monotonicSampleMs = 777_777L,
            ),
        )
        assertEquals(msg, roundTrip(msg))
    }

    @Test
    fun `unknown opcode raises IOException`() {
        // size = 1, body-less, opcode = 99 (unknown)
        val frame = byteArrayOf(0x01, 0x00, 0x00, 0x00, 99)
        val input = DataInputStream(ByteArrayInputStream(frame))
        val ex = assertThrows(IOException::class.java) { FCastFrame.read(input) }
        assertTrue(ex.message!!.contains("unknown opcode"))
    }

    @Test
    fun `oversize frame raises IOException without consuming the body`() {
        val oversize = FCAST_MAX_PACKET_BYTES // size + 4 > limit
        val frame = ByteArray(5)
        frame[0] = (oversize and 0xFF).toByte()
        frame[1] = ((oversize ushr 8) and 0xFF).toByte()
        frame[2] = ((oversize ushr 16) and 0xFF).toByte()
        frame[3] = ((oversize ushr 24) and 0xFF).toByte()
        frame[4] = FCastOpcode.Play.code.toByte()
        val input = DataInputStream(ByteArrayInputStream(frame))
        assertThrows(IOException::class.java) { FCastFrame.read(input) }
    }

    @Test
    fun `clean EOF before any bytes returns null`() {
        val input = DataInputStream(ByteArrayInputStream(ByteArray(0)))
        assertNull(FCastFrame.read(input))
    }

    @Test
    fun `JSON tolerates unknown fields for forward-compat`() {
        // Hand-craft a Play body with an extra field the codec has never seen.
        val body = """{"container":"video/mp4","url":"u","futureField":42}""".toByteArray(Charsets.UTF_8)
        val decoded = FCastFrame.decode(FCastOpcode.Play, body)
        assertTrue(decoded is FCastMessage.Play)
        assertEquals("u", (decoded as FCastMessage.Play).payload.url)
    }

    @Test
    fun `optional Play fields are omitted from wire body when null`() {
        val msg = FCastMessage.Play(PlayMessage(container = "video/mp4", url = "u"))
        val body = FCastFrame.encodeBody(msg)
        val text = String(body, Charsets.UTF_8)
        // No `time`, `volume`, `headers`, `metadata` keys.
        assertTrue("body should not include null `time`: $text", !text.contains("\"time\""))
        assertTrue("body should not include null `headers`: $text", !text.contains("\"headers\""))
        assertTrue("body should not include null `metadata`: $text", !text.contains("\"metadata\""))
    }

    @Test
    fun `opcode minVersion gating reflects spec`() {
        assertEquals(1, FCastOpcode.Play.minVersion)
        assertEquals(2, FCastOpcode.Version.minVersion)
        assertEquals(3, FCastOpcode.Initial.minVersion)
        assertEquals(3, FCastOpcode.Event.minVersion)
    }

    @Test
    fun `PlaybackUpdate maps state code to enum`() {
        val msg = PlaybackUpdateMessage(generationTime = 0, state = 1)
        assertEquals(PlaybackState.Playing, msg.playbackState)
        assertNull(PlaybackUpdateMessage(generationTime = 0, state = 99).playbackState)
    }

    private fun roundTrip(message: FCastMessage): FCastMessage? {
        val bytes = FCastFrame.encode(message)
        val input = DataInputStream(ByteArrayInputStream(bytes))
        val decoded = FCastFrame.read(input)
        assertNotNull("decoded null", decoded)
        return decoded
    }
}
