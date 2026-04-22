package dev.jdtech.jellyfin.player.xr

import androidx.compose.ui.geometry.Offset
import androidx.media3.common.Player
import dev.jdtech.jellyfin.models.SpatialFinItem
import dev.jdtech.jellyfin.player.local.presentation.PlayerViewModel
import dev.jdtech.jellyfin.player.session.voice.PlayerSessionController
import dev.jdtech.jellyfin.player.session.voice.PlayerStateSnapshot
import dev.jdtech.jellyfin.player.session.voice.XrPlayerAction
import dev.jdtech.jellyfin.player.xr.capture.PlayerFrameCapture
import dev.jdtech.jellyfin.player.xr.voice.AssistantPreferences
import dev.jdtech.jellyfin.player.xr.voice.RecommendationContext
import dev.jdtech.jellyfin.player.xr.voice.SmartChatEngine
import dev.jdtech.jellyfin.player.xr.voice.SpatialCommandCoordinator
import dev.jdtech.jellyfin.player.xr.voice.SpatialVoiceService
import dev.jdtech.jellyfin.player.xr.voice.VoiceParseResult
import dev.jdtech.jellyfin.settings.voice.VoiceTelemetryEntry
import dev.jdtech.jellyfin.settings.voice.VoiceTelemetryStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Drives a single voice turn while the XR player is on screen.
 *
 * `SpatialPlayerScreen` calls into this when a gesture or mic-orbiter triggers
 * recognition. The function handles:
 * - building the rich [PlayerStateSnapshot] (item metadata, track state,
 *   passthrough, voice-search context),
 * - parsing the transcript via [SpatialCommandCoordinator],
 * - for `ChatQuery`, gathering visual context (a high-res frame for
 *   "who is this?" queries, otherwise a temporal trickplay sample), pausing
 *   GPU-bound on-device inference, calling [SmartChatEngine.query], speaking
 *   the reply when allowed, and updating the in-screen recommendation panel,
 * - for non-chat actions, dispatching to [PlayerSessionController] and
 *   recording telemetry,
 * - cleanup via [SpatialVoiceService.resetState] and bitmap recycling.
 *
 * Lifted out of `SpatialPlayerScreen.kt` to keep that file focused on the
 * spatial UI tree. Visibility is `internal` so the surrounding screen can
 * call it without forcing a public surface on the player module.
 */
internal fun startVoiceCapture(
    voiceService: SpatialVoiceService,
    commandCoordinatorProvider: () -> SpatialCommandCoordinator,
    chatEngineProvider: () -> SmartChatEngine,
    recentSubtitles: List<Pair<Long, String>>,
    player: Player,
    viewModel: PlayerViewModel,
    uiState: PlayerViewModel.UiState,
    snapshot: PlayerStateSnapshot,
    controller: PlayerSessionController,
    telemetryStore: VoiceTelemetryStore,
    onSearchQuery: suspend (String) -> List<SpatialFinItem>,
    assistantPreferences: AssistantPreferences,
    responseLanguageHint: String?,
    conversationHistory: List<Pair<String, String>>,
    onConversationTurn: (String, String) -> Unit,
    recommendationContext: RecommendationContext?,
    onRecommendationContextUpdated: (RecommendationContext) -> Unit,
    onScheduleFollowUp: () -> Unit,
    onGetSuggestions: suspend () -> List<SpatialFinItem>,
    onResult: (String) -> Unit,
    onSpokenReply: (String, String?, Int) -> Unit,
    onCharacterScanActiveChanged: ((Boolean) -> Unit)? = null,
    subtitleCacheFallback: ((fromMs: Long, toMs: Long) -> List<Pair<Long, String>>)? = null,
    scope: CoroutineScope,
    lastPointerPosition: Offset?,
    onJobStarted: ((Job) -> Unit)? = null,
) {
    val startedAtMs = System.currentTimeMillis()
    voiceService.startListening { transcript ->
        val job = scope.launch {
            try {
                val commandCoordinator = commandCoordinatorProvider()
                val parseResult = commandCoordinator.parse(transcript, snapshot)
                val action = parseResult.action
                if (action is XrPlayerAction.ChatQuery) {
                    onResult("…")
                    val chatEngine = chatEngineProvider()

                    val visualContexts = mutableListOf<android.graphics.Bitmap>()
                    val ownedBitmaps = mutableListOf<android.graphics.Bitmap>()
                    val trickplay = uiState.currentTrickplay

                    // Detect "who is this/him/her/the character" style queries.
                    val normalizedQuery = action.query.lowercase()
                    val isCharacterIDQuery = (normalizedQuery.startsWith("who is") || normalizedQuery.startsWith("who was")) &&
                        run {
                            val afterWho = normalizedQuery.removePrefix("who is ").removePrefix("who was ").trim()
                            afterWho in setOf(
                                "this", "that", "him", "her", "he", "she", "they",
                                "this character", "this person", "this actor", "this actress",
                                "the character", "this guy", "this man", "this woman",
                                "this girl", "this boy",
                            ) || afterWho.startsWith("this ") || afterWho.startsWith("the ")
                        }

                    if (isCharacterIDQuery) {
                        // Single high-res frame via MediaMetadataRetriever; falls back to trickplay.
                        val streamUri = player.currentMediaItem?.localConfiguration?.uri?.toString()
                        val frame = PlayerFrameCapture.bestFrameForCharacterID(
                            streamUri = streamUri,
                            positionMs = player.currentPosition,
                            trickplayImages = trickplay?.images.orEmpty(),
                            trickplayIntervalSeconds = trickplay?.interval?.toLong() ?: 0L,
                            ownedBitmapOut = ownedBitmaps,
                        )
                        if (frame != null) visualContexts.add(frame)
                        onCharacterScanActiveChanged?.invoke(true)
                    } else if (trickplay != null && trickplay.images.isNotEmpty() && trickplay.interval > 0) {
                        // Temporal sequence of trickplay frames for general queries.
                        val currentIdx = (player.currentPosition / 1000 / trickplay.interval).toInt()
                            .coerceIn(0, trickplay.images.size - 1)
                        val indices = listOf(
                            (currentIdx - 3).coerceAtLeast(0),
                            (currentIdx - 1).coerceAtLeast(0),
                            currentIdx,
                        ).distinct()
                        indices.forEach { idx -> visualContexts.add(trickplay.images[idx]) }
                    }

                    val isGpu = chatEngine.modelManager.instance?.backendName == "GPU"
                    val shouldPauseForGemma = isGpu && chatEngine.shouldUseGemma()
                    var wasPlaying = false
                    if (shouldPauseForGemma) {
                        wasPlaying = player.isPlaying
                        if (wasPlaying) {
                            player.pause()
                        }
                    }

                    var firstChunk = true
                    val response = try {
                        chatEngine.query(
                            question = action.query,
                            playerState = snapshot,
                            storySoFarContext = uiState.storySoFarContext,
                            recentSubtitleLines = recentSubtitles,
                            currentPositionMs = player.currentPosition,
                            assistantPreferences = assistantPreferences,
                            onSearchQuery = onSearchQuery,
                            conversationHistory = conversationHistory,
                            recommendationContext = recommendationContext,
                            onGetSuggestions = onGetSuggestions,
                            visualContexts = visualContexts,
                            lastPointerPosition = lastPointerPosition,
                            subtitleCacheFallback = subtitleCacheFallback,
                            onTokenStream = { partial -> onResult(partial) },
                            onSentenceStream = { chunk ->
                                if (assistantPreferences.spokenRepliesEnabled && chunk.isNotBlank()) {
                                    onSpokenReply(chunk, responseLanguageHint, if (firstChunk) android.speech.tts.TextToSpeech.QUEUE_FLUSH else android.speech.tts.TextToSpeech.QUEUE_ADD)
                                    firstChunk = false
                                }
                            }
                        )
                    } finally {
                        if (isCharacterIDQuery) onCharacterScanActiveChanged?.invoke(false)
                        ownedBitmaps.forEach { it.recycle() }
                        ownedBitmaps.clear()
                    }

                    if (wasPlaying) {
                        player.play()
                    }

                    if (response.text != null) {
                        Timber.i(
                            "VOICE: chat reply strategy=%s skill=%s recommendations=%d disposition=%s spokenReplies=%b",
                            response.strategy,
                            response.selectedSkill,
                            response.recommendedItems.size,
                            response.resultDisposition,
                            assistantPreferences.spokenRepliesEnabled,
                        )
                        onResult(response.text)
                        onConversationTurn(action.query, response.text)
                        onScheduleFollowUp()
                        if (assistantPreferences.spokenRepliesEnabled && firstChunk) {
                            Timber.i("VOICE: speaking chat reply chars=%d", response.text.length)
                            onSpokenReply(response.text, responseLanguageHint, android.speech.tts.TextToSpeech.QUEUE_FLUSH)
                        }
                        if (response.recommendedItems.isNotEmpty()) {
                            Timber.i(
                                "VOICE: showing recommendation results query=%s count=%d first=%s",
                                action.query,
                                response.recommendedItems.size,
                                response.recommendedItems.firstOrNull()?.name,
                            )
                            onRecommendationContextUpdated(
                                RecommendationContext(
                                    query = action.query,
                                    items = response.recommendedItems,
                                ),
                            )
                            controller.showRecommendations(action.query, response.recommendedItems)
                        }
                    } else {
                        Timber.w("VOICE: chat reply was null")
                        onResult("Sorry, I couldn't process that.")
                    }
                    telemetryStore.record(
                        VoiceTelemetryEntry(
                            transcript = transcript,
                            normalizedTranscript = parseResult.normalizedTranscript,
                            action = "ChatQuery",
                            strategy = response.strategy,
                            latencyMs = System.currentTimeMillis() - startedAtMs,
                            success = response.text != null,
                            selectedSkill = response.selectedSkill,
                            validatedInput = response.validatedInput,
                            resultDisposition = response.resultDisposition,
                            details = "parse=${parseResult.debugInfo}; reply=${response.debugInfo}",
                        )
                    )
                } else {
                    val feedback = dispatchVoiceParseResult(controller, parseResult)
                    onResult(feedback)
                    if (assistantPreferences.spokenRepliesEnabled && shouldSpeakVoiceFeedback(action)) {
                        onSpokenReply(feedback, responseLanguageHint, android.speech.tts.TextToSpeech.QUEUE_FLUSH)
                    }
                    telemetryStore.record(
                        VoiceTelemetryEntry(
                            transcript = transcript,
                            normalizedTranscript = parseResult.normalizedTranscript,
                            action = parseResult.action::class.simpleName ?: "Unknown",
                            strategy = parseResult.strategy.name,
                            latencyMs = System.currentTimeMillis() - startedAtMs,
                            success = parseResult.action !is XrPlayerAction.Unrecognized,
                            details = parseResult.debugInfo,
                        )
                    )
                }
            } finally {
                voiceService.resetState()
            }
        }
        onJobStarted?.invoke(job)
    }
}

internal suspend fun dispatchVoiceParseResult(
    controller: PlayerSessionController,
    parseResult: VoiceParseResult,
): String = controller.dispatch(parseResult.action)

internal fun shouldSpeakVoiceFeedback(action: XrPlayerAction): Boolean {
    return when (action) {
        is XrPlayerAction.ReportCurrentTime,
        is XrPlayerAction.ReportRemainingTime,
        is XrPlayerAction.ReportEndTime,
        is XrPlayerAction.ReportCurrentMedia,
        is XrPlayerAction.ReportPassthroughStatus -> true
        else -> false
    }
}
