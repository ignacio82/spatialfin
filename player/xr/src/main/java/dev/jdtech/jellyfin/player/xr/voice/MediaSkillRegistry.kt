package dev.jdtech.jellyfin.player.xr.voice

import dev.jdtech.jellyfin.api.OmdbApi
import dev.jdtech.jellyfin.api.OmdbResult
import dev.jdtech.jellyfin.api.TmdbApi
import dev.jdtech.jellyfin.api.TmdbMovieDetails
import dev.jdtech.jellyfin.api.TmdbMovieResult
import dev.jdtech.jellyfin.api.TmdbTvDetails
import dev.jdtech.jellyfin.api.TmdbTvResult
import dev.jdtech.jellyfin.models.SpatialFinEpisode
import dev.jdtech.jellyfin.models.SpatialFinItem
import dev.jdtech.jellyfin.models.SpatialFinMovie
import dev.jdtech.jellyfin.models.SpatialFinSeason
import dev.jdtech.jellyfin.models.SpatialFinShow
import dev.jdtech.jellyfin.models.SortBy
import dev.jdtech.jellyfin.models.SortOrder
import dev.jdtech.jellyfin.player.session.voice.PlayerStateSnapshot
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.jellyfin.sdk.model.api.BaseItemKind

internal enum class MediaSkillId {
    PLAYBACK_CONTROL,
    WATCH_RECOMMENDER,
    LIBRARY_SEARCH,
    RECAP,
    DIALOGUE_EXPLAINER,
    METADATA_QA,
    CONTINUE_WATCHING,
    MOOD_SURPRISE,
    EXTERNAL_KNOWLEDGE,
    CHARACTER_IDENTIFICATION,
    GENERAL_CHAT,
}

internal data class MediaSkillPlan(
    val skillId: MediaSkillId,
    val validatedInput: String,
    val taskInstructions: String,
    val actionableItems: List<SpatialFinItem> = emptyList(),
    val relatedItemsContext: String? = null,
    val directAnswer: String? = null,
    val fallbackText: String? = null,
    val debugInfo: String = "",
    val shouldSkipModel: Boolean = false,
    /**
     * Structured verified facts to hand the model as grounding context. Distinct
     * from [relatedItemsContext] (which is "items in the user's library") —
     * [researchNotes] is "sourced facts that should appear, unmodified, in the
     * reply". Populated by skills that gather external data (EXTERNAL_KNOWLEDGE)
     * so the model can rephrase the facts into natural speech rather than the
     * concatenation short-circuit surfacing a robotic "Per TMDB: ... Per OMDb:
     * ..." reply. Rendered by the chat prompt as a "Research notes" section.
     */
    val researchNotes: String? = null,
)

internal class MediaSkillRegistry(
    private val repository: JellyfinRepository,
    appPreferences: AppPreferences,
) {
    // Share a single OkHttpClient across the voice-package helpers (skill registry
    // and ChatToolRegistry) so we don't allocate independent dispatcher thread
    // pools per-instance. Full Hilt-scoping of these clients would require
    // reshaping SmartChatEngine's construction (it is created from a Composable
    // `remember` block, not injected), so a package-level lazy val is the minimum
    // that removes duplication without a cascading refactor.
    private val httpClient = voiceHttpClient
    private val tmdbApi = TmdbApi(appPreferences, httpClient)
    private val omdbApi = OmdbApi(appPreferences, httpClient)
    private val wikipediaClient = WikipediaSummaryClient(httpClient)

    suspend fun plan(
        question: String,
        playerState: PlayerStateSnapshot,
        storySoFarContext: String?,
        subtitleContext: String,
        recommendationContext: RecommendationContext?,
        onSearchQuery: (suspend (String) -> List<SpatialFinItem>)? = null,
        onGetSuggestions: (suspend () -> List<SpatialFinItem>)? = null,
        hasVisualContext: Boolean = false,
    ): MediaSkillPlan {
        val skillId = selectSkill(question, playerState, recommendationContext, hasVisualContext)
        return when (skillId) {
            MediaSkillId.PLAYBACK_CONTROL -> playbackControlPlan(question, playerState)
            MediaSkillId.WATCH_RECOMMENDER ->
                recommendationPlan(
                    skillId = skillId,
                    question = question,
                    playerState = playerState,
                    recommendationContext = recommendationContext,
                    onGetSuggestions = onGetSuggestions,
                )
            MediaSkillId.LIBRARY_SEARCH ->
                librarySearchPlan(question, onSearchQuery)
            MediaSkillId.RECAP ->
                recapPlan(question, playerState, storySoFarContext, subtitleContext)
            MediaSkillId.DIALOGUE_EXPLAINER ->
                dialogueExplainerPlan(question, playerState, subtitleContext, storySoFarContext)
            MediaSkillId.METADATA_QA ->
                metadataQaPlan(question, playerState)
            MediaSkillId.CONTINUE_WATCHING ->
                continueWatchingPlan(question)
            MediaSkillId.MOOD_SURPRISE ->
                recommendationPlan(
                    skillId = skillId,
                    question = question,
                    playerState = playerState,
                    recommendationContext = recommendationContext,
                    onGetSuggestions = onGetSuggestions,
                )
            MediaSkillId.EXTERNAL_KNOWLEDGE ->
                externalKnowledgePlan(question, playerState)
            MediaSkillId.CHARACTER_IDENTIFICATION ->
                characterIdentificationPlan(question, playerState)
            MediaSkillId.GENERAL_CHAT ->
                MediaSkillPlan(
                    skillId = MediaSkillId.GENERAL_CHAT,
                    validatedInput = question.trim(),
                    taskInstructions = "Task: Answer briefly and directly. If the answer is uncertain, say so plainly.",
                )
        }
    }

    internal fun selectSkill(
        question: String,
        playerState: PlayerStateSnapshot,
        recommendationContext: RecommendationContext? = null,
        hasVisualContext: Boolean = false,
    ): MediaSkillId {
        val normalized = normalize(question)
        return when {
            isPlaybackInfoQuery(normalized) -> MediaSkillId.PLAYBACK_CONTROL
            isContinueWatchingQuery(normalized) -> MediaSkillId.CONTINUE_WATCHING
            isLibrarySearchQuery(normalized) -> MediaSkillId.LIBRARY_SEARCH
            isExternalKnowledgeQuery(normalized, playerState) -> MediaSkillId.EXTERNAL_KNOWLEDGE
            isMoodSurpriseQuery(normalized) -> MediaSkillId.MOOD_SURPRISE
            RecommendationPlanner.analyzeQuestion(question, recommendationContext) != null -> MediaSkillId.WATCH_RECOMMENDER
            hasVisualContext && isGenericCharacterQuery(normalized) -> MediaSkillId.CHARACTER_IDENTIFICATION
            isDialogueExplainerQuery(normalized) -> MediaSkillId.DIALOGUE_EXPLAINER
            isRecapQuery(normalized) -> MediaSkillId.RECAP
            isMetadataQuery(normalized) -> MediaSkillId.METADATA_QA
            else -> MediaSkillId.GENERAL_CHAT
        }
    }

    private fun playbackControlPlan(
        question: String,
        playerState: PlayerStateSnapshot,
    ): MediaSkillPlan {
        val normalized = normalize(question)
        val directAnswer =
            when {
                normalized.contains("what am i watching") || normalized.contains("what is this") ->
                    playerState.currentItemTitle.takeIf { it.isNotBlank() }?.let { "You're watching $it." }
                normalized.contains("remaining") || normalized.contains("time left") ->
                    playerState.durationSeconds.takeIf { it > 0 }?.let { duration ->
                        val remaining = (duration - playerState.positionSeconds).coerceAtLeast(0)
                        "There are ${formatDuration(remaining)} left."
                    }
                normalized.contains("when does it end") || normalized.contains("end time") ->
                    playerState.durationSeconds.takeIf { it > 0 }?.let { duration ->
                        val remaining = (duration - playerState.positionSeconds).coerceAtLeast(0)
                        "This should end in about ${formatDuration(remaining)}."
                    }
                normalized.contains("passthrough") ->
                    if (playerState.passthroughEnabled) "Passthrough is on." else "Passthrough is off."
                else -> null
            }

        return MediaSkillPlan(
            skillId = MediaSkillId.PLAYBACK_CONTROL,
            validatedInput = normalized,
            taskInstructions = "Task: Answer from current playback state only. Keep it short and factual.",
            directAnswer = directAnswer,
            fallbackText = directAnswer,
            debugInfo = "playback-info",
            shouldSkipModel = directAnswer != null,
        )
    }

    private suspend fun librarySearchPlan(
        question: String,
        onSearchQuery: (suspend (String) -> List<SpatialFinItem>)?,
    ): MediaSkillPlan {
        val searchQuery = extractLibrarySearchQuery(question)
        if (onSearchQuery == null || searchQuery.isBlank()) {
            return MediaSkillPlan(
                skillId = MediaSkillId.LIBRARY_SEARCH,
                validatedInput = searchQuery.ifBlank { question.trim() },
                taskInstructions = "Task: Search the local Jellyfin library and answer with concise results.",
                directAnswer = "I need a search term to look through your library.",
                fallbackText = "I need a search term to look through your library.",
                debugInfo = "missing-search-query",
                shouldSkipModel = true,
            )
        }

        val results = runCatching { onSearchQuery(searchQuery) }.getOrDefault(emptyList()).take(8)
        val answer =
            when {
                results.isEmpty() -> "I couldn't find anything for $searchQuery in your library."
                results.size == 1 -> "I found ${results.first().name} in your library."
                else -> "I found ${results.size} matches for $searchQuery, including ${results.take(3).joinToString(", ") { it.name }}."
            }

        return MediaSkillPlan(
            skillId = MediaSkillId.LIBRARY_SEARCH,
            validatedInput = searchQuery,
            taskInstructions = "Task: Search the local Jellyfin library and answer with concise results.",
            actionableItems = results,
            relatedItemsContext = RecommendationPlanner.buildRelatedItemsContext(results),
            directAnswer = answer,
            fallbackText = answer,
            debugInfo = "library-search results=${results.size}",
            shouldSkipModel = true,
        )
    }

    private fun recapPlan(
        question: String,
        playerState: PlayerStateSnapshot,
        storySoFarContext: String?,
        subtitleContext: String,
    ): MediaSkillPlan {
        val n = normalize(question)
        val isRecent = n.contains("last") || n.contains("just now") || n.contains("minutes") || n.contains("seconds")

        if (isRecent && subtitleContext.isNotBlank()) {
            // Format the subtitle lines into a clean spoken narrative locally.
            // Gemma on this device takes 45+ seconds to respond, so we skip it and
            // produce a well-formatted spoken answer directly.
            val answer = buildRecapNarrative(question, playerState, subtitleContext)
            return MediaSkillPlan(
                skillId = MediaSkillId.RECAP,
                validatedInput = question.trim(),
                taskInstructions = "",
                directAnswer = answer,
                fallbackText = answer,
                debugInfo = "recap-local-narrative",
                shouldSkipModel = true,
            )
        }

        val directFallback =
            if (isRecent) {
                "I don't have subtitle data for this content yet. Try enabling subtitles in the player so I can track what's happening."
            } else {
                storySoFarContext?.takeIf { it.isNotBlank() }
                    ?: playerState.currentOverview.takeIf { it.isNotBlank() }
                    ?: "I don't have enough context yet to recap the story so far."
            }

        val instructions = if (isRecent) {
            "Task: Summarize the events from the recent dialogue. Use the supplied dialogue with timestamps to explain what just happened. Keep it strictly focused on the most recent events."
        } else {
            "Task: Give a short recap of the story so far for this movie or episode. Use the supplied story-so-far context."
        }

        return MediaSkillPlan(
            skillId = MediaSkillId.RECAP,
            validatedInput = question.trim(),
            taskInstructions = instructions,
            fallbackText = directFallback,
            debugInfo = "recap isRecent=$isRecent hasSubtitles=${subtitleContext.isNotBlank()}",
        )
    }

    /**
     * Converts raw timestamped subtitle lines into a concise spoken narrative.
     *
     * Keeps the response short enough for comfortable TTS (under ~300 chars of dialogue)
     * by taking only the most recent lines from the window.
     */
    private fun buildRecapNarrative(
        question: String,
        playerState: PlayerStateSnapshot,
        subtitleContext: String,
    ): String {
        // Build the show/episode identifier.
        val title = buildString {
            if (!playerState.currentSeriesName.isNullOrBlank()) {
                append(playerState.currentSeriesName)
                if (playerState.currentSeasonNumber != null && playerState.currentEpisodeNumber != null) {
                    append(", season ${playerState.currentSeasonNumber} episode ${playerState.currentEpisodeNumber}")
                }
                if (playerState.currentItemTitle.isNotBlank()) {
                    append(", \"${playerState.currentItemTitle}\"")
                }
            } else if (playerState.currentItemTitle.isNotBlank()) {
                append("\"${playerState.currentItemTitle}\"")
            }
        }
        val chapterHint = playerState.currentChapterName
            ?.takeIf { it.isNotBlank() }
            ?.let { " during $it" }
            ?: ""

        // Strip [M:SS] timestamps and deduplicate.
        val allLines = subtitleContext.lines()
            .mapNotNull { line ->
                val cleaned = line.trimStart().removePrefix("[").let { s ->
                    val closeBracket = s.indexOf(']')
                    if (closeBracket > 0) s.substring(closeBracket + 1).trim() else s.trim()
                }
                cleaned.ifBlank { null }
            }
            .distinct()

        if (allLines.isEmpty()) return "I couldn't find any dialogue for that moment."

        // Keep only the most recent lines that fit in a reasonable spoken response.
        // Aim for ~15 lines max — enough to convey what happened without a 3-minute TTS read.
        val lines = allLines.takeLast(15)
        val omittedCount = allLines.size - lines.size

        val intro = buildString {
            if (title.isNotBlank()) {
                append("In $title$chapterHint")
            } else {
                append("Just now")
            }
            if (omittedCount > 0) {
                append(", here are the most recent exchanges")
            } else {
                append(", here's what was said")
            }
            append(": ")
        }

        // Pair short lines together ("A" + "B" → "A. B"), keep long lines solo.
        val body = buildString {
            var i = 0
            while (i < lines.size) {
                val cur = lines[i]
                val next = lines.getOrNull(i + 1)
                if (next != null && cur.length + next.length < 80) {
                    append("$cur. $next")
                    i += 2
                } else {
                    append(cur)
                    i++
                }
                if (i < lines.size) append(". ")
            }
        }

        return "$intro$body"
    }

    private fun dialogueExplainerPlan(
        question: String,
        playerState: PlayerStateSnapshot,
        subtitleContext: String,
        storySoFarContext: String?,
    ): MediaSkillPlan {
        val normalized = normalize(question)
        val isCharacterQuery = normalized.startsWith("who is") || normalized.startsWith("who are") || normalized.startsWith("who was")

        val taskInstructions: String
        val directFallback: String

        if (isCharacterQuery) {
            // Build a cast-aware fallback: use recent dialogue to identify who's on screen,
            // then list the cast so the user has something useful even without the model.
            val castLine = playerState.castNames.takeIf { it.isNotEmpty() }
                ?.let { "Cast: ${it.take(6).joinToString(", ")}." }
            val subtitleLine = subtitleContext.takeIf { it.isNotBlank() }
                ?.let { "Recent dialogue:\n$it" }
            directFallback = listOfNotNull(subtitleLine, castLine)
                .joinToString("\n\n")
                .ifBlank { "I don't have enough context to identify who's on screen." }
            taskInstructions = "Task: Identify which character from the cast is currently on screen. " +
                "Use the recent subtitles to determine who is speaking or being referred to. " +
                "Then describe that character briefly using the story-so-far or episode overview. " +
                "If unclear, list the most likely candidates from the cast."
        } else {
            directFallback =
                subtitleContext.takeIf { it.isNotBlank() }
                    ?: storySoFarContext?.takeIf { it.isNotBlank() }
                    ?: playerState.currentOverview.takeIf { it.isNotBlank() }
                    ?: "I don't have enough recent dialogue context yet to explain that."
            taskInstructions = "Task: Explain the most recent dialogue or action. Use recent subtitles first, then the story-so-far context if needed. Do not invent dialogue that is not in the supplied context."
        }

        return MediaSkillPlan(
            skillId = MediaSkillId.DIALOGUE_EXPLAINER,
            validatedInput = question.trim(),
            taskInstructions = taskInstructions,
            fallbackText = directFallback,
            debugInfo = "subtitle-context=${subtitleContext.isNotBlank()} character-query=$isCharacterQuery",
        )
    }

    private fun metadataQaPlan(
        question: String,
        playerState: PlayerStateSnapshot,
    ): MediaSkillPlan {
        val directAnswer =
            metadataAnswer(question, playerState)
                ?: "I couldn't find that metadata for ${playerState.currentItemTitle.ifBlank { "this title" }}."

        return MediaSkillPlan(
            skillId = MediaSkillId.METADATA_QA,
            validatedInput = question.trim(),
            taskInstructions = "Task: Answer from metadata only. Keep the answer factual and concise.",
            directAnswer = directAnswer,
            fallbackText = directAnswer,
            debugInfo = "metadata-direct",
            shouldSkipModel = true,
        )
    }

    private suspend fun continueWatchingPlan(question: String): MediaSkillPlan {
        val items = runCatching { repository.getResumeItems() }.getOrDefault(emptyList()).take(6)
        val answer =
            when {
                items.isEmpty() -> "You don't have anything in continue watching right now."
                items.size == 1 -> "You can continue with ${items.first().name}."
                else -> "You can continue with ${items.take(3).joinToString(", ") { it.name }}."
            }

        return MediaSkillPlan(
            skillId = MediaSkillId.CONTINUE_WATCHING,
            validatedInput = question.trim(),
            taskInstructions = "Task: Suggest titles from the continue watching row.",
            actionableItems = items,
            relatedItemsContext = RecommendationPlanner.buildRelatedItemsContext(items),
            directAnswer = answer,
            fallbackText = answer,
            debugInfo = "continue-watching results=${items.size}",
            shouldSkipModel = true,
        )
    }

    private suspend fun recommendationPlan(
        skillId: MediaSkillId,
        question: String,
        playerState: PlayerStateSnapshot,
        recommendationContext: RecommendationContext?,
        onGetSuggestions: (suspend () -> List<SpatialFinItem>)?,
    ): MediaSkillPlan {
        val analysis =
            RecommendationPlanner.analyzeQuestion(question, recommendationContext)
                ?: RecommendationPlanner.analyzeQuestion("recommend something for me to watch", recommendationContext)
                ?: return MediaSkillPlan(
                    skillId = skillId,
                    validatedInput = question.trim(),
                    taskInstructions = "Task: Recommend what to watch next.",
                    fallbackText = "I couldn't assemble any recommendations yet.",
                    debugInfo = "recommendation-analysis-missing",
                )

        val sourceWeights = linkedMapOf<java.util.UUID, Int>()
        val candidates = mutableListOf<SpatialFinItem>()
        fun addWeighted(items: List<SpatialFinItem>, weight: Int) {
            items.forEach { item ->
                sourceWeights[item.id] = maxOf(sourceWeights[item.id] ?: Int.MIN_VALUE, weight)
                if (candidates.none { existing -> existing.id == item.id }) {
                    candidates.add(item)
                }
            }
        }

        coroutineScope {
            val suggestions = async { runCatching { repository.getSuggestions() }.getOrNull() }
            val resume = async { runCatching { repository.getResumeItems() }.getOrNull() }
            val favorites = async { runCatching { repository.getFavoriteItems() }.getOrNull() }
            val recentlyAdded =
                async {
                    runCatching {
                        repository.getItems(
                            includeTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES),
                            recursive = true,
                            sortBy = SortBy.DATE_ADDED,
                            sortOrder = SortOrder.DESCENDING,
                            limit = 40,
                        )
                    }.getOrNull()
                }
            val topRated =
                async {
                    runCatching {
                        repository.getItems(
                            includeTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES),
                            recursive = true,
                            sortBy = SortBy.IMDB_RATING,
                            sortOrder = SortOrder.DESCENDING,
                            limit = 40,
                        )
                    }.getOrNull()
                }

            resume.await()?.take(8)?.let { addWeighted(it, 3) }
            favorites.await()?.take(8)?.let { addWeighted(it, 4) }
            suggestions.await()?.take(10)?.let { addWeighted(it, 5) }
            recentlyAdded.await()?.take(20)?.let { addWeighted(it, 2) }
            topRated.await()?.take(20)?.let { addWeighted(it, 2) }
        }

        if (candidates.isEmpty() && onGetSuggestions != null) {
            runCatching { onGetSuggestions() }.getOrNull()?.take(10)?.let { addWeighted(it, 4) }
        }
        recommendationContext?.items?.take(6)?.let { addWeighted(it, 6) }

        val ranked =
            RecommendationPlanner.rankCandidates(
                candidates = candidates,
                sourceWeights = sourceWeights,
                playerState = playerState,
                question = question,
                analysis = analysis,
                previousContext = recommendationContext,
            )
        val fallback =
            when {
                ranked.isEmpty() -> "I couldn't find a strong match in your library yet."
                skillId == MediaSkillId.MOOD_SURPRISE ->
                    "For that mood, try ${ranked.take(3).joinToString(", ") { it.name }}."
                else -> "Based on your library, try ${ranked.take(3).joinToString(", ") { it.name }}."
            }
        val directRecommendationReply =
            RecommendationPlanner.buildRecommendationReply(
                items = ranked,
                analysis = analysis,
            )
        val instructions =
            when (skillId) {
                MediaSkillId.MOOD_SURPRISE ->
                    "Task: Pick 2-4 titles that fit the user's mood or vibe. Prioritize the supplied Jellyfin library items. Reply with only the titles — do not explain why each is a good fit."
                else ->
                    "Task: Recommend what to watch next. Prioritize the supplied Jellyfin library items. Name 2-4 concrete titles when possible. Respect any user filters like shorter, movie only, funny, not anime, newer, or English audio. Reply with only the titles — do not justify or explain the picks."
            }

        return MediaSkillPlan(
            skillId = skillId,
            validatedInput = buildRecommendationValidation(question, analysis),
            taskInstructions = instructions,
            actionableItems = ranked,
            relatedItemsContext = RecommendationPlanner.buildRelatedItemsContext(ranked),
            directAnswer = directRecommendationReply,
            fallbackText = fallback,
            debugInfo = "recommendation candidates=${candidates.size} ranked=${ranked.size} direct=${directRecommendationReply != null}",
            shouldSkipModel = directRecommendationReply != null,
        )
    }

    private suspend fun externalKnowledgePlan(
        question: String,
        playerState: PlayerStateSnapshot,
    ): MediaSkillPlan {
        val target = extractExternalLookupTarget(question, playerState)
        if (target.isBlank()) {
            return MediaSkillPlan(
                skillId = MediaSkillId.EXTERNAL_KNOWLEDGE,
                validatedInput = question.trim(),
                taskInstructions = "Task: Answer from external movie knowledge sources when available.",
                directAnswer = "Tell me which title, actor, or director you want me to look up.",
                fallbackText = "Tell me which title, actor, or director you want me to look up.",
                debugInfo = "missing-external-target",
                shouldSkipModel = true,
            )
        }

        val personMode = isLikelyPersonLookup(question, playerState)
        val wikipedia = wikipediaClient.getSummary(target)

        if (personMode) {
            val wikiExtract = wikipedia?.extract?.let { trimToSentenceBoundary(it, 420) }
            val fallbackText =
                wikiExtract?.let { "Per Wikipedia — $target: $it" }
                    ?: "I couldn't find an external summary for $target."
            val researchNotes = wikiExtract?.let { "Wikipedia — $target: $it" }
            return MediaSkillPlan(
                skillId = MediaSkillId.EXTERNAL_KNOWLEDGE,
                validatedInput = target,
                taskInstructions = externalKnowledgeRewriteInstructions(target),
                directAnswer = fallbackText,
                fallbackText = fallbackText,
                researchNotes = researchNotes,
                debugInfo = buildExternalDebugInfo(target, wikipedia?.canonicalUrl, null, letterboxdUrlFor(target)),
                // Skip the model when there's nothing to rephrase — no sense
                // paying an inference round trip to parrot "I couldn't find...".
                shouldSkipModel = researchNotes == null,
            )
        }

        val tmdbSummary = externalTitleSummary(target, playerState)
        val omdbLine = if (omdbApi.isConfigured()) {
            val movie = omdbApi.searchMovie(target, playerState.productionYear)
            val fallback = movie ?: omdbApi.searchSeries(target, playerState.productionYear)
            fallback?.let { buildOmdbReplyLine(it) }
        } else null
        val isCriticsFocus = isCriticsRatingQuery(normalize(question))

        // [fallbackText] is what we speak if the model isn't available or
        // rephrases to nothing usable — it is the honest concatenated reply the
        // old shouldSkipModel=true path used, preserved verbatim so we never
        // degrade below the pre-rewrite baseline.
        val fallbackText = buildString {
            if (isCriticsFocus && omdbLine != null) {
                append("Per OMDb: $omdbLine")
                tmdbSummary?.let { append(" Per TMDB: ${it.first}") }
                wikipedia?.let { append(" Per Wikipedia: ${trimToSentenceBoundary(it.extract, 180)}") }
            } else {
                val parts = listOfNotNull(
                    tmdbSummary?.let { "Per TMDB: ${it.first}" },
                    wikipedia?.let { "Per Wikipedia: ${trimToSentenceBoundary(it.extract, 220)}" },
                    omdbLine?.let { "Per OMDb: $it" },
                )
                if (parts.isEmpty()) append("I couldn't find external details for $target.")
                else append(parts.joinToString(" "))
            }
        }

        // [researchNotes] is the model's ground-truth cheat sheet. The
        // labelled "TMDB:", "OMDb:", "Wikipedia:" prefixes make source
        // attribution trivial when the rephrase prompt asks the model to
        // cite. Critics-flavored queries surface OMDb first so the model
        // doesn't bury the RT/Metacritic number the user actually asked for.
        val researchNotes = buildString {
            if (isCriticsFocus && omdbLine != null) {
                appendLine("OMDb (ratings): $omdbLine")
                tmdbSummary?.let { appendLine("TMDB: ${it.first}") }
                wikipedia?.let { appendLine("Wikipedia: ${trimToSentenceBoundary(it.extract, 220)}") }
            } else {
                tmdbSummary?.let { appendLine("TMDB: ${it.first}") }
                omdbLine?.let { appendLine("OMDb (ratings): $it") }
                wikipedia?.let { appendLine("Wikipedia: ${trimToSentenceBoundary(it.extract, 260)}") }
            }
        }.trim().ifBlank { null }

        return MediaSkillPlan(
            skillId = MediaSkillId.EXTERNAL_KNOWLEDGE,
            validatedInput = target,
            taskInstructions = externalKnowledgeRewriteInstructions(target),
            directAnswer = fallbackText,
            fallbackText = fallbackText,
            researchNotes = researchNotes,
            debugInfo = buildExternalDebugInfo(
                target = target,
                wikipediaUrl = wikipedia?.canonicalUrl,
                tmdbUrl = tmdbSummary?.second,
                letterboxdUrl = letterboxdUrlFor(target),
                omdbHit = omdbLine != null,
                criticsFocus = isCriticsFocus,
            ),
            // When we have no notes to rephrase, short-circuit to the
            // fallback text — there's nothing for the model to improve.
            shouldSkipModel = researchNotes == null,
        )
    }

    /**
     * Rephrase-oriented task instructions for EXTERNAL_KNOWLEDGE replies. The
     * skill already gathered verified facts and handed them to the model via
     * [MediaSkillPlan.researchNotes]; the model's job is to turn that into one
     * natural spoken paragraph, not to recite it. Explicit "don't invent",
     * "keep numbers exact", and "name the source" guardrails compensate for
     * small on-device models (Gemini Nano, Gemma) tending to either parrot
     * the input verbatim or cheerfully hallucinate around it.
     */
    private fun externalKnowledgeRewriteInstructions(target: String): String =
        "Task: Rephrase the verified facts under \"Research notes\" below into ONE natural spoken reply about $target. " +
            "2-3 sentences, conversational tone, no preamble like \"here's what I found\" or \"based on research\". " +
            "Use ONLY facts present in the notes — do NOT add details from your own memory. " +
            "Keep numeric ratings exact (e.g. Rotten Tomatoes 88%, IMDb 8.0). " +
            "When you cite a fact, name the source inline (OMDb, TMDB, or Wikipedia) so the listener can trust it. " +
            "If the user asked about critics or ratings, lead with the review scores."

    /**
     * Ratings/awards-first OMDb line for spoken replies. Parallel to
     * [ChatToolRegistry.buildOmdbResearchLine] but tuned to read well
     * standalone — there's no outer "OMDb — " label yet at this site.
     * Returns null when OMDb has neither ratings nor awards nor runtime.
     */
    private fun buildOmdbReplyLine(result: OmdbResult): String? {
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
        if (ratings.isNotBlank()) parts.add(ratings)
        if (!awards.isNullOrBlank()) parts.add(awards)
        if (!runtime.isNullOrBlank()) parts.add(runtime)
        return parts.joinToString("; ").takeIf { it.isNotBlank() }
    }

    private fun characterIdentificationPlan(
        question: String,
        playerState: PlayerStateSnapshot,
    ): MediaSkillPlan {
        val castPairs = playerState.castWithCharacters.take(12)
        val titleLine = playerState.currentItemTitle.ifBlank { "the current title" }

        // Fast-fail when there's no cast metadata at all. A multimodal model
        // staring at a frame with no reference names is a worst case: it'll
        // happily describe what it sees ("a man with armor") for 15-20s,
        // which is neither helpful nor an honest answer to "who is this".
        // Skip the model and tell the user directly.
        if (castPairs.isEmpty() && playerState.castNames.isEmpty()) {
            val direct = "I don't have cast info for ${titleLine} yet — try again in a moment, or check the item's metadata on your Jellyfin server."
            return MediaSkillPlan(
                skillId = MediaSkillId.CHARACTER_IDENTIFICATION,
                validatedInput = question.trim(),
                taskInstructions = "",
                directAnswer = direct,
                fallbackText = direct,
                debugInfo = "character-id cast=0 visual=true fast-fail=no-cast",
                shouldSkipModel = true,
            )
        }

        val fallback = when {
            castPairs.isNotEmpty() ->
                "Cast for $titleLine:\n" +
                    castPairs.take(8).joinToString("\n") { (actor, character) -> "  $character ($actor)" }
            playerState.castNames.isNotEmpty() ->
                "Cast: ${playerState.castNames.take(8).joinToString(", ")}."
            else ->
                "I couldn't identify the character — no cast metadata is available."
        }

        val taskInstructions =
            dev.jdtech.jellyfin.player.xr.voice.prompt.characterIdentificationInstructions(
                titleLine = titleLine,
                castPairs = castPairs,
            )

        return MediaSkillPlan(
            skillId = MediaSkillId.CHARACTER_IDENTIFICATION,
            validatedInput = question.trim(),
            taskInstructions = taskInstructions,
            fallbackText = fallback,
            debugInfo = "character-id cast=${castPairs.size} visual=true",
        )
    }

    private suspend fun externalTitleSummary(
        target: String,
        playerState: PlayerStateSnapshot,
    ): Pair<String, String?>? = coroutineScope {
        if (!tmdbApi.isConfigured()) return@coroutineScope null

        val movieResults = async { tmdbApi.searchMovies(target, playerState.productionYear) }
        val tvResults = async { tmdbApi.searchTv(target, playerState.productionYear) }

        val bestMovie = movieResults.await().maxByOrNull { scoreMovieCandidate(it, target, playerState.productionYear) }
        val bestTv = tvResults.await().maxByOrNull { scoreTvCandidate(it, target, playerState.productionYear) }

        val bestIsMovie =
            when {
                bestMovie == null -> false
                bestTv == null -> true
                else -> scoreMovieCandidate(bestMovie, target, playerState.productionYear) >= scoreTvCandidate(bestTv, target, playerState.productionYear)
            }

        if (bestIsMovie && bestMovie != null) {
            val details = tmdbApi.getMovieDetails(bestMovie.id) ?: return@coroutineScope null
            return@coroutineScope buildMovieSummary(details)
        }

        if (bestTv != null) {
            val details = tmdbApi.getTvDetails(bestTv.id) ?: return@coroutineScope null
            return@coroutineScope buildTvSummary(details)
        }

        null
    }

    private fun buildMovieSummary(details: TmdbMovieDetails): Pair<String, String?> {
        val director =
            details.credits?.crew
                ?.firstOrNull { it.job.equals("Director", ignoreCase = true) }
                ?.name
        val genres = details.genres.take(3).joinToString(", ") { it.name }
        val year = details.releaseDate?.take(4).orEmpty()
        val summary =
            buildString {
                append(details.title)
                if (year.isNotBlank()) append(" ($year)")
                if (genres.isNotBlank()) append(" is a $genres title")
                if (details.runtime != null) append(" running about ${details.runtime} minutes")
                if (director != null) append(", directed by $director")
                if (details.voteAverage > 0.0) append(", with a TMDb rating of ${"%.1f".format(Locale.US, details.voteAverage)}")
                append(". ")
                append(trimToSentenceBoundary(details.overview, 280))
            }.trim()
        return summary to "https://www.themoviedb.org/movie/${details.id}"
    }

    private fun buildTvSummary(details: TmdbTvDetails): Pair<String, String?> {
        val creator =
            details.credits?.crew
                ?.firstOrNull { it.job.equals("Director", ignoreCase = true) || it.job.equals("Executive Producer", ignoreCase = true) }
                ?.name
        val genres = details.genres.take(3).joinToString(", ") { it.name }
        val year = details.firstAirDate?.take(4).orEmpty()
        val summary =
            buildString {
                append(details.name)
                if (year.isNotBlank()) append(" ($year)")
                if (genres.isNotBlank()) append(" is a $genres series")
                if (details.numberOfSeasons > 0) append(" with ${details.numberOfSeasons} season${if (details.numberOfSeasons == 1) "" else "s"}")
                if (creator != null) append(", associated with $creator")
                if (details.voteAverage > 0.0) append(", and a TMDb rating of ${"%.1f".format(Locale.US, details.voteAverage)}")
                append(". ")
                append(trimToSentenceBoundary(details.overview, 280))
            }.trim()
        return summary to "https://www.themoviedb.org/tv/${details.id}"
    }

    private fun buildRecommendationValidation(
        question: String,
        analysis: RecommendationAnalysis,
    ): String =
        buildString {
            append(question.trim())
            append(" | filters=")
            append(
                listOfNotNull(
                    analysis.filters.mediaType?.name?.lowercase(),
                    "shorter".takeIf { analysis.filters.preferShorter },
                    "funny".takeIf { analysis.filters.preferFunny },
                    "new".takeIf { analysis.filters.preferNew },
                    "avoidAnime".takeIf { analysis.filters.avoidAnime },
                    "englishAudio".takeIf { analysis.filters.requireEnglishAudio },
                    analysis.filters.maxRuntimeMinutes?.let { "under${it}m" },
                    "comfort".takeIf { analysis.filters.preferComfort },
                    "lateNight".takeIf { analysis.filters.preferLateNight },
                    "surprise".takeIf { analysis.filters.preferSurprise },
                ).joinToString(","),
            )
            analysis.referenceItem?.let { append(" | ref=${it.name}") }
        }

    private fun metadataAnswer(
        question: String,
        playerState: PlayerStateSnapshot,
    ): String? {
        val normalized = normalize(question)
        return when {
            normalized.contains("who directed") || normalized.contains("director") ->
                playerState.directors.takeIf { it.isNotEmpty() }?.let { "Directed by ${it.joinToString(", ")}." }
            normalized.contains("who wrote") || normalized.contains("writer") ->
                playerState.writers.takeIf { it.isNotEmpty() }?.let { "Written by ${it.joinToString(", ")}." }
            normalized.contains("cast") || normalized.contains("who stars") || normalized.contains("actors") ->
                playerState.castNames.takeIf { it.isNotEmpty() }?.let { "Cast: ${it.joinToString(", ")}." }
            normalized.contains("genre") || normalized.contains("what kind of") ->
                playerState.currentGenres.takeIf { it.isNotEmpty() }?.let { "Genres: ${it.joinToString(", ")}." }
            normalized.contains("what year") || normalized.contains("release year") || normalized.contains("when did this come out") ->
                playerState.productionYear?.let { "Released in $it." }
            normalized.contains("rated") || normalized.contains("content rating") || normalized.contains("age rating") ->
                playerState.officialRating?.takeIf { it.isNotBlank() }?.let { "Rated $it." }
            else -> null
        }
    }

    private fun extractLibrarySearchQuery(question: String): String {
        val normalized = question.trim()
        val patterns =
            listOf(
                Regex("(?i)^(find|search for|search|look for|look up|show me|find me)\\s+"),
                Regex("(?i)\\s+in (my )?library$"),
            )
        var query = normalized
        patterns.forEach { regex -> query = query.replace(regex, "") }
        return query.trim().trim('"')
    }

    private fun extractExternalLookupTarget(
        question: String,
        playerState: PlayerStateSnapshot,
    ): String {
        val trimmed = question.trim()
        val normalized = normalize(trimmed)

        if ((normalized.contains("this movie") || normalized.contains("this show") || normalized.contains("this title")) &&
            playerState.currentItemTitle.isNotBlank()
        ) {
            return playerState.currentItemTitle
        }

        val prefixes =
            listOf(
                "tell me about",
                "look up",
                "what is",
                "what's",
                "who is",
                "who was",
                "give me details on",
                "external info on",
            )
        prefixes.forEach { prefix ->
            val regex = Regex("(?i)^$prefix\\s+")
            if (regex.containsMatchIn(trimmed)) {
                return stripLeadingDemonstrative(trimmed.replace(regex, "").trim().trim('"'))
            }
        }

        // Substring variants: users say "what can you tell me about X", "what
        // do you know about X", etc. Catch the two high-traffic phrasings by
        // keeping everything that follows the anchor — same logic, looser
        // leading pattern. [extractAfter] returns null when the phrase is
        // absent so we fall through to the post-prefix fallbacks below.
        listOf(
            "tell me about",
            "tell me more about",
            "know about",
        ).forEach { anchor ->
            extractAfter(trimmed, anchor)?.let { tail ->
                return stripLeadingDemonstrative(tail.trim().trim('"'))
            }
        }

        // Critics-flavored rephrasings: strip the ratings framing and whatever
        // "about / for / of (the)" linker precedes the title. Keeps the
        // EXTERNAL_KNOWLEDGE path from demanding exact "tell me about X"
        // phrasing for ratings questions.
        val criticsPrefixes =
            listOf(
                "what are critics saying about",
                "what are the critics saying about",
                "what do critics say about",
                "what did critics say about",
                "what are the ratings for",
                "what is the rating for",
                "what's the rating for",
                "how good is",
                "how well reviewed is",
                "how well reviewed was",
                "what's the rotten tomatoes score for",
                "rotten tomatoes score for",
                "metacritic score for",
                "imdb rating for",
            )
        criticsPrefixes.forEach { prefix ->
            val regex = Regex("(?i)^$prefix\\s+(?:the\\s+)?")
            if (regex.containsMatchIn(trimmed)) {
                val stripped = trimmed.replace(regex, "").trim().trim('"')
                // Fall back to the currently-playing item when the user said
                // "how good is this" without naming a title.
                if (stripped.equals("this", ignoreCase = true) ||
                    stripped.equals("this movie", ignoreCase = true) ||
                    stripped.equals("this show", ignoreCase = true)
                ) {
                    playerState.currentItemTitle.takeIf { it.isNotBlank() }?.let { return it }
                }
                return stripLeadingDemonstrative(stripped)
            }
        }

        return when {
            normalized.contains("director") && playerState.directors.isNotEmpty() -> playerState.directors.first()
            normalized.contains("actor") && playerState.castNames.isNotEmpty() -> playerState.castNames.first()
            playerState.currentItemTitle.isNotBlank() && normalized in setOf("tell me more", "look this up", "what is this", "what's this") ->
                playerState.currentItemTitle
            else -> trimmed
        }
    }

    /**
     * Case-insensitive: return the substring after the first occurrence of
     * [anchor] followed by at least one space, or null if [anchor] is absent.
     * Used by [extractExternalLookupTarget] for phrasings like "what can you
     * tell me about X" where the relevant tail is whatever follows the
     * "tell me about" anchor.
     */
    private fun extractAfter(source: String, anchor: String): String? {
        val idx = source.indexOf(anchor, ignoreCase = true)
        if (idx < 0) return null
        val tail = source.substring(idx + anchor.length)
        if (!tail.startsWith(' ') && !tail.startsWith('\t')) return null
        return tail.trimStart()
    }

    /**
     * Strip a leading "the movie" / "the film" / "the show" / "the series" /
     * "the book" phrase from an extracted lookup target. "tell me about the
     * movie Blade Runner" otherwise yields the literal target "the movie Blade
     * Runner", which then 404s Wikipedia's direct page-summary endpoint and
     * hides the actual intent behind a search fallback.
     */
    private fun stripLeadingDemonstrative(target: String): String {
        val regex = Regex("(?i)^the\\s+(movie|film|show|series|book|novel|tv\\s+show)\\s+")
        return target.replace(regex, "").trim()
    }

    private fun isLikelyPersonLookup(question: String, playerState: PlayerStateSnapshot): Boolean {
        val normalized = normalize(question)
        return normalized.startsWith("who is") ||
            normalized.startsWith("who was") ||
            normalized.contains("actor") ||
            normalized.contains("director") ||
            playerState.castNames.any { normalized.contains(normalize(it)) } ||
            playerState.directors.any { normalized.contains(normalize(it)) }
    }

    private fun buildExternalDebugInfo(
        target: String,
        wikipediaUrl: String?,
        tmdbUrl: String?,
        letterboxdUrl: String?,
        omdbHit: Boolean = false,
        criticsFocus: Boolean = false,
    ): String =
        buildString {
            append("target=")
            append(target)
            wikipediaUrl?.let { append(" wiki=").append(it) }
            tmdbUrl?.let { append(" tmdb=").append(it) }
            letterboxdUrl?.let { append(" letterboxd=").append(it) }
            if (omdbHit) append(" omdb=hit")
            if (criticsFocus) append(" focus=critics")
        }

    /**
     * True when the question is specifically about critical reception — the
     * phrasing tells us to lead with Rotten Tomatoes / Metacritic / IMDb
     * scores (OMDb data) rather than TMDB/Wikipedia prose. Keep permissive:
     * false negatives fall through to the normal EXTERNAL_KNOWLEDGE layout,
     * so a missed keyword just means the user hears a less-focused reply.
     */
    private fun isCriticsRatingQuery(normalized: String): Boolean =
        normalized.contains("critic") ||
            normalized.contains("rotten tomatoes") ||
            normalized.contains("metacritic") ||
            normalized.contains("how good is") ||
            normalized.contains("how well reviewed") ||
            normalized.contains("imdb") ||
            Regex("\\brating\\b").containsMatchIn(normalized) ||
            Regex("\\brated\\b").containsMatchIn(normalized) ||
            Regex("\\breviews?\\b").containsMatchIn(normalized)

    private fun letterboxdUrlFor(target: String): String =
        "https://letterboxd.com/search/${encodeForUrl(target)}/"

    private fun scoreMovieCandidate(
        result: TmdbMovieResult,
        target: String,
        year: Int?,
    ): Double {
        var score = titleSimilarity(target, result.title)
        score += if (year != null && result.releaseDate?.startsWith(year.toString()) == true) 2.0 else 0.0
        score += result.voteAverage / 10.0
        return score
    }

    private fun scoreTvCandidate(
        result: TmdbTvResult,
        target: String,
        year: Int?,
    ): Double {
        var score = titleSimilarity(target, result.name)
        score += if (year != null && result.firstAirDate?.startsWith(year.toString()) == true) 2.0 else 0.0
        score += result.voteAverage / 10.0
        return score
    }

    private fun titleSimilarity(left: String, right: String): Double {
        val normalizedLeft = normalize(left)
        val normalizedRight = normalize(right)
        if (normalizedLeft == normalizedRight) return 10.0
        val overlap = normalizedLeft.split(' ').toSet().intersect(normalizedRight.split(' ').toSet()).size
        return overlap.toDouble() - (abs(normalizedLeft.length - normalizedRight.length) / 20.0)
    }

    private fun isPlaybackInfoQuery(normalized: String): Boolean =
        normalized.contains("what am i watching") ||
            normalized.contains("what is this") ||
            normalized.contains("time left") ||
            normalized.contains("remaining") ||
            normalized.contains("when does it end") ||
            normalized.contains("end time") ||
            normalized.contains("passthrough")

    private fun isContinueWatchingQuery(normalized: String): Boolean =
        normalized.contains("continue watching") ||
            normalized.contains("resume watching") ||
            normalized.contains("what was i watching") ||
            normalized.contains("pick up where i left off")

    private fun isLibrarySearchQuery(normalized: String): Boolean =
        listOf("find ", "search ", "search for ", "look for ", "show me ", "find me ").any(normalized::startsWith) ||
            normalized.contains("in my library")

    private fun isTimeBasedSummaryQuery(normalized: String): Boolean =
        (normalized.contains("last minute") ||
            normalized.contains("last few minutes") ||
            Regex("last\\s+\\d+\\s*minutes?").containsMatchIn(normalized) ||
            Regex("last\\s+\\d+\\s*seconds?").containsMatchIn(normalized)) ||
            (normalized.contains("summarize") &&
                (normalized.contains("minute") || normalized.contains("second") ||
                    normalized.contains("scene") || normalized.contains("just now")))

    // A "who is …" query that refers to a character generically on screen (no named target).
    // These should use subtitle/cast context, not an external Wikipedia lookup.
    private fun isGenericCharacterQuery(normalized: String): Boolean {
        val afterWho = normalized.removePrefix("who is ").removePrefix("who was ").trim()
        return afterWho in setOf(
            "this", "that", "him", "her", "he", "she", "they", "this character",
            "this person", "this actor", "this actress", "the character",
            "this guy", "this man", "this woman", "this girl", "this boy",
        ) || afterWho.startsWith("this ") || afterWho.startsWith("the ")
    }

    private fun isDialogueExplainerQuery(normalized: String): Boolean =
        normalized.contains("what did they say") ||
            normalized.contains("what are they saying") ||
            normalized.contains("what did i miss") ||
            normalized.contains("what just happened") ||
            ((normalized.startsWith("who is") || normalized.startsWith("who are") || normalized.startsWith("who was")) &&
                isGenericCharacterQuery(normalized))

    private fun isRecapQuery(normalized: String): Boolean =
        isTimeBasedSummaryQuery(normalized) ||
            (normalized.contains("story so far") ||
                normalized.contains("recap") ||
                normalized.contains("summarize") ||
                normalized.contains("summary"))

    private fun isMetadataQuery(normalized: String): Boolean =
        normalized.contains("who directed") ||
            normalized.contains("director") ||
            normalized.contains("who wrote") ||
            normalized.contains("writer") ||
            normalized.contains("cast") ||
            normalized.contains("who stars") ||
            normalized.contains("actors") ||
            normalized.contains("genre") ||
            normalized.contains("what year") ||
            normalized.contains("release year") ||
            normalized.contains("rated")

    private fun isMoodSurpriseQuery(normalized: String): Boolean =
        normalized.contains("surprise me") ||
            normalized.contains("comfort show") ||
            normalized.contains("comfort movie") ||
            normalized.contains("late night") ||
            normalized.contains("under 90") ||
            normalized.contains("under 60") ||
            normalized.contains("feel like")

    private fun isExternalKnowledgeQuery(
        normalized: String,
        playerState: PlayerStateSnapshot,
    ): Boolean {
        // Phrasing variants users actually say in practice, captured from
        // voice_telemetry after watching real sessions:
        //   "tell me about X"                  ← original strict form
        //   "what can you tell me about X"     ← extremely common
        //   "can you tell me about X"
        //   "what do you know about X"
        //   "what's X about" / "what is X about"
        // Matching any of these under a single `contains` covers the lot
        // without turning isExternalKnowledgeQuery into a regex zoo.
        val tellMeAboutPhrases = listOf(
            "tell me about",
            "know about",
            "tell me more about",
        )
        val whatIsItAboutPhrases = listOf(
            "what is it about",
            "what's it about",
            "what is this about",
            "what's this about",
        )
        return tellMeAboutPhrases.any { normalized.contains(it) } ||
            whatIsItAboutPhrases.any { normalized.contains(it) } ||
            normalized.startsWith("look up") ||
            normalized.contains("wikipedia") ||
            normalized.contains("tmdb") ||
            normalized.contains("letterboxd") ||
            isCriticsRatingQuery(normalized) ||
            (normalized.contains("external") && (normalized.contains("title") || normalized.contains("actor") || normalized.contains("director"))) ||
            ((normalized.startsWith("who is") || normalized.startsWith("who was")) &&
                !isGenericCharacterQuery(normalized) &&
                (playerState.castNames.isNotEmpty() || playerState.directors.isNotEmpty() || playerState.currentItemTitle.isNotBlank()))
    }

    private fun normalize(input: String): String =
        input.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun formatDuration(totalSeconds: Long): String {
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return buildList {
            if (hours > 0) add("$hours hour${if (hours == 1L) "" else "s"}")
            if (minutes > 0) add("$minutes minute${if (minutes == 1L) "" else "s"}")
            if (hours == 0L && minutes == 0L) add("$seconds second${if (seconds == 1L) "" else "s"}")
        }.joinToString(" and ")
    }

    private fun encodeForUrl(value: String): String = URLEncoder.encode(value, "UTF-8")
}
