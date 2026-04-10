package dev.spatialfin.unified

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.compose.rememberNavController
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.xr.compose.material3.ExperimentalMaterial3XrApi
import androidx.xr.compose.spatial.Subspace
import androidx.xr.compose.subspace.SceneCoreEntity
import androidx.xr.compose.subspace.SpatialPanel
import androidx.xr.compose.subspace.layout.SubspaceModifier
import androidx.xr.compose.subspace.layout.height
import androidx.xr.compose.subspace.layout.offset
import androidx.xr.compose.subspace.layout.width
import androidx.xr.runtime.HandTrackingMode
import androidx.xr.runtime.Session
import androidx.xr.runtime.SessionCreateSuccess
import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Quaternion
import androidx.xr.runtime.math.Vector3
import androidx.xr.scenecore.GroupEntity
import androidx.xr.scenecore.MovableComponent
import androidx.xr.scenecore.scene
import dagger.Lazy
import dagger.hilt.android.AndroidEntryPoint
import dev.jdtech.jellyfin.core.llm.LlmModelManager
import dev.jdtech.jellyfin.player.session.voice.PlayerStateSnapshot
import dev.jdtech.jellyfin.player.session.voice.XrPlayerAction
import dev.jdtech.jellyfin.player.xr.voice.AssistantPreferences
import dev.jdtech.jellyfin.player.xr.voice.AssistantReply
import dev.jdtech.jellyfin.player.xr.voice.GeminiCloudService
import dev.jdtech.jellyfin.player.xr.voice.GeminiNanoService
import dev.jdtech.jellyfin.player.xr.voice.RecommendationContext
import dev.jdtech.jellyfin.player.xr.voice.SecondaryHandPinchDetector
import dev.jdtech.jellyfin.player.xr.voice.SmartChatEngine
import dev.jdtech.jellyfin.player.xr.voice.SpatialCommandCoordinator
import dev.jdtech.jellyfin.player.xr.voice.SpatialVoiceService
import dev.jdtech.jellyfin.player.xr.voice.SpatialVoiceSynthesizer
import dev.jdtech.jellyfin.player.xr.voice.VoiceControlOverlay
import dev.jdtech.jellyfin.player.xr.voice.VoiceState
import dev.jdtech.jellyfin.presentation.local.localVideoPermissions
import dev.jdtech.jellyfin.presentation.utils.LocalOfflineMode
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.settings.voice.VoiceTelemetryEntry
import dev.jdtech.jellyfin.settings.voice.VoiceTelemetryStore
import dev.jdtech.jellyfin.viewmodels.MainState
import dev.jdtech.jellyfin.viewmodels.MainViewModel
import dev.jdtech.jellyfin.work.SyncWorker
import dev.spatialfin.HomeRoute
import dev.spatialfin.NavigationRoot
import dev.spatialfin.beam.BeamNavigationRoot
import dev.spatialfin.beam.BeamTheme
import dev.spatialfin.presentation.theme.SpatialFinTheme
import dev.spatialfin.tv.TvNavigationRoot
import dev.spatialfin.tv.TvTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@OptIn(ExperimentalMaterial3XrApi::class)
@AndroidEntryPoint
class UnifiedMainActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_INITIAL_SEARCH_QUERY = "initial_search_query"
        private const val HAND_TRACKING_PERMISSION = "android.permission.HAND_TRACKING"
        private const val PERMISSIONS_PREFS = "startup_permissions"
        private const val STARTUP_PERMISSIONS_REQUESTED_KEY = "startup_permissions_requested"
        private const val XR_APP_PANEL_LEGACY_DEPTH_METERS = -5f
        private const val XR_APP_PANEL_V2_DEPTH_METERS = -6f
        private const val XR_APP_PANEL_V3_DEPTH_METERS = -9f
        private const val XR_APP_PANEL_DEFAULT_DEPTH_METERS = -11f
        private const val XR_APP_PANEL_POSE_VERSION_DEFAULT_DISTANCE = 4
        private const val XR_APP_PANEL_DEFAULT_POSE_EPSILON = 0.05f
        private const val XR_APP_PANEL_WIDTH_DP = 1792
        private const val XR_APP_PANEL_HEIGHT_DP = 1008
        private const val XR_APP_PANEL_WIDTH_METERS = 1.792f
        private const val XR_APP_PANEL_HEIGHT_METERS = 1.008f
    }

    private val deviceClass by lazy { detectDeviceClass() }
    private val viewModel: MainViewModel by viewModels()

    @Inject
    lateinit var appPreferences: AppPreferences

    @Inject
    lateinit var modelManager: Lazy<LlmModelManager>

    @Inject
    lateinit var repository: JellyfinRepository

    @Inject
    lateinit var voiceTelemetryStore: VoiceTelemetryStore

    private val llmModelManager: LlmModelManager by lazy(LazyThreadSafetyMode.NONE) {
        modelManager.get()
    }


    private val xrSessionState = mutableStateOf<Session?>(null)
    private val startupPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Lock landscape for TV and phone; XR adapts to the window freely.
        when (deviceClass) {
            DeviceClass.TV, DeviceClass.PHONE ->
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            DeviceClass.XR -> Unit
        }

        if (deviceClass == DeviceClass.XR) {
            window.colorMode = android.content.pm.ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT
            window.setBackgroundDrawable(
                android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
            )
        }

        val initialSearchQueryExtra = intent.getStringExtra(EXTRA_INITIAL_SEARCH_QUERY)

        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            val onboardingCompleted by rememberBooleanPreferenceState(
                appPreferences = appPreferences,
                preference = appPreferences.onboardingCompleted,
            )

            when (deviceClass) {
                DeviceClass.TV -> {
                    TvTheme {
                        TvNavigationRoot(
                            state = state,
                            appPreferences = appPreferences,
                            onReconnect = viewModel::reconnect,
                        )
                    }
                }

                DeviceClass.PHONE -> {
                    BeamTheme {
                        Surface(modifier = Modifier, color = Color.Transparent) {
                            BeamNavigationRoot(
                                state = state,
                                appPreferences = appPreferences,
                                onReconnect = viewModel::reconnect,
                            )
                        }
                    }
                }

                DeviceClass.XR -> {
                    val coroutineScope = rememberCoroutineScope()
                    val context = LocalContext.current

                    SpatialFinTheme(dynamicColor = state.isDynamicColors) {
                        val navController = rememberNavController()
                        val lifecycleOwner = LocalLifecycleOwner.current

                        LaunchedEffect(Unit) {
                            delay(350L)
                            requestStartupPermissionsIfNeeded()
                        }

                        // Create XR session (no space mode requested yet).
                        LaunchedEffect(Unit) {
                            if (xrSessionState.value == null) {
                                try {
                                    val result = Session.create(this@UnifiedMainActivity)
                                    if (result is SessionCreateSuccess) {
                                        xrSessionState.value = result.session
                                    }
                                } catch (e: Exception) {
                                    Timber.w(e, "XR session not available")
                                }
                            }
                        }

                        if (!onboardingCompleted || !state.isLoading) {
                            CompositionLocalProvider(LocalOfflineMode provides state.isOfflineMode) {
                                val session = xrSessionState.value
                                if (session != null) {
                                    XrContent(
                                        session = session,
                                        navController = navController,
                                        lifecycleOwner = lifecycleOwner,
                                        coroutineScope = coroutineScope,
                                        context = context,
                                        state = state,
                                        appPreferences = appPreferences,
                                        repository = repository,
                                        initialSearchQueryExtra = initialSearchQueryExtra,
                                        onboardingCompleted = onboardingCompleted,
                                        onFinishAffinity = { finishAffinity() },
                                        onFinish = { finish() },
                                    )
                                } else {
                                    // Session not yet available: render standard Compose UI.
                                    NavigationRoot(
                                        navController = navController,
                                        hasServers = state.hasServers,
                                        hasCurrentServer = state.hasCurrentServer,
                                        hasCurrentUser = state.hasCurrentUser,
                                        onboardingCompleted = onboardingCompleted,
                                        appPreferences = appPreferences,
                                        initialSearchQuery = initialSearchQueryExtra,
                                        onReconnect = viewModel::reconnect,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        scheduleUserDataSync()
    }

    // ---------------------------------------------------------------------------
    // XrContent — the full XR branch once a Session is available
    // ---------------------------------------------------------------------------

    @Composable
    private fun XrContent(
        session: Session,
        navController: androidx.navigation.NavHostController,
        lifecycleOwner: androidx.lifecycle.LifecycleOwner,
        coroutineScope: kotlinx.coroutines.CoroutineScope,
        context: android.content.Context,
        state: MainState,
        appPreferences: AppPreferences,
        repository: JellyfinRepository,
        initialSearchQueryExtra: String?,
        onboardingCompleted: Boolean,
        onFinishAffinity: () -> Unit,
        onFinish: () -> Unit,
    ) {
        // ------------------------------------------------------------------
        // Space controller — single source of truth for HOME / FULL
        // ------------------------------------------------------------------
        val spaceController = remember(session) {
            XrSpaceController(session, appPreferences)
        }
        val spaceUiState by spaceController.uiState.collectAsState()

        LaunchedEffect(session) {
            spaceController.applyLaunchPreference()
        }

        // ------------------------------------------------------------------
        // Voice setup (shared across both space modes)
        // ------------------------------------------------------------------
        val voiceService = remember(context) {
            SpatialVoiceService(context.applicationContext)
        }
        val tts = remember(context) { SpatialVoiceSynthesizer(context.applicationContext) }
        val isTtsSpeaking by tts.isSpeaking.collectAsState()
        var geminiNanoService by remember { mutableStateOf<GeminiNanoService?>(null) }
        var geminiCloudService by remember { mutableStateOf<GeminiCloudService?>(null) }
        var commandCoordinator by remember { mutableStateOf<SpatialCommandCoordinator?>(null) }
        var chatEngine by remember { mutableStateOf<SmartChatEngine?>(null) }
        val voiceState by voiceService.state.collectAsState()
        val partialTranscript by voiceService.partialTranscript.collectAsState()
        var voiceFeedback by remember { mutableStateOf<String?>(null) }
        val conversationHistory = remember { mutableStateListOf<Pair<String, String>>() }
        var recommendationContext by remember { mutableStateOf<RecommendationContext?>(null) }
        var voiceGestureHint by remember { mutableStateOf<String?>(null) }
        var voiceGestureArmingProgress by remember { mutableFloatStateOf(0f) }
        var voiceSearchQuery by remember { mutableStateOf(initialSearchQueryExtra) }
        var followUpPending by remember { mutableStateOf(false) }
        var followUpDeadlineMs by remember { mutableLongStateOf(0L) }
        val voiceControlEnabled = appPreferences.getValue(appPreferences.voiceControlEnabled)
        val voiceGestureHand =
            appPreferences.getValue(appPreferences.voiceGestureHand) ?: "left"
        val assistantSpokenReplies = appPreferences.getValue(appPreferences.voiceAssistantSpokenReplies)
        val assistantVoicePreference = appPreferences.getValue(appPreferences.voiceAssistantVoice) ?: "male"

        val pinchDetector = remember(session, voiceGestureHand) {
            SecondaryHandPinchDetector(session, this@UnifiedMainActivity, voiceGestureHand)
        }
        var hasAudioPermission by remember {
            mutableStateOf(
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
            )
        }
        var hasHandTrackingPermission by remember {
            mutableStateOf(
                ContextCompat.checkSelfPermission(
                    context, HAND_TRACKING_PERMISSION
                ) == PackageManager.PERMISSION_GRANTED
            )
        }

        val audioPermissionLauncher =
            androidx.activity.compose.rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { granted -> hasAudioPermission = granted }
        val handTrackingPermissionLauncher =
            androidx.activity.compose.rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { granted -> hasHandTrackingPermission = granted }

        data class HomeVoiceActionOutcome(
            val feedback: String,
            val assistantReply: AssistantReply? = null,
        )

        suspend fun handleVoiceAction(action: XrPlayerAction): HomeVoiceActionOutcome {
            return when (action) {
                is XrPlayerAction.Search -> {
                    voiceSearchQuery = action.query
                    HomeVoiceActionOutcome("Searching for ${action.query}")
                }
                is XrPlayerAction.ChatQuery -> {
                    voiceFeedback = "Thinking..."
                    val nano = geminiNanoService
                        ?: GeminiNanoService(context.applicationContext).also { geminiNanoService = it }
                    val cloud = geminiCloudService
                        ?: GeminiCloudService(context.applicationContext, appPreferences, repository)
                            .also { geminiCloudService = it }
                    val assistant = chatEngine
                        ?: SmartChatEngine(nano, cloud, appPreferences, llmModelManager, repository)
                            .also { it.initialize(); chatEngine = it }
                    val response =
                        assistant.query(
                            question = action.query,
                            playerState =
                                PlayerStateSnapshot(
                                    screenContext = dev.jdtech.jellyfin.player.session.voice.VoiceScreenContext.HOME,
                                    lastRecommendationQuery = recommendationContext?.query,
                                    lastRecommendationCount = recommendationContext?.items?.size ?: 0,
                                    lastRecommendationTitles = recommendationContext?.items?.take(3)?.map { it.name } ?: emptyList(),
                                ),
                            assistantPreferences =
                                AssistantPreferences(
                                    verbosity = appPreferences.getValue(appPreferences.voiceAssistantVerbosity),
                                    spoilerPolicy = appPreferences.getValue(appPreferences.voiceAssistantSpoilerPolicy),
                                    spokenRepliesEnabled = appPreferences.getValue(appPreferences.voiceAssistantSpokenReplies),
                                ),
                            onSearchQuery = { query -> repository.getSearchItems(query) },
                            conversationHistory = conversationHistory,
                            recommendationContext = recommendationContext,
                            onGetSuggestions = { repository.getSuggestions() },
                        )
                    response.recommendedItems
                        .takeIf { it.isNotEmpty() }
                        ?.let { recommendationContext = RecommendationContext(action.query, it) }
                    val feedback =
                        response.text?.also { reply ->
                            conversationHistory.add(action.query to reply)
                            if (conversationHistory.size > 6) conversationHistory.removeAt(0)
                            followUpPending = true
                            followUpDeadlineMs = System.currentTimeMillis() + 8_000L
                            voiceGestureHint = "Ask a follow-up"
                            if (assistantSpokenReplies && tts.canSpeak()) {
                                tts.speak(reply, null, assistantVoicePreference)
                            }
                        } ?: "Sorry, I couldn't process that."
                    HomeVoiceActionOutcome(feedback, response)
                }
                is XrPlayerAction.CloseApp -> {
                    onFinishAffinity()
                    HomeVoiceActionOutcome("Closing SpatialFin")
                }
                is XrPlayerAction.GoBack -> {
                    if (!navController.popBackStack()) onFinish()
                    HomeVoiceActionOutcome("Going back")
                }
                is XrPlayerAction.GoHome -> {
                    navController.navigate(HomeRoute) {
                        popUpTo(navController.graph.startDestinationId)
                        launchSingleTop = true
                    }
                    HomeVoiceActionOutcome("Returning home")
                }
                is XrPlayerAction.Unrecognized -> HomeVoiceActionOutcome("I didn't catch that: ${action.transcript}")
                else -> HomeVoiceActionOutcome("Command not available on home screen")
            }
        }

        fun requestVoiceCommand() {
            if (!voiceService.isAvailable()) {
                voiceFeedback = "Speech recognition unavailable"
                return
            }
            if (isTtsSpeaking) {
                tts.stop()
            }
            followUpPending = false
            if (!hasAudioPermission) {
                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                return
            }
            voiceService.startListening { transcript ->
                coroutineScope.launch {
                    val startedAtMs = System.currentTimeMillis()
                    val nano = geminiNanoService
                        ?: GeminiNanoService(context.applicationContext).also { geminiNanoService = it }
                    val cloud = geminiCloudService
                        ?: GeminiCloudService(context.applicationContext, appPreferences, repository)
                            .also { geminiCloudService = it }
                    val coordinator = commandCoordinator
                        ?: SpatialCommandCoordinator(
                            context.applicationContext,
                            nano,
                            cloud,
                            appPreferences,
                            llmModelManager,
                        )
                            .also { it.initialize(); commandCoordinator = it }
                    val snapshot =
                        PlayerStateSnapshot(
                            screenContext = dev.jdtech.jellyfin.player.session.voice.VoiceScreenContext.HOME,
                            lastRecommendationQuery = recommendationContext?.query,
                            lastRecommendationCount = recommendationContext?.items?.size ?: 0,
                            lastRecommendationTitles = recommendationContext?.items?.take(3)?.map { it.name } ?: emptyList(),
                        )
                    val parseResult = coordinator.parse(transcript, snapshot)
                    val outcome = handleVoiceAction(parseResult.action)
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
                }
            }
        }

        LaunchedEffect(session, hasHandTrackingPermission) {
            if (hasHandTrackingPermission) {
                runCatching {
                    session.configure(
                        session.config.copy(handTracking = HandTrackingMode.BOTH)
                    )
                }.onFailure { Timber.w(it, "VOICE: Hand tracking not available") }
            }
        }

        LaunchedEffect(pinchDetector, hasHandTrackingPermission, lifecycleOwner) {
            if (!hasHandTrackingPermission) return@LaunchedEffect
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                pinchDetector.gestureStates.collect { event ->
                    when (event) {
                        is SecondaryHandPinchDetector.GestureState.Arming -> {
                            voiceGestureArmingProgress = event.progress
                            voiceGestureHint = "Hold to talk"
                        }
                        SecondaryHandPinchDetector.GestureState.Started -> {
                            voiceGestureArmingProgress = 1f
                            voiceGestureHint = null
                            if (voiceState == VoiceState.IDLE) requestVoiceCommand()
                        }
                        SecondaryHandPinchDetector.GestureState.Ended -> {
                            voiceGestureArmingProgress = 0f
                            voiceGestureHint = null
                            voiceService.stopListening()
                        }
                        SecondaryHandPinchDetector.GestureState.Idle -> {
                            voiceGestureArmingProgress = 0f
                            if (voiceState != VoiceState.LISTENING) voiceGestureHint = null
                        }
                    }
                }
            }
        }

        LaunchedEffect(voiceFeedback, isTtsSpeaking) {
            val feedback = voiceFeedback ?: return@LaunchedEffect
            if (feedback != "Thinking..." && !isTtsSpeaking) {
                delay((feedback.length * 55L).coerceIn(4_000L, 10_000L))
                if (voiceFeedback == feedback && !isTtsSpeaking) {
                    voiceFeedback = null
                    voiceService.resetState()
                }
            }
        }

        LaunchedEffect(voiceState) {
            if (voiceState == VoiceState.ERROR) {
                delay(2_000L)
                if (voiceState == VoiceState.ERROR) {
                    voiceService.resetState()
                }
            }
        }

        LaunchedEffect(followUpPending, isTtsSpeaking, voiceState) {
            if (!followUpPending || isTtsSpeaking || voiceState != VoiceState.IDLE) return@LaunchedEffect
            val remaining = followUpDeadlineMs - System.currentTimeMillis()
            if (remaining <= 0L) {
                followUpPending = false
                voiceGestureHint = null
                return@LaunchedEffect
            }
            delay(600L)
            if (followUpPending && !isTtsSpeaking && voiceState == VoiceState.IDLE) {
                requestVoiceCommand()
            }
        }

        DisposableEffect(session) {
            onDispose {
                voiceService.destroy()
                tts.destroy()
                commandCoordinator?.destroy()
                chatEngine?.destroy()
            }
        }

        // ------------------------------------------------------------------
        // Mode-specific rendering
        // ------------------------------------------------------------------
        if (spaceUiState.mode == XrSpaceMode.FULL && spaceUiState.spatialUiAvailable) {
            FullSpaceContent(
                session = session,
                navController = navController,
                state = state,
                appPreferences = appPreferences,
                onboardingCompleted = onboardingCompleted,
                voiceSearchQuery = voiceSearchQuery,
                voiceState = voiceState,
                partialTranscript = partialTranscript,
                voiceFeedback = voiceFeedback,
                voiceGestureArmingProgress = voiceGestureArmingProgress,
                voiceGestureHint = voiceGestureHint,
                onReconnect = viewModel::reconnect,
                onEnterHomeSpace = { spaceController.enterHomeSpace() },
            )
        } else {
            HomeSpaceContent(
                navController = navController,
                state = state,
                appPreferences = appPreferences,
                onboardingCompleted = onboardingCompleted,
                voiceSearchQuery = voiceSearchQuery,
                voiceState = voiceState,
                partialTranscript = partialTranscript,
                voiceFeedback = voiceFeedback,
                voiceGestureArmingProgress = voiceGestureArmingProgress,
                voiceGestureHint = voiceGestureHint,
                onReconnect = viewModel::reconnect,
                onEnterFullSpace = { spaceController.enterFullSpace() },
            )
        }
    }

    // ---------------------------------------------------------------------------
    // Home Space path — standard adaptive Compose, no Subspace/SpatialPanel
    // ---------------------------------------------------------------------------

    @Composable
    private fun HomeSpaceContent(
        navController: androidx.navigation.NavHostController,
        state: MainState,
        appPreferences: AppPreferences,
        onboardingCompleted: Boolean,
        voiceSearchQuery: String?,
        voiceState: VoiceState,
        partialTranscript: String,
        voiceFeedback: String?,
        voiceGestureArmingProgress: Float,
        voiceGestureHint: String?,
        onReconnect: () -> Unit,
        onEnterFullSpace: () -> Unit,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            NavigationRoot(
                navController = navController,
                hasServers = state.hasServers,
                hasCurrentServer = state.hasCurrentServer,
                hasCurrentUser = state.hasCurrentUser,
                onboardingCompleted = onboardingCompleted,
                appPreferences = appPreferences,
                initialSearchQuery = voiceSearchQuery,
                onReconnect = onReconnect,
                xrSpaceMode = XrSpaceMode.HOME,
                onEnterFullSpace = onEnterFullSpace,
            )
            VoiceControlOverlay(
                state = voiceState,
                partialTranscript = partialTranscript,
                feedbackText = voiceFeedback,
                gestureArmingProgress = voiceGestureArmingProgress,
                gestureHint = voiceGestureHint,
            )
        }
    }

    // ---------------------------------------------------------------------------
    // Full Space path — existing spatialized XR UI with movable SpatialPanel
    // ---------------------------------------------------------------------------

    @Composable
    private fun FullSpaceContent(
        session: Session,
        navController: androidx.navigation.NavHostController,
        state: MainState,
        appPreferences: AppPreferences,
        onboardingCompleted: Boolean,
        voiceSearchQuery: String?,
        voiceState: VoiceState,
        partialTranscript: String,
        voiceFeedback: String?,
        voiceGestureArmingProgress: Float,
        voiceGestureHint: String?,
        onReconnect: () -> Unit,
        onEnterHomeSpace: () -> Unit,
    ) {
        val rootEntity = remember { mutableStateOf<GroupEntity?>(null) }
        val movableComponent = remember { mutableStateOf<MovableComponent?>(null) }
        var controlsVisible by remember { mutableStateOf(true) }
        var hideTimestamp by remember { mutableLongStateOf(System.currentTimeMillis()) }
        val latestRootEntity = rememberUpdatedState(rootEntity.value)

        LaunchedEffect(rootEntity.value) {
            val root = rootEntity.value ?: return@LaunchedEffect
            if (movableComponent.value == null) {
                val m = MovableComponent.createSystemMovable(session, false)
                m.size =
                    androidx.xr.runtime.math.FloatSize3d(
                        XR_APP_PANEL_WIDTH_METERS,
                        XR_APP_PANEL_HEIGHT_METERS,
                        0.1f,
                    )
                root.addComponent(m)
                movableComponent.value = m
                Timber.d("VOICE: Home movable permanently enabled")
            }
        }

        LaunchedEffect(controlsVisible, hideTimestamp) {
            if (controlsVisible) {
                delay(5000L)
                controlsVisible = false
            }
        }

        LaunchedEffect(rootEntity.value) {
            val root = rootEntity.value ?: return@LaunchedEffect
            while (true) {
                safeGetAppRootPose(root)?.let(::saveAppRootPose)
                delay(1_000L)
            }
        }

        DisposableEffect(session) {
            try {
                val root = GroupEntity.create(session, "MainScreenRoot", loadAppRootPose())
                rootEntity.value = root
            } catch (e: Exception) {
                Timber.w(e, "Failed to create movable root for main screen")
            }
            onDispose {
                latestRootEntity.value?.let { root ->
                    safeGetAppRootPose(root)?.let(::saveAppRootPose)
                }
                rootEntity.value?.dispose()
                rootEntity.value = null
            }
        }

        Subspace {
            val root = rootEntity.value
            if (root != null) {
                SceneCoreEntity(
                    factory = { root },
                    modifier = SubspaceModifier,
                ) {
                    SpatialPanel(
                        modifier = SubspaceModifier
                            .width(XR_APP_PANEL_WIDTH_DP.dp)
                            .height(XR_APP_PANEL_HEIGHT_DP.dp)
                            .offset(x = 0.dp, y = 0.dp, z = 0.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(Unit) {
                                    awaitPointerEventScope {
                                        while (true) {
                                            val event = awaitPointerEvent()
                                            if (
                                                event.type == PointerEventType.Enter ||
                                                event.type == PointerEventType.Move
                                            ) {
                                                controlsVisible = true
                                                hideTimestamp = System.currentTimeMillis()
                                            }
                                        }
                                    }
                                }
                        ) {
                            NavigationRoot(
                                navController = navController,
                                hasServers = state.hasServers,
                                hasCurrentServer = state.hasCurrentServer,
                                hasCurrentUser = state.hasCurrentUser,
                                onboardingCompleted = onboardingCompleted,
                                appPreferences = appPreferences,
                                initialSearchQuery = voiceSearchQuery,
                                onReconnect = onReconnect,
                                xrSpaceMode = XrSpaceMode.FULL,
                                onEnterHomeSpace = onEnterHomeSpace,
                            )
                            VoiceControlOverlay(
                                state = voiceState,
                                partialTranscript = partialTranscript,
                                feedbackText = voiceFeedback,
                                gestureArmingProgress = voiceGestureArmingProgress,
                                gestureHint = voiceGestureHint,
                            )
                        }
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private fun requestStartupPermissionsIfNeeded() {
        val prefs = getSharedPreferences(PERMISSIONS_PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(STARTUP_PERMISSIONS_REQUESTED_KEY, false)) return

        val missingPermissions =
            buildList {
                add(Manifest.permission.RECORD_AUDIO)
                add(Manifest.permission.CAMERA)
                add(HAND_TRACKING_PERMISSION)
                addAll(localVideoPermissions())
            }
            .distinct()
            .filter { permission ->
                ContextCompat.checkSelfPermission(this@UnifiedMainActivity, permission) !=
                    PackageManager.PERMISSION_GRANTED
            }

        prefs.edit().putBoolean(STARTUP_PERMISSIONS_REQUESTED_KEY, true).apply()
        if (missingPermissions.isNotEmpty()) {
            startupPermissionsLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    private fun scheduleUserDataSync() {
        val syncWorkRequest =
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

        WorkManager.getInstance(applicationContext)
            .beginUniqueWork("syncUserData", ExistingWorkPolicy.KEEP, syncWorkRequest)
            .enqueue()
    }

    private fun loadAppRootPose(): Pose {
        val poseVersion = appPreferences.getValue(appPreferences.xrAppPanelPoseVersion)
        if (poseVersion < 1) {
            return Pose(Vector3(0f, 0f, XR_APP_PANEL_DEFAULT_DEPTH_METERS), Quaternion.Identity)
        }
        val savedPose =
            Pose(
                Vector3(
                    appPreferences.getValue(appPreferences.xrAppPanelX),
                    appPreferences.getValue(appPreferences.xrAppPanelY),
                    appPreferences.getValue(appPreferences.xrAppPanelZ),
                ),
                Quaternion(
                    appPreferences.getValue(appPreferences.xrAppPanelRotX),
                    appPreferences.getValue(appPreferences.xrAppPanelRotY),
                    appPreferences.getValue(appPreferences.xrAppPanelRotZ),
                    appPreferences.getValue(appPreferences.xrAppPanelRotW),
                ),
            )
        if (poseVersion < XR_APP_PANEL_POSE_VERSION_DEFAULT_DISTANCE) {
            val migratedPose = migrateLegacyCenteredAppPose(savedPose)
            saveAppRootPose(migratedPose)
            return migratedPose
        }
        return savedPose
    }

    private fun saveAppRootPose(pose: Pose) {
        val translation = pose.translation
        val rotation = pose.rotation
        appPreferences.setValue(appPreferences.xrAppPanelX, translation.x)
        appPreferences.setValue(appPreferences.xrAppPanelY, translation.y)
        appPreferences.setValue(appPreferences.xrAppPanelZ, translation.z)
        appPreferences.setValue(appPreferences.xrAppPanelRotX, rotation.x)
        appPreferences.setValue(appPreferences.xrAppPanelRotY, rotation.y)
        appPreferences.setValue(appPreferences.xrAppPanelRotZ, rotation.z)
        appPreferences.setValue(appPreferences.xrAppPanelRotW, rotation.w)
        appPreferences.setValue(
            appPreferences.xrAppPanelPoseVersion,
            XR_APP_PANEL_POSE_VERSION_DEFAULT_DISTANCE,
        )
    }

    private fun safeGetAppRootPose(entity: GroupEntity): Pose? {
        return runCatching { entity.getPose() }.getOrNull()
    }

    private fun migrateLegacyCenteredAppPose(pose: Pose): Pose {
        val translation = pose.translation
        val rotation = pose.rotation
        val usesLegacyDefaultPosition =
            kotlin.math.abs(translation.x) <= XR_APP_PANEL_DEFAULT_POSE_EPSILON &&
                kotlin.math.abs(translation.y) <= XR_APP_PANEL_DEFAULT_POSE_EPSILON &&
                (kotlin.math.abs(translation.z - XR_APP_PANEL_LEGACY_DEPTH_METERS) <=
                    XR_APP_PANEL_DEFAULT_POSE_EPSILON ||
                    kotlin.math.abs(translation.z - XR_APP_PANEL_V2_DEPTH_METERS) <=
                        XR_APP_PANEL_DEFAULT_POSE_EPSILON ||
                    kotlin.math.abs(translation.z - XR_APP_PANEL_V3_DEPTH_METERS) <=
                        XR_APP_PANEL_DEFAULT_POSE_EPSILON)
        val usesIdentityRotation =
            kotlin.math.abs(rotation.x) <= XR_APP_PANEL_DEFAULT_POSE_EPSILON &&
                kotlin.math.abs(rotation.y) <= XR_APP_PANEL_DEFAULT_POSE_EPSILON &&
                kotlin.math.abs(rotation.z) <= XR_APP_PANEL_DEFAULT_POSE_EPSILON &&
                kotlin.math.abs(rotation.w - 1f) <= XR_APP_PANEL_DEFAULT_POSE_EPSILON

        return if (usesLegacyDefaultPosition && usesIdentityRotation) {
            Pose(Vector3(0f, 0f, XR_APP_PANEL_DEFAULT_DEPTH_METERS), Quaternion.Identity)
        } else {
            pose
        }
    }
}

@Composable
private fun rememberBooleanPreferenceState(
    appPreferences: AppPreferences,
    preference: dev.jdtech.jellyfin.settings.domain.models.Preference<Boolean>,
): androidx.compose.runtime.State<Boolean> {
    val preferenceState =
        remember(appPreferences, preference) {
            mutableStateOf(appPreferences.getValue(preference))
        }

    DisposableEffect(appPreferences, preference) {
        val listener =
            android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == preference.backendName) {
                    preferenceState.value = appPreferences.getValue(preference)
                }
            }
        appPreferences.sharedPreferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            appPreferences.sharedPreferences.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    return preferenceState
}
