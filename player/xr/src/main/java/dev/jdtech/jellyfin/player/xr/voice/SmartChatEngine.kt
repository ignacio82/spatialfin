package dev.jdtech.jellyfin.player.xr.voice

import android.graphics.Bitmap
import dev.jdtech.jellyfin.core.llm.LlmInferenceProfile
import dev.jdtech.jellyfin.core.llm.LlmModelManager
import dev.jdtech.jellyfin.core.llm.VoiceAiEngine
import dev.jdtech.jellyfin.models.SpatialFinItem
import dev.jdtech.jellyfin.player.session.voice.PlayerStateSnapshot
import dev.jdtech.jellyfin.player.xr.voice.prompt.PromptContext
import dev.jdtech.jellyfin.player.xr.voice.prompt.chatPrompt
import dev.jdtech.jellyfin.player.xr.voice.prompt.characterIdentificationPrompt
import dev.jdtech.jellyfin.player.xr.voice.prompt.recapPrompt
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeoutException
import timber.log.Timber

// Skip the tool-call research pass on very short questions — the entity signal
// is too weak to justify a 3–5s round trip.
private const val MIN_TOOL_CALL_QUESTION_LEN = 12

/**
 * Heuristic: does the given reply text read like the on-device model giving up
 * on identifying a character? Matches the canned phrase the
 * CHARACTER_IDENTIFICATION prompt asks the model to emit ("I can't tell which
 * character this is from the frame") plus a short list of near-variants small
 * models commonly emit instead. Used to gate the cloud retry — we only pay for
 * a cloud call when on-device has explicitly admitted defeat.
 */
internal fun looksLikeCharacterIdPunt(text: String?): Boolean {
    // Null / blank input is the strongest punt signal — the model returned
    // nothing usable. Cloud retry should absolutely fire there.
    val normalized = text?.lowercase()?.trim() ?: return true
    if (normalized.isBlank()) return true
    val puntPhrases = listOf(
        "can't tell",
        "can't identify",
        "cannot tell",
        "cannot identify",
        "unable to identify",
        "unable to tell",
        "don't know who",
        "do not know who",
        "not sure who",
        "i'm not sure",
        "im not sure",
        "not confident",
        "no cast metadata",
    )
    return puntPhrases.any { normalized.contains(it) }
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
    private val llmInstance: VoiceAiEngine?
        get() = modelManager.instance
    private val mediaSkillRegistry = MediaSkillRegistry(repository, appPreferences)
    private val chatToolRegistry = ChatToolRegistry(appPreferences)

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
        /**
         * Optional fallback that loads subtitle lines from the disk cache for a given time window.
         * Called when the in-memory [recentSubtitleLines] buffer has no coverage for the window
         * extracted from the user's question (e.g. "what happened an hour ago").
         * Parameters: (fromMs, toMs) → list of (timestampMs, text) pairs.
         */
        subtitleCacheFallback: ((fromMs: Long, toMs: Long) -> List<Pair<Long, String>>)? = null,
        /** Called on the IO thread with accumulating text as each token arrives. */
        onTokenStream: ((String) -> Unit)? = null,
    ): AssistantReply = withContext(Dispatchers.IO) {
        val subtitleContext = buildSubtitleContext(question, recentSubtitleLines, currentPositionMs, subtitleCacheFallback)
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

        // Fast path: RECAP with subtitle data → use a focused summarization prompt.
        // Try cloud first (fast), then Gemma (slower but on-device), then local narrative.
        if (plan.skillId == MediaSkillId.RECAP && subtitleContext.isNotBlank()) {
            val recapPrompt = buildRecapSummaryPrompt(question, playerState, subtitleContext)

            // 1. Cloud (preferred — instant, no GPU bottleneck)
            if (hasGeminiApiKey()) {
                Timber.d("RECAP: routing to cloud (subtitleContext lines=%d)", subtitleContext.lines().size)
                val cloudResult = geminiCloudService.generateText(
                    prompt = recapPrompt,
                    reason = "recap",
                    temperature = 0.3,
                    maxOutputTokens = 200,
                )
                if (cloudResult.status.usedModel && !cloudResult.text.isNullOrBlank()) {
                    return@withContext AssistantReply(
                        text = finalizeReply(cloudResult.text.trim()),
                        strategy = "CLOUD_RECAP",
                        debugInfo = "${plan.debugInfo}; cloud-recap; ${cloudResult.status.details}",
                        recommendedItems = recommendedItems,
                        selectedSkill = plan.skillId.name,
                        validatedInput = plan.validatedInput,
                        resultDisposition = "MODEL",
                    )
                }
                Timber.w("RECAP: cloud failed (%s), trying Gemma", cloudResult.status.details)
            }

            // 2. Gemma with focused prompt (shorter input → faster inference than full buildPrompt)
            val cpuAllowed = appPreferences.getValue(appPreferences.voiceAssistantCpuInference) ?: false
            val currentInstance = if (shouldUseGemma()) { modelManager.ensureInitialized(); llmInstance } else null
            if (currentInstance != null && (currentInstance.backendName != "CPU" || cpuAllowed)) {
                Timber.d("RECAP: routing to Gemma focused prompt")
                // Run Gemma in a child job so we can return as soon as we detect a
                // complete sentence in the stream — without waiting for onDone (which Gemma
                // often delays or never fires because it keeps generating past the answer).
                var streamedAnswer: String? = null
                val latch = CompletableDeferred<String?>()
                val gemmaResult: String? = try {
                    coroutineScope {
                        val inferenceJob = launch {
                            try {
                                val fullResult = currentInstance.runInference(
                                    prompt = recapPrompt,
                                    images = emptyList(),
                                    profile = LlmInferenceProfile.CHAT,
                                    onToken = { accumulated ->
                                        if (!latch.isCompleted) {
                                            onTokenStream?.invoke(accumulated)
                                            val t = accumulated.trimEnd()
                                            val looksComplete = t.endsWith('.') || t.endsWith('!') ||
                                                t.endsWith('?') || t.endsWith('\n')
                                            if (t.length >= 30 && looksComplete) {
                                                streamedAnswer = t.trimEnd('\n')
                                                latch.complete(streamedAnswer)
                                            }
                                        }
                                    },
                                )
                                // onDone fired — use full result if we didn't already get a stream answer
                                latch.complete(if (fullResult.isNotBlank()) fullResult else streamedAnswer)
                            } catch (e: CancellationException) {
                                latch.complete(streamedAnswer)
                                // Do not re-throw: this job is cancelled intentionally after we get the answer
                            } catch (e: Exception) {
                                Timber.e(e, "RECAP: Gemma inference error")
                                latch.complete(streamedAnswer)
                            }
                        }
                        // Wait for the first complete sentence, full result, or 40s timeout
                        val result = withTimeoutOrNull(40_000L) { latch.await() } ?: streamedAnswer
                        if (inferenceJob.isActive) inferenceJob.cancel()
                        result
                    }
                } catch (e: CancellationException) {
                    latch.complete(streamedAnswer)
                    throw e
                }
                Timber.d("RECAP: Gemma result chars=%d blank=%b streamed=%b", gemmaResult?.length ?: -1, gemmaResult.isNullOrBlank(), streamedAnswer != null)
                if (!gemmaResult.isNullOrBlank()) {
                    val cleaned = gemmaResult.removePrefix("Model: ").trim()
                    return@withContext AssistantReply(
                        text = finalizeReply(cleaned),
                        strategy = "GEMMA_RECAP",
                        debugInfo = "${plan.debugInfo}; gemma-recap-focused streamed=${streamedAnswer != null}",
                        recommendedItems = recommendedItems,
                        selectedSkill = plan.skillId.name,
                        validatedInput = plan.validatedInput,
                        resultDisposition = "MODEL",
                    )
                }
                Timber.w("RECAP: Gemma timed out with no complete sentence, falling back to local narrative")
            }

            // 3. Local narrative formatter (instant, no model required)
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

        // Tool-call research pass for GENERAL_CHAT: the skill registry already
        // gathers what's needed for every other skill, so engaging here is the
        // smallest wedge where the model might want to pull in TMDB/Wikipedia/
        // current-item facts before generating. Gated on a non-CPU on-device
        // backend — AICore uses a JSON-in-text fallback now that mlkit-genai-
        // prompt still doesn't expose typed tool schemas, and CPU-only LiteRT
        // is too slow to add a tool round trip to.
        val researchNotes: ChatToolRegistry.ResearchNotes? =
            if (plan.skillId == MediaSkillId.GENERAL_CHAT && shouldUseGemma() && question.length >= MIN_TOOL_CALL_QUESTION_LEN) {
                modelManager.ensureInitialized()
                val engine = llmInstance
                if (engine != null && !engine.backendName.equals("CPU", ignoreCase = true)) {
                    try {
                        chatToolRegistry.gather(question, playerState, engine)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.w(e, "ChatTool: gather failed, skipping research pass")
                        null
                    }
                } else null
            } else null
        if (researchNotes != null) {
            Timber.d("ChatTool: researchNotes attached (%s)", researchNotes.debugInfo)
        }

        // Either the skill pre-gathered notes (EXTERNAL_KNOWLEDGE) or the
        // tool-call research pass produced them (GENERAL_CHAT). Prefer the
        // skill-provided ones — they're source-labelled and already shaped as a
        // cheat sheet, whereas the tool-call path returns best-effort text.
        // If both exist (rare: only when a skill set notes AND the tool-call
        // also engaged), concatenate so the model sees both signals.
        val effectiveResearchNotes = when {
            plan.researchNotes != null && researchNotes?.body != null ->
                "${plan.researchNotes}\n\n${researchNotes.body}"
            plan.researchNotes != null -> plan.researchNotes
            else -> researchNotes?.body
        }
        val prompt = if (plan.skillId == MediaSkillId.CHARACTER_IDENTIFICATION) {
            // subtitleContext is load-bearing: the character-ID prompt includes
            // a "Recent dialogue" section so the model can correlate a name just
            // spoken (e.g. "Jon, wait!") with the frame. Without it, the model
            // has only the still image + cast list to work from and small
            // multimodal models punt much more often.
            buildCharacterIdentificationPrompt(playerState, plan.taskInstructions, subtitleContext)
        } else {
            buildPrompt(
                question = question,
                playerState = playerState,
                storySoFarContext = storySoFarContext,
                subtitleContext = subtitleContext,
                assistantPreferences = assistantPreferences,
                conversationHistory = conversationHistory,
                relatedItemsContext = plan.relatedItemsContext,
                researchNotes = effectiveResearchNotes,
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
                    // GPU on a mid-tier mobile chip generates a full chat reply in
                    // 30–45s; that far exceeds a conversational feel. Cap the
                    // wall-clock budget and bail out earlier with whatever tokens
                    // the model has already streamed (see [lastStreamed] below)
                    // so "Thinking…" never sits longer than this cap without
                    // the user seeing *something*. NPU / AICore are fast enough
                    // to keep the full 45s budget.
                    val timeoutMs = chatInferenceTimeoutMs(currentInstance.backendName)
                    var lastStreamed: String = ""
                    val responseText = withTimeoutOrNull(timeoutMs) {
                        currentInstance.runInference(
                            prompt = prompt,
                            images = visualContexts,
                            profile = LlmInferenceProfile.CHAT,
                            onToken = { partial ->
                                lastStreamed = partial
                                onTokenStream?.invoke(partial)
                            },
                        )
                    }
                    val effectiveResponse = responseText
                        ?: lastStreamed.takeIf { it.isNotBlank() }
                        ?: run {
                            Timber.w(
                                "LiteRT: inference timed out on %s after %dms with no streamed tokens",
                                currentInstance.backendName, timeoutMs,
                            )
                            null
                        }
                    if (!effectiveResponse.isNullOrBlank()) {
                        val prefixToRemove = "Model: "
                        val cleanedResponse = if (effectiveResponse.startsWith(prefixToRemove)) {
                            effectiveResponse.substring(prefixToRemove.length)
                        } else {
                            effectiveResponse
                        }
                        val finalText =
                            cleanedResponse.trim()
                                .ifBlank { fallbackText ?: "" }
                                .takeIf { it.isNotBlank() }
                        val strategy = if (responseText == null) "GEMMA_LITERT_PARTIAL" else "GEMMA_LITERT"

                        // Cloud retry for CHARACTER_IDENTIFICATION punts. On-device
                        // multimodal models (Gemini Nano / Gemma 4B) can usually
                        // describe a frame but struggle to name specific characters
                        // without very distinctive visual features. Cloud Gemini is
                        // substantially better at this — so when on-device explicitly
                        // admits it can't tell AND the user has a cloud key, try
                        // cloud with the same frame + prompt before committing to
                        // the punt. Budget-capped to one retry per turn.
                        if (plan.skillId == MediaSkillId.CHARACTER_IDENTIFICATION &&
                            hasGeminiApiKey() &&
                            looksLikeCharacterIdPunt(finalText) &&
                            visualContexts.isNotEmpty()
                        ) {
                            Timber.i("CHARACTER_ID: on-device punted ('%s'); retrying on cloud Gemini", finalText?.take(80))
                            val cloudRetry = geminiCloudService.generateText(
                                prompt = prompt,
                                reason = "character-id-retry",
                                temperature = 0.2,
                                maxOutputTokens = 160,
                                images = visualContexts,
                            )
                            if (cloudRetry.status.usedModel && !cloudRetry.text.isNullOrBlank() &&
                                !looksLikeCharacterIdPunt(cloudRetry.text)
                            ) {
                                return@withContext AssistantReply(
                                    text = finalizeReply(cloudRetry.text.trim()),
                                    strategy = "CLOUD_CHARACTER_ID_RETRY",
                                    debugInfo = "${plan.debugInfo}; on-device punted, cloud resolved; ${cloudRetry.status.details}",
                                    recommendedItems = recommendedItems,
                                    selectedSkill = plan.skillId.name,
                                    validatedInput = plan.validatedInput,
                                    resultDisposition = "MODEL",
                                )
                            }
                            // Cloud also punted or failed — fall through to the
                            // on-device punt. Better than pretending we have a
                            // confident answer.
                            Timber.i("CHARACTER_ID: cloud retry also punted or failed (%s)", cloudRetry.status.details)
                        }

                        return@withContext AssistantReply(
                            text = finalizeReply(finalText),
                            strategy = strategy,
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
            // We reached here because on-device inference failed or produced
            // nothing usable (timeout with no streamed tokens, empty response,
            // exception). The heuristic fallback is often empty for open-ended
            // GENERAL_CHAT, which surfaces as "Sorry, I couldn't process that"
            // — misleading after the user watched "Thinking…" for 30s. Tell
            // them what actually happened.
            val honestFallback = fallbackText
                ?: "That one was too slow for on-device AI. Try a shorter question, or add a Gemini cloud key in settings for longer answers."
            return@withContext AssistantReply(
                text = finalizeReply(honestFallback),
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
        geminiCloudService.destroy()
        // LlmModelManager owns the engine lifecycle; do not close it here.
    }

    fun shouldUseGemma(): Boolean =
        appPreferences.getValue(appPreferences.voiceAssistantGemmaEnabled)

    /**
     * Wall-clock budget for a single chat inference, keyed on the active
     * backend. GPU on mid-tier mobile chips generates a full reply in 30–45s
     * which is too long for a conversational UI; capping earlier and using
     * whichever tokens streamed in that window keeps the "Thinking…" state
     * from lingering, at the cost of occasionally shorter replies. NPU and
     * AICore (Gemini Nano) are fast enough to keep the longer legacy budget.
     */
    private fun chatInferenceTimeoutMs(backendName: String): Long = when {
        backendName.equals("GPU", ignoreCase = true) -> 25_000L
        backendName.equals("CPU", ignoreCase = true) -> 45_000L
        else -> 45_000L
    }

    private fun hasGeminiApiKey(): Boolean =
        !appPreferences.getValue(appPreferences.voiceAssistantCloudApiKey).orEmpty().trim().isBlank()

    private fun shouldAttemptGemini(): Boolean = hasGeminiApiKey() && !shouldUseGemma()


    private fun buildSubtitleContext(
        question: String,
        lines: List<Pair<Long, String>>,
        currentPositionMs: Long,
        cacheFallback: ((fromMs: Long, toMs: Long) -> List<Pair<Long, String>>)? = null,
    ): String {
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
                val n = (lastNMinutesMatch.groupValues[1].toLongOrNull() ?: 2L).coerceAtMost(60L)
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

        // Always merge disk cache + in-memory lines for the window.
        val inMemory = lines.filter { (ts, _) -> ts in targetWindowMs }
        val cached = cacheFallback?.invoke(targetWindowMs.first, targetWindowMs.last) ?: emptyList()
        Timber.d(
            "SubtitleCtx: posMs=%d window=%d..%d inMemory=%d cached=%d",
            currentPositionMs, targetWindowMs.first, targetWindowMs.last,
            inMemory.size, cached.size,
        )
        val source = if (cached.isNotEmpty()) {
            (cached + inMemory).distinctBy { it.first }.sortedBy { it.first }
        } else {
            inMemory
        }

        if (source.isEmpty()) return ""

        return source.takeLast(120)
            .joinToString("\n") { (ts, line) ->
                val totalSec = ts / 1000L
                val m = totalSec / 60
                val s = totalSec % 60
                "[%d:%02d] %s".format(m, s, line)
            }
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

    /**
     * Builds a short, focused prompt for cloud-based RECAP summarization.
     * Intentionally minimal — just the show context, the subtitles, and a clear instruction.
     * Long prompts waste tokens and produce worse summaries on Gemini Flash.
     */
    private fun buildRecapSummaryPrompt(
        question: String,
        playerState: PlayerStateSnapshot,
        subtitleContext: String,
    ): String = recapPrompt(
        PromptContext(
            question = question,
            playerState = playerState,
            subtitleContext = subtitleContext,
        ),
    ).render()

    private fun buildCharacterIdentificationPrompt(
        playerState: PlayerStateSnapshot,
        taskInstructions: String,
        subtitleContext: String,
    ): String = characterIdentificationPrompt(
        PromptContext(
            playerState = playerState,
            taskInstructions = taskInstructions,
            subtitleContext = subtitleContext,
        ),
    ).render()

    private fun buildPrompt(
        question: String,
        playerState: PlayerStateSnapshot,
        storySoFarContext: String?,
        subtitleContext: String,
        assistantPreferences: AssistantPreferences,
        conversationHistory: List<Pair<String, String>>,
        relatedItemsContext: String?,
        researchNotes: String? = null,
        taskInstructions: String,
        lastPointerPosition: androidx.compose.ui.geometry.Offset? = null,
    ): String = chatPrompt(
        PromptContext(
            question = question,
            playerState = playerState,
            assistantPreferences = assistantPreferences,
            storySoFar = storySoFarContext,
            subtitleContext = subtitleContext,
            conversationHistory = conversationHistory,
            relatedItems = relatedItemsContext,
            researchNotes = researchNotes,
            pointerPosition = lastPointerPosition,
            taskInstructions = taskInstructions,
        ),
    ).render()

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
            // If we have subtitle context from the disk cache, return it as a plain transcript
            // rather than blocking the user with a "need AI" message.
            return if (subtitleContext.isNotBlank()) {
                "Here's the dialogue from that part:\n$subtitleContext"
            } else {
                val chapterHint = playerState.currentChapterName?.let { " You are currently in the chapter \"$it\"." } ?: ""
                val overviewHint = playerState.currentOverview.takeIf { it.isNotBlank() }
                    ?.let { " Here's the episode overview: $it" } ?: ""
                "I don't have subtitle data for this content, so I can't give a dialogue recap.$chapterHint$overviewHint For the best experience, enable subtitles in the player and I'll be able to answer questions like this."
            }
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
            return if (subtitleContext.isNotBlank()) {
                "Here's the recent dialogue:\n$subtitleContext"
            } else {
                val chapterHint = playerState.currentChapterName?.let { " You are in the chapter \"$it\"." } ?: ""
                "I don't have subtitle data to show recent dialogue.$chapterHint Enable subtitles so I can track the conversation for you."
            }
        }

        return null
    }
}
