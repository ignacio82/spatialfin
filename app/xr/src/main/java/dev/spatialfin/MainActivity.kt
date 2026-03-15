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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import kotlinx.coroutines.delay
import timber.log.Timber

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.collectAsState
import dev.jdtech.jellyfin.player.xr.voice.SpatialCommandCoordinator
import dev.jdtech.jellyfin.player.xr.voice.SpatialVoiceService
import dev.jdtech.jellyfin.player.xr.voice.SecondaryHandPinchDetector
import dev.jdtech.jellyfin.player.xr.voice.VoiceControlOverlay
import dev.jdtech.jellyfin.player.xr.voice.VoiceState
import dev.jdtech.jellyfin.player.xr.voice.XrPlayerAction
import androidx.xr.runtime.HandTrackingMode
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

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
    private var xrSession: Session? = null
    private val startupPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        requestStartupPermissionsIfNeeded()

        // Ensure window is transparent so we can see the XR scene behind the UI
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))

        // Initialize XR Session
        try {
            val result = Session.create(this)
            if (result is SessionCreateSuccess) {
                xrSession = result.session

                // Request full space mode for 3D support and immersive app experience
                try {
                    val capabilities = xrSession?.scene?.spatialCapabilities
                    if (capabilities?.contains(androidx.xr.scenecore.SpatialCapability.SPATIAL_3D_CONTENT) == true) {
                        xrSession?.scene?.requestFullSpaceMode()
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Failed to request full space mode")
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "XR session not available")
        }

        val initialSearchQueryExtra = intent.getStringExtra(EXTRA_INITIAL_SEARCH_QUERY)

        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            val coroutineScope = rememberCoroutineScope()
            val context = androidx.compose.ui.platform.LocalContext.current

            SpatialFinTheme(dynamicColor = state.isDynamicColors) {
                val navController = rememberNavController()
                if (!state.isLoading) {
                    CompositionLocalProvider(LocalOfflineMode provides state.isOfflineMode) {
                        val session = xrSession
                        if (session != null) {
                            // --- Voice and Gesture state ---
                            val voiceService = remember(context) { SpatialVoiceService(context.applicationContext) }
                            val commandCoordinator = remember(context) { SpatialCommandCoordinator(context.applicationContext) }
                            val voiceState by voiceService.state.collectAsState()
                            val partialTranscript by voiceService.partialTranscript.collectAsState()
                            var voiceFeedback by remember { mutableStateOf<String?>(null) }
                            var voiceSearchQuery by remember { mutableStateOf(initialSearchQueryExtra) }
                            
                            val pinchDetector = remember(session) { SecondaryHandPinchDetector(session, this@MainActivity) }
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
                                        navController.navigate("HomeRoute") {
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
                                
                                val startedAtMs = System.currentTimeMillis()
                                voiceService.startListening { transcript ->
                                    coroutineScope.launch {
                                        val parseResult = commandCoordinator.parse(transcript)
                                        voiceFeedback = handleVoiceAction(parseResult.action)
                                    }
                                }
                            }

                            LaunchedEffect(Unit) {
                                commandCoordinator.initialize()
                            }

                            LaunchedEffect(session, hasHandTrackingPermission) {
                                if (hasHandTrackingPermission) {
                                    runCatching {
                                        session.configure(session.config.copy(handTracking = HandTrackingMode.BOTH))
                                    }.onFailure { Timber.w(it, "VOICE: Hand tracking not available") }
                                }
                            }

                            LaunchedEffect(pinchDetector, hasHandTrackingPermission) {
                                if (!hasHandTrackingPermission) return@LaunchedEffect
                                pinchDetector.pinchEvents.collect { event ->
                                    when (event) {
                                        SecondaryHandPinchDetector.PinchEvent.STARTED -> requestVoiceCommand()
                                        SecondaryHandPinchDetector.PinchEvent.ENDED -> voiceService.stopListening()
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

                            LaunchedEffect(controlsVisible, rootEntity.value) {
                                val root = rootEntity.value ?: return@LaunchedEffect
                                if (controlsVisible) {
                                    if (movableComponent.value == null) {
                                        val m = MovableComponent.createSystemMovable(session, false)
                                        root.addComponent(m)
                                        movableComponent.value = m
                                    }
                                } else {
                                    movableComponent.value?.let {
                                        root.removeComponent(it)
                                        movableComponent.value = null
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

                            DisposableEffect(session) {
                                try {
                                    val root = GroupEntity.create(
                                        session,
                                        "MainScreenRoot",
                                        Pose(Vector3(0f, 0f, -3f), Quaternion.Identity),
                                    )
                                    rootEntity.value = root
                                } catch (e: Exception) {
                                    Timber.w(e, "Failed to create movable root for main screen")
                                }
                                onDispose {
                                    voiceService.destroy()
                                    commandCoordinator.destroy()
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
                                                    initialSearchQuery = voiceSearchQuery,
                                                )

                                                VoiceControlOverlay(
                                                    state = voiceState,
                                                    partialTranscript = partialTranscript,
                                                    feedback = voiceFeedback,
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
}
