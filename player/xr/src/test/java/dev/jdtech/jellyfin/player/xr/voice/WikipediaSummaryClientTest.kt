package dev.jdtech.jellyfin.player.xr.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure URL-construction sanity check.
 *
 * A previous revision built the search URL as `$WIKIPEDIA_SEARCH_URL?srsearch=...`
 * which, because the constant already ends with `?action=query&list=search`,
 * produced `...api.php?action=query&list=search?srsearch=...&format=json` —
 * Wikipedia's action API silently reads that as `list=search?srsearch=...`
 * (one bogus parameter) and returns zero hits. The visible symptom was the
 * assistant answering "I couldn't find external details" for every title.
 *
 * Locking the exact URL shape here keeps a future well-intentioned refactor
 * of the URL builder from reintroducing the same bug.
 */
class WikipediaSummaryClientTest {

    @Test
    fun `search URL joins with ampersand not question mark`() {
        val url = WikipediaSummaryClient.buildSearchUrl("Blade Runner")
        assertEquals(
            "https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=Blade+Runner&format=json",
            url,
        )
        // Hard guard: exactly one `?` separates host+path from the query string.
        assertEquals("URL should have exactly one '?'", 1, url.count { it == '?' })
    }

    @Test
    fun `search URL encodes reserved characters`() {
        val url = WikipediaSummaryClient.buildSearchUrl("R&B music")
        assertTrue("ampersand should be encoded: $url", url.contains("srsearch=R%26B+music"))
        // The wrapping api.php & separators should still be literal.
        assertTrue(url.contains("?action=query&list=search&srsearch="))
    }

    @Test
    fun `search URL preserves unicode query content`() {
        val url = WikipediaSummaryClient.buildSearchUrl("映画")
        // URLEncoder outputs percent-encoded UTF-8 — we just verify the
        // parameter made it in without truncation or scheme corruption.
        assertTrue(url.startsWith("https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch="))
        assertTrue(url.contains("&format=json"))
    }
}
