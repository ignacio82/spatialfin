package dev.jdtech.jellyfin.player.xr.voice

import dev.jdtech.jellyfin.player.xr.capture.PlayerFrameCapture
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import io.mockk.mockk
import java.net.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the Stage 1 voice search & frame capture security invariants:
 * - Direct Android SearXNG URLs require HTTPS (no loopback http:// exception).
 * - voiceHttpClient stays alive after GeminiCloudService.destroy().
 * - WebSearchClient.companionClient uses LAN_ONLY with Proxy.NO_PROXY.
 * - PlayerFrameCapture.captureExact() allows only active Jellyfin server origin/basePath.
 */
class WebSearchClientTest {
    @Test
    fun `accepts https url and normalizes`() {
        assertEquals(
            "https://searx.example.com/",
            WebSearchClient.sanitizeSearxngUrl("https://searx.example.com"),
        )
    }

    @Test
    fun `rejects plaintext http over network and loopback`() {
        assertNull(WebSearchClient.sanitizeSearxngUrl("http://searx.example.com"))
        assertNull(WebSearchClient.sanitizeSearxngUrl("http://192.168.1.50:8080"))
        assertNull(WebSearchClient.sanitizeSearxngUrl("http://localhost:8080"))
        assertNull(WebSearchClient.sanitizeSearxngUrl("http://127.0.0.1:8888"))
        assertNull(WebSearchClient.sanitizeSearxngUrl("http://[::1]:8080"))
    }

    @Test
    fun `rejects blank and unparseable input`() {
        assertNull(WebSearchClient.sanitizeSearxngUrl(null))
        assertNull(WebSearchClient.sanitizeSearxngUrl("   "))
        assertNull(WebSearchClient.sanitizeSearxngUrl("not a url"))
        assertNull(WebSearchClient.sanitizeSearxngUrl("ftp://searx.example.com"))
    }

    @Test
    fun `voiceHttpClient stays alive after GeminiCloudService destroy and companionClient enforces LAN_ONLY`() {
        assertFalse(voiceHttpClient.followRedirects)
        assertFalse(voiceHttpClient.fastFallback)

        val gemini = GeminiCloudService(
            appContext = mockk(relaxed = true),
            appPreferences = mockk(relaxed = true),
            repository = mockk(relaxed = true),
        )
        gemini.destroy()
        assertFalse(
            "voiceHttpClient dispatcher must remain alive after GeminiCloudService.destroy()",
            voiceHttpClient.dispatcher.executorService.isShutdown,
        )

        val webSearch = WebSearchClient(mockk<AppPreferences>(relaxed = true))
        assertEquals(Proxy.NO_PROXY, webSearch.companionClient.proxy)
        assertFalse(webSearch.companionClient.followRedirects)
    }

    @Test
    fun `PlayerFrameCapture allows only active Jellyfin origin and falls back for non-Jellyfin streams`() = runBlocking {
        val baseUrl = "https://jf.example.com/jellyfin"
        assertTrue(
            PlayerFrameCapture.isAllowedJellyfinStreamUri(
                "https://jf.example.com/jellyfin/Videos/123/stream?static=true",
                baseUrl,
            ),
        )
        assertFalse(
            PlayerFrameCapture.isAllowedJellyfinStreamUri(
                "https://jf.example.com/jellyfin2/Videos/123/stream",
                baseUrl,
            ),
        )
        assertFalse(
            PlayerFrameCapture.isAllowedJellyfinStreamUri(
                "https://other.example.com/Videos/123/stream",
                baseUrl,
            ),
        )
        assertFalse(
            PlayerFrameCapture.isAllowedJellyfinStreamUri(
                "http://127.0.0.1:8096/Videos/123/stream",
                "http://127.0.0.1:8096",
            ),
        )
        assertFalse(
            PlayerFrameCapture.isAllowedJellyfinStreamUri(
                "file:///data/data/dev.spatialfin/secret.mp4",
                baseUrl,
            ),
        )
        assertNull(
            PlayerFrameCapture.captureExact(
                streamUri = "https://evil.example.com/stream.mp4",
                positionMs = 1_000L,
                allowedBaseUrl = baseUrl,
            ),
        )
    }
}
