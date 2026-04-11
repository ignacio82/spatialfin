package dev.jdtech.jellyfin.player.xr.voice

import android.graphics.Bitmap
import dev.jdtech.jellyfin.core.llm.LlmChatModelHelper
import dev.jdtech.jellyfin.core.llm.LlmInferenceProfile
import dev.jdtech.jellyfin.core.llm.LlmModelInstance
import dev.jdtech.jellyfin.core.llm.LlmModelManager
import dev.jdtech.jellyfin.models.SpatialFinItem
import dev.jdtech.jellyfin.player.session.voice.PlayerStateSnapshot
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeoutException
import timber.log.Timber

data class AssistantPreferences(
    val verbosity: String = "balanced",
    val spoilerPolicy: String = "cautious",
    val spokenRepliesEnabled: Boolean = true,
)

data class AssistantReply(
    val text: String?,
    val strategy: String,
    val debugInfo: String,
    val recommendedItems: List<SpatialFinItem> = emptyList(),
    val selectedSkill: String = MediaSkillId.GENERAL_CHAT.name,
    val validatedInput: String = "",
    val resultDisposition: String = "MODEL",
)

class SmartChatEngine(
    private val geminiNanoService: GeminiNanoService,
    private val geminiCloudService: GeminiCloudService,
    private val appPreferences: AppPreferences,
    val modelManager: LlmModelManager,
    private val repository: JellyfinRepository,
) {
    private val llmInstance: LlmModelInstance?
        get() = modelManager.instance
    private val mediaSkillRegistry = MediaSkillRegistry(repository, appPreferences)

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
        recommendationContext: RecommendationContext? = null,
        visualContexts: List<Bitmap> = emptyList(),
        lastPointerPosition: androidx.compose.ui.geometry.Offset? = null,
        /** Called on the IO thread with accumulating text as each token arrives. */
        onTokenStream: ((String) -> Unit)? = null,
    ): AssistantReply = withContext(Dispatchers.IO) {
        val subtitleContext = buildSubtitleContext(question, recentSubtitleLines, currentPositionMs)
        val plan =
            mediaSkillRegistry.plan(
                question = question,
                playerState = playerState,
                storySoFarContext = storySoFarContext,
                subtitleContext = subtitleContext,
                recommendationContext = recommendationContext,
                onSearchQuery = onSearchQuery,
                onGetSuggestions = onGetSuggestions,
                hasVisualContext = visualContexts.isNotEmpty(),
            )
        val recommendedItems = plan.actionableItems
        val finalizeReply: (String?) -> String? = { text ->
            finalizeRecommendationReply(
                text = text,
                skillId = plan.skillId,
                recommendedItems = recommendedItems,
            )
        }

        if (plan.directAnswer != null && plan.shouldSkipModel) {
            return@withContext AssistantReply(
                text = finalizeReply(plan.directAnswer),
                strategy = "SKILL_DIRECT",
                debugInfo = plan.debugInfo,
                recommendedItems = recommendedItems,
                selectedSkill = plan.skillId.name,
                validatedInput = plan.validatedInput,
                resultDisposition = "DIRECT",
            )
        }

        val fallbackText =
            plan.fallbackText
                ?: fallbackAnswer(
                    question = question,
                    playerState = playerState,
                    storySoFarContext = storySoFarContext,
                    subtitleContext = subtitleContext,
                    recommendedItems = recommendedItems,
                )

        val prompt = if (plan.skillId == MediaSkillId.CHARACTER_IDENTIFICATION) {
            buildCharacterIdentificationPrompt(playerState, plan.taskInstructions)
        } else {
            buildPrompt(
                question = question,
                playerState = playerState,
                storySoFarContext = storySoFarContext,
                subtitleContext = subtitleContext,
                assistantPreferences = assistantPreferences,
                conversationHistory = conversationHistory,
                relatedItemsContext = plan.relatedItemsContext,
                taskInstructions = plan.taskInstructions,
                lastPointerPosition = lastPointerPosition,
            )
        }

        val gemmaEnabled = shouldUseGemma()
        val cpuAllowed = appPreferences.getValue(appPreferences.voiceAssistantCpuInference) ?: false

        if (gemmaEnabled) {
            modelManager.ensureInitialized()
        }

        if (gemmaEnabled && llmInstance != null) {
            if (llmInstance!!.backendName == "CPU" && !cpuAllowed) {
                Timber.w("LiteRT: skipping CPU inference because it is not explicitly enabled in settings")
            } else {
                try {
                    val currentInstance = llmInstance!!
                    val responseText = withTimeoutOrNull(10_000L) {
                        LlmChatModelHelper.runInference(
                            instance = currentInstance,
                            prompt = prompt,
                            images = visualContexts,
                            profile = LlmInferenceProfile.CHAT,
                            onToken = { partial -> 
                                onTokenStream?.invoke(partial)
                            },
                        )
                    } ?: throw TimeoutException("Inference timed out")
                    if (responseText.isNotBlank()) {
                        val prefixToRemove = "Model: "
                        val cleanedResponse = if (responseText.startsWith(prefixToRemove)) {
                            responseText.substring(prefixToRemove.length)
                        } else {
                            responseText
                        }
                        val finalText =
                            cleanedResponse.trim()
                                .ifBlank { fallbackText ?: "" }
                                .takeIf { it.isNotBlank() }
                        return@withContext AssistantReply(
                            text = finalizeReply(finalText),
                            strategy = "GEMMA_LITERT",
                            debugInfo = plan.debugInfo.ifBlank { "LiteRT LM Engine" },
                            recommendedItems = recommendedItems,
                            selectedSkill = plan.skillId.name,
                            validatedInput = plan.validatedInput,
                            resultDisposition = "MODEL",
                        )
                    }
                } catch (e: Exception) {
                    Timber.e(e, "LiteRT inference failed")
                }
            }
        }

        if (gemmaEnabled) {
            return@withContext AssistantReply(
                text = finalizeReply(fallbackText),
                strategy = "HEURISTIC",
                debugInfo = "${plan.debugInfo}; Gemma enabled; Gemini disabled; using heuristic fallback",
                recommendedItems = recommendedItems,
                selectedSkill = plan.skillId.name,
                validatedInput = plan.validatedInput,
                resultDisposition = "FALLBACK",
            )
        }

        if (!shouldAttemptGemini()) {
            return@withContext AssistantReply(
                text = finalizeReply(fallbackText),
                strategy = "HEURISTIC",
                debugInfo = "${plan.debugInfo}; Gemini disabled: cloud API key missing",
                recommendedItems = recommendedItems,
                selectedSkill = plan.skillId.name,
                validatedInput = plan.validatedInput,
                resultDisposition = "FALLBACK",
            )
        }

        val result = geminiNanoService.generateText(prompt = prompt, reason = "chat")
        if (result.usedModel && !result.text.isNullOrBlank()) {
            val finalText =
                result.text.trim()
                    .ifBlank { fallbackText ?: "" }
                    .takeIf { it.isNotBlank() }
            return@withContext AssistantReply(
                text = finalizeReply(finalText),
                strategy = "MODEL",
                debugInfo = "${plan.debugInfo}; ${result.status.details}",
                recommendedItems = recommendedItems,
                selectedSkill = plan.skillId.name,
                validatedInput = plan.validatedInput,
                resultDisposition = "MODEL",
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
            val finalText =
                cloudResult.text.trim()
                    .ifBlank { fallbackText ?: "" }
                    .takeIf { it.isNotBlank() }
            return@withContext AssistantReply(
                text = finalizeReply(finalText),
                strategy = "CLOUD",
                debugInfo = "${plan.debugInfo}; ${cloudResult.status.details}",
                recommendedItems = recommendedItems,
                selectedSkill = plan.skillId.name,
                validatedInput = plan.validatedInput,
                resultDisposition = "MODEL",
            )
        }

        Timber.d("GEMINI: chat fallback to heuristic answer details=%s", result.status.details)
        AssistantReply(
            text = finalizeReply(fallbackText),
            strategy = "HEURISTIC",
            debugInfo = "${plan.debugInfo}; ${result.status.details}; ${cloudResult.status.details}",
            recommendedItems = recommendedItems,
            selectedSkill = plan.skillId.name,
            validatedInput = plan.validatedInput,
            resultDisposition = "FALLBACK",
        )
    }

    fun destroy() {
        geminiNanoService.destroy()
        // LlmModelManager owns the engine lifecycle; do not close it here.
    }

    fun shouldUseGemma(): Boolean =
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

        // Extract time window from the question.
        val minutesAgoMatch = Regex("(\\d+)\\s*minutes?\\s*ago").find(normalized)
        val lastNMinutesMatch = Regex("last\\s+(\\d+)\\s*minutes?").find(normalized)
        val lastNSecondsMatch = Regex("last\\s+(\\d+)\\s*seconds?").find(normalized)
        val lastMinuteMatch = if (lastNMinutesMatch == null) Regex("\\blast\\s+minute\\b").find(normalized) else null
        val targetWindowMs: LongRange = when {
            minutesAgoMatch != null -> {
                val n = minutesAgoMatch.groupValues[1].toLongOrNull() ?: 2L
                val center = currentPositionMs - n * 60_000L
                (center - 60_000L)..(center + 60_000L)
            }
            lastNMinutesMatch != null -> {
                val n = (lastNMinutesMatch.groupValues[1].toLongOrNull() ?: 2L).coerceAtMost(20L)
                (currentPositionMs - n * 60_000L)..currentPositionMs
            }
            lastMinuteMatch != null -> {
                (currentPositionMs - 60_000L)..currentPositionMs
            }
            lastNSecondsMatch != null -> {
                val n = (lastNSecondsMatch.groupValues[1].toLongOrNull() ?: 30L).coerceAtMost(300L)
                (currentPositionMs - n * 1_000L)..currentPositionMs
            }
            normalized.contains("just happened") || normalized.contains("right now") || normalized.contains("what did they just") ||
                normalized.contains("what happened") || normalized.contains("summarize") -> {
                (currentPositionMs - 180_000L)..currentPositionMs
            }
            else -> (currentPositionMs - 120_000L)..currentPositionMs
        }

        val relevant = lines.filter { (ts, _) -> ts in targetWindowMs }
            .takeLast(100)
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

    private fun buildCharacterIdentificationPrompt(
        playerState: PlayerStateSnapshot,
        taskInstructions: String,
    ): String {
        val castBlock = playerState.castWithCharacters.take(12)
            .joinToString("\n") { (actor, character) -> "Character: $character — Actor: $actor" }
            .ifBlank { playerState.castNames.take(8).joinToString("\n") { "Actor: $it" } }

        return buildString {
            appendLine("You are SpatialFin, an on-device XR assistant identifying characters in a video frame.")
            appendLine(taskInstructions)
            if (castBlock.isNotBlank()) {
                appendLine()
                appendLine("Reference cast:")
                appendLine(castBlock)
            }
            appendLine()
            append("Analyze the video frame above and respond.")
        }
    }

    private fun buildPrompt(
        question: String,
        playerState: PlayerStateSnapshot,
        storySoFarContext: String?,
        subtitleContext: String,
        assistantPreferences: AssistantPreferences,
        conversationHistory: List<Pair<String, String>>,
        relatedItemsContext: String?,
        taskInstructions: String,
        lastPointerPosition: androidx.compose.ui.geometry.Offset? = null,
    ): String {
        val historyBlock = if (conversationHistory.isNotEmpty()) {
            "\nConversation history:\n" + conversationHistory.joinToString("\n") { (u, a) ->
                "User: $u\nAssistant: $a"
            } + "\n"
        } else ""

        val relatedBlock = if (relatedItemsContext != null) {
            "\nItems available in library that may be relevant:\n$relatedItemsContext\n"
        } else ""

        val pointerContext = if (lastPointerPosition != null) {
            "\nUser is currently pointing/looking at coordinates: x=${(lastPointerPosition.x * 100).toInt()}%, y=${(lastPointerPosition.y * 100).toInt()}% of the video screen.\n"
        } else ""

        return """
            You are SpatialFin, an on-device XR media assistant.
            Respect the spoiler policy: ${assistantPreferences.spoilerPolicy}.
            Verbosity: ${assistantPreferences.verbosity}.
            Do not invent details not present in the supplied context.
            $taskInstructions

            Current title: ${playerState.currentItemTitle}
            Series: ${playerState.currentSeriesName ?: ""}
            Season: ${playerState.currentSeasonNumber ?: ""}
            Episode: ${playerState.currentEpisodeNumber ?: ""}
            Year: ${playerState.productionYear ?: ""}
            Rating: ${playerState.officialRating ?: ""}
            Overview: ${playerState.currentOverview.take(1000)}
            Genres: ${playerState.currentGenres.joinToString(", ")}
            Community ratings: ${playerState.currentRatings.joinToString(", ")}
            Cast: ${playerState.castNames.joinToString(", ")}
            Directors: ${playerState.directors.joinToString(", ")}
            Writers: ${playerState.writers.joinToString(", ")}
            Current chapter: ${playerState.currentChapterName ?: ""}
            Story so far: ${(storySoFarContext ?: "").take(1200)}
            Recent subtitles: ${subtitleContext.takeLast(1200)}
            Audio track: ${playerState.currentAudioTrack ?: ""}
            Subtitle track: ${playerState.currentSubtitleTrack ?: ""}
            $historyBlock$relatedBlock$pointerContext
            User question:
            $question
        """.trimIndent()
    }

    private fun fallbackAnswer(
        question: String,
        playerState: PlayerStateSnapshot,
        storySoFarContext: String?,
        subtitleContext: String,
        recommendedItems: List<SpatialFinItem>,
    ): String? {
        return heuristicAnswer(question, playerState, storySoFarContext, subtitleContext)
            ?: recommendationFallback(recommendedItems)
    }

    private fun recommendationFallback(items: List<SpatialFinItem>): String? {
        val picks = items.map { it.name.trim() }.filter { it.isNotBlank() }.distinct().take(3)
        if (picks.isEmpty()) return null

        val titleList = when (picks.size) {
            1 -> picks.first()
            2 -> "${picks[0]} or ${picks[1]}"
            else -> "${picks[0]}, ${picks[1]}, or ${picks[2]}"
        }
        return "Based on your library, try $titleList."
    }

    private fun finalizeRecommendationReply(
        text: String?,
        skillId: MediaSkillId,
        recommendedItems: List<SpatialFinItem>,
    ): String? {
        val base = text?.trim()?.takeIf { it.isNotBlank() } ?: return text
        if (recommendedItems.isEmpty()) return base
        if (skillId != MediaSkillId.WATCH_RECOMMENDER && skillId != MediaSkillId.MOOD_SURPRISE) {
            return base
        }
        if (base.contains("would you like to watch", ignoreCase = true)) return base
        if (base.contains("which one", ignoreCase = true)) return base
        return "$base Would you like to watch any of these? You can say play the first one or say a title."
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

        val isTimeBasedSummary = (normalized.contains("last minute") || normalized.contains("last few minutes") ||
            Regex("last\\s+\\d+\\s*minutes?").containsMatchIn(normalized) ||
            Regex("last\\s+\\d+\\s*seconds?").containsMatchIn(normalized)) ||
            (normalized.contains("summarize") && (normalized.contains("minute") || normalized.contains("second") ||
                normalized.contains("scene") || normalized.contains("just now")))

        if (isTimeBasedSummary) {
            return "I need the AI assistant enabled to summarize what just happened."
        }

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
                "Cast: ${playerState.castNames.joinToString(", ")}."
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
            return "I need the AI assistant enabled to explain what they just said."
        }

        return null
    }
}
