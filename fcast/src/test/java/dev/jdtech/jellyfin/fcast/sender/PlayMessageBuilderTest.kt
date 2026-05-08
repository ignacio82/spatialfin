package dev.jdtech.jellyfin.fcast.sender

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayMessageBuilderTest {

    @Test fun `position zero is omitted from PlayMessage`() {
        val msg = PlayMessageBuilder.build(
            url = "u", container = "video/mp4", positionSeconds = 0.0,
        )
        assertNull(msg.time)
    }

    @Test fun `position above zero is preserved`() {
        val msg = PlayMessageBuilder.build(
            url = "u", container = "video/mp4", positionSeconds = 12.5,
        )
        assertEquals(12.5, msg.time!!, 0.0)
    }

    @Test fun `volume is clamped to 0 to 1`() {
        assertEquals(1.0, PlayMessageBuilder.build("u", "v", volume = 5.0).volume!!, 0.0)
        assertEquals(0.0, PlayMessageBuilder.build("u", "v", volume = -1.0).volume!!, 0.0)
        assertEquals(0.4, PlayMessageBuilder.build("u", "v", volume = 0.4).volume!!, 0.0)
    }

    @Test fun `empty headers map collapses to null`() {
        assertNull(PlayMessageBuilder.build("u", "v", headers = emptyMap()).headers)
    }

    @Test fun `metadata is built only when title or thumbnail are set`() {
        assertNull(PlayMessageBuilder.build("u", "v").metadata)
        val withTitle = PlayMessageBuilder.build("u", "v", title = "Movie")
        assertEquals("Movie", withTitle.metadata!!.title)
    }

    @Test fun `guessContainer covers the common cases`() {
        assertEquals("application/vnd.apple.mpegurl", PlayMessageBuilder.guessContainer("https://e.org/x.m3u8"))
        assertEquals("application/dash+xml", PlayMessageBuilder.guessContainer("https://e.org/x.mpd?token=1"))
        assertEquals("video/mp4", PlayMessageBuilder.guessContainer("https://e.org/x.MP4"))
        assertEquals("video/x-matroska", PlayMessageBuilder.guessContainer("https://e.org/x.mkv"))
        assertNull(PlayMessageBuilder.guessContainer("https://e.org/unknown.bin"))
    }
}
