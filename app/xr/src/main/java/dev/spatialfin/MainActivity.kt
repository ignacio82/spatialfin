package dev.spatialfin

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import androidx.xr.compose.subspace.SpatialPanel
import androidx.xr.compose.subspace.layout.SubspaceModifier
import androidx.xr.compose.subspace.layout.height
import androidx.xr.compose.subspace.layout.width
import androidx.xr.runtime.Session
import androidx.xr.runtime.SessionCreateSuccess
import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Quaternion
import androidx.xr.runtime.math.Vector3
import androidx.xr.scenecore.scene
import dagger.hilt.android.AndroidEntryPoint
import dev.spatialfin.presentation.theme.SpatialFinTheme
import dev.jdtech.jellyfin.presentation.utils.LocalOfflineMode
import dev.jdtech.jellyfin.viewmodels.MainViewModel
import dev.jdtech.jellyfin.work.SyncWorker
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
                        if (xrSession != null) {
                            val session = xrSession!!
                            val root = remember(session) {
                                androidx.xr.scenecore.GroupEntity.create(session, "MainNavigationRoot", Pose(Vector3(0f, 0f, 0f), Quaternion.Identity)).apply {
                                    val movable = androidx.xr.scenecore.MovableComponent.createSystemMovable(session)
                                    addComponent(movable)
                                }
                            }
                            Subspace {
                                androidx.xr.compose.subspace.SceneCoreEntity(factory = { root }, modifier = SubspaceModifier) {
                                    SpatialPanel(
                                        modifier = SubspaceModifier.width(1400.dp).height(900.dp)
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
