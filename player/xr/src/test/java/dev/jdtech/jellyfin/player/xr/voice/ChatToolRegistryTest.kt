package dev.jdtech.jellyfin.player.xr.voice

import dev.jdtech.jellyfin.api.TmdbApi
import dev.jdtech.jellyfin.core.llm.ParsedToolCall
import dev.jdtech.jellyfin.core.llm.VoiceAiEngine
import dev.jdtech.jellyfin.player.session.voice.PlayerStateSnapshot
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the ChatToolRegistry dispatch without touching the network.
 *
 * TmdbApi and WikipediaSummaryClient are injected as mocks; the only remaining
 * external surface is [VoiceAiEngine.runToolCall], which is also mocked. These
 * tests don't need Robolectric — nothing reaches into Android framework classes.
 */
class ChatToolRegistryTest {

    private val appPreferences = mockk<AppPreferences>(relaxed = true).also {
        // ChatToolRegistry's default constructor would call `TmdbApi(appPreferences,...)` which
        // touches the preference's cloud API key lookup. We bypass that by injecting fakes below.
    }

    @Test fun `gather returns null when engine returns no tool call`() = runTest {
        val engine = mockk<VoiceAiEngine>(relaxed = true)
        coEvery { engine.runToolCall(any(), any(), any()) } returns null

        val registry = ChatToolRegistry(
            appPreferences = appPreferences,
            tmdbApi = mockk(relaxed = true),
            wikipediaClient = mockk(relaxed = true),
            webSearchClient = mockk(relaxed = true),
        )

        val notes = registry.gather("who wrote this movie", PlayerStateSnapshot(), engine)
        assertNull(notes)
    }

    @Test fun `gather returns null for unknown action`() = runTest {
        val engine = mockk<VoiceAiEngine>(relaxed = true)
        coEvery { engine.runToolCall(any(), any(), any()) } returns ParsedToolCall(
            name = "research_media",
            arguments = mapOf("action" to "invent_something"),
        )

        val registry = ChatToolRegistry(
            appPreferences = appPreferences,
            tmdbApi = mockk(relaxed = true),
            wikipediaClient = mockk(relaxed = true),
            webSearchClient = mockk(relaxed = true),
        )

        val notes = registry.gather("something", PlayerStateSnapshot(), engine)
        assertNull(notes)
    }

    @Test fun `describe_current_item synthesises a digest from player state`() = runTest {
        val engine = mockk<VoiceAiEngine>(relaxed = true)
        coEvery { engine.runToolCall(any(), any(), any()) } returns ParsedToolCall(
            name = "research_media",
            arguments = mapOf("action" to "describe_current_item"),
        )

        val registry = ChatToolRegistry(
            appPreferences = appPreferences,
            tmdbApi = mockk(relaxed = true),
            wikipediaClient = mockk(relaxed = true),
            webSearchClient = mockk(relaxed = true),
        )

        val notes = registry.gather(
            question = "tell me about what's on",
            playerState = PlayerStateSnapshot(
                currentItemTitle = "Blade Runner",
                productionYear = 1982,
                currentGenres = listOf("Science Fiction", "Thriller"),
                directors = listOf("Ridley Scott"),
                castNames = listOf("Harrison Ford", "Rutger Hauer"),
                currentOverview = "A blade runner hunts down replicants in dystopian Los Angeles.",
            ),
            engine = engine,
        )

        assertNotNull(notes)
        val body = notes!!.body
        assertTrue("missing title: $body", body.contains("Blade Runner"))
        assertTrue("missing year: $body", body.contains("1982"))
        assertTrue("missing director: $body", body.contains("Ridley Scott"))
        assertTrue("missing overview: $body", body.contains("blade runner"))
    }

    @Test fun `describe_current_item returns null when nothing is playing`() = runTest {
        val engine = mockk<VoiceAiEngine>(relaxed = true)
        coEvery { engine.runToolCall(any(), any(), any()) } returns ParsedToolCall(
            name = "research_media",
            arguments = mapOf("action" to "describe_current_item"),
        )

        val registry = ChatToolRegistry(
            appPreferences = appPreferences,
            tmdbApi = mockk(relaxed = true),
            wikipediaClient = mockk(relaxed = true),
            webSearchClient = mockk(relaxed = true),
        )

        val notes = registry.gather("what am I watching", PlayerStateSnapshot(), engine)
        assertNull(notes)
    }

    @Test fun `lookup_person returns wikipedia summary when available`() = runTest {
        val engine = mockk<VoiceAiEngine>(relaxed = true)
        coEvery { engine.runToolCall(any(), any(), any()) } returns ParsedToolCall(
            name = "research_media",
            arguments = mapOf("action" to "lookup_person", "name" to "Denis Villeneuve"),
        )

        val wiki = mockk<WikipediaSummaryClient>()
        coEvery { wiki.getSummary("Denis Villeneuve") } returns WikipediaSummary(
            title = "Denis Villeneuve",
            extract = "Denis Villeneuve is a Canadian filmmaker known for Arrival, Blade Runner 2049, and Dune.",
            canonicalUrl = null,
        )

        val registry = ChatToolRegistry(
            appPreferences = appPreferences,
            tmdbApi = mockk(relaxed = true),
            wikipediaClient = wiki,
            webSearchClient = mockk(relaxed = true),
        )

        val notes = registry.gather("who is Denis Villeneuve", PlayerStateSnapshot(), engine)

        assertNotNull(notes)
        assertTrue(notes!!.body.contains("Canadian filmmaker"))
    }

    @Test fun `lookup_person returns null when wikipedia has nothing`() = runTest {
        val engine = mockk<VoiceAiEngine>(relaxed = true)
        coEvery { engine.runToolCall(any(), any(), any()) } returns ParsedToolCall(
            name = "research_media",
            arguments = mapOf("action" to "lookup_person", "name" to "Nobody Nowhere"),
        )

        val wiki = mockk<WikipediaSummaryClient>()
        coEvery { wiki.getSummary("Nobody Nowhere") } returns null

        val registry = ChatToolRegistry(
            appPreferences = appPreferences,
            tmdbApi = mockk(relaxed = true),
            wikipediaClient = wiki,
            webSearchClient = mockk(relaxed = true),
        )

        val notes = registry.gather("who is nobody", PlayerStateSnapshot(), engine)
        assertNull(notes)
    }

    @Test fun `lookup_title with tmdb not configured falls back to wikipedia`() = runTest {
        val engine = mockk<VoiceAiEngine>(relaxed = true)
        coEvery { engine.runToolCall(any(), any(), any()) } returns ParsedToolCall(
            name = "research_media",
            arguments = mapOf("action" to "lookup_title", "title" to "Arrival"),
        )

        val tmdb = mockk<TmdbApi>()
        every { tmdb.isConfigured() } returns false

        val wiki = mockk<WikipediaSummaryClient>()
        coEvery { wiki.getSummary("Arrival") } returns WikipediaSummary(
            title = "Arrival",
            extract = "Arrival is a 2016 American science fiction drama film.",
            canonicalUrl = null,
        )

        val registry = ChatToolRegistry(
            appPreferences = appPreferences,
            tmdbApi = tmdb,
            wikipediaClient = wiki,
            webSearchClient = mockk(relaxed = true),
        )

        val notes = registry.gather("tell me about Arrival", PlayerStateSnapshot(), engine)

        assertNotNull(notes)
        val body = notes!!.body
        assertTrue("expected wikipedia marker, got: $body", body.contains("Wikipedia"))
        assertTrue(body.contains("2016 American science fiction"))
    }

    @Test fun `blank title in lookup_title returns null`() = runTest {
        val engine = mockk<VoiceAiEngine>(relaxed = true)
        coEvery { engine.runToolCall(any(), any(), any()) } returns ParsedToolCall(
            name = "research_media",
            arguments = mapOf("action" to "lookup_title", "title" to ""),
        )

        val registry = ChatToolRegistry(
            appPreferences = appPreferences,
            tmdbApi = mockk(relaxed = true),
            wikipediaClient = mockk(relaxed = true),
            webSearchClient = mockk(relaxed = true),
        )

        val notes = registry.gather("lookup a title please", PlayerStateSnapshot(), engine)
        assertNull(notes)
    }

    @Test fun `web_search returns research notes when client yields hits`() = runTest {
        val engine = mockk<VoiceAiEngine>(relaxed = true)
        coEvery { engine.runToolCall(any(), any(), any()) } returns ParsedToolCall(
            name = "research_media",
            arguments = mapOf("action" to "web_search", "query" to "Villeneuve Dune sequel"),
        )

        val webSearch = mockk<WebSearchClient>()
        every { webSearch.isConfigured() } returns true
        coEvery { webSearch.search("Villeneuve Dune sequel", any()) } returns listOf(
            WebSearchHit(title = "Dune: Part Three news", snippet = "Production starts 2026.", url = "https://example.com/a"),
            WebSearchHit(title = "Villeneuve interview", snippet = "Filmmaker confirms script finalized.", url = "https://example.com/b"),
        )

        val registry = ChatToolRegistry(
            appPreferences = appPreferences,
            tmdbApi = mockk(relaxed = true),
            wikipediaClient = mockk(relaxed = true),
            webSearchClient = webSearch,
        )

        val notes = registry.gather("anything about the next Dune", PlayerStateSnapshot(), engine)
        assertNotNull(notes)
        val body = notes!!.body
        assertTrue("missing first hit: $body", body.contains("Dune: Part Three news"))
        assertTrue("missing second hit: $body", body.contains("Villeneuve interview"))
    }

    @Test fun `web_search is swallowed when client reports not configured`() = runTest {
        val engine = mockk<VoiceAiEngine>(relaxed = true)
        coEvery { engine.runToolCall(any(), any(), any()) } returns ParsedToolCall(
            name = "research_media",
            arguments = mapOf("action" to "web_search", "query" to "anything"),
        )

        val webSearch = mockk<WebSearchClient>()
        every { webSearch.isConfigured() } returns false

        val registry = ChatToolRegistry(
            appPreferences = appPreferences,
            tmdbApi = mockk(relaxed = true),
            wikipediaClient = mockk(relaxed = true),
            webSearchClient = webSearch,
        )

        val notes = registry.gather("asking for trouble", PlayerStateSnapshot(), engine)
        assertNull(notes)
        coVerify(exactly = 0) { webSearch.search(any(), any()) }
    }

    @Test fun `web_search returns null when query is blank`() = runTest {
        val engine = mockk<VoiceAiEngine>(relaxed = true)
        coEvery { engine.runToolCall(any(), any(), any()) } returns ParsedToolCall(
            name = "research_media",
            arguments = mapOf("action" to "web_search", "query" to "  "),
        )

        val webSearch = mockk<WebSearchClient>()
        every { webSearch.isConfigured() } returns true

        val registry = ChatToolRegistry(
            appPreferences = appPreferences,
            tmdbApi = mockk(relaxed = true),
            wikipediaClient = mockk(relaxed = true),
            webSearchClient = webSearch,
        )

        val notes = registry.gather("valid long question", PlayerStateSnapshot(), engine)
        assertNull(notes)
        coVerify(exactly = 0) { webSearch.search(any(), any()) }
    }

    @Test fun `wrong tool name returns null`() = runTest {
        val engine = mockk<VoiceAiEngine>(relaxed = true)
        coEvery { engine.runToolCall(any(), any(), any()) } returns ParsedToolCall(
            name = "interpret_command",
            arguments = mapOf("action" to "describe_current_item"),
        )

        val registry = ChatToolRegistry(
            appPreferences = appPreferences,
            tmdbApi = mockk(relaxed = true),
            wikipediaClient = mockk(relaxed = true),
            webSearchClient = mockk(relaxed = true),
        )

        val notes = registry.gather(
            question = "what's on",
            playerState = PlayerStateSnapshot(currentItemTitle = "Dune", productionYear = 2021),
            engine = engine,
        )
        assertEquals(null, notes)
    }
}
