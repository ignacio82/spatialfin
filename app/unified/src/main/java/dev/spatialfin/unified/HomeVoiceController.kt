package dev.spatialfin.unified

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.jdtech.jellyfin.core.llm.LlmModelManager
import dev.jdtech.jellyfin.models.SpatialFinItem
import dev.jdtech.jellyfin.player.session.voice.PlayerStateSnapshot
import dev.jdtech.jellyfin.player.session.voice.VoiceScreenContext
import dev.jdtech.jellyfin.player.session.voice.XrPlayerAction
import dev.jdtech.jellyfin.player.xr.voice.AssistantPreferences
import dev.jdtech.jellyfin.player.xr.voice.AssistantReply
import dev.jdtech.jellyfin.player.xr.voice.GeminiCloudService
import dev.jdtech.jellyfin.player.xr.voice.GeminiNanoService
import dev.jdtech.jellyfin.player.xr.voice.RecommendationContext
import dev.jdtech.jellyfin.player.xr.voice.SmartChatEngine
import dev.jdtech.jellyfin.player.xr.voice.SpatialCommandCoordinator
import dev.jdtech.jellyfin.player.xr.voice.SpatialVoiceService
import dev.jdtech.jellyfin.player.xr.voice.SpatialVoiceSynthesizer
import dev.jdtech.jellyfin.player.xr.voice.VoiceState
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.settings.voice.VoiceTelemetryEntry
import dev.jdtech.jellyfin.settings.voice.VoiceTelemetryStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Navigation surface the controller uses to act on parsed voice intents.
 * Implementations are typically thin lambdas around a NavController + Activity.
 */
interface HomeVoiceNavigation {
    /** Try to start playback or open a detail screen for [item]. Returns true on success. */
    fun launchItem(item: SpatialFinItem): Boolean
    fun goHome()
    fun goBack()
    fun closeApp()
}

/**
 * Holds all Home-Space voice state and the request/interrupt state machine.
 *
 * The class is designed to live for the duration of an Activity composition: callers
 * construct it via [rememberHomeVoiceController] and drive its lifecycle from the
 * composable that owns the NavController and permission launchers.
 *
 * Compose-observable state (feedback text, gesture hint, etc.) is exposed as
 * mutableState-backed properties so any composable that reads them recomposes
 * automatically. Long-running services (LLM clients, command coordinator, chat engine)
 * are lazily created on first use to keep cold-start cheap for users who never speak.
 */
class HomeVoiceController(
    private val applicationContext: Context,
    private val appPreferences: AppPreferences,
    private val repository: JellyfinRepository,
    private val llmModelManager: LlmModelManager,
    private val voiceTelemetryStore: VoiceTelemetryStore,
) {
    companion object {
        private const val FOLLOW_UP_LISTEN_WINDOW_MS = 12_000L
        private const val FOLLOW_UP_AUTO_START_DELAY_MS = 200L
        private const val ERROR_AUTO_RESET_MS = 2_000L
        private const val SPOKEN_REPLY_GRACE_MS = 1_500L
    }

    val voiceService: SpatialVoiceService = SpatialVoiceService(applicationContext)
    val tts: SpatialVoiceSynthesizer = SpatialVoiceSynthesizer(applicationContext)

    // Lazily created — no point spinning up LLM services for users who never speak.
    private var geminiNanoService: GeminiNanoService? = null
    private var geminiCloudService: GeminiCloudService? = null
    private var commandCoordinator: SpatialCommandCoordinator? = null
    private var chatEngine: SmartChatEngine? = null

    // ----- Compose-observable state -----
    var voiceFeedback by mutableStateOf<String?>(null)
        private set
    var voiceGestureHint by mutableStateOf<String?>(null)
        private set
    var voiceGestureArmingProgress by mutableFloatStateOf(0f)
        private set

    /**
     * Search query the Home Space NavigationRoot reads. Voice search updates this;
     * navigation callbacks may also write to it when launching ambiguous items.
     */
    var voiceSearchQuery by mutableStateOf<String?>(null)

    var assistantSpeechPendingStart by mutableStateOf(false)
        private set
    var assistantSpeechStarted by mutableStateOf(false)
        private set
    var followUpPending by mutableStateOf(false)
        private set
    var followUpDeadlineMs by mutableLongStateOf(0L)
        private set

    private var activeVoiceJob by mutableStateOf<Job?>(null)
    private val conversationHistory = mutableListOf<Pair<String, String>>()
    private var recommendationContext: RecommendationContext? = null

    /** Seed the search query from an Activity intent extra. Idempotent. */
    fun setInitialSearchQuery(query: String?) {
        if (voiceSearchQuery == null && query != null) voiceSearchQuery = query
    }

    fun isVoiceTurnBusy(voiceState: VoiceState, isTtsSpeaking: Boolean): Boolean {
        return HomeVoicePolicy.isVoiceTurnBusy(
            voiceState = voiceState,
            isTtsSpeaking = isTtsSpeaking,
            isJobActive = activeVoiceJob?.isActive == true,
            assistantSpeechPendingStart = assistantSpeechPendingStart,
            assistantSpeechStarted = assistantSpeechStarted,
        )
    }

    fun onGestureUpdate(progress: Float, hint: String?) {
        voiceGestureArmingProgress = progress
        voiceGestureHint = hint
    }

    fun clearGestureHintIfIdle(voiceState: VoiceState) {
        voiceGestureArmingProgress = 0f
        if (voiceState != VoiceState.LISTENING) voiceGestureHint = null
    }

    fun showFeedback(message: String) {
        voiceFeedback = message
    }

    private fun ensureNano(): GeminiNanoService {
        return geminiNanoService ?: GeminiNanoService(applicationContext).also {
            geminiNanoService = it
        }
    }

    private fun ensureCloud(): GeminiCloudService {
        return geminiCloudService ?: GeminiCloudService(
            applicationContext,
            appPreferences,
            repository,
        ).also { geminiCloudService = it }
    }

    private suspend fun ensureCoordinator(
        nano: GeminiNanoService,
        cloud: GeminiCloudService,
    ): SpatialCommandCoordinator {
        commandCoordinator?.let { return it }
        val coordinator = SpatialCommandCoordinator(
            applicationContext,
            nano,
            cloud,
            appPreferences,
            llmModelManager,
        )
        coordinator.initialize()
        commandCoordinator = coordinator
        return coordinator
    }

    private suspend fun ensureChatEngine(
        nano: GeminiNanoService,
        cloud: GeminiCloudService,
    ): SmartChatEngine {
        chatEngine?.let { return it }
        val engine = SmartChatEngine(
            nano,
            cloud,
            appPreferences,
            llmModelManager,
            repository,
        )
        engine.initialize()
        chatEngine = engine
        return engine
    }

    private fun armFollowUpWindow(reason: String) {
        followUpPending = true
        followUpDeadlineMs = System.currentTimeMillis() + FOLLOW_UP_LISTEN_WINDOW_MS
        voiceGestureHint = "Answer now"
        Timber.i("VOICE: follow-up armed reason=%s windowMs=%d", reason, FOLLOW_UP_LISTEN_WINDOW_MS)
    }

    private fun scheduleFollowUp(spokenReplyExpected: Boolean) {
        if (spokenReplyExpected) {
            followUpPending = true
            followUpDeadlineMs = 0L
            voiceGestureHint = "Wait for the reply, then answer"
            Timber.i("VOICE: follow-up scheduled pending spoken reply")
        } else {
            armFollowUpWindow("text-only-reply")
        }
    }

    private data class HomeVoiceActionOutcome(
        val feedback: String,
        val assistantReply: AssistantReply? = null,
    )

    private fun homePlayerStateSnapshot(): PlayerStateSnapshot {
        return PlayerStateSnapshot(
            screenContext = VoiceScreenContext.HOME,
            lastRecommendationQuery = recommendationContext?.query,
            lastRecommendationCount = recommendationContext?.items?.size ?: 0,
            lastRecommendationTitles = recommendationContext?.items?.take(6)?.map { it.name }
                ?: emptyList(),
        )
    }

    private suspend fun handleVoiceAction(
        action: XrPlayerAction,
        navigation: HomeVoiceNavigation,
        assistantSpokenReplies: Boolean,
        assistantVoicePreference: String,
        onToken: ((String) -> Unit)? = null,
    ): HomeVoiceActionOutcome {
        return when (action) {
            is XrPlayerAction.Search -> {
                if (action.autoPlay) {
                    val matchedItem = runCatching { repository.getSearchItems(action.query) }
                        .getOrDefault(emptyList())
                        .firstOrNull()
                    if (matchedItem != null && navigation.launchItem(matchedItem)) {
                        followUpPending = false
                        return HomeVoiceActionOutcome("Playing ${matchedItem.name}")
                    }
                }
                voiceSearchQuery = action.query
                HomeVoiceActionOutcome("Searching for ${action.query}")
            }
            is XrPlayerAction.SelectOption -> {
                val selectedItem = recommendationContext?.items?.getOrNull(action.index)
                if (selectedItem != null && navigation.launchItem(selectedItem)) {
                    followUpPending = false
                    HomeVoiceActionOutcome("Playing ${selectedItem.name}")
                } else {
                    HomeVoiceActionOutcome("I couldn't match that recommendation")
                }
            }
            is XrPlayerAction.ChatQuery -> {
                val nano = ensureNano()
                val cloud = ensureCloud()
                val assistant = ensureChatEngine(nano, cloud)
                val response = assistant.query(
                    question = action.query,
                    playerState = homePlayerStateSnapshot(),
                    assistantPreferences = AssistantPreferences(
                        verbosity = appPreferences.getValue(appPreferences.voiceAssistantVerbosity),
                        spoilerPolicy = appPreferences.getValue(appPreferences.voiceAssistantSpoilerPolicy),
                        spokenRepliesEnabled = appPreferences.getValue(appPreferences.voiceAssistantSpokenReplies),
                    ),
                    onSearchQuery = { query -> repository.getSearchItems(query) },
                    conversationHistory = conversationHistory,
                    recommendationContext = recommendationContext,
                    onGetSuggestions = { repository.getSuggestions() },
                    onTokenStream = onToken,
                )
                response.recommendedItems
                    .takeIf { it.isNotEmpty() }
                    ?.let { recommendationContext = RecommendationContext(action.query, it) }
                val feedback = response.text?.also { reply ->
                    conversationHistory.add(action.query to reply)
                    if (conversationHistory.size > 6) conversationHistory.removeAt(0)
                    val willSpeakReply = assistantSpokenReplies && tts.canSpeak()
                    scheduleFollowUp(willSpeakReply)
                    if (willSpeakReply) {
                        assistantSpeechPendingStart = true
                        assistantSpeechStarted = false
                        tts.speak(reply, null, assistantVoicePreference)
                    }
                } ?: "Sorry, I couldn't process that."
                HomeVoiceActionOutcome(feedback, response)
            }
            is XrPlayerAction.CloseApp -> {
                navigation.closeApp()
                HomeVoiceActionOutcome("Closing SpatialFin")
            }
            is XrPlayerAction.GoBack -> {
                navigation.goBack()
                HomeVoiceActionOutcome("Going back")
            }
            is XrPlayerAction.GoHome -> {
                navigation.goHome()
                HomeVoiceActionOutcome("Returning home")
            }
            is XrPlayerAction.Unrecognized ->
                HomeVoiceActionOutcome("I didn't catch that: ${action.transcript}")
            else -> HomeVoiceActionOutcome("Command not available on home screen")
        }
    }

    /**
     * Begin a voice turn. Idempotent against an in-flight turn — repeat calls
     * while `isVoiceTurnBusy` are logged and ignored.
     */
    fun requestVoiceCommand(
        scope: CoroutineScope,
        source: String,
        currentVoiceState: VoiceState,
        currentTtsSpeaking: Boolean,
        hasAudioPermission: Boolean,
        assistantSpokenReplies: Boolean,
        assistantVoicePreference: String,
        navigation: HomeVoiceNavigation,
        onAudioPermissionMissing: () -> Unit,
    ) {
        val decision = HomeVoicePolicy.decideRequest(
            isBusy = isVoiceTurnBusy(currentVoiceState, currentTtsSpeaking),
            isRecognitionAvailable = voiceService.isAvailable(),
            hasAudioPermission = hasAudioPermission,
        )
        when (decision) {
            HomeVoicePolicy.RequestDecision.Busy -> {
                Timber.i(
                    "VOICE: request ignored source=%s state=%s speaking=%b pendingSpeech=%b startedSpeech=%b jobActive=%b",
                    source,
                    currentVoiceState,
                    currentTtsSpeaking,
                    assistantSpeechPendingStart,
                    assistantSpeechStarted,
                    activeVoiceJob?.isActive == true,
                )
                return
            }
            HomeVoicePolicy.RequestDecision.SpeechRecognitionUnavailable -> {
                voiceFeedback = "Speech recognition unavailable"
                return
            }
            HomeVoicePolicy.RequestDecision.NeedsAudioPermission -> {
                onAudioPermissionMissing()
                return
            }
            HomeVoicePolicy.RequestDecision.Proceed -> Unit
        }
        Timber.i(
            "VOICE: request starting source=%s followUpPending=%b followUpDeadlineMs=%d",
            source,
            followUpPending,
            followUpDeadlineMs,
        )
        followUpPending = false
        followUpDeadlineMs = 0L
        voiceGestureHint = null
        voiceService.startListening { transcript ->
            val job = scope.launch {
                try {
                    val startedAtMs = System.currentTimeMillis()
                    val nano = ensureNano()
                    val cloud = ensureCloud()
                    val coordinator = ensureCoordinator(nano, cloud)
                    val parseResult = coordinator.parse(transcript, homePlayerStateSnapshot())
                    if (parseResult.action is XrPlayerAction.ChatQuery) {
                        voiceFeedback = "…"
                    }
                    val outcome = handleVoiceAction(
                        action = parseResult.action,
                        navigation = navigation,
                        assistantSpokenReplies = assistantSpokenReplies,
                        assistantVoicePreference = assistantVoicePreference,
                        onToken = { partial -> voiceFeedback = partial },
                    )
                    voiceFeedback = outcome.feedback
                    voiceTelemetryStore.record(
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
                    if (parseResult.action is XrPlayerAction.ChatQuery && outcome.assistantReply != null) {
                        voiceTelemetryStore.record(
                            VoiceTelemetryEntry(
                                transcript = transcript,
                                normalizedTranscript = parseResult.normalizedTranscript,
                                action = "ChatQuery",
                                strategy = outcome.assistantReply.strategy,
                                latencyMs = System.currentTimeMillis() - startedAtMs,
                                success = outcome.assistantReply.text != null,
                                selectedSkill = outcome.assistantReply.selectedSkill,
                                validatedInput = outcome.assistantReply.validatedInput,
                                resultDisposition = outcome.assistantReply.resultDisposition,
                                details = "parse=${parseResult.debugInfo}; reply=${outcome.assistantReply.debugInfo}",
                            )
                        )
                    }
                } catch (e: CancellationException) {
                    voiceFeedback = null
                    throw e
                } finally {
                    activeVoiceJob = null
                    voiceService.resetState()
                }
            }
            activeVoiceJob = job
        }
    }

    /**
     * Cancel an in-flight turn and (when appropriate) re-arm a follow-up window.
     * If the user interrupts mid-spoken-reply, [requestFollowUp] is invoked after a
     * short delay so the user can immediately answer.
     */
    fun interruptVoiceCommand(
        scope: CoroutineScope,
        reason: String,
        currentVoiceState: VoiceState,
        currentTtsSpeaking: Boolean,
        requestFollowUp: () -> Unit,
    ) {
        if (!isVoiceTurnBusy(currentVoiceState, currentTtsSpeaking)) return
        val shouldResumeFollowUp = HomeVoicePolicy.shouldResumeFollowUpAfterInterrupt(
            followUpPending = followUpPending,
            isTtsSpeaking = currentTtsSpeaking,
            assistantSpeechPendingStart = assistantSpeechPendingStart,
            assistantSpeechStarted = assistantSpeechStarted,
        )
        Timber.i(
            "VOICE: interrupt requested reason=%s state=%s speaking=%b jobActive=%b resumeFollowUp=%b",
            reason,
            currentVoiceState,
            currentTtsSpeaking,
            activeVoiceJob?.isActive == true,
            shouldResumeFollowUp,
        )
        voiceGestureArmingProgress = 0f
        voiceGestureHint = null
        voiceFeedback = null
        assistantSpeechPendingStart = false
        assistantSpeechStarted = false
        activeVoiceJob?.cancel()
        activeVoiceJob = null
        tts.stop()
        voiceService.cancelListening()
        voiceService.resetState()
        if (shouldResumeFollowUp) {
            armFollowUpWindow("speech-interrupted")
            scope.launch {
                delay(FOLLOW_UP_AUTO_START_DELAY_MS)
                requestFollowUp()
            }
        } else {
            followUpPending = false
            followUpDeadlineMs = 0L
        }
    }

    /**
     * Compose effects that own the voice state machine timers. Call once from the
     * Activity's XR composition; the function reads the current TTS / recognizer
     * state via [collectAsState] and updates controller state in response.
     *
     * [requestFollowUp] is invoked when the auto-start window decides to re-listen;
     * the caller wires it to [requestVoiceCommand] with the latest environment.
     */
    @Composable
    fun RegisterEffects(requestFollowUp: () -> Unit) {
        val voiceState by voiceService.state.collectAsState()
        val isTtsSpeaking by tts.isSpeaking.collectAsState()

        // Auto-clear stale feedback. "…" is the in-progress indicator and gets
        // a long timeout so it clears even if inference never completes.
        LaunchedEffect(voiceFeedback, isTtsSpeaking) {
            val feedback = voiceFeedback ?: return@LaunchedEffect
            if (!isTtsSpeaking) {
                delay(HomeVoicePolicy.feedbackTimeoutMs(feedback))
                if (voiceFeedback == feedback && !isTtsSpeaking) {
                    voiceFeedback = null
                    voiceService.resetState()
                }
            }
        }

        // Recover from transient ERROR state automatically.
        LaunchedEffect(voiceState) {
            if (voiceState == VoiceState.ERROR) {
                delay(ERROR_AUTO_RESET_MS)
                if (voiceService.state.value == VoiceState.ERROR) {
                    voiceService.resetState()
                }
            }
        }

        // TTS state machine: handle the gap between "tts.speak() called" and
        // "tts actually started speaking", and arm a follow-up window when speech ends.
        LaunchedEffect(
            isTtsSpeaking,
            assistantSpeechPendingStart,
            assistantSpeechStarted,
            followUpPending,
        ) {
            if (assistantSpeechPendingStart && isTtsSpeaking) {
                assistantSpeechPendingStart = false
                assistantSpeechStarted = true
            } else if (assistantSpeechPendingStart && !isTtsSpeaking) {
                delay(SPOKEN_REPLY_GRACE_MS)
                if (assistantSpeechPendingStart && !isTtsSpeaking) {
                    assistantSpeechPendingStart = false
                    assistantSpeechStarted = false
                    if (followUpPending && followUpDeadlineMs == 0L) {
                        armFollowUpWindow("spoken-reply-did-not-start")
                    }
                }
            } else if (!isTtsSpeaking && assistantSpeechStarted) {
                assistantSpeechStarted = false
                if (followUpPending && followUpDeadlineMs == 0L) {
                    armFollowUpWindow("spoken-reply-finished")
                }
            }
        }

        // Auto-start follow-up listening when the window is open and nothing is
        // currently speaking, recognizing, or processing.
        LaunchedEffect(
            followUpPending,
            followUpDeadlineMs,
            isTtsSpeaking,
            assistantSpeechPendingStart,
            assistantSpeechStarted,
            voiceState,
            activeVoiceJob,
        ) {
            if (
                !followUpPending ||
                followUpDeadlineMs == 0L ||
                isTtsSpeaking ||
                assistantSpeechPendingStart ||
                assistantSpeechStarted ||
                voiceState != VoiceState.IDLE ||
                activeVoiceJob?.isActive == true
            ) {
                return@LaunchedEffect
            }
            val remaining = followUpDeadlineMs - System.currentTimeMillis()
            if (remaining <= 0L) {
                followUpPending = false
                voiceGestureHint = null
                return@LaunchedEffect
            }
            delay(FOLLOW_UP_AUTO_START_DELAY_MS)
            if (
                followUpPending &&
                followUpDeadlineMs > 0L &&
                !isTtsSpeaking &&
                !assistantSpeechPendingStart &&
                !assistantSpeechStarted &&
                voiceState == VoiceState.IDLE &&
                activeVoiceJob?.isActive != true
            ) {
                Timber.i(
                    "VOICE: auto-starting follow-up listening remainingMs=%d",
                    followUpDeadlineMs - System.currentTimeMillis(),
                )
                requestFollowUp()
            }
        }
    }

    /**
     * Release every resource the controller owns. Must be called from the host
     * Composable's DisposableEffect.onDispose so background work doesn't outlive
     * the Activity.
     */
    fun destroy() {
        voiceService.destroy()
        tts.destroy()
        runCatching { commandCoordinator?.destroy() }
            .onFailure { Timber.w(it, "VOICE: command coordinator destroy failed") }
        commandCoordinator = null
        runCatching { chatEngine?.destroy() }
            .onFailure { Timber.w(it, "VOICE: chat engine destroy failed") }
        chatEngine = null
        runCatching { geminiNanoService?.destroy() }
            .onFailure { Timber.w(it, "VOICE: gemini nano destroy failed") }
        geminiNanoService = null
        runCatching { geminiCloudService?.destroy() }
            .onFailure { Timber.w(it, "VOICE: gemini cloud destroy failed") }
        geminiCloudService = null
    }
}
