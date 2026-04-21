package dev.jdtech.jellyfin.player.xr.voice

import dev.jdtech.jellyfin.api.TmdbApi
import dev.jdtech.jellyfin.core.llm.LlmInferenceProfile
import dev.jdtech.jellyfin.core.llm.VoiceAiEngine
import dev.jdtech.jellyfin.player.session.voice.PlayerStateSnapshot
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import timber.log.Timber

/** Shared HTTP client for the voice package. See note in [MediaSkillRegistry]. */
internal val voiceHttpClient: OkHttpClient by lazy { OkHttpClient() }

/**
 * Lets the on-device model gather structured facts (TMDB metadata, Wikipedia
 * summaries, a compact view of the currently-playing item) *before* the chat
 * prompt is built. Each turn makes at most one tool call — enough to answer
 * multi-hop questions like "who wrote the book this movie is based on?" without
 * doubling perceived latency.
 *
 * Only LiteRT-backed engines engage this path: AICore's ML Kit GenAI API
 * (`mlkit-genai-prompt:1.0.0-beta2`) doesn't expose tool schemas yet, so
 * [VoiceAiEngine.runToolCall] returns null there and we skip. Cloud flows also
 * skip — going straight to the cloud model is usually faster than a round trip
 * through on-device tool calling first.
 *
 * Kept deliberately narrow: the full skill registry already handles playback
 * control, recommendations, library search, recap, dialogue explainer, and
 * explicit external-knowledge lookups. Tool calling is the fallback the model
 * reaches for when the skill classifier landed on [MediaSkillId.GENERAL_CHAT]
 * and a grounded fact would actually help.
 */
internal class ChatToolRegistry(
    appPreferences: AppPreferences,
    private val tmdbApi: TmdbApi = TmdbApi(appPreferences, voiceHttpClient),
    private val wikipediaClient: WikipediaSummaryClient = WikipediaSummaryClient(voiceHttpClient),
    private val webSearchClient: WebSearchClient = WebSearchClient(appPreferences, voiceHttpClient),
) {
    data class ResearchNotes(
        val body: String,
        val debugInfo: String,
    )

    /**
     * Run a single tool-selection pass on [engine]. Returns null if the model
     * declined to call a tool, the tool returned nothing useful, or the engine
     * doesn't support typed tool calls (AICore).
     *
     * Uses [LlmInferenceProfile.COMMAND] so the output is a short schema blob,
     * not free-form chat — the tool-selection round trip finishes in a few
     * seconds even on LiteRT GPU.
     */
    suspend fun gather(
        question: String,
        playerState: PlayerStateSnapshot,
        engine: VoiceAiEngine,
    ): ResearchNotes? {
        val webSearchAvailable = webSearchClient.isConfigured()
        val prompt = buildToolSelectionPrompt(question, playerState)
        val toolCall = try {
            withTimeoutOrNull(TOOL_CALL_TIMEOUT_MS) {
                engine.runToolCall(
                    prompt = prompt,
                    toolDescriptionJson = buildResearchMediaTool(webSearchAvailable),
                    profile = LlmInferenceProfile.COMMAND,
                )
            }
        } catch (e: Exception) {
            Timber.d(e, "ChatTool: runToolCall failed")
            return null
        }
        if (toolCall == null || toolCall.name != "research_media") {
            Timber.d("ChatTool: no tool call emitted (toolCall=%s)", toolCall?.name)
            return null
        }

        val action = (toolCall.arguments["action"] as? String)?.lowercase() ?: return null
        Timber.d("ChatTool: dispatching action=%s args=%s", action, toolCall.arguments.keys)
        return when (action) {
            "lookup_title" -> executeLookupTitle(toolCall.arguments, playerState)
            "lookup_person" -> executeLookupPerson(toolCall.arguments)
            "describe_current_item" -> executeDescribeCurrentItem(playerState)
            "web_search" -> if (webSearchAvailable) executeWebSearch(toolCall.arguments) else null
            else -> {
                Timber.d("ChatTool: unknown action=%s", action)
                null
            }
        }
    }

    private suspend fun executeLookupTitle(
        args: Map<String, Any?>,
        playerState: PlayerStateSnapshot,
    ): ResearchNotes? {
        val title = (args["title"] as? String)?.trim().orEmpty()
        if (title.isBlank()) return null
        if (!tmdbApi.isConfigured()) {
            val wiki = wikipediaClient.getSummary(title) ?: return null
            return ResearchNotes(
                body = "Wikipedia — ${wiki.title}: ${wiki.extract.take(WIKI_EXTRACT_CAP).trim()}",
                debugInfo = "lookup_title wiki-only title=$title",
            )
        }
        val movie = tmdbApi.searchMovies(title, playerState.productionYear).firstOrNull()
        val tv = tmdbApi.searchTv(title, playerState.productionYear).firstOrNull()
        val summary = when {
            movie != null && (tv == null || movie.voteAverage >= tv.voteAverage) -> {
                tmdbApi.getMovieDetails(movie.id)?.let { details ->
                    val director = details.credits?.crew
                        ?.firstOrNull { it.job.equals("Director", ignoreCase = true) }
                        ?.name
                    buildString {
                        append("TMDB — ${details.title}")
                        details.releaseDate?.take(4)?.takeIf { it.isNotBlank() }?.let { append(" ($it)") }
                        append(". ")
                        if (director != null) append("Directed by $director. ")
                        append(details.overview.take(OVERVIEW_CAP).trim())
                    }
                }
            }
            tv != null -> {
                tmdbApi.getTvDetails(tv.id)?.let { details ->
                    buildString {
                        append("TMDB — ${details.name}")
                        details.firstAirDate?.take(4)?.takeIf { it.isNotBlank() }?.let { append(" ($it)") }
                        append(". ")
                        append(details.overview.take(OVERVIEW_CAP).trim())
                    }
                }
            }
            else -> null
        }
        val wiki = wikipediaClient.getSummary(title)?.extract?.take(WIKI_EXTRACT_CAP)?.trim()
        val body = listOfNotNull(
            summary,
            wiki?.let { "Wikipedia — $title: $it" },
        ).joinToString("\n\n")
        if (body.isBlank()) return null
        return ResearchNotes(body = body, debugInfo = "lookup_title title=$title tmdb=${summary != null} wiki=${wiki != null}")
    }

    private suspend fun executeLookupPerson(args: Map<String, Any?>): ResearchNotes? {
        val name = (args["name"] as? String)?.trim().orEmpty()
        if (name.isBlank()) return null
        val wiki = wikipediaClient.getSummary(name) ?: return null
        return ResearchNotes(
            body = "Wikipedia — ${wiki.title}: ${wiki.extract.take(WIKI_EXTRACT_CAP).trim()}",
            debugInfo = "lookup_person name=$name",
        )
    }

    private suspend fun executeWebSearch(args: Map<String, Any?>): ResearchNotes? {
        val query = (args["query"] as? String)?.trim().orEmpty()
        if (query.isBlank()) return null
        val hits = webSearchClient.search(query, maxResults = WEB_SEARCH_MAX_HITS)
        if (hits.isEmpty()) return null
        val body = buildString {
            append("Web search results for \"$query\" (use as supporting context, do not invent):")
            hits.forEach { hit ->
                appendLine()
                append("- ${hit.title}")
                if (hit.snippet.isNotBlank()) append(" — ${hit.snippet.take(WIKI_EXTRACT_CAP)}")
            }
        }
        return ResearchNotes(body = body, debugInfo = "web_search query=$query hits=${hits.size}")
    }

    private fun executeDescribeCurrentItem(playerState: PlayerStateSnapshot): ResearchNotes? {
        val title = playerState.currentItemTitle.takeIf { it.isNotBlank() } ?: return null
        val parts = buildList {
            add(title)
            playerState.productionYear?.let { add("($it)") }
            if (playerState.currentGenres.isNotEmpty()) {
                add("genres: ${playerState.currentGenres.take(3).joinToString(", ")}")
            }
            if (playerState.directors.isNotEmpty()) {
                add("directed by ${playerState.directors.take(2).joinToString(", ")}")
            }
            if (playerState.castNames.isNotEmpty()) {
                add("cast: ${playerState.castNames.take(4).joinToString(", ")}")
            }
        }
        val overview = playerState.currentOverview.takeIf { it.isNotBlank() }
            ?.take(OVERVIEW_CAP)
            ?.trim()
        val body = buildString {
            append(parts.joinToString(", "))
            if (!overview.isNullOrBlank()) {
                append(". ")
                append(overview)
            }
        }
        return ResearchNotes(body = body, debugInfo = "describe_current_item")
    }

    private fun buildToolSelectionPrompt(
        question: String,
        playerState: PlayerStateSnapshot,
    ): String {
        val context = buildString {
            if (playerState.currentItemTitle.isNotBlank()) {
                append("Currently playing: ${playerState.currentItemTitle}")
                playerState.productionYear?.let { append(" ($it)") }
                append(". ")
            }
        }
        return """
            You are SpatialFin's research planner. The user asked: "$question".
            ${context.ifBlank { "No media is currently playing." }}
            If gathering one fact would help answer — a movie/show overview, a person's background,
            or the details of what's on screen — call the `research_media` tool exactly once with
            the best-fitting action. If no lookup would help (small talk, playback control,
            already-answered facts), do not call the tool.
        """.trimIndent()
    }

    /**
     * The `web_search` action is swapped in when [WebSearchClient.isConfigured]
     * returns true — i.e. a paired companion is advertising the capability or
     * the user has pasted a SearXNG URL. Emitting the action in the enum when
     * nothing can serve it wastes a tool round trip for no benefit.
     */
    private fun buildResearchMediaTool(webSearchEnabled: Boolean): String {
        val actions = buildList {
            add("lookup_title")
            add("lookup_person")
            add("describe_current_item")
            if (webSearchEnabled) add("web_search")
        }.joinToString(", ") { "\"$it\"" }
        return """
        {
          "name": "research_media",
          "description": "Gather one piece of grounded media context before answering the user. Fill only the fields that the chosen action requires.",
          "parameters": {
            "type": "object",
            "properties": {
              "action": {
                "type": "string",
                "description": "Which lookup to perform.",
                "enum": [$actions]
              },
              "title": { "type": "string", "description": "Movie or TV-show title for lookup_title." },
              "name":  { "type": "string", "description": "Person name for lookup_person." },
              "query": { "type": "string", "description": "Free-form query string for web_search." }
            },
            "required": ["action"]
          }
        }
        """
    }

    companion object {
        private const val TOOL_CALL_TIMEOUT_MS = 8_000L
        private const val OVERVIEW_CAP = 320
        private const val WIKI_EXTRACT_CAP = 360
        private const val WEB_SEARCH_MAX_HITS = 3
    }
}
