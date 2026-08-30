package dev.spatialfin.companion.wear.presentation

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.wear.ambient.AmbientLifecycleObserver
import dagger.hilt.android.AndroidEntryPoint
import dev.spatialfin.companion.protocol.WearPlayerAction
import dev.spatialfin.companion.wear.ambient.AmbientStateHolder
import dev.spatialfin.companion.wear.ambient.LocalAmbientMode
import dev.spatialfin.companion.wear.pairing.WearPairingManager
import dev.spatialfin.companion.wear.presentation.components.WearAudioTracksSheet
import dev.spatialfin.companion.wear.presentation.components.WearChaptersSheet
import dev.spatialfin.companion.wear.presentation.components.WearDevicePickerSheet
import dev.spatialfin.companion.wear.presentation.components.WearSpatialControlsSheet
import dev.spatialfin.companion.wear.presentation.components.WearSubtitleTracksSheet
import dev.spatialfin.companion.wear.presentation.components.WearVoiceDialog
import dev.spatialfin.companion.wear.presentation.theme.SpatialFinWearTheme
import dev.spatialfin.companion.wear.transport.TransportState
import dev.spatialfin.companion.wear.transport.WearTransportManager
import dev.spatialfin.companion.wear.voice.WearVoiceCapture
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Where the watch can be.
 *
 * After the redesign the switcher sheets are destinations rather than overlays
 * drawn from inside the player: the player owns the arc and nothing else, and
 * every sheet is reached from [Actions].
 */
sealed interface WearDestination {
    data object Player : WearDestination
    data object Actions : WearDestination
    data object AudioTracks : WearDestination
    data object SubtitleTracks : WearDestination
    data object Chapters : WearDestination
    data object Spatial : WearDestination
    data object NextUp : WearDestination
    data object Voice : WearDestination
    data object DevicePicker : WearDestination
    data object PrivateAudio : WearDestination
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
            var destination by remember { mutableStateOf<WearDestination>(WearDestination.Player) }
            val scope = rememberCoroutineScope()

            val transportState by transportManager.transportState.collectAsState()
            // Observed, not read once: the setup screen must fall away the moment the
            // host's first credential push lands.
            val credentials by transportManager.credentialsStore.credentials.collectAsState()
            val nowPlaying by transportManager.nowPlaying.collectAsState()
            val voiceState by voiceCapture.recordingState.collectAsState()

            // Ambient never renders a sheet — the always-on surface is the player's
            // hairline branch, and waking to a track list nobody chose is worse than
            // waking to the timeline.
            LaunchedEffect(isAmbient) {
                if (isAmbient) destination = WearDestination.Player
            }

            val dispatch: (WearPlayerAction) -> Unit = { action ->
                scope.launch { transportManager.dispatchAction(action) }
            }
            val backToPlayer = { destination = WearDestination.Player }

            CompositionLocalProvider(LocalAmbientMode provides isAmbient) {
                SpatialFinWearTheme {
                    if (transportState is TransportState.Disconnected && credentials == null) {
                        WearStandaloneSetupScreen(
                            onRetry = { transportManager.checkConnectivity() },
                        )
                        return@SpatialFinWearTheme
                    }

                    when (destination) {
                        is WearDestination.Player -> WearRemoteControlScreen(
                            transportManager = transportManager,
                            voiceCapture = voiceCapture,
                            pairingManager = pairingManager,
                            onNavigateToActions = { destination = WearDestination.Actions },
                            onNavigateToDevicePicker = { destination = WearDestination.DevicePicker },
                        )

                        is WearDestination.Actions -> WearActionRingScreen(
                            onOpenAudio = { destination = WearDestination.AudioTracks },
                            onOpenSubtitles = { destination = WearDestination.SubtitleTracks },
                            onOpenChapters = { destination = WearDestination.Chapters },
                            onOpenNextUp = { destination = WearDestination.NextUp },
                            onOpenSpatial = { destination = WearDestination.Spatial },
                            onOpenVoice = {
                                destination = WearDestination.Voice
                                voiceCapture.startCapture()
                            },
                            onOpenPrivateAudio = { destination = WearDestination.PrivateAudio },
                            onDismiss = backToPlayer,
                        )

                        is WearDestination.AudioTracks -> WearAudioTracksSheet(
                            tracks = nowPlaying?.audioTracks.orEmpty(),
                            currentTrack = nowPlaying?.currentAudioTrack,
                            onSelectTrack = { track ->
                                dispatch(
                                    WearPlayerAction.SelectAudioTrack(
                                        language = track.language,
                                        index = track.index,
                                    ),
                                )
                            },
                            onDismiss = backToPlayer,
                        )

                        is WearDestination.SubtitleTracks -> WearSubtitleTracksSheet(
                            tracks = nowPlaying?.subtitleTracks.orEmpty(),
                            currentTrack = nowPlaying?.currentSubtitleTrack,
                            onSelectTrack = { track ->
                                if (track == null) {
                                    dispatch(WearPlayerAction.DisableSubtitles)
                                } else {
                                    dispatch(
                                        WearPlayerAction.SelectSubtitleTrack(
                                            language = track.language,
                                            index = track.index,
                                        ),
                                    )
                                }
                            },
                            onDismiss = backToPlayer,
                        )

                        is WearDestination.Chapters -> WearChaptersSheet(
                            chapters = nowPlaying?.chapters.orEmpty(),
                            currentChapterName = nowPlaying?.currentChapterName,
                            positionSeconds = nowPlaying?.positionSeconds ?: 0L,
                            durationSeconds = nowPlaying?.durationSeconds ?: 0L,
                            onSelectChapter = { chapter ->
                                dispatch(WearPlayerAction.SeekTo(chapter.startPositionSeconds))
                            },
                            onDismiss = backToPlayer,
                        )

                        is WearDestination.Spatial -> WearSpatialControlsSheet(
                            onDispatchAction = dispatch,
                            onDismiss = backToPlayer,
                        )

                        is WearDestination.NextUp -> WearNextUpScreen(
                            transportManager = transportManager,
                            onNavigateBack = backToPlayer,
                        )

                        is WearDestination.Voice -> WearVoiceDialog(
                            state = voiceState,
                            onStopCapture = { voiceCapture.stopCapture() },
                            onRequestPermission = {
                                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            },
                            onDismiss = {
                                voiceCapture.stopCapture()
                                backToPlayer()
                            },
                        )

                        is WearDestination.PrivateAudio -> WearReceiverSettingsScreen(
                            onNavigateBack = backToPlayer,
                        )

                        is WearDestination.DevicePicker -> {
                            val lanReceivers by transportManager.directLanClient
                                .discoveredReceivers.collectAsState()
                            LaunchedEffect(Unit) { transportManager.directLanClient.startDiscovery() }
                            WearDevicePickerSheet(
                                currentDeviceName = nowPlaying?.targetDeviceName ?: "SpatialFin",
                                lanReceivers = lanReceivers,
                                canFling = !nowPlaying?.streamUrl.isNullOrBlank(),
                                onSelectLanReceiver = { receiver ->
                                    scope.launch {
                                        transportManager.directLanClient.connectToReceiver(receiver)
                                        transportManager.checkConnectivity()
                                    }
                                },
                                onFlingToReceiver = { receiver ->
                                    val stream = nowPlaying
                                    scope.launch {
                                        val url = stream?.streamUrl ?: return@launch
                                        transportManager.directLanClient.castStream(
                                            receiver = receiver,
                                            streamUrl = url,
                                            container = stream.mediaContainer,
                                            title = stream.title.ifBlank { "SpatialFin" },
                                            positionSeconds = stream.positionSeconds.toDouble(),
                                        )
                                        transportManager.checkConnectivity()
                                    }
                                },
                                onDismiss = {
                                    transportManager.directLanClient.stopDiscovery()
                                    backToPlayer()
                                },
                            )
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
