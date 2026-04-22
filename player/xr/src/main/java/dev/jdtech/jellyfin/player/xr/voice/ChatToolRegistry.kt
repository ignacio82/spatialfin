package dev.jdtech.jellyfin.player.xr.voice

import dev.jdtech.jellyfin.api.OmdbApi
import dev.jdtech.jellyfin.api.OmdbResult
import dev.jdtech.jellyfin.api.TmdbApi
import dev.jdtech.jellyfin.core.llm.LlmInferenceProfile
import dev.jdtech.jellyfin.core.llm.VoiceAiEngine
import dev.jdtech.jellyfin.player.session.voice.PlayerStateSnapshot
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.json.JSONObject
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
    private val omdbApi: OmdbApi = OmdbApi(appPreferences, voiceHttpClient),
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

        // Primary path: typed tool calling (LiteRT-LM). The model replies with a
        // structured ParsedToolCall whose arguments are already typed. Nothing
        // to parse.
        val typedArgs: Map<String, Any?>? = try {
            withTimeoutOrNull(TOOL_CALL_TIMEOUT_MS) {
                engine.runToolCall(
                    prompt = buildToolSelectionPrompt(question, playerState),
                    toolDescriptionJson = buildResearchMediaTool(webSearchAvailable),
                    profile = LlmInferenceProfile.COMMAND,
                )
            }?.takeIf { it.name == "research_media" }?.arguments
        } catch (e: Exception) {
            Timber.d(e, "ChatTool: runToolCall failed")
            null
        }

        // Fallback path: JSON-in-text. AICore / Gemini Nano has no tool schema
        // surface in mlkit-genai-prompt:1.0.0-beta2, so we prompt it to reply
        // with a bare JSON object and parse it (same pattern GemmaCommandParser
        // uses for its command-parse fallback). This is what lets the research
        // pass engage on Pixel 10 Pro's Gemini Nano even without typed tools.
        val args: Map<String, Any?>? = typedArgs ?: runJsonFallback(
            engine = engine,
            question = question,
            playerState = playerState,
            webSearchAvailable = webSearchAvailable,
        )

        if (args == null) {
            Timber.d("ChatTool: no usable tool call emitted")
            return null
        }
        val action = (args["action"] as? String)?.lowercase() ?: return null
        Timber.d("ChatTool: dispatching action=%s args=%s strategy=%s", action, args.keys, if (typedArgs != null) "typed" else "json-fallback")
        return when (action) {
            "lookup_title" -> executeLookupTitle(args, playerState)
            "lookup_person" -> executeLookupPerson(args)
            "describe_current_item" -> executeDescribeCurrentItem(playerState)
            "web_search" -> if (webSearchAvailable) executeWebSearch(args) else null
            "none" -> null
            else -> {
                Timber.d("ChatTool: unknown action=%s", action)
                null
            }
        }
    }

    private suspend fun runJsonFallback(
        engine: VoiceAiEngine,
        question: String,
        playerState: PlayerStateSnapshot,
        webSearchAvailable: Boolean,
    ): Map<String, Any?>? {
        val prompt = buildJsonFallbackPrompt(question, playerState, webSearchAvailable)
        val responseText = try {
            withTimeoutOrNull(TOOL_CALL_TIMEOUT_MS) {
                engine.runInference(prompt = prompt, profile = LlmInferenceProfile.COMMAND)
            }
        } catch (e: Exception) {
            Timber.d(e, "ChatTool: JSON-fallback inference threw")
            null
        } ?: return null
        val jsonStr = extractJsonObject(responseText) ?: run {
            Timber.d("ChatTool: JSON-fallback response had no parseable JSON: %s", responseText.take(120))
            return null
        }
        return try {
            val obj = JSONObject(jsonStr)
            val result = mutableMapOf<String, Any?>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next() as? String ?: continue
                result[key] = obj.opt(key)
            }
            result
        } catch (e: Exception) {
            Timber.d(e, "ChatTool: JSON-fallback parse failed for %s", jsonStr)
            null
        }
    }

    private fun extractJsonObject(raw: String): String? {
        val start = raw.indexOf('{')
        if (start == -1) return null
        var depth = 0
        for (i in start until raw.length) {
            when (raw[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return raw.substring(start, i + 1)
                }
            }
        }
        return null
    }

    private fun buildJsonFallbackPrompt(
        question: String,
        playerState: PlayerStateSnapshot,
        webSearchAvailable: Boolean,
    ): String {
        val context = buildString {
            if (playerState.currentItemTitle.isNotBlank()) {
                append("Currently playing: ${playerState.currentItemTitle}")
                playerState.productionYear?.let { append(" ($it)") }
                append(". ")
            }
        }
        val webLine = if (webSearchAvailable) {
            "\n  {\"action\": \"web_search\", \"query\": \"<short search query>\"}"
        } else ""
        return """
            You are SpatialFin's research planner. The user asked: "$question".
            ${context.ifBlank { "No media is currently playing." }}
            If gathering one fact would help answer — a movie/show overview, a
            person's background, or the details of what's on screen — reply
            with EXACTLY ONE JSON object. Otherwise reply {"action": "none"}.

            Valid shapes (pick one):
              {"action": "lookup_title", "title": "<movie or show title>"}
              {"action": "lookup_person", "name": "<person name>"}
              {"action": "describe_current_item"}$webLine
              {"action": "none"}

            No prose, no explanation. Just the JSON object.
        """.trimIndent()
    }

    private suspend fun executeLookupTitle(
        args: Map<String, Any?>,
        playerState: PlayerStateSnapshot,
    ): ResearchNotes? {
        var title = (args["title"] as? String)?.trim().orEmpty()
        if (title.isBlank()) return null

        val normalized = title.lowercase()
        val isCurrentItemRef = normalized == "this" || 
            normalized == "this movie" || 
            normalized == "this show" || 
            normalized == "this title" ||
            normalized.contains("on my screen") ||
            normalized.contains("on the screen") ||
            normalized.contains("playing right now") ||
            normalized.contains("playing now") ||
            normalized.contains("currently playing")

        if (isCurrentItemRef && playerState.currentItemTitle.isNotBlank()) {
            title = playerState.currentItemTitle
        }

        val tmdbSummary = if (tmdbApi.isConfigured()) {
            tmdbTitleSummary(title, playerState)
        } else null
        val wiki = wikipediaClient.getSummary(title)?.extract?.let { trimToSentenceBoundary(it, WIKI_EXTRACT_CAP) }
        val omdbLine = if (omdbApi.isConfigured()) {
            val movie = omdbApi.searchMovie(title, playerState.productionYear)
            val fallback = movie ?: omdbApi.searchSeries(title, playerState.productionYear)
            fallback?.let { buildOmdbResearchLine(it) }
        } else null

        val body = listOfNotNull(
            tmdbSummary,
            omdbLine?.let { "OMDb — $title: $it" },
            wiki?.let { "Wikipedia — $title: $it" },
        ).joinToString("\n\n")
        if (body.isBlank()) return null
        return ResearchNotes(
            body = body,
            debugInfo = "lookup_title title=$title tmdb=${tmdbSummary != null} omdb=${omdbLine != null} wiki=${wiki != null}",
        )
    }

    /**
     * Compact ratings-first OMDb line. Voice replies bias toward score-heavy
     * claims ("what are critics saying"), so we lead with RT/Metacritic/IMDb
     * when available and trim awards/runtime to keep the line spoken-length.
     * Returns null when OMDb had no ratings AND no awards worth surfacing.
     */
    private fun buildOmdbResearchLine(result: OmdbResult): String? {
        if (result.response != "True") return null
        val naSet = setOf("", "N/A")
        val ratings = result.ratings
            .mapNotNull { r ->
                val label = when {
                    r.source.contains("Rotten", ignoreCase = true) -> "Rotten Tomatoes"
                    r.source.contains("Metacritic", ignoreCase = true) -> "Metacritic"
                    r.source.contains("Internet Movie Database", ignoreCase = true) -> "IMDb"
                    else -> null
                } ?: return@mapNotNull null
                val value = r.value.trim().takeIf { it !in naSet } ?: return@mapNotNull null
                "$label $value"
            }
            .take(3)
            .joinToString(", ")
        val awards = result.awards.takeIf { it !in naSet }?.let { trimToSentenceBoundary(it, 120) }
        val runtime = result.runtime.takeIf { it !in naSet }
        val parts = mutableListOf<String>()
        if (ratings.isNotBlank()) parts.add("ratings $ratings")
        if (!awards.isNullOrBlank()) parts.add("awards — $awards")
        if (!runtime.isNullOrBlank()) parts.add("runtime $runtime")
        return parts.joinToString("; ").takeIf { it.isNotBlank() }
    }

    private suspend fun tmdbTitleSummary(
        title: String,
        playerState: PlayerStateSnapshot,
    ): String? {
        val movie = tmdbApi.searchMovies(title, playerState.productionYear).firstOrNull()
        val tv = tmdbApi.searchTv(title, playerState.productionYear).firstOrNull()
        return when {
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
                        append(trimToSentenceBoundary(details.overview, OVERVIEW_CAP))
                    }
                }
            }
            tv != null -> {
                tmdbApi.getTvDetails(tv.id)?.let { details ->
                    buildString {
                        append("TMDB — ${details.name}")
                        details.firstAirDate?.take(4)?.takeIf { it.isNotBlank() }?.let { append(" ($it)") }
                        append(". ")
                        append(trimToSentenceBoundary(details.overview, OVERVIEW_CAP))
                    }
                }
            }
            else -> null
        }
    }

    private suspend fun executeLookupPerson(args: Map<String, Any?>): ResearchNotes? {
        val name = (args["name"] as? String)?.trim().orEmpty()
        if (name.isBlank()) return null
        val wiki = wikipediaClient.getSummary(name) ?: return null
        return ResearchNotes(
            body = "Wikipedia — ${wiki.title}: ${trimToSentenceBoundary(wiki.extract, WIKI_EXTRACT_CAP)}",
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
                if (hit.snippet.isNotBlank()) append(" — ${trimToSentenceBoundary(hit.snippet, WIKI_EXTRACT_CAP)}")
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
