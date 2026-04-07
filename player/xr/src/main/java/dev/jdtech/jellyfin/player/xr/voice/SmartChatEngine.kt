package dev.jdtech.jellyfin.player.xr.voice

import android.graphics.Bitmap
import dev.jdtech.jellyfin.core.llm.LlmChatModelHelper
import dev.jdtech.jellyfin.core.llm.LlmModelInstance
import dev.jdtech.jellyfin.core.llm.LlmModelManager
import dev.jdtech.jellyfin.models.SpatialFinItem
import dev.jdtech.jellyfin.player.session.voice.PlayerStateSnapshot
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

private val RECOMMENDATION_KEYWORDS = listOf(
    "similar", "recommend", "suggestion", "what else", "what should i watch",
    "watch after", "watch next", "anything else", "like this", "other movies",
    "other shows", "what other", "something similar", "more like",
)

private fun isRecommendationQuery(question: String): Boolean {
    val q = question.lowercase()
    return RECOMMENDATION_KEYWORDS.any { q.contains(it) }
}

data class AssistantPreferences(
    val verbosity: String = "balanced",
    val spoilerPolicy: String = "cautious",
    val spokenRepliesEnabled: Boolean = true,
)

data class AssistantReply(
    val text: String?,
    val strategy: String,
    val debugInfo: String,
)

class SmartChatEngine(
    private val geminiNanoService: GeminiNanoService,
    private val geminiCloudService: GeminiCloudService,
    private val appPreferences: AppPreferences,
    private val modelManager: LlmModelManager,
) {
    private val llmInstance: LlmModelInstance?
        get() = modelManager.instance

    suspend fun initialize() = withContext(Dispatchers.IO) {
        if (shouldUseGemma()) {
            modelManager.ensureInitialized()
            return@withContext
        }

        if (shouldAttemptGemini()) {
            geminiNanoService.initialize()
        }
    }

    suspend fun query(
        question: String,
        playerState: PlayerStateSnapshot,
        storySoFarContext: String? = null,
        recentSubtitleLines: List<Pair<Long, String>> = emptyList(),
        currentPositionMs: Long = Long.MAX_VALUE,
        assistantPreferences: AssistantPreferences = AssistantPreferences(),
        onSearchQuery: (suspend (String) -> List<SpatialFinItem>)? = null,
        conversationHistory: List<Pair<String, String>> = emptyList(),
        onGetSuggestions: (suspend () -> List<SpatialFinItem>)? = null,
        visualContext: Bitmap? = null,
    ): AssistantReply = withContext(Dispatchers.IO) {
        // Fast-path: answer unambiguous factual lookups without any model call
        confidenceGatedAnswer(question, playerState)?.let { answer ->
            return@withContext AssistantReply(text = answer, strategy = "CONFIDENCE_GATE", debugInfo = "fast-path")
        }

        val relatedItemsContext: String? = if (isRecommendationQuery(question) && onGetSuggestions != null) {
            runCatching { onGetSuggestions() }
                .onFailure { Timber.w(it, "GEMINI: failed to fetch suggestions") }
                .getOrNull()
                ?.take(6)
                ?.joinToString("\n") { "- ${it.name}: ${it.overview.take(120)}" }
                ?.takeIf { it.isNotBlank() }
        } else null

        val subtitleContext = buildSubtitleContext(question, recentSubtitleLines, currentPositionMs)

        val prompt =
            buildPrompt(
                question = question,
                playerState = playerState,
                storySoFarContext = storySoFarContext,
                subtitleContext = subtitleContext,
                assistantPreferences = assistantPreferences,
                conversationHistory = conversationHistory,
                relatedItemsContext = relatedItemsContext,
            )

        val gemmaEnabled = shouldUseGemma()

        if (gemmaEnabled) {
            modelManager.ensureInitialized()
        }

        if (gemmaEnabled && llmInstance != null) {
            try {
                val responseText = suspendCoroutine<String> { continuation ->
                    val sb = StringBuilder()
                    val images = if (visualContext != null) listOf(visualContext) else emptyList()
                    LlmChatModelHelper.runInference(llmInstance!!, prompt, images) { text, isDone ->
                        if (isDone) {
                            continuation.resume(sb.toString())
                        } else {
                            sb.append(text)
                        }
                    }
                }
                if (responseText.isNotBlank()) {
                    val prefixToRemove = "Model: "
                    val cleanedResponse = if (responseText.startsWith(prefixToRemove)) {
                        responseText.substring(prefixToRemove.length)
                    } else {
                        responseText
                    }
                    return@withContext AssistantReply(
                        text = cleanedResponse.trim(),
                        strategy = "GEMMA_LITERT",
                        debugInfo = "LiteRT LM Engine"
                    )
                }
            } catch (e: Exception) {
                Timber.e(e, "LiteRT inference failed")
            }
        }

        if (gemmaEnabled) {
            return@withContext AssistantReply(
                text = heuristicAnswer(question, playerState, storySoFarContext, subtitleContext),
                strategy = "HEURISTIC",
                debugInfo = "Gemma enabled; Gemini disabled; using heuristic fallback",
            )
        }

        if (!shouldAttemptGemini()) {
            return@withContext AssistantReply(
                text = heuristicAnswer(question, playerState, storySoFarContext, subtitleContext),
                strategy = "HEURISTIC",
                debugInfo = "Gemini disabled: cloud API key missing",
            )
        }

        val result = geminiNanoService.generateText(prompt = prompt, reason = "chat")
        if (result.usedModel && !result.text.isNullOrBlank()) {
            return@withContext AssistantReply(
                text = result.text.trim(),
                strategy = "MODEL",
                debugInfo = result.status.details,
            )
        }

        val cloudResult =
            geminiCloudService.generateText(
                prompt = prompt,
                reason = "chat",
                temperature = 0.3,
                maxOutputTokens = 320,
            )
        if (cloudResult.status.usedModel && !cloudResult.text.isNullOrBlank()) {
            return@withContext AssistantReply(
                text = cloudResult.text.trim(),
                strategy = "CLOUD",
                debugInfo = cloudResult.status.details,
            )
        }

        Timber.d("GEMINI: chat fallback to heuristic answer details=%s", result.status.details)
        AssistantReply(
            text = heuristicAnswer(question, playerState, storySoFarContext, subtitleContext),
            strategy = "HEURISTIC",
            debugInfo = "${result.status.details}; ${cloudResult.status.details}",
        )
    }

    fun destroy() {
        geminiNanoService.destroy()
        // LlmModelManager owns the engine lifecycle; do not close it here.
    }

    private fun shouldUseGemma(): Boolean =
        appPreferences.getValue(appPreferences.voiceAssistantGemmaEnabled)

    private fun hasGeminiApiKey(): Boolean =
        !appPreferences.getValue(appPreferences.voiceAssistantCloudApiKey).orEmpty().trim().isBlank()

    private fun shouldAttemptGemini(): Boolean = hasGeminiApiKey() && !shouldUseGemma()

    private fun buildSubtitleContext(
        question: String,
        lines: List<Pair<Long, String>>,
        currentPositionMs: Long,
    ): String {
        if (lines.isEmpty()) return ""
        val normalized = question.lowercase()

        // Extract an optional "N minutes ago" offset from the question
        val minutesAgoMatch = Regex("(\\d+)\\s*minutes?\\s*ago").find(normalized)
        val targetWindowMs: LongRange = when {
            minutesAgoMatch != null -> {
                val n = minutesAgoMatch.groupValues[1].toLongOrNull() ?: 2L
                val center = currentPositionMs - n * 60_000L
                (center - 30_000L)..(center + 30_000L)
            }
            normalized.contains("just happened") || normalized.contains("right now") || normalized.contains("what did they just") -> {
                (currentPositionMs - 30_000L)..currentPositionMs
            }
            else -> (currentPositionMs - 120_000L)..currentPositionMs
        }

        val relevant = lines.filter { (ts, _) -> ts in targetWindowMs }
            .takeLast(40)
            .joinToString("\n") { (ts, line) ->
                val totalSec = ts / 1000L
                val m = totalSec / 60
                val s = totalSec % 60
                "[%d:%02d] %s".format(m, s, line)
            }
        return relevant
    }

    private fun confidenceGatedAnswer(question: String, playerState: PlayerStateSnapshot): String? {
        val n = question.lowercase().replace(Regex("[^a-z0-9\\s]"), " ").trim()

        if (n.contains("who directed") || (n.contains("director") && !n.contains("style") && !n.contains("vision"))) {
            return if (playerState.directors.isNotEmpty()) {
                "Directed by ${playerState.directors.joinToString(", ")}."
            } else null
        }

        if (n.contains("who wrote") || n.contains("written by") || (n.contains("writer") && !n.contains("type writer"))) {
            return if (playerState.writers.isNotEmpty()) {
                "Written by ${playerState.writers.joinToString(", ")}."
            } else null
        }

        if (n.contains("what year") || n.contains("when was it made") || n.contains("year released") || n.contains("production year") || (n.contains("release") && n.contains("year"))) {
            return playerState.productionYear?.let { "Released in $it." }
        }

        if ((n.contains("age rating") || n.contains("content rating") || n.contains("rated")) && !n.contains("how") && !n.contains("why")) {
            val rating = playerState.officialRating
            return if (!rating.isNullOrBlank()) "Rated $rating." else null
        }

        if (n.matches(Regex(".*\\bwhat (is|am) (i|this|it)\\b.*")) || (n.contains("title") && n.contains("what"))) {
            return playerState.currentItemTitle.takeIf { it.isNotBlank() }?.let { "You're watching $it." }
        }

        return null
    }

    private fun buildPrompt(
        question: String,
        playerState: PlayerStateSnapshot,
        storySoFarContext: String?,
        subtitleContext: String,
        assistantPreferences: AssistantPreferences,
        conversationHistory: List<Pair<String, String>>,
        relatedItemsContext: String?,
    ): String {
        val historyBlock = if (conversationHistory.isNotEmpty()) {
            "\nConversation history:\n" + conversationHistory.joinToString("\n") { (u, a) ->
                "User: $u\nAssistant: $a"
            } + "\n"
        } else ""

        val relatedBlock = if (relatedItemsContext != null) {
            "\nItems available in library that may be relevant:\n$relatedItemsContext\n"
        } else ""

        return """
            You are SpatialFin, an on-device XR media assistant.
            Answer briefly and directly.
            Respect the spoiler policy: ${assistantPreferences.spoilerPolicy}.
            Verbosity: ${assistantPreferences.verbosity}.
            If the answer is uncertain, say so plainly.
            Do not invent details not present in the supplied context.

            Current title: ${playerState.currentItemTitle}
            Series: ${playerState.currentSeriesName ?: ""}
            Season: ${playerState.currentSeasonNumber ?: ""}
            Episode: ${playerState.currentEpisodeNumber ?: ""}
            Year: ${playerState.productionYear ?: ""}
            Rating: ${playerState.officialRating ?: ""}
            Overview: ${playerState.currentOverview.take(1000)}
            Genres: ${playerState.currentGenres.joinToString(", ")}
            Community ratings: ${playerState.currentRatings.joinToString(", ")}
            Cast: ${playerState.castNames.take(12).joinToString(", ")}
            Directors: ${playerState.directors.joinToString(", ")}
            Writers: ${playerState.writers.joinToString(", ")}
            Current chapter: ${playerState.currentChapterName ?: ""}
            Story so far: ${(storySoFarContext ?: "").take(1200)}
            Recent subtitles: ${subtitleContext.takeLast(1200)}
            Audio track: ${playerState.currentAudioTrack ?: ""}
            Subtitle track: ${playerState.currentSubtitleTrack ?: ""}
            $historyBlock$relatedBlock
            User question:
            $question
        """.trimIndent()
    }

    private fun heuristicAnswer(
        question: String,
        playerState: PlayerStateSnapshot,
        storySoFarContext: String?,
        subtitleContext: String,
    ): String? {
        val normalized =
            question.lowercase()
                .replace(Regex("[^a-z0-9\\s]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
        if (normalized.isBlank()) return null

        if (
            normalized.contains("plot") ||
                normalized.contains("movie about") ||
                normalized.contains("show about") ||
                normalized.contains("summary") ||
                normalized.contains("summarize")
        ) {
            return playerState.currentOverview
                .takeIf { it.isNotBlank() }
                ?: storySoFarContext?.takeIf { it.isNotBlank() }
                ?: "I don't have a plot summary for ${playerState.currentItemTitle.ifBlank { "this title" }}."
        }

        if (normalized.contains("story so far") || normalized.contains("recap") || (normalized.contains("previous episode") && (normalized.contains("about") || normalized.contains("happened")))) {
            return storySoFarContext?.takeIf { it.isNotBlank() }
                ?: playerState.currentOverview.takeIf { it.isNotBlank() }
                ?: "I don't have enough context yet to recap what happened in the previous episode."
        }

        if (normalized.contains("director") || normalized.contains("who directed")) {
            return if (playerState.directors.isNotEmpty()) {
                "Directed by ${playerState.directors.joinToString(", ")}."
            } else {
                "I couldn't find director metadata for ${playerState.currentItemTitle.ifBlank { "this title" }}."
            }
        }

        if (normalized.contains("writer") || normalized.contains("who wrote")) {
            return if (playerState.writers.isNotEmpty()) {
                "Written by ${playerState.writers.joinToString(", ")}."
            } else {
                "I couldn't find writer metadata for ${playerState.currentItemTitle.ifBlank { "this title" }}."
            }
        }

        if (
            normalized.contains("cast") ||
                normalized.contains("who stars") ||
                normalized.contains("who is in this") ||
                normalized.contains("actors")
        ) {
            return if (playerState.castNames.isNotEmpty()) {
                "Cast: ${playerState.castNames.take(5).joinToString(", ")}."
            } else {
                "I couldn't find cast metadata for ${playerState.currentItemTitle.ifBlank { "this title" }}."
            }
        }

        if (normalized.contains("genre") || normalized.contains("what kind of")) {
            return playerState.currentGenres
                .takeIf { it.isNotEmpty() }
                ?.let { "Genres: ${it.take(4).joinToString(", ")}." }
                ?: "I couldn't find genre metadata for ${playerState.currentItemTitle.ifBlank { "this title" }}."
        }

        if (normalized.contains("age rating") || normalized.contains("content rating") || normalized.contains("how old")) {
            return playerState.officialRating?.let { "Rated $it." }
                ?: "I don't have a content rating for ${playerState.currentItemTitle.ifBlank { "this title" }}."
        }

        if (normalized.contains("rating") || normalized.contains("score") || normalized.contains("is it good") || normalized.contains("reviews")) {
            return playerState.currentRatings
                .takeIf { it.isNotEmpty() }
                ?.let { "Ratings for ${playerState.currentItemTitle}: ${it.joinToString(", ")}." }
                ?: "I don't have rating information for ${playerState.currentItemTitle.ifBlank { "this title" }}."
        }

        if (normalized.contains("what year") || normalized.contains("year released") || (normalized.contains("release") && normalized.contains("year"))) {
            return playerState.productionYear?.let { "Released in $it." }
                ?: "I don't have the release year for ${playerState.currentItemTitle.ifBlank { "this title" }}."
        }

        if (normalized.contains("title") || normalized.contains("what am i watching")) {
            return playerState.currentItemTitle.takeIf { it.isNotBlank() } ?: "I don't know the current title."
        }

        if (normalized.contains("what happened") || normalized.contains("what did they say")) {
            return subtitleContext.takeIf { it.isNotBlank() }
                ?.let { "Based on the recent dialogue:\n$it" }
                ?: storySoFarContext?.takeIf { it.isNotBlank() }
                ?: "I don't have enough recent dialogue context to answer that."
        }

        return if (playerState.currentOverview.isNotBlank()) {
            playerState.currentOverview
        } else {
            "I don't have enough metadata to answer that right now."
        }
    }
}
