package dev.spatialfin

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import dev.jdtech.jellyfin.viewmodels.MainViewModel
import dev.jdtech.jellyfin.work.SyncWorker
import kotlinx.coroutines.delay
import timber.log.Timber

@OptIn(ExperimentalMaterial3XrApi::class)
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    private val viewModel: MainViewModel by viewModels()
    private var xrSession: Session? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

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

        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()

            SpatialFinTheme(dynamicColor = state.isDynamicColors) {
                val navController = rememberNavController()
                if (!state.isLoading) {
                    CompositionLocalProvider(LocalOfflineMode provides state.isOfflineMode) {
                        val session = xrSession
                        if (session != null) {
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
                            )
                        }
                    }
                }
            }
        }

        scheduleUserDataSync()
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
