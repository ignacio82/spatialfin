package dev.spatialfin.companion.wear.presentation

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.wear.ambient.AmbientLifecycleObserver
import dagger.hilt.android.AndroidEntryPoint
import dev.spatialfin.companion.wear.ambient.AmbientStateHolder
import dev.spatialfin.companion.wear.ambient.LocalAmbientMode
import dev.spatialfin.companion.wear.pairing.WearPairingManager
import dev.spatialfin.companion.wear.presentation.theme.SpatialFinWearTheme
import dev.spatialfin.companion.wear.transport.TransportState
import dev.spatialfin.companion.wear.transport.WearTransportManager
import dev.spatialfin.companion.wear.voice.WearVoiceCapture
import timber.log.Timber
import javax.inject.Inject

sealed interface WearDestination {
    data object Remote : WearDestination
    data object NextUp : WearDestination
    data object ReceiverSettings : WearDestination
}

@AndroidEntryPoint
class WearMainActivity : ComponentActivity() {

    @Inject
    lateinit var transportManager: WearTransportManager

    @Inject
    lateinit var voiceCapture: WearVoiceCapture

    @Inject
    lateinit var pairingManager: WearPairingManager

    private val ambientStateHolder = AmbientStateHolder()

    private val ambientObserver: AmbientLifecycleObserver by lazy {
        AmbientLifecycleObserver(
            this,
            object : AmbientLifecycleObserver.AmbientLifecycleCallback {
                override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) {
                    ambientStateHolder.onEnterAmbient(
                        burnInProtection = ambientDetails.burnInProtectionRequired,
                        lowBitAmbient = ambientDetails.deviceHasLowBitAmbient,
                    )
                }

                override fun onExitAmbient() = ambientStateHolder.onExitAmbient()

                override fun onUpdateAmbient() = ambientStateHolder.onUpdateAmbient()
            },
        )
    }

    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Timber.i("WearMainActivity: RECORD_AUDIO granted=%b", granted)
            if (granted) voiceCapture.startCapture()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Timber.i("WearMainActivity: onCreate")

        // Ambient (always-on) callbacks. Without this the theme's ambient branch never
        // engages and the remote keeps animating with the screen dimmed.
        lifecycle.addObserver(ambientObserver)

        setContent {
            val isAmbient by ambientStateHolder.isAmbient
            var currentDestination by remember { mutableStateOf<WearDestination>(WearDestination.Remote) }

            val transportState by transportManager.transportState.collectAsState()
            // Observed, not read once: the setup screen must fall away the moment the
            // host's first credential push lands.
            val credentials by transportManager.credentialsStore.credentials.collectAsState()

            CompositionLocalProvider(LocalAmbientMode provides isAmbient) {
                SpatialFinWearTheme {
                    if (transportState is TransportState.Disconnected && credentials == null) {
                        WearStandaloneSetupScreen(
                            onRetry = { transportManager.checkConnectivity() },
                        )
                    } else {
                        when (currentDestination) {
                            is WearDestination.Remote -> {
                                WearRemoteControlScreen(
                                    transportManager = transportManager,
                                    voiceCapture = voiceCapture,
                                    pairingManager = pairingManager,
                                    onNavigateToNextUp = { currentDestination = WearDestination.NextUp },
                                    onNavigateToReceiverSettings = {
                                        currentDestination = WearDestination.ReceiverSettings
                                    },
                                    onRequestMicPermission = {
                                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    },
                                )
                            }
                            is WearDestination.NextUp -> {
                                WearNextUpScreen(
                                    transportManager = transportManager,
                                    onNavigateBack = { currentDestination = WearDestination.Remote },
                                )
                            }
                            is WearDestination.ReceiverSettings -> {
                                WearReceiverSettingsScreen(
                                    onNavigateBack = { currentDestination = WearDestination.Remote },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        transportManager.checkConnectivity()
    }
}
