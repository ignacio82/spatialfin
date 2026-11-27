package dev.spatialfin.fcast.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitAvStreamUrlPolicyTest {

    @Test
    fun `hls transcode stream starts at requested media offset`() {
        val url = "http://jellyfin/Videos/item/master.m3u8?api_key=redacted"
        assertTrue(SplitAvStreamUrlPolicy.isJellyfinHlsUrl(url))
        assertEquals(420_000L, SplitAvStreamUrlPolicy.receiverMediaStartOffsetMs(url, 420_000L))
    }

    @Test
    fun `direct stream keeps absolute media timeline`() {
        val url = "http://jellyfin/Videos/item/stream?static=true&api_key=redacted"
        assertFalse(SplitAvStreamUrlPolicy.isJellyfinHlsUrl(url))
        assertEquals(0L, SplitAvStreamUrlPolicy.receiverMediaStartOffsetMs(url, 420_000L))
    }

    @Test
    fun `negative requested offsets clamp to zero`() {
        val url = "http://jellyfin/videos/item/hls1/main/master.m3u8"
        assertEquals(0L, SplitAvStreamUrlPolicy.receiverMediaStartOffsetMs(url, -1L))
    }

    @Test
    fun `stripAndValidateTheaterStreamUrl strips query tokens and rejects userinfo`() {
        val cleaned = SplitAvStreamUrlPolicy.stripAndValidateTheaterStreamUrl(
            rawUrl = "https://jellyfin.lan:8920/Videos/item1/stream?static=true&api_key=SECRET1&ApiKey=SECRET2&token=SECRET3&audioStreamIndex=2",
            allowedHost = "jellyfin.lan",
        ).getOrThrow()
        assertEquals(
            "https://jellyfin.lan:8920/Videos/item1/stream?static=true&audioStreamIndex=2",
            cleaned,
        )
        assertFalse(cleaned.contains("SECRET", ignoreCase = true))

        val userInfoRes = SplitAvStreamUrlPolicy.stripAndValidateTheaterStreamUrl(
            rawUrl = "https://user:pass@jellyfin.lan:8920/Videos/item1/stream?static=true",
            allowedHost = "jellyfin.lan",
        )
        assertTrue(userInfoRes.isFailure)
    }

    @Test
    fun `scrubUrlForLog redacts query string and userinfo`() {
        val scrubbed = SplitAvStreamUrlPolicy.scrubUrlForLog(
            "https://admin:pw@jellyfin.lan:8920/Videos/item1/stream?static=true&api_key=LEAK",
        )
        assertEquals("https://jellyfin.lan:8920/Videos/item1/stream?<redacted>", scrubbed)
        assertFalse(scrubbed.contains("LEAK"))
        assertFalse(scrubbed.contains("pw"))
    }
}
