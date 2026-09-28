package dev.spatialfin.companion.wear.presentation

import android.Manifest
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.wear.ambient.AmbientLifecycleObserver
import dagger.hilt.android.AndroidEntryPoint
import dev.spatialfin.companion.protocol.WearPlayerAction
import dev.spatialfin.companion.wear.ambient.AmbientStateHolder
import dev.spatialfin.companion.wear.ambient.LocalAmbientMode
import dev.spatialfin.companion.wear.pairing.WearPairingManager
import dev.spatialfin.companion.wear.presentation.components.WearAudioTracksSheet
import dev.spatialfin.companion.wear.presentation.components.WearChaptersSheet
import dev.spatialfin.companion.wear.presentation.components.WearConnectionSheet
import dev.spatialfin.companion.wear.presentation.components.WearDevicePickerSheet
import dev.spatialfin.companion.wear.presentation.components.WearSpatialControlsSheet
import dev.spatialfin.companion.wear.presentation.components.WearSubtitleTracksSheet
import dev.spatialfin.companion.wear.presentation.components.WearVoiceDialog
import dev.spatialfin.companion.wear.presentation.theme.SpatialFinWearTheme
import dev.spatialfin.companion.wear.transport.TransportState
import dev.spatialfin.companion.wear.transport.WearTransportManager
import dev.spatialfin.companion.wear.voice.WearVoiceCapture
import javax.inject.Inject
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Where the watch can be.
 *
 * After the redesign the switcher sheets are destinations rather than overlays drawn from inside
 * the player: the player owns the arc and nothing else, and every sheet is reached from [Actions].
 */
enum class WearDestination {
    Player,
    Actions,
    AudioTracks,
    SubtitleTracks,
    Chapters,
    Spatial,
    NextUp,
    Voice,
    DevicePicker,
    PrivateAudio,
    Connection,
}

@AndroidEntryPoint
class WearMainActivity : ComponentActivity() {

    @Inject lateinit var transportManager: WearTransportManager

    @Inject lateinit var voiceCapture: WearVoiceCapture

    @Inject lateinit var pairingManager: WearPairingManager

    private var voiceScreenVisible = false

    private val ambientStateHolder = AmbientStateHolder()

    private val ambientObserver: AmbientLifecycleObserver by lazy {
        AmbientLifecycleObserver(
            this,
            object : AmbientLifecycleObserver.AmbientLifecycleCallback {
                override fun onEnterAmbient(
                    ambientDetails: AmbientLifecycleObserver.AmbientDetails
                ) {
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
            if (
                granted &&
                    voiceScreenVisible &&
                    lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
                    !ambientStateHolder.isAmbient.value
            )
                voiceCapture.startCapture()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Timber.i("WearMainActivity: onCreate")

        // Ambient (always-on) callbacks. Without this the theme's ambient branch never
        // engages and the remote keeps animating with the screen dimmed.
        lifecycle.addObserver(ambientObserver)

        setContent {
            val isAmbient by ambientStateHolder.isAmbient
            var destination by rememberSaveable {
                mutableStateOf<WearDestination>(WearDestination.Player)
            }
            val scope = rememberCoroutineScope()

            val transportState by transportManager.transportState.collectAsState()
            // Observed, not read once: the setup screen must fall away the moment the
            // host's first credential push lands.
            val credentials by transportManager.credentialsStore.credentials.collectAsState()
            val nowPlaying by transportManager.nowPlaying.collectAsState()
            val voiceState by voiceCapture.recordingState.collectAsState()
            val status by transportManager.connectionStatus.collectAsState()
            val feedback by transportManager.feedback.collectAsState()
            LaunchedEffect(feedback) {
                feedback?.let {
                    Toast.makeText(this@WearMainActivity, it, Toast.LENGTH_LONG).show()
                }
                transportManager.clearFeedback()
            }
            BackHandler(destination != WearDestination.Player) {
                destination = WearDestination.Player
            }
            DisposableEffect(destination) {
                val current = destination
                voiceScreenVisible = current == WearDestination.Voice
                val observer = LifecycleEventObserver { _, event ->
                    if (
                        event == Lifecycle.Event.ON_START && current == WearDestination.DevicePicker
                    )
                        transportManager.directLanClient.startDiscovery()
                    if (event == Lifecycle.Event.ON_STOP) {
                        voiceCapture.stopCapture()
                        transportManager.directLanClient.stopDiscovery()
                    }
                }
                lifecycle.addObserver(observer)
                onDispose {
                    lifecycle.removeObserver(observer)
                    if (current == WearDestination.Voice) {
                        voiceScreenVisible = false
                        voiceCapture.stopCapture()
                    }
                    if (current == WearDestination.DevicePicker)
                        transportManager.directLanClient.stopDiscovery()
                }
            }

            // Ambient never renders a sheet — the always-on surface is the player's
            // hairline branch, and waking to a track list nobody chose is worse than
            // waking to the timeline.
            LaunchedEffect(isAmbient) { if (isAmbient) destination = WearDestination.Player }

            val dispatch: (WearPlayerAction) -> Unit = { action ->
                scope.launch { transportManager.dispatchAction(action) }
            }
            val backToPlayer = { destination = WearDestination.Player }

            CompositionLocalProvider(LocalAmbientMode provides isAmbient) {
                SpatialFinWearTheme {
                    if (
                        destination == WearDestination.Player &&
                            transportState is TransportState.Disconnected &&
                            credentials == null
                    ) {
                        WearStandaloneSetupScreen(
                            onRetry = { transportManager.syncFromPhone() },
                            onFindReceiver = { destination = WearDestination.DevicePicker },
                            onPrivateAudio = { destination = WearDestination.PrivateAudio },
                            onConnection = { destination = WearDestination.Connection },
                        )
                        return@SpatialFinWearTheme
                    }

                    when (destination) {
                        WearDestination.Player ->
                            WearRemoteControlScreen(
                                transportManager = transportManager,
                                voiceCapture = voiceCapture,
                                pairingManager = pairingManager,
                                onNavigateToActions = { destination = WearDestination.Actions },
                                onNavigateToDevicePicker = {
                                    destination = WearDestination.DevicePicker
                                },
                            )

                        WearDestination.Actions ->
                            WearActionRingScreen(
                                onOpenAudio = { destination = WearDestination.AudioTracks },
                                onOpenSubtitles = { destination = WearDestination.SubtitleTracks },
                                onOpenChapters = { destination = WearDestination.Chapters },
                                onOpenNextUp = { destination = WearDestination.NextUp },
                                onOpenSpatial = {
                                    if (transportManager.supportsExtendedControls())
                                        destination = WearDestination.Spatial
                                    else
                                        transportManager.showFeedback(
                                            "Spatial controls require an updated SpatialFin player selected in Target"
                                        )
                                },
                                onOpenVoice = {
                                    destination = WearDestination.Voice
                                    voiceCapture.startCapture()
                                },
                                onOpenPrivateAudio = { destination = WearDestination.PrivateAudio },
                                onDismiss = backToPlayer,
                            )

                        WearDestination.AudioTracks ->
                            WearAudioTracksSheet(
                                tracks = nowPlaying?.audioTracks.orEmpty(),
                                currentTrack = nowPlaying?.currentAudioTrack,
                                onSelectTrack = { track ->
                                    dispatch(
                                        WearPlayerAction.SelectAudioTrack(
                                            language = track.language,
                                            index = track.index,
                                        )
                                    )
                                },
                                onDismiss = backToPlayer,
                            )

                        WearDestination.SubtitleTracks ->
                            WearSubtitleTracksSheet(
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
                                            )
                                        )
                                    }
                                },
                                onDismiss = backToPlayer,
                            )

                        WearDestination.Chapters ->
                            WearChaptersSheet(
                                chapters = nowPlaying?.chapters.orEmpty(),
                                currentChapterName = nowPlaying?.currentChapterName,
                                positionSeconds = nowPlaying?.positionSeconds ?: 0L,
                                durationSeconds = nowPlaying?.durationSeconds ?: 0L,
                                onSelectChapter = { chapter ->
                                    dispatch(WearPlayerAction.SeekTo(chapter.startPositionSeconds))
                                },
                                onDismiss = backToPlayer,
                            )

                        WearDestination.Spatial ->
                            WearSpatialControlsSheet(
                                onDispatchAction = dispatch,
                                onDismiss = backToPlayer,
                            )

                        WearDestination.NextUp ->
                            WearNextUpScreen(
                                transportManager = transportManager,
                                onNavigateBack = backToPlayer,
                            )

                        WearDestination.Voice ->
                            WearVoiceDialog(
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

                        WearDestination.PrivateAudio ->
                            WearReceiverSettingsScreen(onNavigateBack = backToPlayer)

                        WearDestination.Connection ->
                            WearConnectionSheet(
                                status = status,
                                account =
                                    credentials?.let {
                                        "${it.username ?: "Signed in"} · ${it.serverName ?: it.serverUrl}"
                                    } ?: "No account synced",
                                onSync = transportManager::syncFromPhone,
                                onRetry = transportManager::checkConnectivity,
                                onDismiss = backToPlayer,
                            )
                        WearDestination.DevicePicker -> {
                            val remoteSessions by transportManager.remoteSessions.collectAsState()
                            val lanReceivers by
                                transportManager.directLanClient.discoveredReceivers
                                    .collectAsState()
                            LaunchedEffect(Unit) {
                                transportManager.directLanClient.startDiscovery()
                            }
                            WearDevicePickerSheet(
                                currentDeviceName = nowPlaying?.targetDeviceName ?: "SpatialFin",
                                connectionStatus = status,
                                onConnectionDetails = { destination = WearDestination.Connection },
                                remoteSessions = remoteSessions,
                                selectedRemoteSessionId =
                                    (transportState as? TransportState.ConnectedViaJellyfinRelay)
                                        ?.sessionId
                                        ?: remoteSessions
                                            .firstOrNull {
                                                transportState is
                                                    TransportState.ConnectedViaDataLayer &&
                                                    it.deviceId == credentials?.deviceId
                                            }
                                            ?.sessionId,
                                onSelectRemoteSession = transportManager::selectRemoteSession,
                                lanReceivers = lanReceivers,
                                canFling = !nowPlaying?.streamUrl.isNullOrBlank(),
                                onSelectLanReceiver = { receiver ->
                                    scope.launch { transportManager.selectLanReceiver(receiver) }
                                },
                                onFlingToReceiver = { receiver ->
                                    val stream = nowPlaying
                                    scope.launch {
                                        val url = stream?.streamUrl ?: return@launch
                                        val result =
                                            transportManager.directLanClient.castStream(
                                                receiver = receiver,
                                                streamUrl = url,
                                                container = stream.mediaContainer,
                                                title = stream.title.ifBlank { "SpatialFin" },
                                                positionSeconds = stream.positionSeconds.toDouble(),
                                            )
                                        transportManager.showFeedback(
                                            result.getOrElse { it.message ?: "Casting failed" }
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

    override fun onStart() {
        super.onStart()
        transportManager.startPolling()
    }

    override fun onStop() {
        transportManager.stopPolling()
        super.onStop()
    }
}
