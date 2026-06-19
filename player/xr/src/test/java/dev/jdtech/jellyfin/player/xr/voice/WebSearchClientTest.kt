package dev.jdtech.jellyfin.player.xr.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the SearXNG URL scheme policy: the assistant query travels in the URL,
 * so cleartext is only acceptable to a loopback host. Everything else must be
 * HTTPS or be dropped (degrading to the companion path / graceful no-op).
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
    fun `rejects plaintext http over the network`() {
        assertNull(WebSearchClient.sanitizeSearxngUrl("http://searx.example.com"))
        assertNull(WebSearchClient.sanitizeSearxngUrl("http://192.168.1.50:8080"))
    }

    @Test
    fun `allows http only for loopback hosts`() {
        assertEquals("http://localhost:8080/", WebSearchClient.sanitizeSearxngUrl("http://localhost:8080"))
        assertEquals("http://127.0.0.1:8888/", WebSearchClient.sanitizeSearxngUrl("http://127.0.0.1:8888"))
        assertEquals("http://[::1]:8080/", WebSearchClient.sanitizeSearxngUrl("http://[::1]:8080"))
    }

    @Test
    fun `rejects blank and unparseable input`() {
        assertNull(WebSearchClient.sanitizeSearxngUrl(null))
        assertNull(WebSearchClient.sanitizeSearxngUrl("   "))
        assertNull(WebSearchClient.sanitizeSearxngUrl("not a url"))
        assertNull(WebSearchClient.sanitizeSearxngUrl("ftp://searx.example.com"))
    }
}
