package dev.spatialfin

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import androidx.navigation.compose.rememberNavController
import androidx.core.content.ContextCompat
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
import androidx.xr.runtime.Session
import androidx.xr.runtime.SessionCreateSuccess
import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Quaternion
import androidx.xr.runtime.math.Vector3
import androidx.xr.scenecore.GroupEntity
import androidx.xr.scenecore.MovableComponent
import androidx.xr.scenecore.scene
import dagger.hilt.android.AndroidEntryPoint
import dev.spatialfin.presentation.theme.SpatialFinTheme
import dev.jdtech.jellyfin.presentation.utils.LocalOfflineMode
import dev.jdtech.jellyfin.presentation.local.localVideoPermission
import dev.jdtech.jellyfin.viewmodels.MainViewModel
import dev.jdtech.jellyfin.work.SyncWorker
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import kotlinx.coroutines.delay
import timber.log.Timber

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import dev.jdtech.jellyfin.player.xr.voice.GeminiNanoService
import dev.jdtech.jellyfin.player.xr.voice.GeminiCloudService
import dev.jdtech.jellyfin.player.xr.voice.SpatialCommandCoordinator
import dev.jdtech.jellyfin.player.xr.voice.SpatialVoiceService
import dev.jdtech.jellyfin.player.xr.voice.SecondaryHandPinchDetector
import dev.jdtech.jellyfin.player.xr.voice.VoiceControlOverlay
import dev.jdtech.jellyfin.player.xr.voice.VoiceState
import dev.jdtech.jellyfin.player.session.voice.XrPlayerAction
import androidx.xr.runtime.HandTrackingMode
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import javax.inject.Inject

@OptIn(ExperimentalMaterial3XrApi::class)
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_INITIAL_SEARCH_QUERY = "initial_search_query"
        private const val HAND_TRACKING_PERMISSION = "android.permission.HAND_TRACKING"
        private const val PERMISSIONS_PREFS = "startup_permissions"
        private const val STARTUP_PERMISSIONS_REQUESTED_KEY = "startup_permissions_requested"
    }

    private val viewModel: MainViewModel by viewModels()
    @Inject lateinit var appPreferences: AppPreferences
    private val xrSessionState = mutableStateOf<Session?>(null)
    private val startupPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Enable wide color gamut for HDR support
        window.colorMode = android.content.pm.ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT

        val onboardingCompleted = appPreferences.getValue(appPreferences.onboardingCompleted)

        // Ensure window is transparent so we can see the XR scene behind the UI
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))

        val initialSearchQueryExtra = intent.getStringExtra(EXTRA_INITIAL_SEARCH_QUERY)

        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            val coroutineScope = rememberCoroutineScope()
            val context = androidx.compose.ui.platform.LocalContext.current

            SpatialFinTheme(dynamicColor = state.isDynamicColors) {
                val navController = rememberNavController()
                val lifecycleOwner = LocalLifecycleOwner.current
                LaunchedEffect(Unit) {
                    // Let the first frame render before permission work starts.
                    delay(350L)
                    requestStartupPermissionsIfNeeded()
                }
                LaunchedEffect(Unit) {
                    if (xrSessionState.value == null) {
                        try {
                            val result = Session.create(this@MainActivity)
                            if (result is SessionCreateSuccess) {
                                xrSessionState.value = result.session

                                try {
                                    val capabilities = xrSessionState.value?.scene?.spatialCapabilities
                                    if (capabilities?.contains(androidx.xr.scenecore.SpatialCapability.SPATIAL_3D_CONTENT) == true) {
                                        xrSessionState.value?.scene?.requestFullSpaceMode()
                                    }
                                } catch (e: Exception) {
                                    Timber.w(e, "Failed to request full space mode")
                                }
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
                            // --- Voice and Gesture state ---
                            val voiceService = remember(context) { SpatialVoiceService(context.applicationContext) }
                            var geminiNanoService by remember { mutableStateOf<GeminiNanoService?>(null) }
                            var geminiCloudService by remember { mutableStateOf<GeminiCloudService?>(null) }
                            var commandCoordinator by remember { mutableStateOf<SpatialCommandCoordinator?>(null) }
                            val voiceState by voiceService.state.collectAsState()
                            val partialTranscript by voiceService.partialTranscript.collectAsState()
                            var voiceFeedback by remember { mutableStateOf<String?>(null) }
                            var voiceGestureHint by remember { mutableStateOf<String?>(null) }
                            var voiceGestureArmingProgress by remember { mutableFloatStateOf(0f) }
                            var voiceSearchQuery by remember { mutableStateOf(initialSearchQueryExtra) }
                            val voiceControlEnabled = appPreferences.getValue(appPreferences.voiceControlEnabled)
                            val voiceGestureHand = appPreferences.getValue(appPreferences.voiceGestureHand) ?: "left"

                            val pinchDetector = remember(session, voiceGestureHand) {
                                SecondaryHandPinchDetector(session, this@MainActivity, voiceGestureHand)
                            }
                            var hasAudioPermission by remember {
                                mutableStateOf(
                                    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                                        PackageManager.PERMISSION_GRANTED
                                )
                            }
                            var hasHandTrackingPermission by remember {
                                mutableStateOf(
                                    ContextCompat.checkSelfPermission(context, HAND_TRACKING_PERMISSION) ==
                                        PackageManager.PERMISSION_GRANTED
                                )
                            }

                            val audioPermissionLauncher =
                                rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                                    hasAudioPermission = granted
                                }
                            val handTrackingPermissionLauncher =
                                rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                                    hasHandTrackingPermission = granted
                                }

                            fun handleVoiceAction(action: XrPlayerAction): String {
                                return when (action) {
                                    is XrPlayerAction.Search -> {
                                        voiceSearchQuery = action.query
                                        "Searching for ${action.query}"
                                    }
                                    is XrPlayerAction.CloseApp -> {
                                        finishAffinity()
                                        "Closing SpatialFin"
                                    }
                                    is XrPlayerAction.GoBack -> {
                                        if (!navController.popBackStack()) {
                                            finish()
                                        }
                                        "Going back"
                                    }
                                    is XrPlayerAction.GoHome -> {
                                        navController.navigate(HomeRoute) {
                                            popUpTo(navController.graph.startDestinationId)
                                            launchSingleTop = true
                                        }
                                        "Returning home"
                                    }
                                    is XrPlayerAction.Unrecognized -> "I didn't catch that: ${action.transcript}"
                                    else -> "Command not available on home screen"
                                }
                            }

                            fun requestVoiceCommand() {
                                if (!voiceService.isAvailable()) {
                                    voiceFeedback = "Speech recognition unavailable"
                                    return
                                }
                                if (!hasAudioPermission) {
                                    audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    return
                                }
                                
                                voiceService.startListening { transcript ->
                                    coroutineScope.launch {
                                        val nanoService =
                                            geminiNanoService
                                                ?: GeminiNanoService(context.applicationContext)
                                                    .also { geminiNanoService = it }
                                        val cloudService =
                                            geminiCloudService
                                                ?: GeminiCloudService(
                                                    context.applicationContext,
                                                    appPreferences,
                                                ).also { geminiCloudService = it }
                                        val coordinator =
                                            commandCoordinator
                                                ?: SpatialCommandCoordinator(
                                                    context.applicationContext,
                                                    nanoService,
                                                    cloudService,
                                                ).also {
                                                    it.initialize()
                                                    commandCoordinator = it
                                                }
                                        val parseResult = coordinator.parse(transcript)
                                        voiceFeedback = handleVoiceAction(parseResult.action)
                                    }
                                }
                            }

                            LaunchedEffect(session, hasHandTrackingPermission) {
                                if (hasHandTrackingPermission) {
                                    runCatching {
                                        session.configure(session.config.copy(handTracking = HandTrackingMode.BOTH))
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
                                                if (voiceState == VoiceState.IDLE) {
                                                    requestVoiceCommand()
                                                }
                                            }
                                            SecondaryHandPinchDetector.GestureState.Ended -> {
                                                voiceGestureArmingProgress = 0f
                                                voiceGestureHint = null
                                                voiceService.stopListening()
                                            }
                                            SecondaryHandPinchDetector.GestureState.Idle -> {
                                                voiceGestureArmingProgress = 0f
                                                if (voiceState != VoiceState.LISTENING) {
                                                    voiceGestureHint = null
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            LaunchedEffect(voiceFeedback) {
                                if (voiceFeedback != null) {
                                    delay(2000L)
                                    voiceFeedback = null
                                    voiceService.resetState()
                                }
                            }

                            // Create a movable GroupEntity so the user can reposition the main screen
                            val rootEntity = remember { mutableStateOf<GroupEntity?>(null) }
                            val movableComponent = remember { mutableStateOf<MovableComponent?>(null) }
                            var controlsVisible by remember { mutableStateOf(true) }
                            var hideTimestamp by remember { mutableLongStateOf(System.currentTimeMillis()) }
                            val defaultRootPose = remember { Pose(Vector3(0f, 0f, -3f), Quaternion.Identity) }
                            val latestRootEntity = rememberUpdatedState(rootEntity.value)

                            LaunchedEffect(controlsVisible, rootEntity.value) {
                                val root = rootEntity.value ?: return@LaunchedEffect
                                if (controlsVisible) {
                                    if (movableComponent.value == null) {
                                        val m = MovableComponent.createSystemMovable(session, false)
                                        root.addComponent(m)
                                        movableComponent.value = m
                                        Timber.d("VOICE: Home movable enabled controlsVisible=%b", controlsVisible)
                                    }
                                } else {
                                    movableComponent.value?.let {
                                        val latestPose = safeGetAppRootPose(root)
                                        latestPose?.let(::saveAppRootPose)
                                        root.removeComponent(it)
                                        latestPose?.let { pose ->
                                            runCatching { root.setPose(pose) }
                                        }
                                        movableComponent.value = null
                                        Timber.d(
                                            "VOICE: Home movable disabled controlsVisible=%b",
                                            controlsVisible,
                                        )
                                    }
                                }
                            }

                            // Auto-hide handles after 5 seconds of inactivity
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
                                    val root = GroupEntity.create(
                                        session,
                                        "MainScreenRoot",
                                        loadAppRootPose(),
                                    )
                                    rootEntity.value = root
                                } catch (e: Exception) {
                                    Timber.w(e, "Failed to create movable root for main screen")
                                }
                                onDispose {
                                    latestRootEntity.value?.let { root ->
                                        safeGetAppRootPose(root)?.let(::saveAppRootPose)
                                    }
                                    voiceService.destroy()
                                    commandCoordinator?.destroy()
                                    rootEntity.value?.dispose()
                                    rootEntity.value = null
                                }
                            }

                            Subspace {
                                val root = rootEntity.value
                                if (root != null) {
                                    SceneCoreEntity(factory = { root }, modifier = SubspaceModifier) {
                                        SpatialPanel(
                                            modifier = SubspaceModifier
                                                .width(1800.dp)
                                                .height(1200.dp)
                                                .offset(x = 0.dp, y = 0.dp, z = 0.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .pointerInput(Unit) {
                                                        awaitPointerEventScope {
                                                            while (true) {
                                                                val event = awaitPointerEvent()
                                                                if (event.type == PointerEventType.Enter || 
                                                                    event.type == PointerEventType.Move) {
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
                                                )

                                                VoiceControlOverlay(
                                                    state = voiceState,
                                                    partialTranscript = partialTranscript,
                                                    gestureArmingProgress = voiceGestureArmingProgress,
                                                    gestureHint = voiceGestureHint,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            NavigationRoot(
                                navController = navController,
                                hasServers = state.hasServers,
                                hasCurrentServer = state.hasCurrentServer,
                                hasCurrentUser = state.hasCurrentUser,
                                onboardingCompleted = onboardingCompleted,
                                appPreferences = appPreferences,
                                initialSearchQuery = initialSearchQueryExtra,
                            )
                        }
                    }
                }
            }
        }

        scheduleUserDataSync()
    }


    private fun requestStartupPermissionsIfNeeded() {
        val prefs = getSharedPreferences(PERMISSIONS_PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(STARTUP_PERMISSIONS_REQUESTED_KEY, false)) return

        val missingPermissions =
            listOf(
                Manifest.permission.RECORD_AUDIO,
                HAND_TRACKING_PERMISSION,
                localVideoPermission(),
            ).distinct().filter { permission ->
                ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
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
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()

        val workManager = WorkManager.getInstance(applicationContext)

        workManager
            .beginUniqueWork("syncUserData", ExistingWorkPolicy.KEEP, syncWorkRequest)
            .enqueue()
    }

    private fun loadAppRootPose(): Pose {
        return Pose(
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
    }

    private fun safeGetAppRootPose(entity: GroupEntity): Pose? {
        return runCatching { entity.getPose() }.getOrNull()
    }
}
