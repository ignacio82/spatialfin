package dev.spatialfin.unified

import android.Manifest
import android.app.SearchManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.view.Display
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.ui.draw.alpha
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.xr.scenecore.GroupEntity
import androidx.xr.scenecore.MovableComponent
import androidx.xr.scenecore.scene
import dagger.Lazy
import dagger.hilt.android.AndroidEntryPoint
import dev.jdtech.jellyfin.core.llm.LlmModelManager
import dev.jdtech.jellyfin.models.SpatialFinEpisode
import dev.jdtech.jellyfin.models.SpatialFinItem
import dev.jdtech.jellyfin.models.SpatialFinMovie
import dev.jdtech.jellyfin.models.SpatialFinSeason
import dev.jdtech.jellyfin.models.SpatialFinShow
import dev.jdtech.jellyfin.player.xr.XrPlayerActivity
import dev.jdtech.jellyfin.player.xr.voice.SecondaryHandPinchDetector
import dev.jdtech.jellyfin.player.xr.voice.VoiceControlOverlay
import dev.jdtech.jellyfin.player.xr.voice.RecommendationContext
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
import dev.spatialfin.EpisodeRoute
import dev.spatialfin.HomeRoute
import dev.spatialfin.MediaRoute
import dev.spatialfin.MovieRoute
import dev.spatialfin.NavigationRoot
import dev.spatialfin.SeasonRoute
import dev.spatialfin.ShowRoute
import dev.spatialfin.beam.BeamNavigationRoot
import dev.spatialfin.beam.BeamTheme
import dev.spatialfin.presentation.theme.SpatialFinTheme
import dev.spatialfin.unified.applock.AppLockManager
import dev.spatialfin.unified.applock.AppLockScreen
import dev.spatialfin.tv.TvNavigationRoot
import dev.spatialfin.tv.TvTheme
import kotlinx.coroutines.delay
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
        private const val XR_APP_PANEL_WIDTH_DP = 1792
        private const val XR_APP_PANEL_HEIGHT_DP = 1008
        private const val XR_APP_PANEL_WIDTH_METERS = 1.792f
        private const val XR_APP_PANEL_HEIGHT_METERS = 1.008f
    }

    private val deviceClass by lazy { detectDeviceClass() }
    private val capabilities by lazy { DeviceClassCapabilities(deviceClass) }
    private val viewModel: MainViewModel by viewModels()

    @Inject
    lateinit var appPreferences: AppPreferences

    @Inject
    lateinit var modelManager: Lazy<LlmModelManager>

    @Inject
    lateinit var repository: JellyfinRepository

    @Inject
    lateinit var voiceTelemetryStore: VoiceTelemetryStore

    @Inject
    lateinit var appLockManager: AppLockManager

    private val llmModelManager: LlmModelManager by lazy(LazyThreadSafetyMode.NONE) {
        modelManager.get()
    }


    private val xrSessionState = mutableStateOf<Session?>(null)
    private val startupPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    // Listens for external-display hot-plug (Beam Pro ↔ Xreal glasses).
    // When glasses attach we lock landscape so the virtual display orientation
    // matches what the app is rendering; when they detach we hand orientation
    // back to the system so the handheld rotates normally.
    private var displayListener: DisplayManager.DisplayListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // TV is always landscape. XR adapts to the window freely. Phone
        // (including Beam Pro handheld) only locks landscape when an external
        // display is attached (Xreal glasses); otherwise we follow the user's
        // rotation preference so held-vertical → portrait works.
        when (deviceClass) {
            DeviceClass.TV ->
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            DeviceClass.PHONE -> {
                applyPhoneOrientation()
                val dm = getSystemService(DisplayManager::class.java)
                if (dm != null) {
                    val listener = object : DisplayManager.DisplayListener {
                        override fun onDisplayAdded(displayId: Int) = applyPhoneOrientation()
                        override fun onDisplayRemoved(displayId: Int) = applyPhoneOrientation()
                        override fun onDisplayChanged(displayId: Int) = Unit
                    }
                    dm.registerDisplayListener(listener, null)
                    displayListener = listener
                }
            }
            DeviceClass.XR -> Unit
        }

        if (capabilities.isXr) {
            window.colorMode = android.content.pm.ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT
            window.setBackgroundDrawable(
                android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
            )
        }

        // `ACTION_SEARCH` lands here when the Google TV Launcher's search bar
        // or the Assistant hands off a voice query (see the TV-flavor manifest
        // overlay + `res/xml/tv_searchable.xml`). We treat it as the same signal
        // as the companion-import / voice-follow-up `EXTRA_INITIAL_SEARCH_QUERY`,
        // which downstream nav graphs already know how to consume.
        val actionSearchQuery =
            if (Intent.ACTION_SEARCH == intent.action) {
                intent.getStringExtra(SearchManager.QUERY)
            } else {
                null
            }
        val initialSearchQueryExtra =
            intent.getStringExtra(EXTRA_INITIAL_SEARCH_QUERY) ?: actionSearchQuery

        appLockManager.refreshState()

        setContent {
            val lockState by appLockManager.lockState.collectAsStateWithLifecycle()
            if (lockState == AppLockManager.LockState.LOCKED) {
                AppLockScreen(lockManager = appLockManager)
                return@setContent
            }
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
                            initialSearchQuery = initialSearchQueryExtra,
                        )
                    }
                }

                DeviceClass.PHONE -> {
                    BeamTheme {
                        Surface(modifier = Modifier, color = Color.Transparent) {
                            BeamNavigationRoot(
                                state = state,
                                appPreferences = appPreferences,
                                repository = repository,
                                llmModelManager = llmModelManager,
                                voiceTelemetryStore = voiceTelemetryStore,
                                onReconnect = viewModel::reconnect,
                                onFinishApp = { finishAndRemoveTask() },
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

    override fun onStop() {
        super.onStop()
        // Skip re-locking on configuration changes (rotation, theme, etc.);
        // only arm the lock when the user genuinely leaves the app.
        if (!isChangingConfigurations) {
            appLockManager.onAppBackgrounded()
        }
    }

    override fun onResume() {
        super.onResume()
        appLockManager.refreshState()
    }

    override fun onDestroy() {
        super.onDestroy()
        displayListener?.let { listener ->
            getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(listener)
        }
        displayListener = null
    }

    private fun applyPhoneOrientation() {
        if (!capabilities.isPhone) return
        val dm = getSystemService(DisplayManager::class.java)
        val hasExternal = dm?.displays?.any { it.displayId != Display.DEFAULT_DISPLAY } == true
        requestedOrientation = if (hasExternal) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_USER
        }
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
        val voiceController = remember(context) {
            HomeVoiceController(
                applicationContext = context.applicationContext,
                appPreferences = appPreferences,
                repository = repository,
                llmModelManager = llmModelManager,
                voiceTelemetryStore = voiceTelemetryStore,
            )
        }
        LaunchedEffect(initialSearchQueryExtra) {
            voiceController.setInitialSearchQuery(initialSearchQueryExtra)
        }
        val voiceState by voiceController.voiceService.state.collectAsState()
        val partialTranscript by voiceController.voiceService.partialTranscript.collectAsState()
        val voiceMicLevel by voiceController.voiceService.micLevel.collectAsState()
        val isTtsSpeaking by voiceController.tts.isSpeaking.collectAsState()
        // Reactive prefs: observe SharedPreferences changes so toggling a setting
        // from the Settings screen recomposes the Home Space voice UI immediately
        // instead of requiring the Activity to be recreated.
        val voiceControlEnabled by appPreferences.rememberPrefState(appPreferences.voiceControlEnabled) {
            appPreferences.getValue(appPreferences.voiceControlEnabled)
        }
        val voiceGestureHand by appPreferences.rememberPrefState(appPreferences.voiceGestureHand) {
            appPreferences.getValue(appPreferences.voiceGestureHand) ?: "left"
        }
        val assistantSpokenReplies by appPreferences.rememberPrefState(appPreferences.voiceAssistantSpokenReplies) {
            appPreferences.getValue(appPreferences.voiceAssistantSpokenReplies)
        }
        val assistantVoiceName by appPreferences.rememberPrefState(appPreferences.voiceAssistantVoice) {
            appPreferences.getValue(appPreferences.voiceAssistantVoice)
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

        val pinchDetector = remember(session, voiceGestureHand) {
            SecondaryHandPinchDetector(
                session = session,
                activity = this@UnifiedMainActivity,
                preferredHand = voiceGestureHand,
                shouldDetectActivation = {
                    voiceControlEnabled &&
                        hasHandTrackingPermission &&
                        hasAudioPermission &&
                        voiceController.voiceService.isAvailable() &&
                        !voiceController.isVoiceTurnBusy(voiceState, isTtsSpeaking)
                },
                shouldDetectInterrupt = {
                    voiceControlEnabled &&
                        hasHandTrackingPermission &&
                        voiceController.isVoiceTurnBusy(voiceState, isTtsSpeaking)
                },
            )
        }

        // Navigation surface the controller invokes when a parsed voice intent
        // resolves to a media item or a screen jump. Closure captures the latest
        // navController/context so per-call lookups stay current.
        val navigation = remember(navController, context) {
            object : HomeVoiceNavigation {
                private fun navigateToItem(item: SpatialFinItem): Boolean {
                    if (navController.currentBackStackEntry?.lifecycle?.currentState !=
                        Lifecycle.State.RESUMED
                    ) {
                        return false
                    }
                    return when (item) {
                        is SpatialFinMovie -> {
                            navController.navigate(MovieRoute(movieId = item.id.toString()))
                            true
                        }
                        is SpatialFinShow -> {
                            navController.navigate(ShowRoute(showId = item.id.toString()))
                            true
                        }
                        is SpatialFinSeason -> {
                            navController.navigate(SeasonRoute(seasonId = item.id.toString()))
                            true
                        }
                        is SpatialFinEpisode -> {
                            navController.navigate(EpisodeRoute(episodeId = item.id.toString()))
                            true
                        }
                        else -> {
                            voiceController.voiceSearchQuery = item.name
                            navController.navigate(MediaRoute) {
                                popUpTo(navController.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                            true
                        }
                    }
                }

                override fun launchItem(item: SpatialFinItem): Boolean {
                    val playbackIntent = XrPlayerActivity.createIntentForItem(context, item)
                    if (playbackIntent != null) {
                        if (runCatching { context.startActivity(playbackIntent) }.isSuccess) {
                            return true
                        }
                    }
                    return navigateToItem(item)
                }

                override fun goHome() {
                    navController.navigate(HomeRoute) {
                        popUpTo(navController.graph.startDestinationId)
                        launchSingleTop = true
                    }
                }

                override fun goBack() {
                    if (!navController.popBackStack()) onFinish()
                }

                override fun closeApp() {
                    onFinishAffinity()
                }
            }
        }

        // Captured environment for follow-up auto-listen; rememberUpdatedState keeps
        // the latest values without re-keying the gesture coroutine collector.
        val latestHasAudioPermission = rememberUpdatedState(hasAudioPermission)
        val latestAssistantSpoken = rememberUpdatedState(assistantSpokenReplies)
        val latestAssistantVoice = rememberUpdatedState(assistantVoiceName)
        val onAudioPermissionMissing = rememberUpdatedState({
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        })

        fun startVoiceCommand(source: String) {
            voiceController.requestVoiceCommand(
                scope = coroutineScope,
                source = source,
                currentVoiceState = voiceState,
                currentTtsSpeaking = isTtsSpeaking,
                hasAudioPermission = latestHasAudioPermission.value,
                assistantSpokenReplies = latestAssistantSpoken.value,
                assistantVoiceName = latestAssistantVoice.value,
                navigation = navigation,
                onAudioPermissionMissing = { onAudioPermissionMissing.value() },
            )
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
                            voiceController.onGestureUpdate(event.progress, event.hint)
                        }
                        is SecondaryHandPinchDetector.GestureState.Started -> {
                            voiceController.onGestureUpdate(1f, null)
                            when (event.gestureType) {
                                SecondaryHandPinchDetector.GestureType.ACTIVATE ->
                                    startVoiceCommand("manual")
                                SecondaryHandPinchDetector.GestureType.INTERRUPT ->
                                    voiceController.interruptVoiceCommand(
                                        scope = coroutineScope,
                                        reason = "fist-gesture",
                                        currentVoiceState = voiceState,
                                        currentTtsSpeaking = isTtsSpeaking,
                                        requestFollowUp = { startVoiceCommand("follow-up-interrupt") },
                                    )
                            }
                        }
                        is SecondaryHandPinchDetector.GestureState.Ended ->
                            voiceController.clearGestureHintIfIdle(voiceState)
                        SecondaryHandPinchDetector.GestureState.Idle ->
                            voiceController.clearGestureHintIfIdle(voiceState)
                    }
                }
            }
        }

        // The controller owns the rest of the voice state machine (feedback timeout,
        // ERROR auto-reset, TTS bookkeeping, follow-up auto-start).
        voiceController.RegisterEffects(
            requestFollowUp = { startVoiceCommand("follow-up-auto") },
        )

        DisposableEffect(voiceController) {
            onDispose { voiceController.destroy() }
        }

        // ------------------------------------------------------------------
        // Mode-specific rendering
        // ------------------------------------------------------------------
        val recommendationContext = voiceController.recommendationContext
        val onSelectRecommendation: (Int, SpatialFinItem) -> Unit = { _, item ->
            if (navigation.launchItem(item)) {
                voiceController.clearRecommendationContext()
            }
        }
        val onDismissRecommendation: () -> Unit = { voiceController.clearRecommendationContext() }

        if (spaceUiState.mode == XrSpaceMode.FULL && spaceUiState.spatialUiAvailable) {
            FullSpaceContent(
                session = session,
                navController = navController,
                state = state,
                appPreferences = appPreferences,
                onboardingCompleted = onboardingCompleted,
                voiceSearchQuery = voiceController.voiceSearchQuery,
                voiceState = voiceState,
                partialTranscript = partialTranscript,
                voiceFeedback = voiceController.voiceFeedback,
                voiceGestureArmingProgress = voiceController.voiceGestureArmingProgress,
                voiceGestureHint = voiceController.voiceGestureHint,
                voiceMicLevel = voiceMicLevel,
                transitioning = spaceUiState.transitioning,
                recommendationContext = recommendationContext,
                onSelectRecommendation = onSelectRecommendation,
                onDismissRecommendation = onDismissRecommendation,
                onReconnect = viewModel::reconnect,
                onEnterHomeSpace = { spaceController.enterHomeSpace() },
            )
        } else {
            HomeSpaceContent(
                navController = navController,
                state = state,
                appPreferences = appPreferences,
                onboardingCompleted = onboardingCompleted,
                voiceSearchQuery = voiceController.voiceSearchQuery,
                voiceState = voiceState,
                partialTranscript = partialTranscript,
                voiceFeedback = voiceController.voiceFeedback,
                voiceGestureArmingProgress = voiceController.voiceGestureArmingProgress,
                voiceGestureHint = voiceController.voiceGestureHint,
                voiceMicLevel = voiceMicLevel,
                transitioning = spaceUiState.transitioning,
                recommendationContext = recommendationContext,
                onSelectRecommendation = onSelectRecommendation,
                onDismissRecommendation = onDismissRecommendation,
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
        voiceMicLevel: Float,
        transitioning: Boolean,
        recommendationContext: RecommendationContext?,
        onSelectRecommendation: (Int, SpatialFinItem) -> Unit,
        onDismissRecommendation: () -> Unit,
        onReconnect: () -> Unit,
        onEnterFullSpace: () -> Unit,
    ) {
        val surfaceAlpha by animateFloatAsState(
            targetValue = if (transitioning) 0f else 1f,
            animationSpec = tween(durationMillis = 260),
            label = "home-space-fade",
        )
        Box(modifier = Modifier.fillMaxSize().alpha(surfaceAlpha)) {
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
                micLevel = voiceMicLevel,
            )
            recommendationContext?.let { ctx ->
                if (ctx.items.size > 1) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 96.dp),
                        contentAlignment = androidx.compose.ui.Alignment.TopCenter,
                    ) {
                        DisambiguationCarousel(
                            query = ctx.query,
                            items = ctx.items,
                            onSelect = onSelectRecommendation,
                            onDismiss = onDismissRecommendation,
                        )
                    }
                }
            }
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
        voiceMicLevel: Float,
        @Suppress("UNUSED_PARAMETER") transitioning: Boolean,
        recommendationContext: RecommendationContext?,
        onSelectRecommendation: (Int, SpatialFinItem) -> Unit,
        onDismissRecommendation: () -> Unit,
        onReconnect: () -> Unit,
        onEnterHomeSpace: () -> Unit,
    ) {
        // Full Space entry deliberately doesn't animate alpha on its inner Box:
        // applying Modifier.alpha to a child of an XR SpatialPanel raced the
        // panel teardown during Full→Home transitions on device. Only the
        // HomeSpaceContent fades in; Full Space entry snaps (still bearable,
        // since the XR compositor does its own brief cross-fade on mode switch).
        val poseController = remember(appPreferences) { PanelPoseController(appPreferences) }
        val rootEntity = remember { mutableStateOf<GroupEntity?>(null) }
        val movableComponent = remember { mutableStateOf<MovableComponent?>(null) }
        var controlsVisible by remember { mutableStateOf(true) }
        var hideTimestamp by remember { mutableLongStateOf(System.currentTimeMillis()) }
        val latestRootEntity = rememberUpdatedState(rootEntity.value)

        LaunchedEffect(rootEntity.value) {
            val root = rootEntity.value ?: return@LaunchedEffect
            if (movableComponent.value == null) {
                val m = MovableComponent.createSystemMovable(session, true)
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
            poseController.trackEntityPose(root)
        }

        DisposableEffect(session) {
            try {
                val root = GroupEntity.create(session, "MainScreenRoot", poseController.loadPose())
                rootEntity.value = root
            } catch (e: Exception) {
                Timber.w(e, "Failed to create movable root for main screen")
            }
            onDispose {
                latestRootEntity.value?.let { root ->
                    poseController.readEntityPose(root)?.let(poseController::savePose)
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
                                micLevel = voiceMicLevel,
                            )
                            recommendationContext?.let { ctx ->
                                if (ctx.items.size > 1) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(top = 80.dp),
                                        contentAlignment = androidx.compose.ui.Alignment.TopCenter,
                                    ) {
                                        DisambiguationCarousel(
                                            query = ctx.query,
                                            items = ctx.items,
                                            onSelect = onSelectRecommendation,
                                            onDismiss = onDismissRecommendation,
                                        )
                                    }
                                }
                            }
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

}

/**
 * Generic version of [rememberBooleanPreferenceState] that re-reads any preference
 * whenever its backing SharedPreferences key changes. Callers supply a [read]
 * lambda so this doesn't depend on the inline/reified `getValue` overload.
 */
@Composable
private fun <T> AppPreferences.rememberPrefState(
    preference: dev.jdtech.jellyfin.settings.domain.models.Preference<*>,
    read: () -> T,
): androidx.compose.runtime.State<T> {
    val state = remember(this, preference) { mutableStateOf(read()) }
    DisposableEffect(this, preference) {
        val listener =
            android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == preference.backendName) {
                    state.value = read()
                }
            }
        sharedPreferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            sharedPreferences.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }
    return state
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
