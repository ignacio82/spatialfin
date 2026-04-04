package dev.jdtech.jellyfin.player.xr

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import android.graphics.Bitmap
import android.util.TypedValue
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import androidx.xr.compose.spatial.ContentEdge
import androidx.xr.compose.spatial.Orbiter
import androidx.xr.compose.spatial.SpatialDialog
import androidx.xr.compose.spatial.Subspace
import androidx.xr.compose.subspace.ResizePolicy
import androidx.xr.compose.subspace.SpatialPanel
import androidx.xr.compose.subspace.layout.SubspaceModifier
import androidx.xr.compose.subspace.layout.height
import androidx.xr.compose.subspace.layout.offset
import androidx.xr.compose.subspace.layout.rotate
import androidx.xr.compose.subspace.layout.width
import androidx.xr.runtime.Session
import androidx.xr.runtime.HandTrackingMode
import androidx.xr.runtime.math.FloatSize2d
import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Quaternion
import androidx.xr.runtime.math.Vector3
import androidx.xr.scenecore.GltfModel
import androidx.xr.scenecore.GltfModelEntity
import androidx.xr.scenecore.SpatialCapability
import androidx.xr.scenecore.SpatialEnvironment
import androidx.xr.scenecore.SurfaceEntity
import androidx.xr.scenecore.GroupEntity
import androidx.xr.compose.subspace.SceneCoreEntity
import androidx.xr.scenecore.scene
import androidx.core.content.ContextCompat
import dev.jdtech.jellyfin.player.local.domain.getTrackNames
import dev.jdtech.jellyfin.player.local.presentation.PlayerEvents
import dev.jdtech.jellyfin.player.local.presentation.PlayerViewModel
import dev.jdtech.jellyfin.player.session.voice.PlayerSessionController
import dev.jdtech.jellyfin.player.session.voice.PlayerStateSnapshot
import dev.jdtech.jellyfin.player.xr.voice.AssistantPreferences
import dev.jdtech.jellyfin.player.xr.voice.GeminiCloudService
import dev.jdtech.jellyfin.player.xr.voice.GeminiNanoService
import dev.jdtech.jellyfin.player.xr.voice.VoiceControlOverlay
import dev.jdtech.jellyfin.player.xr.voice.VoiceParseResult
import dev.jdtech.jellyfin.player.xr.voice.SecondaryHandPinchDetector
import dev.jdtech.jellyfin.player.xr.voice.SmartChatEngine
import dev.jdtech.jellyfin.player.xr.voice.SpatialCommandCoordinator
import dev.jdtech.jellyfin.player.xr.voice.SpatialVoiceService
import dev.jdtech.jellyfin.player.xr.voice.SpatialVoiceSynthesizer
import dev.jdtech.jellyfin.player.xr.voice.VoiceState
import dev.jdtech.jellyfin.models.SpatialFinEpisode
import dev.jdtech.jellyfin.models.SpatialFinItem
import dev.jdtech.jellyfin.models.SpatialFinMovie
import dev.jdtech.jellyfin.models.SpatialFinSeason
import dev.jdtech.jellyfin.models.SpatialFinShow
import dev.jdtech.jellyfin.settings.voice.VoiceTelemetryEntry
import dev.jdtech.jellyfin.settings.voice.VoiceTelemetryStore
import java.nio.file.Paths
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import coil3.compose.AsyncImage
import dev.jdtech.jellyfin.player.core.domain.models.PlayerItem
import dev.jdtech.jellyfin.player.core.domain.models.PlayerChapter
import dev.jdtech.jellyfin.player.core.domain.models.PlayerPerson
import dev.jdtech.jellyfin.player.session.voice.XrPlayerAction
import dev.jdtech.jellyfin.player.xr.mcp.EntityInfo
import dev.jdtech.jellyfin.player.xr.mcp.LibassState
import dev.jdtech.jellyfin.player.xr.mcp.McpBridge
import dev.jdtech.jellyfin.player.xr.mcp.PlaybackState
import dev.jdtech.jellyfin.player.xr.mcp.SceneState

import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.player.local.R as LocalR

// ── Next-episode panel threshold ───────────────────────────────────────────────────
private const val NEXT_EPISODE_THRESHOLD_MS = 2 * 60 * 1_000L  // show in last 2 minutes
private const val HAND_TRACKING_PERMISSION = "android.permission.HAND_TRACKING"
private const val PAUSED_MASCOT_DELAY_MS = 1_000L
private const val MIN_VOICE_LISTEN_MS_AFTER_GESTURE = 900L

private val PausedMascotPose =
    Pose(
        Vector3(-2.15f, -0.98f, 0.95f),
        Quaternion(0f, 0.9914f, 0f, 0.1305f),
    )

private fun Cue.debugSummary(): String {
    val preview = text?.toString()?.replace('\n', ' ')?.replace('\r', ' ')?.take(60) ?: "<bitmap>"
    return "text=\"$preview\" line=$line lineType=$lineType lineAnchor=$lineAnchor position=$position positionAnchor=$positionAnchor size=$size textSize=$textSize verticalType=$verticalType"
}

/**
 * SpatialPlayerScreen — IMAX-style immersive XR playback experience.
 *
 * Architecture:
 *  - GroupEntity (SceneCore): high-fidelity video root, user-movable
 *  - SurfaceEntity (SceneCore): high-fidelity video surface, child of GroupEntity
 *  - Subtitle SpatialPanel: perspective-scaled, follows video pose via state
 *  - Control SpatialPanel: floats below the video with:
 *      • Orbiter (End): secondary controls (audio / subtitle / speed)
 *      • Orbiter (Bottom): reveal button when controls are hidden
 *      • SpatialDialog: track & speed selection (pushes panel back 125dp for depth)
 *  - Skip SpatialPanel: contextual, right of controls
 */
@Composable
fun SpatialPlayerScreen(
    viewModel: PlayerViewModel,
    session: Session,
    initialStereoMode: String,
    itemId: UUID?,
    localMediaId: Long?,
    networkVideoId: String? = null,
    itemKind: String,
    startFromBeginning: Boolean,
    mediaSourceIndex: Int? = null,
    maxBitrate: Long? = null,
    openSyncPlayDialogOnStart: Boolean = false,
    libassRenderer: LibassRenderer?,
    onSearchQuery: suspend (String) -> List<SpatialFinItem>,
    onLaunchSearchResult: (SpatialFinItem) -> Unit,
    telemetryStore: VoiceTelemetryStore,
    onBackClick: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val coroutineScope = rememberCoroutineScope()
    val uiState by viewModel.uiState.collectAsState()
    val syncPlayState by viewModel.syncPlayState.collectAsState()
    val player by viewModel.playerFlow.collectAsState()
    val xrSubtitleSize = viewModel.appPreferences.getValue(viewModel.appPreferences.xrSubtitleSize).toFloat()
    val libassUsagePref = viewModel.appPreferences.getValue(viewModel.appPreferences.libassSubtitleUsage)
    val voiceControlEnabled = viewModel.appPreferences.getValue(viewModel.appPreferences.voiceControlEnabled)
    val voiceGestureHand = viewModel.appPreferences.getValue(viewModel.appPreferences.voiceGestureHand) ?: "left"
    val assistantVerbosity = viewModel.appPreferences.getValue(viewModel.appPreferences.voiceAssistantVerbosity)
    val assistantSpoilerPolicy = viewModel.appPreferences.getValue(viewModel.appPreferences.voiceAssistantSpoilerPolicy)
    val assistantSpokenReplies = viewModel.appPreferences.getValue(viewModel.appPreferences.voiceAssistantSpokenReplies)
    val assistantVoicePreference = viewModel.appPreferences.getValue(viewModel.appPreferences.voiceAssistantVoice) ?: "male"
    val voiceService = remember(context) { SpatialVoiceService(context.applicationContext) }
    val geminiNanoService = remember(context) { GeminiNanoService(context.applicationContext) }
    val geminiCloudService = remember(context) { GeminiCloudService(context.applicationContext, viewModel.appPreferences, viewModel.repository) }
    val commandCoordinator = remember(context) {
        SpatialCommandCoordinator(context.applicationContext, geminiNanoService, geminiCloudService)
    }
    val chatEngine = remember(context) {
        SmartChatEngine(context.applicationContext, geminiNanoService, geminiCloudService)
    }
    val tts = remember(context) { SpatialVoiceSynthesizer(context.applicationContext) }
    val isTtsSpeaking by tts.isSpeaking.collectAsState()
    val recentSubtitles = remember { mutableStateListOf<Pair<Long, String>>() }
    val voiceState by voiceService.state.collectAsState()
    val partialTranscript by voiceService.partialTranscript.collectAsState()
    var voiceFeedback by remember { mutableStateOf<String?>(null) }
    val conversationHistory = remember { mutableStateListOf<Pair<String, String>>() }
    var voiceGestureArmingProgress by remember { mutableFloatStateOf(0f) }
    var voiceGestureHint by remember { mutableStateOf<String?>(null) }
    var shouldStartVoiceCapture by remember { mutableStateOf(false) }
    var gestureVoiceSessionActive by remember { mutableStateOf(false) }
    var voiceCaptureStartedAtMs by remember { mutableLongStateOf(0L) }
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
    val pinchDetector =
        remember(session, activity, voiceGestureHand) {
            activity?.let { SecondaryHandPinchDetector(session, it, voiceGestureHand) }
        }

    val audioPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            hasAudioPermission = granted
            if (!granted) {
                shouldStartVoiceCapture = false
                voiceFeedback = "Microphone permission required"
                voiceService.resetState()
            }
        }
    val handTrackingPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            hasHandTrackingPermission = granted
            if (!granted) {
                voiceFeedback = "Hand tracking permission denied"
            }
        }
    
    // --- Libass state ---
    var useLibass by remember { mutableStateOf(false) }
    var libassBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var hasLibassContent by remember { mutableStateOf(false) }
    var libassFrameVersion by remember { mutableIntStateOf(0) }
    var videoOverlayAttachmentVersion by remember { mutableIntStateOf(0) }
    var lastLoggedMoveFrameVersion by remember { mutableIntStateOf(0) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    // Build caption style from user preferences so the selected colours / background
    // are honoured instead of the system default (which renders a black background box).
    val subtitleTextColor = viewModel.appPreferences.getValue(viewModel.appPreferences.subtitleTextColor)
    val subtitleBackgroundColor = viewModel.appPreferences.getValue(viewModel.appPreferences.subtitleBackgroundColor)
    val captionStyle = CaptionStyleCompat(
        subtitleTextColor,
        subtitleBackgroundColor,
        android.graphics.Color.TRANSPARENT, // no window background
        CaptionStyleCompat.EDGE_TYPE_NONE,
        android.graphics.Color.WHITE,       // edge colour (unused with EDGE_TYPE_NONE)
        null,                               // typeface — use system default
    )

    // --- Spatial Audio capability (reactive) ---
    var spatialAudioAvailable by remember {
        mutableStateOf(session.scene.spatialCapabilities.contains(SpatialCapability.SPATIAL_AUDIO))
    }
    DisposableEffect(session) {
        val listener = java.util.function.Consumer<Set<SpatialCapability>> { caps ->
            spatialAudioAvailable = caps.contains(SpatialCapability.SPATIAL_AUDIO)
            Timber.d("XR_AUDIO: spatial audio available=$spatialAudioAvailable")
        }
        session.scene.addSpatialCapabilitiesChangedListener(listener)
        onDispose { session.scene.removeSpatialCapabilitiesChangedListener(listener) }
    }

    // --- Player state ---
    var currentPosition by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    var playbackState by remember { mutableIntStateOf(Player.STATE_IDLE) }
    var passthroughOverrideEnabled by remember { mutableStateOf<Boolean?>(null) }
    var currentStereoMode by remember { mutableStateOf(initialStereoMode) }
    var videoWidth by remember { mutableFloatStateOf(10.0f) }
    var videoHeight by remember { mutableFloatStateOf(5.625f) }
    var currentCues by remember { mutableStateOf<List<Cue>>(emptyList()) }
    var subtitleTrackVersion by remember { mutableIntStateOf(0) }
    val passthroughEnabled = passthroughOverrideEnabled ?: !isPlaying
    var resumePlaybackAfterAssistantSpeech by remember { mutableStateOf(false) }
    var assistantSpeechPendingStart by remember { mutableStateOf(false) }
    var assistantSpeechStarted by remember { mutableStateOf(false) }

    // --- MCP Bridge integration ---
    var showPausedMascot by remember { mutableStateOf(false) }
    val isActuallyPaused = playbackState == Player.STATE_READY && !isPlaying
    LaunchedEffect(isActuallyPaused) {
        if (isActuallyPaused) {
            delay(PAUSED_MASCOT_DELAY_MS)
            showPausedMascot = true
            Timber.i("Paused mascot requested after pause delay")
        } else {
            showPausedMascot = false
            Timber.d("Paused mascot hidden because playback resumed")
        }
    }

    // --- Controls UI state ---
    var controlsVisible by remember { mutableStateOf(true) }
    var isLocked by remember { mutableStateOf(false) }
    var hideTimestamp by remember { mutableLongStateOf(System.currentTimeMillis()) }

    // --- Next episode panel state ---
    var nextEpisodePanelDismissed by remember { mutableStateOf(false) }
    // Reset dismissal whenever the title changes (user started a new episode).
    LaunchedEffect(uiState.currentItemTitle) { nextEpisodePanelDismissed = false }
    // Show the panel during the last NEXT_EPISODE_THRESHOLD_MS of an episode when a next
    // episode exists — but not for movies, very short content, or when controls are locked.
    val showNextEpisodePanel = !nextEpisodePanelDismissed &&
        !isLocked &&
        uiState.nextEpisode != null &&
        duration > NEXT_EPISODE_THRESHOLD_MS &&
        (duration - currentPosition) in 0L..NEXT_EPISODE_THRESHOLD_MS

    // --- Dialog state (lifted here so SpatialDialog lives inside the control SpatialPanel) ---
    var activeDialog by remember { mutableStateOf<String?>(null) }
    var voiceSearchQuery by remember { mutableStateOf("") }
    var voiceSearchResults by remember { mutableStateOf<List<SpatialFinItem>>(emptyList()) }
    var voiceSearchLoading by remember { mutableStateOf(false) }
    var voiceSearchError by remember { mutableStateOf<String?>(null) }
    val voiceSearchOpen = activeDialog == "voice_search"
    val latestSyncPlayGroups = androidx.compose.runtime.rememberUpdatedState(syncPlayState.availableGroups)

    fun resetAutoHide() {
        hideTimestamp = System.currentTimeMillis()
    }

    DisposableEffect(context) {
        McpBridge.register(context)
        McpBridge.onActionTriggered = { action ->
            coroutineScope.launch {
                when (action) {
                    "play" -> player.play()
                    "pause" -> player.pause()
                    "skip_forward" -> player.seekForward()
                    "skip_backward" -> player.seekBack()
                    "next_episode" -> viewModel.skipToNextItem()
                    "toggle_controls" -> controlsVisible = !controlsVisible
                    "toggle_passthrough" -> passthroughOverrideEnabled = !(passthroughOverrideEnabled ?: !isPlaying)
                }
            }
        }
        onDispose {
            McpBridge.onActionTriggered = null
            McpBridge.unregister(context)
        }
    }

    LaunchedEffect(uiState.currentItemTitle, currentPosition, duration, isPlaying, playbackState, videoWidth, videoHeight) {
        McpBridge.updatePlayback(
            PlaybackState(
                title = uiState.currentItemTitle,
                positionMs = currentPosition,
                durationMs = duration,
                isPlaying = isPlaying,
                playbackState = playbackState,
                videoWidth = videoWidth.toInt(),
                videoHeight = videoHeight.toInt(),
            ),
        )
    }

    LaunchedEffect(useLibass, hasLibassContent, libassFrameVersion, libassBitmap) {
        McpBridge.updateLibass(
            LibassState(
                renderWidth = libassBitmap?.width ?: 0,
                renderHeight = libassBitmap?.height ?: 0,
                hasContent = hasLibassContent,
                frameVersion = libassFrameVersion,
            ),
        )
    }

    LaunchedEffect(openSyncPlayDialogOnStart) {
        if (openSyncPlayDialogOnStart) {
            activeDialog = "syncplay"
            viewModel.refreshSyncPlayGroups()
        }
    }

    fun openVoiceSearch(
        query: String,
        results: List<SpatialFinItem> = emptyList(),
        error: String? = null,
        loading: Boolean = false,
    ) {
        voiceSearchQuery = query
        voiceSearchLoading = loading
        voiceSearchError = error
        voiceSearchResults = results
        activeDialog = "voice_search"
    }

    val sessionController =
        remember(player, viewModel, activity) {
            PlayerSessionController(
                viewModel = viewModel,
                player = player,
                onControlsVisibilityChange = { controlsVisible = it },
                onNavigateBack = onBackClick,
                onShowVoiceSearch = ::openVoiceSearch,
                onShowSyncPlayDialog = {
                    activeDialog = "syncplay"
                    viewModel.refreshSyncPlayGroups()
                },
                onGoHome = {
                    val launchIntent =
                        Intent().setClassName(context, "dev.spatialfin.MainActivity")
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    runCatching {
                        context.startActivity(launchIntent)
                        activity?.finish()
                        true
                    }.getOrElse { false }
                },
                onCloseApp = { activity?.finishAffinity() },
                onLaunchSearchResult = {
                    onLaunchSearchResult(it)
                    activeDialog = null
                },
                onSearchQuery = onSearchQuery,
                getAvailableSyncPlayGroups = { latestSyncPlayGroups.value },
                setPassthroughEnabled = { enabled -> passthroughOverrideEnabled = enabled },
                getPassthroughEnabled = { passthroughEnabled },
            )
        }

    fun speakAssistantReply(text: String, languageHint: String?) {
        if (!assistantSpokenReplies || !tts.canSpeak()) return
        resumePlaybackAfterAssistantSpeech = player.isPlaying
        assistantSpeechPendingStart = true
        assistantSpeechStarted = false
        if (resumePlaybackAfterAssistantSpeech) {
            player.pause()
        }
        tts.speak(text, languageHint, assistantVoicePreference)
    }

    fun requestVoiceCommand() {
        if (voiceState == VoiceState.LISTENING || voiceState == VoiceState.PROCESSING) {
            return
        }
        if (!voiceControlEnabled) {
            voiceFeedback = "Voice control disabled"
            return
        }
        if (!voiceService.isAvailable()) {
            voiceFeedback = "On-device speech unavailable"
            return
        }
        if (!hasAudioPermission) {
            shouldStartVoiceCapture = true
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        shouldStartVoiceCapture = false
        controlsVisible = true
        resetAutoHide()
        voiceCaptureStartedAtMs = System.currentTimeMillis()
        startVoiceCapture(
            voiceService = voiceService,
            commandCoordinator = commandCoordinator,
            chatEngine = chatEngine,
            recentSubtitles = recentSubtitles.toList(),
            player = player,
            viewModel = viewModel,
            uiState = uiState,
            controlsVisible = controlsVisible,
            controller = sessionController,
            telemetryStore = telemetryStore,
            onSearchQuery = onSearchQuery,
            assistantPreferences = AssistantPreferences(
                verbosity = assistantVerbosity,
                spoilerPolicy = assistantSpoilerPolicy,
                spokenRepliesEnabled = assistantSpokenReplies,
            ),
            passthroughEnabled = passthroughEnabled,
            responseLanguageHint =
                selectedTrackLanguage(player, C.TRACK_TYPE_TEXT)
                    ?: selectedTrackLanguage(player, C.TRACK_TYPE_AUDIO)
                    ?: selectedTrackName(player, C.TRACK_TYPE_TEXT)
                    ?: selectedTrackName(player, C.TRACK_TYPE_AUDIO),
            conversationHistory = conversationHistory,
            onConversationTurn = { q, a ->
                conversationHistory.add(q to a)
                if (conversationHistory.size > 6) conversationHistory.removeAt(0)
            },
            onGetSuggestions = { viewModel.repository.getSuggestions() },
            onResult = { voiceFeedback = it },
            onSpokenReply = { text, languageHint -> speakAssistantReply(text, languageHint) },
            scope = coroutineScope,
        )
    }

    // Reset conversation history when the playing item changes
    LaunchedEffect(uiState.currentItemTitle) { conversationHistory.clear() }

    // Auto-hide controls after 10 s during playback
    LaunchedEffect(controlsVisible, hideTimestamp, isPlaying) {
        if (controlsVisible && isPlaying && !isLocked) {
            delay(10_000L)
            controlsVisible = false
        }
    }

    LaunchedEffect(isActuallyPaused) {
        if (isActuallyPaused) {
            controlsVisible = true
            resetAutoHide()
        }
    }

    // Immersive environment: blackout during playback unless passthrough is forced on.
    LaunchedEffect(passthroughEnabled) {
        val environment = session.scene.spatialEnvironment
        try {
            environment.preferredSpatialEnvironment =
                if (passthroughEnabled) null
                else SpatialEnvironment.SpatialEnvironmentPreference(skybox = null, geometry = null)
        } catch (_: Exception) {}
    }

    LaunchedEffect(voiceControlEnabled) {
        if (voiceControlEnabled) {
            commandCoordinator.initialize()
            chatEngine.initialize()
        }
    }

    LaunchedEffect(session, voiceControlEnabled, hasHandTrackingPermission) {
        if (!voiceControlEnabled || !hasHandTrackingPermission) return@LaunchedEffect
        runCatching {
            session.configure(session.config.copy(handTracking = HandTrackingMode.BOTH))
        }.onFailure { Timber.w(it, "VOICE: Hand tracking not available") }
    }

    LaunchedEffect(voiceControlEnabled) {
        if (voiceControlEnabled && !hasHandTrackingPermission && activity != null) {
            handTrackingPermissionLauncher.launch(HAND_TRACKING_PERMISSION)
        }
    }

    LaunchedEffect(hasAudioPermission, shouldStartVoiceCapture) {
        if (hasAudioPermission && shouldStartVoiceCapture) {
            shouldStartVoiceCapture = false
            requestVoiceCommand()
        }
    }

    LaunchedEffect(pinchDetector, voiceControlEnabled, hasHandTrackingPermission) {
        if (!voiceControlEnabled || !hasHandTrackingPermission) return@LaunchedEffect
        pinchDetector?.gestureStates?.collect { event ->
            when (event) {
                is SecondaryHandPinchDetector.GestureState.Arming -> {
                    voiceGestureArmingProgress = event.progress
                    voiceGestureHint = event.hint
                }
                SecondaryHandPinchDetector.GestureState.Started -> {
                    voiceGestureArmingProgress = 1f
                    voiceGestureHint = null
                    if (!gestureVoiceSessionActive && voiceState == VoiceState.IDLE) {
                        gestureVoiceSessionActive = true
                        requestVoiceCommand()
                    }
                }
                SecondaryHandPinchDetector.GestureState.Ended -> {
                    voiceGestureArmingProgress = 0f
                    voiceGestureHint = null
                    gestureVoiceSessionActive = false
                    val listeningDurationMs = System.currentTimeMillis() - voiceCaptureStartedAtMs
                    if (
                        voiceState == VoiceState.LISTENING &&
                        listeningDurationMs >= MIN_VOICE_LISTEN_MS_AFTER_GESTURE
                    ) {
                        voiceService.stopListening()
                    } else {
                        Timber.d("VOICE: Ignoring early gesture release durationMs=%d state=%s", listeningDurationMs, voiceState)
                    }
                }
                SecondaryHandPinchDetector.GestureState.Idle -> {
                    voiceGestureArmingProgress = 0f
                    gestureVoiceSessionActive = false
                    if (voiceState != VoiceState.LISTENING) {
                        voiceGestureHint = null
                    }
                }
            }
        }
    }

    LaunchedEffect(voiceState, isTtsSpeaking) {
        if (voiceState == VoiceState.LISTENING) {
            player.volume = 0.2f
            voiceGestureHint = null
        } else if (isTtsSpeaking) {
            player.volume = 0.5f
        } else {
            player.volume = 1.0f
        }
    }

    LaunchedEffect(voiceFeedback, isTtsSpeaking) {
        if (voiceFeedback != null && !isTtsSpeaking && voiceFeedback != "Thinking...") {
            delay(4_000L)
            if (!isTtsSpeaking) {
                voiceFeedback = null
            }
        }
    }

    LaunchedEffect(isTtsSpeaking, assistantSpeechPendingStart, assistantSpeechStarted) {
        if (assistantSpeechPendingStart && isTtsSpeaking) {
            assistantSpeechPendingStart = false
            assistantSpeechStarted = true
        } else if (assistantSpeechPendingStart && !isTtsSpeaking) {
            delay(1_500L)
            if (assistantSpeechPendingStart && !isTtsSpeaking) {
                assistantSpeechPendingStart = false
                if (resumePlaybackAfterAssistantSpeech) {
                    player.play()
                }
                resumePlaybackAfterAssistantSpeech = false
                assistantSpeechStarted = false
            }
        } else if (!isTtsSpeaking && assistantSpeechStarted) {
            if (resumePlaybackAfterAssistantSpeech) {
                player.play()
            }
            resumePlaybackAfterAssistantSpeech = false
            assistantSpeechStarted = false
        }
    }

    // Subtitle cue listener
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onCues(cueGroup: CueGroup) {
                currentCues = cueGroup.cues
                if (cueGroup.cues.isNotEmpty()) {
                    val first = cueGroup.cues[0].text?.toString()
                    if (first != null) {
                        val now = System.currentTimeMillis()
                        recentSubtitles.add(now to first)
                        recentSubtitles.removeAll { now - it.first > 60_000L }
                    }
                }
            }
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                    libassRenderer?.clearCache()
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                subtitleTrackVersion++
                Timber.i(
                    "subtitle: XR track snapshot updated version=%d textGroups=%d",
                    subtitleTrackVersion,
                    tracks.groups.count { it.type == C.TRACK_TYPE_TEXT },
                )
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // Initialize libass when track info becomes available
    LaunchedEffect(player, subtitleTrackVersion, currentStereoMode, libassUsagePref) {
        val stereoPlayback = currentStereoMode == "sbs" || currentStereoMode == "top_bottom" || currentStereoMode == "multiview"
        useLibass = !stereoPlayback &&
            libassRenderer != null &&
            LibassSubtitleHelper.shouldUseLibass(player, emptyList(), libassUsagePref)
        if (!useLibass) {
            hasLibassContent = false
            libassBitmap = null
        }
        Timber.i(
            "subtitle: useLibass=%b (renderer=%b pref=%s stereoMode=%s trackVersion=%d)",
            useLibass,
            libassRenderer != null,
            libassUsagePref,
            currentStereoMode,
            subtitleTrackVersion,
        )
    }

    // Render libass subtitles in the playback polling loop
    LaunchedEffect(useLibass) {
        while (useLibass) {
            // Use player.currentPosition directly instead of the 500ms-polled state
            // to ensure smooth animations and correct timing at 30fps.
            val pos = player.currentPosition
            val result = libassRenderer?.renderFrame(pos)
            if (result != null) {
                hasLibassContent = result.hasContent
                if (result.bitmap != null) {
                    libassBitmap = result.bitmap
                    libassFrameVersion++
                }
                if (
                    videoOverlayAttachmentVersion > 0 &&
                    videoOverlayAttachmentVersion > lastLoggedMoveFrameVersion &&
                    (result.bitmap != null || result.hasContent)
                ) {
                    lastLoggedMoveFrameVersion = videoOverlayAttachmentVersion
                    Timber.i(
                        "subtitle: first frame after move overlayVersion=%d hasContent=%b bitmap=%b frameVersion=%d posMs=%d",
                        videoOverlayAttachmentVersion,
                        result.hasContent,
                        result.bitmap != null,
                        libassFrameVersion,
                        pos,
                    )
                }
            }
            delay(33) // ~30fps for subtitle updates (smooth for \move, \fad)
        }
    }

    // Navigate back when playback ends (movie finished or last episode of season)
    LaunchedEffect(Unit) {
        viewModel.eventsChannelFlow.collect { event ->
            if (event is PlayerEvents.NavigateBack) {
                onBackClick()
            }
        }
    }

    // Poll playback state (position, duration, isPlaying, segments)
    LaunchedEffect(player) {
        while (true) {
            currentPosition = player.currentPosition
            duration = player.duration.coerceAtLeast(0L)
            isPlaying = player.isPlaying
            playbackState = player.playbackState
            viewModel.updateCurrentSegment()
            delay(500)
        }
    }

    val videoDepth = 5.0f
    val uiDepth = 1.25f
    val overlayProjectionScale = uiDepth / videoDepth

    // --- SceneCore video entity ---
    val videoRootEntity = remember { mutableStateOf<GroupEntity?>(null) }
    val uiRootEntity = remember { mutableStateOf<GroupEntity?>(null) }
    val subtitleRootEntity = remember { mutableStateOf<GroupEntity?>(null) }
    val videoEntity = remember { mutableStateOf<SurfaceEntity?>(null) }
    val mascotEntity = remember { mutableStateOf<GltfModelEntity?>(null) }
    val mascotModel = remember { mutableStateOf<GltfModel?>(null) }
    // Separate entity for the cast panel — deliberately has NO MovableComponent so
    // scroll gestures inside the panel are never intercepted by the video's grab handle.
    val castPanelEntity = remember { mutableStateOf<GroupEntity?>(null) }
    val movableComponent = remember { mutableStateOf<androidx.xr.scenecore.MovableComponent?>(null) }
    // Cache the video root pose reported by move callbacks so the UI root can mirror it
    // and the pose can be persisted without relying on SceneCore's internal drag overlay.
    val lastReportedMovePose = remember { mutableStateOf<androidx.xr.runtime.math.Pose?>(null) }

    DisposableEffect(session) {
        val savedPose = loadSavedPlayerRootPose(viewModel)
        val projectedOverlayPose = projectPoseFromOrigin(savedPose, overlayProjectionScale)
        val initialShape = SurfaceEntity.Shape.Quad(FloatSize2d(10.0f, 5.625f))
        try {
            val videoRoot = GroupEntity.create(session, "PlayerVideoRoot", savedPose)
            val uiRoot = GroupEntity.create(session, "PlayerUiRoot", projectedOverlayPose)
            val subtitleRoot = GroupEntity.create(session, "PlayerSubtitleRoot", projectedOverlayPose)
            videoRootEntity.value = videoRoot
            uiRootEntity.value = uiRoot
            subtitleRootEntity.value = subtitleRoot

            val entity = SurfaceEntity.create(
                session = session,
                pose = Pose(Vector3(0f, 0f, -5.0f), Quaternion.Identity),
                shape = initialShape,
                stereoMode = mapStereoMode(currentStereoMode) ?: SurfaceEntity.StereoMode.MONO,
            ).apply {
                mediaBlendingMode = SurfaceEntity.MediaBlendingMode.OPAQUE
            }
            videoRoot.addChild(entity)
            videoEntity.value = entity
            lastReportedMovePose.value = savedPose

            val movable = androidx.xr.scenecore.MovableComponent.createCustomMovable(
                session,
                false,
                ContextCompat.getMainExecutor(context),
                object : androidx.xr.scenecore.EntityMoveListener {
                    override fun onMoveStart(
                        entity: androidx.xr.scenecore.Entity,
                        initialInputRay: androidx.xr.runtime.math.Ray,
                        initialPose: androidx.xr.runtime.math.Pose,
                        initialScale: Float,
                        initialParent: androidx.xr.scenecore.Entity,
                    ) {
                        runCatching {
                            videoRoot.setPose(initialPose)
                            videoRoot.setScale(initialScale)
                        }
                        syncProjectedOverlayRoots(initialPose, initialScale, uiRoot, subtitleRoot, overlayProjectionScale)
                        lastReportedMovePose.value = initialPose
                        Timber.i(
                            "subtitle: move start targetIsVideoRoot=%b targetClass=%s",
                            entity == videoRoot,
                            entity.javaClass.simpleName,
                        )
                    }

                    override fun onMoveUpdate(
                        entity: androidx.xr.scenecore.Entity,
                        currentInputRay: androidx.xr.runtime.math.Ray,
                        currentPose: androidx.xr.runtime.math.Pose,
                        currentScale: Float,
                    ) {
                        runCatching {
                            videoRoot.setPose(currentPose)
                            videoRoot.setScale(currentScale)
                        }
                        syncProjectedOverlayRoots(currentPose, currentScale, uiRoot, subtitleRoot, overlayProjectionScale)
                        lastReportedMovePose.value = currentPose
                    }

                    override fun onMoveEnd(
                        entity: androidx.xr.scenecore.Entity,
                        finalInputRay: androidx.xr.runtime.math.Ray,
                        finalPose: androidx.xr.runtime.math.Pose,
                        finalScale: Float,
                        updatedParent: androidx.xr.scenecore.Entity?,
                    ) {
                        runCatching {
                            videoRoot.setPose(finalPose)
                            videoRoot.setScale(finalScale)
                        }
                        syncProjectedOverlayRoots(finalPose, finalScale, uiRoot, subtitleRoot, overlayProjectionScale)
                        lastReportedMovePose.value = finalPose
                        videoOverlayAttachmentVersion++
                        savePlayerRootPose(viewModel, finalPose)
                        Timber.i(
                            "subtitle: move end targetIsVideoRoot=%b targetClass=%s overlayVersion=%d useLibass=%b hasContent=%b frameVersion=%d",
                            entity == videoRoot,
                            entity.javaClass.simpleName,
                            videoOverlayAttachmentVersion,
                            useLibass,
                            hasLibassContent,
                            libassFrameVersion,
                        )
                    }
                },
            )
            videoRoot.addComponent(movable)
            movableComponent.value = movable

            // Cast panel root: centered, 3.5 m in front, no movable component.
            val castRoot = GroupEntity.create(session, "CastPanelRoot", Pose(Vector3(0f, 0f, -3.5f), Quaternion.Identity))
            castPanelEntity.value = castRoot
        } catch (_: Exception) {}

        onDispose {
            videoRootEntity.value?.let { root ->
                val finalPose = lastReportedMovePose.value ?: safeGetEntityPose(root)
                finalPose?.let { savePlayerRootPose(viewModel, it) }
            }
            voiceService.destroy()
            commandCoordinator.destroy()
            chatEngine.destroy()
            tts.destroy()
            videoEntity.value?.dispose()
            videoEntity.value = null
            mascotEntity.value?.dispose()
            videoRootEntity.value?.dispose()
            videoRootEntity.value = null
            uiRootEntity.value?.dispose()
            uiRootEntity.value = null
            subtitleRootEntity.value?.dispose()
            subtitleRootEntity.value = null
            mascotEntity.value = null
            mascotModel.value?.close()
            mascotModel.value = null
            movableComponent.value = null
            castPanelEntity.value?.dispose()
            castPanelEntity.value = null
            try { session.scene.spatialEnvironment.preferredSpatialEnvironment = null } catch (_: Exception) {}
        }
    }

    LaunchedEffect(session, videoRootEntity.value) {
        videoRootEntity.value ?: return@LaunchedEffect
        if (mascotEntity.value != null) return@LaunchedEffect
        if (!session.scene.spatialCapabilities.contains(SpatialCapability.SPATIAL_3D_CONTENT)) {
            Timber.i("Skipping paused mascot: SPATIAL_3D_CONTENT not available")
            return@LaunchedEffect
        }
        runCatching {
            val gltfModel = GltfModel.create(session, Paths.get("models", "spatialfin.glb"))
            mascotModel.value = gltfModel
            GltfModelEntity.create(session, gltfModel).also { entity ->
                entity.parent = session.scene.activitySpace
                entity.setPose(PausedMascotPose)
                entity.setScale(1.35f)
                entity.setEnabled(false)
                mascotEntity.value = entity
                Timber.i("Paused mascot loaded and attached to activity space")
            }
        }.onFailure { e ->
            if (e is CancellationException) throw e
            Timber.w(e, "Unable to load paused mascot model")
        }
    }

    LaunchedEffect(showPausedMascot, mascotEntity.value) {
        val mascot = mascotEntity.value ?: return@LaunchedEffect
        safelyToggleMascotEntity(mascot, showPausedMascot)
    }

    LaunchedEffect(videoRootEntity.value, uiRootEntity.value, subtitleRootEntity.value, videoEntity.value, mascotEntity.value) {
        while (true) {
            val entities = mutableListOf<EntityInfo>()
            videoRootEntity.value?.let { e ->
                val p = safeGetEntityPose(e)
                entities.add(EntityInfo("VideoRoot", e.javaClass.simpleName, p?.translation?.x ?: 0f, p?.translation?.y ?: 0f, p?.translation?.z ?: 0f, 1f, true))
            }
            uiRootEntity.value?.let { e ->
                val p = safeGetEntityPose(e)
                entities.add(EntityInfo("UiRoot", e.javaClass.simpleName, p?.translation?.x ?: 0f, p?.translation?.y ?: 0f, p?.translation?.z ?: 0f, 1f, true))
            }
            subtitleRootEntity.value?.let { e ->
                val p = safeGetEntityPose(e)
                entities.add(EntityInfo("SubtitleRoot", e.javaClass.simpleName, p?.translation?.x ?: 0f, p?.translation?.y ?: 0f, p?.translation?.z ?: 0f, 1f, true))
            }
            videoEntity.value?.let { e ->
                val p = safeGetEntityPose(e)
                entities.add(EntityInfo("VideoSurface", e.javaClass.simpleName, p?.translation?.x ?: 0f, p?.translation?.y ?: 0f, p?.translation?.z ?: 0f, 1f, true))
            }
            mascotEntity.value?.let { e ->
                val p = safeGetEntityPose(e)
                entities.add(EntityInfo("Mascot", e.javaClass.simpleName, p?.translation?.x ?: 0f, p?.translation?.y ?: 0f, p?.translation?.z ?: 0f, 1f, true))
            }
            McpBridge.updateScene(SceneState(entities))
            delay(1000L)
        }
    }

    LaunchedEffect(videoRootEntity.value, uiRootEntity.value, subtitleRootEntity.value) {
        val videoRoot = videoRootEntity.value ?: return@LaunchedEffect
        val uiRoot = uiRootEntity.value ?: return@LaunchedEffect
        val subtitleRoot = subtitleRootEntity.value ?: return@LaunchedEffect
        while (true) {
            val poseToMirror = safeGetEntityPose(videoRoot) ?: lastReportedMovePose.value
            val scaleToMirror = runCatching { videoRoot.getScale() }.getOrDefault(1f)
            poseToMirror?.let { pose ->
                lastReportedMovePose.value = pose
                syncProjectedOverlayRoots(pose, scaleToMirror, uiRoot, subtitleRoot, overlayProjectionScale)
            }
            delay(100L)
        }
    }

    LaunchedEffect(videoOverlayAttachmentVersion) {
        if (videoOverlayAttachmentVersion <= 0) return@LaunchedEffect
        delay(750L)
        Timber.i(
            "subtitle: post-move state overlayVersion=%d useLibass=%b hasContent=%b frameVersion=%d bitmap=%b",
            videoOverlayAttachmentVersion,
            useLibass,
            hasLibassContent,
            libassFrameVersion,
            libassBitmap != null,
        )
    }

    LaunchedEffect(videoRootEntity.value) {
        val root = videoRootEntity.value ?: return@LaunchedEffect
        while (true) {
            val poseToSave = lastReportedMovePose.value ?: safeGetEntityPose(root)
            poseToSave?.let { savePlayerRootPose(viewModel, it) }
            delay(1_000L)
        }
    }

            var playerInitialized by remember { mutableStateOf(false) }
    LaunchedEffect(player, videoEntity.value) {
            videoEntity.value?.let { entity ->
                player.setVideoSurface(entity.getSurface())
                if (!playerInitialized) {
                    when {
                        localMediaId != null -> {
                            viewModel.initializeLocalPlayer(
                                localMediaId = localMediaId,
                                startFromBeginning = startFromBeginning,
                            )
                        }
                        networkVideoId != null -> {
                            viewModel.initializeNetworkPlayer(
                                networkVideoId = networkVideoId,
                                startFromBeginning = startFromBeginning,
                            )
                        }
                        itemId != null -> {
                            viewModel.initializePlayer(
                                itemId = itemId,
                                itemKind = itemKind,
                                startFromBeginning = startFromBeginning,
                                mediaSourceIndex = mediaSourceIndex,
                                maxBitrate = maxBitrate,
                            )
                        }
                    }
                    playerInitialized = true
                }
            }
    }
    // Update SurfaceEntity shape when video dimensions or stereo mode changes.
    // Both are consolidated here to avoid races between separate LaunchedEffects.
    LaunchedEffect(player.videoSize, currentStereoMode) {
        // 1. Recalculate video dimensions from the player if available.
        val videoSize = player.videoSize
        
        if (videoSize.width > 0 && videoSize.height > 0) {
            var aspectRatio = videoSize.width.toFloat() / videoSize.height.toFloat()
            if (currentStereoMode == "sbs" && aspectRatio > 3.0f) aspectRatio /= 2f
            else if (currentStereoMode == "top_bottom" && videoSize.height > videoSize.width) aspectRatio *= 2f
            videoWidth = 10.0f
            videoHeight = videoWidth / aspectRatio
        } else {
            videoWidth = 10.0f
            videoHeight = videoWidth / (16f / 9f)
        }
        Timber.d(
            "subtitle: video geometry source=%dx%d ratio=%.4f stereo=%s world=%.3fm x %.3fm",
            videoSize.width,
            videoSize.height,
            if (videoHeight > 0f) videoWidth / videoHeight else 0f,
            currentStereoMode,
            videoWidth,
            videoHeight,
        )

        // 2. Apply the correct shape and pose to the entity.
        val entity = videoEntity.value ?: return@LaunchedEffect
        // Flat mode — simple quad with entity-level stereo mode.
        entity.shape = SurfaceEntity.Shape.Quad(FloatSize2d(videoWidth, videoHeight))
        entity.stereoMode = mapStereoMode(currentStereoMode) ?: SurfaceEntity.StereoMode.MONO
        entity.setPose(Pose(Vector3(0f, 0f, -5.0f), Quaternion.Identity))
    }

    // Frame rate matching: apply content frame rate to the video surface and UI compositing layer.
    val currentFrameRate by viewModel.currentFrameRate.collectAsState()
    LaunchedEffect(currentFrameRate, videoEntity.value) {
        val frameRate = currentFrameRate.takeIf { it > 0f } ?: return@LaunchedEffect
        val surface = videoEntity.value?.getSurface() ?: return@LaunchedEffect
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            runCatching {
                surface.setFrameRate(
                    frameRate,
                    android.view.Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                    android.view.Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS,
                )
                Timber.i("frame-rate: applied %.4f fps to video surface", frameRate)
            }.onFailure { Timber.w(it, "frame-rate: failed to set surface frame rate") }
        }
        // Complementary hint for the UI compositing layer
        (context as? Activity)?.window?.let { window ->
            window.attributes = window.attributes.apply { preferredRefreshRate = frameRate }
        }
    }

    // --- Layout calculations ---
    val uiScaleFactor = uiDepth / videoDepth
    val subtitleScaleFactor = uiDepth / videoDepth
    val scaledVideoWidthDp = videoWidth * subtitleScaleFactor * 1000f
    val scaledVideoHeightDp = videoHeight * subtitleScaleFactor * 1000f
    val subtitlePanelWidthDp = scaledVideoWidthDp
    val subtitlePanelHeightDp = scaledVideoHeightDp
    val finalSubtitleSize = xrSubtitleSize * (subtitlePanelHeightDp / 600f).coerceAtLeast(1f)
    val controlsReferenceHeightDp = videoHeight * uiScaleFactor * 1000f
    // Controls sit further below the video surface to prevent overlap.
    // Reduced offset from 1400f to 1100f to bring them slightly higher/closer to video bottom.
    val controlsPanelY = -(controlsReferenceHeightDp / 2f + 1100f)

    val subtitlePanelZDp = -uiDepth * 1000f
    // XR guide recommended spawn depth: 1.75 m (-1750 dp) from user.
    // We are bringing it closer to 1.25 m for a more reachable, "IMAX" feel for controls.
    val uiAnchorZDp = -uiDepth * 1000f

    LaunchedEffect(subtitlePanelWidthDp, subtitlePanelHeightDp, density.density, player.videoSize) {
        val renderWidth = (subtitlePanelWidthDp * density.density).toInt().coerceIn(1280, 7680)
        val renderHeight = (subtitlePanelHeightDp * density.density).toInt().coerceIn(720, 4320)
        val videoW = player.videoSize.width
        val videoH = player.videoSize.height
        Timber.d(
            "subtitle: panel geometry mode=%s useLibass=%b panel=%.1fdp x %.1fdp density=%.3f render=%dx%d video=%dx%d finalTextSp=%.1f z=%.1fdp",
            currentStereoMode,
            useLibass,
            subtitlePanelWidthDp,
            subtitlePanelHeightDp,
            density.density,
            renderWidth,
            renderHeight,
            videoW,
            videoH,
            finalSubtitleSize,
            subtitlePanelZDp,
        )
        // Only resize when we have valid video dimensions so storage size is set correctly.
        // Without storage size, libass misscales fonts authored at a different resolution.
        if (videoW > 0 && videoH > 0) {
            libassRenderer?.resize(renderWidth, renderHeight, videoW, videoH)
        }
    }

    // Controls at same depth as UI anchor.
    val controlsZDp = -uiDepth * 1000f

    Subspace {
        val subtitleRoot = subtitleRootEntity.value
        if (subtitleRoot != null) {
            SceneCoreEntity(factory = { subtitleRoot }, modifier = SubspaceModifier) {
                // Keep the subtitle panel entity alive even when there is no subtitle content.
                // Recreating the SpatialPanel when a new line appears causes SceneCore to
                // briefly render it at the subtitle root origin before the panel offset lands,
                // which shows up as a flash below the screen.
                if (useLibass) {
                    val currentBitmap = libassBitmap
                    val showLibassContent = hasLibassContent && currentBitmap != null
                    SpatialPanel(
                        modifier = SubspaceModifier
                            .width(subtitlePanelWidthDp.dp)
                            .height(subtitlePanelHeightDp.dp)
                            .offset(x = 0.dp, y = 0.dp, z = (subtitlePanelZDp + 50f).dp),
                    ) {
                        if (showLibassContent) {
                            key(videoOverlayAttachmentVersion, libassFrameVersion) {
                                Image(
                                    painter = BitmapPainter(currentBitmap!!.asImageBitmap(), filterQuality = FilterQuality.High),
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Fit,
                                )
                            }
                        }
                    }
                } else {
                    SpatialPanel(
                        modifier = SubspaceModifier
                            .width(subtitlePanelWidthDp.dp)
                            .height(subtitlePanelHeightDp.dp)
                            .offset(x = 0.dp, y = 0.dp, z = subtitlePanelZDp.dp),
                    ) {
                        AndroidView(
                            factory = { context ->
                                SubtitleView(context).apply {
                                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                                    setBottomPaddingFraction(0.04f)
                                    setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, finalSubtitleSize)
                                    setStyle(captionStyle)
                                }
                            },
                            update = { view ->
                                view.setStyle(captionStyle)
                                view.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, finalSubtitleSize)
                                view.setCues(currentCues)
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }

                if (voiceState == VoiceState.LISTENING || voiceGestureHint != null) {
                    SpatialPanel(
                        modifier = SubspaceModifier
                            .width(subtitlePanelWidthDp.dp)
                            .height(subtitlePanelHeightDp.dp)
                            .offset(x = 0.dp, y = 0.dp, z = subtitlePanelZDp.dp),
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
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

        val uiRoot = uiRootEntity.value
        if (uiRoot != null) {
            SceneCoreEntity(factory = { uiRoot }, modifier = SubspaceModifier) {
        // ── Control Panel ────────────────────────────────────────────────────────────
        // Floats below the video.  Secondary controls are in an Orbiter on the right
        // so the main panel stays uncluttered (IMAX principle: screen first, UI second).
        SpatialPanel(
            modifier = SubspaceModifier
                .width(1800.dp)
                .height(800.dp)
                .offset(x = 0.dp, y = controlsPanelY.dp, z = controlsZDp.dp),
            resizePolicy = ResizePolicy(),
        ) {
            // ── Orbiter: secondary controls (right edge, hidden when locked/controls hidden) ──
            if ((controlsVisible || isActuallyPaused) && !isLocked) {
                Orbiter(
                    position = ContentEdge.End,
                    alignment = Alignment.CenterVertically,
                    offset = 40.dp,
                ) {
                    SecondaryControlsOrbiter(
                        onAudioClick = { activeDialog = "audio"; resetAutoHide() },
                        onSubtitleClick = { activeDialog = "subtitle"; resetAutoHide() },
                        onSpeedClick = { activeDialog = "speed"; resetAutoHide() },
                        onQualityClick = { activeDialog = "quality"; resetAutoHide() },
                        onSyncPlayClick = {
                            activeDialog = "syncplay"
                            viewModel.refreshSyncPlayGroups()
                            resetAutoHide()
                        },
                        onCastCrewClick = { activeDialog = "cast_crew"; resetAutoHide() },
                        onVoiceClick = {
                            requestVoiceCommand()
                            resetAutoHide()
                        },
                        voiceControlEnabled = voiceControlEnabled,
                        voiceAvailable = voiceService.isAvailable(),
                        voiceState = voiceState,
                        syncPlayActive = syncPlayState.activeGroup != null,
                    )
                }
            }

            // ── Main control content ──────────────────────────────────────────────
            // When controls are hidden the whole panel area is tappable.  A faint glass
            // background makes the tap target discoverable without cluttering the view.
            Box(modifier = Modifier.fillMaxSize()) {
                AnimatedVisibility(
                    visible = controlsVisible || isActuallyPaused,
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    ControlPanelUI(
                        viewModel = viewModel,
                        player = player,
                        uiState = uiState,
                        isPlaying = isPlaying,
                        currentPosition = currentPosition,
                        duration = duration,
                        isLocked = isLocked,
                        spatialAudioAvailable = spatialAudioAvailable,
                        onLockToggle = { isLocked = !isLocked },
                        onChaptersClick = {
                            activeDialog = "chapters"
                            resetAutoHide()
                        },
                        onBackClick = onBackClick,
                        resetAutoHide = { resetAutoHide() },
                    )
                }

                if (!controlsVisible && !isActuallyPaused) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(48.dp))
                            .background(Color.Transparent)
                            .pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        if (event.type == PointerEventType.Enter ||
                                            event.type == PointerEventType.Move) {
                                            controlsVisible = true
                                            resetAutoHide()
                                        }
                                    }
                                }
                            }
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {
                                    controlsVisible = true
                                    resetAutoHide()
                                },
                            ),
                    )
                }

            }

            // ── SpatialDialogs ────────────────────────────────────────────────────
            // Placed inside the control SpatialPanel: when shown, the SDK pushes the
            // panel back 125 dp and renders the dialog floating in front — proper XR
            // depth hierarchy without manual zIndex hacks.
            if (activeDialog == "audio") {
                SpatialDialog(onDismissRequest = { activeDialog = null }) {
                    TrackSelectionDialogContent(
                        title = stringResource(LocalR.string.select_audio_track),
                        player = player,
                        trackType = C.TRACK_TYPE_AUDIO,
                        onTrackSelected = { index -> viewModel.switchToTrack(C.TRACK_TYPE_AUDIO, index) },
                        onDismiss = { activeDialog = null },
                    )
                }
            }
            if (activeDialog == "subtitle") {
                SpatialDialog(onDismissRequest = { activeDialog = null }) {
                    TrackSelectionDialogContent(
                        title = stringResource(LocalR.string.select_subtitle_track),
                        player = player,
                        trackType = C.TRACK_TYPE_TEXT,
                        onTrackSelected = { index -> viewModel.switchToTrack(C.TRACK_TYPE_TEXT, index) },
                        onDismiss = { activeDialog = null },
                        onSearchSubtitles = { activeDialog = "search_subtitles" },
                    )
                }
            }
            if (activeDialog == "search_subtitles") {
                SpatialDialog(onDismissRequest = { 
                    activeDialog = null
                    viewModel.clearSubtitleSearchState() 
                }) {
                    SubtitleSearchDialogContent(
                        viewModel = viewModel,
                        onDismiss = {
                            activeDialog = null
                            viewModel.clearSubtitleSearchState()
                        }
                    )
                }
            }
            if (activeDialog == "speed") {
                SpatialDialog(onDismissRequest = { activeDialog = null }) {
                    SpeedDialogContent(
                        currentSpeed = viewModel.playbackSpeed,
                        onSpeedSelected = { speed -> viewModel.selectSpeed(speed) },
                        onDismiss = { activeDialog = null },
                    )
                }
            }
            if (activeDialog == "chapters") {
                SpatialDialog(onDismissRequest = { activeDialog = null }) {
                    ChaptersDialogContent(
                        chapters = uiState.currentChapters,
                        currentPosition = currentPosition,
                        onPreviousChapter = {
                            viewModel.seekToPreviousChapter()
                            resetAutoHide()
                        },
                        onNextChapter = {
                            viewModel.seekToNextChapter()
                            resetAutoHide()
                        },
                        onSelectChapter = { index ->
                            viewModel.seekToChapterIndex(index)
                            resetAutoHide()
                            activeDialog = null
                        },
                        onDismiss = { activeDialog = null },
                    )
                }
            }
            if (activeDialog == "quality") {
                SpatialDialog(onDismissRequest = { activeDialog = null }) {
                    QualityDialogContent(
                        currentMaxBitrate = viewModel.appPreferences.getValue(viewModel.appPreferences.playerMaxBitrate),
                        onQualitySelected = { bitrate ->
                            if (itemId != null) {
                                viewModel.changeQuality(itemId, itemKind, bitrate)
                            }
                        },
                        onDismiss = { activeDialog = null },
                    )
                }
            }
            if (activeDialog == "cast_crew") {
                SpatialDialog(onDismissRequest = { activeDialog = null }) {
                    CastCrewDialogContent(
                        title = uiState.currentItemTitle,
                        overview = uiState.currentOverview,
                        people = uiState.currentPeople,
                        onDismiss = { activeDialog = null },
                    )
                }
            }
            if (activeDialog == "syncplay") {
                SpatialDialog(onDismissRequest = { activeDialog = null }) {
                    SyncPlayDialogContent(
                        state = syncPlayState,
                        onRefresh = { viewModel.refreshSyncPlayGroups() },
                        onCreateGroup = { viewModel.createSyncPlayGroup() },
                        onJoinGroup = { viewModel.joinSyncPlayGroup(it) },
                        onLeaveGroup = { viewModel.leaveSyncPlayGroup() },
                        onDismiss = { activeDialog = null },
                    )
                }
            }
            if (activeDialog == "voice_search") {
                SpatialDialog(onDismissRequest = { activeDialog = null }) {
                    VoiceSearchDialogContent(
                        query = voiceSearchQuery,
                        loading = voiceSearchLoading,
                        error = voiceSearchError,
                        results = voiceSearchResults,
                        currentItemTitle = uiState.currentItemTitle,
                        onPlayResult = {
                            sessionController.clearPendingSelection()
                            onLaunchSearchResult(it)
                            activeDialog = null
                        },
                        onDismiss = { activeDialog = null },
                    )
                }
            }
        }

        // ── Contextual Skip Panel ────────────────────────────────────────────────
        if (!voiceSearchOpen) {
            uiState.currentSegment?.let { segment ->
            SpatialPanel(
                modifier = SubspaceModifier
                    .width(480.dp)
                    .height(160.dp)
                    .offset(x = 1150.dp, y = controlsPanelY.dp, z = controlsZDp.dp),
            ) {
                Surface(
                    onClick = { viewModel.skipSegment(segment); resetAutoHide() },
                    shape = RoundedCornerShape(32.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    tonalElevation = 8.dp,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            painter = painterResource(CoreR.drawable.ic_skip_forward),
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                        )
                        Spacer(Modifier.width(20.dp))
                        Text(
                            text = stringResource(uiState.currentSkipButtonStringRes),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
        }

        // ── Next Episode Panel ───────────────────────────────────────────────────
        // Floats to the LEFT of the control panel during the last 2 minutes of an episode.
        if (!voiceSearchOpen && showNextEpisodePanel) {
            SpatialPanel(
                modifier = SubspaceModifier
                    .width(900.dp)
                    .height(560.dp)
                    .offset(x = (-1350).dp, y = controlsPanelY.dp, z = controlsZDp.dp),
            ) {
                NextEpisodePanelContent(
                    nextEpisode = uiState.nextEpisode!!,
                    onPlayNext = {
                        viewModel.skipToNextItem()
                        nextEpisodePanelDismissed = true
                    },
                    onDismiss = { nextEpisodePanelDismissed = true },
                )
            }
        }

            }
        }

        // ── Cast & Info Panel (auto-visible when paused) ──────────────────────────
        // Uses its own GroupEntity (castPanelEntity) which has NO MovableComponent so
        // scroll gestures inside the panel are not intercepted by the video's grab handle.
        // The SceneCoreEntity is only added to Subspace when the panel should be visible —
        // an invisible (empty) SpatialPanel still intercepts raycasts and would block the
        // user from grabbing and moving the video entity.
        val castRoot = castPanelEntity.value
        val shouldShowCastPanel = false
        if (castRoot != null && shouldShowCastPanel) {
            SceneCoreEntity(factory = { castRoot }, modifier = SubspaceModifier) {
                SpatialPanel(
                    modifier = SubspaceModifier
                        .width(1400.dp)
                        .height(1600.dp)
                        .offset(x = 0.dp, y = 0.dp, z = 0.dp),
                ) {
                    CastCrewPanelContent(
                        title = uiState.currentItemTitle,
                        overview = uiState.currentOverview,
                        people = uiState.currentPeople,
                        onResume = { player.play() },
                    )
                }
            }
        }
    }
}

// ── Secondary Controls Orbiter ────────────────────────────────────────────────────
// Floats to the right of the control panel. Keeps the main panel clean while still
// giving one-glance access to audio, subtitle, speed, and cast & crew info.
@Composable
private fun SecondaryControlsOrbiter(
    onAudioClick: () -> Unit,
    onSubtitleClick: () -> Unit,
    onSpeedClick: () -> Unit,
    onQualityClick: () -> Unit,
    onSyncPlayClick: () -> Unit,
    onCastCrewClick: () -> Unit,
    onVoiceClick: () -> Unit,
    voiceControlEnabled: Boolean,
    voiceAvailable: Boolean,
    voiceState: VoiceState,
    syncPlayActive: Boolean,
) {
    Surface(
        shape = RoundedCornerShape(40.dp),
        color = Color.Black.copy(alpha = 0.9f),
        tonalElevation = 4.dp,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            IconButton(onClick = onAudioClick, modifier = Modifier.size(100.dp)) {
                Icon(
                    painterResource(CoreR.drawable.ic_speaker),
                    contentDescription = "Audio track",
                    tint = Color.White,
                    modifier = Modifier.size(64.dp),
                )
            }
            IconButton(onClick = onSubtitleClick, modifier = Modifier.size(100.dp)) {
                Icon(
                    painterResource(CoreR.drawable.ic_closed_caption),
                    contentDescription = "Subtitle track",
                    tint = Color.White,
                    modifier = Modifier.size(64.dp),
                )
            }
            IconButton(onClick = onSpeedClick, modifier = Modifier.size(100.dp)) {
                Icon(
                    painterResource(CoreR.drawable.ic_gauge),
                    contentDescription = "Playback speed",
                    tint = Color.White,
                    modifier = Modifier.size(64.dp),
                )
            }
            IconButton(onClick = onQualityClick, modifier = Modifier.size(100.dp)) {
                Icon(
                    painterResource(CoreR.drawable.ic_sparkles),
                    contentDescription = "Playback quality",
                    tint = Color.White,
                    modifier = Modifier.size(64.dp),
                )
            }
            IconButton(onClick = onSyncPlayClick, modifier = Modifier.size(100.dp)) {
                Icon(
                    painterResource(CoreR.drawable.ic_tv),
                    contentDescription = "SyncPlay",
                    tint = if (syncPlayActive) Color(0xFF4FC3F7) else Color.White,
                    modifier = Modifier.size(64.dp),
                )
            }
            IconButton(onClick = onCastCrewClick, modifier = Modifier.size(100.dp)) {
                Icon(
                    painterResource(CoreR.drawable.ic_user),
                    contentDescription = "Cast & crew",
                    tint = Color.White,
                    modifier = Modifier.size(64.dp),
                )
            }
            if (voiceControlEnabled) {
                IconButton(onClick = onVoiceClick, modifier = Modifier.size(100.dp)) {
                    Icon(
                        painterResource(CoreR.drawable.ic_microphone),
                        contentDescription = "Voice command",
                        tint =
                            if (!voiceAvailable) {
                                Color.White.copy(alpha = 0.45f)
                            } else {
                                when (voiceState) {
                                    VoiceState.LISTENING -> Color(0xFF4FC3F7)
                                    VoiceState.PROCESSING -> Color(0xFFFFA726)
                                    VoiceState.ERROR -> Color(0xFFEF5350)
                                    VoiceState.IDLE -> Color.White
                                }
                            },
                        modifier = Modifier.size(64.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SyncPlayDialogContent(
    state: PlayerViewModel.SyncPlayUiState,
    onRefresh: () -> Unit,
    onCreateGroup: () -> Unit,
    onJoinGroup: (UUID) -> Unit,
    onLeaveGroup: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(32.dp),
        color = Color(0xFF101114),
        tonalElevation = 8.dp,
    ) {
        Column(
            modifier = Modifier.padding(24.dp).width(540.dp).heightIn(max = 720.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "SyncPlay",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
            )
            state.activeGroup?.let { group ->
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Active group: ${group.name}", color = Color.White)
                        Text(
                            "State: ${group.state}",
                            color = Color.White.copy(alpha = 0.8f),
                        )
                        Text(
                            "Participants: ${group.participants.joinToString().ifBlank { "Just you" }}",
                            color = Color.White.copy(alpha = 0.8f),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = onLeaveGroup) {
                                Text("Leave Group")
                            }
                            TextButton(onClick = onRefresh) {
                                Text("Refresh")
                            }
                        }
                    }
                }
            } ?: Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onCreateGroup, enabled = !state.isLoading) {
                    Text("Create Group")
                }
                TextButton(onClick = onRefresh, enabled = !state.isLoading) {
                    Text("Refresh")
                }
            }

            state.statusMessage?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.secondary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            if (state.isLoading) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    androidx.compose.material3.CircularProgressIndicator()
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                state.availableGroups.forEach { group ->
                    Surface(
                        onClick = { onJoinGroup(group.id) },
                        shape = RoundedCornerShape(20.dp),
                        color = Color.White.copy(alpha = 0.06f),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(group.name, color = Color.White, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${group.participants.size} participant(s) • ${group.state}",
                                color = Color.White.copy(alpha = 0.75f),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
                if (state.availableGroups.isEmpty() && !state.isLoading) {
                    Text(
                        "No active SyncPlay groups on this server.",
                        color = Color.White.copy(alpha = 0.7f),
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Close")
                }
            }
        }
    }
}

private fun startVoiceCapture(
    voiceService: SpatialVoiceService,
    commandCoordinator: SpatialCommandCoordinator,
    chatEngine: SmartChatEngine,
    recentSubtitles: List<Pair<Long, String>>,
    player: Player,
    viewModel: PlayerViewModel,
    uiState: PlayerViewModel.UiState,
    controlsVisible: Boolean,
    controller: PlayerSessionController,
    telemetryStore: VoiceTelemetryStore,
    onSearchQuery: suspend (String) -> List<SpatialFinItem>,
    assistantPreferences: AssistantPreferences,
    passthroughEnabled: Boolean,
    responseLanguageHint: String?,
    conversationHistory: List<Pair<String, String>>,
    onConversationTurn: (String, String) -> Unit,
    onGetSuggestions: suspend () -> List<SpatialFinItem>,
    onResult: (String) -> Unit,
    onSpokenReply: (String, String?) -> Unit,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val startedAtMs = System.currentTimeMillis()
    voiceService.startListening { transcript ->
        scope.launch {
            try {
                val snapshot =
                    PlayerStateSnapshot(
                        isPlaying = player.isPlaying,
                        positionSeconds = player.currentPosition / 1_000L,
                        durationSeconds = player.duration.coerceAtLeast(0L) / 1_000L,
                        controlsVisible = controlsVisible,
                        currentItemTitle = uiState.currentItemTitle,
                        currentOverview = uiState.currentOverview,
                        currentSeriesName = uiState.currentSeriesName,
                        currentSeasonNumber = uiState.currentSeasonNumber,
                        currentEpisodeNumber = uiState.currentEpisodeNumber,
                        currentSegmentType = uiState.currentSegment?.type?.toString(),
                        currentChapterName = currentChapterName(uiState, player.currentPosition),
                        nextEpisodeTitle = uiState.nextEpisode?.name,
                        currentGenres = uiState.currentGenres,
                        currentRatings = uiState.currentRatings.map { "${it.type.label}: ${it.value}" },
                        castNames = uiState.currentPeople
                            .filter { it.type.equals("Actor", ignoreCase = true) }
                            .map { it.name },
                        directors = uiState.currentPeople
                            .filter { it.type.equals("Director", ignoreCase = true) }
                            .map { it.name },
                        writers = uiState.currentPeople
                            .filter { it.type.equals("Writer", ignoreCase = true) }
                            .map { it.name },
                        productionYear = uiState.currentProductionYear,
                        officialRating = uiState.currentOfficialRating,
                        audioTrackNames = trackNames(player, C.TRACK_TYPE_AUDIO),
                        subtitleTrackNames = trackNames(player, C.TRACK_TYPE_TEXT),
                        chapterNames = uiState.currentChapters.mapNotNull { it.name },
                        currentAudioTrack = selectedTrackName(player, C.TRACK_TYPE_AUDIO),
                        currentSubtitleTrack = selectedTrackName(player, C.TRACK_TYPE_TEXT),
                        currentAudioLanguageCode = selectedTrackLanguage(player, C.TRACK_TYPE_AUDIO),
                        currentSubtitleLanguageCode = selectedTrackLanguage(player, C.TRACK_TYPE_TEXT),
                        passthroughEnabled = passthroughEnabled,
                    )
                val parseResult = commandCoordinator.parse(transcript, snapshot)
                val action = parseResult.action
                if (action is XrPlayerAction.ChatQuery) {
                    onResult("Thinking...")
                    val response =
                        chatEngine.query(
                            question = action.query,
                            playerState = snapshot,
                            storySoFarContext = uiState.storySoFarContext,
                            recentSubtitleLines = recentSubtitles,
                            currentPositionMs = player.currentPosition,
                            assistantPreferences = assistantPreferences,
                            onSearchQuery = onSearchQuery,
                            conversationHistory = conversationHistory,
                            onGetSuggestions = onGetSuggestions,
                        )
                    if (response.text != null) {
                        onResult(response.text)
                        onConversationTurn(action.query, response.text)
                        if (assistantPreferences.spokenRepliesEnabled) {
                            onSpokenReply(response.text, responseLanguageHint)
                        }
                    } else {
                        onResult("Sorry, I couldn't process that.")
                    }
                    telemetryStore.record(
                        VoiceTelemetryEntry(
                            transcript = transcript,
                            action = "ChatQuery",
                            strategy = response.strategy,
                            latencyMs = System.currentTimeMillis() - startedAtMs,
                            success = response.text != null,
                            details = response.debugInfo,
                        )
                    )
                } else {
                    val feedback = dispatchVoiceParseResult(controller, parseResult)
                    onResult(feedback)
                    if (assistantPreferences.spokenRepliesEnabled && shouldSpeakVoiceFeedback(action)) {
                        onSpokenReply(feedback, responseLanguageHint)
                    }
                    telemetryStore.record(
                        VoiceTelemetryEntry(
                            transcript = transcript,
                            action = parseResult.action::class.simpleName ?: "Unknown",
                            strategy = parseResult.strategy.name,
                            latencyMs = System.currentTimeMillis() - startedAtMs,
                            success = parseResult.action !is XrPlayerAction.Unrecognized,
                            details = parseResult.debugInfo,
                        )
                    )
                }
            } finally {
                voiceService.resetState()
            }
        }
    }
}

private suspend fun dispatchVoiceParseResult(
    controller: PlayerSessionController,
    parseResult: VoiceParseResult,
): String = controller.dispatch(parseResult.action)

private fun shouldSpeakVoiceFeedback(action: XrPlayerAction): Boolean {
    return when (action) {
        is XrPlayerAction.ReportCurrentTime,
        is XrPlayerAction.ReportRemainingTime,
        is XrPlayerAction.ReportEndTime,
        is XrPlayerAction.ReportCurrentMedia,
        is XrPlayerAction.ReportPassthroughStatus -> true
        else -> false
    }
}

private fun currentChapterName(
    uiState: PlayerViewModel.UiState,
    currentPositionMs: Long,
): String? {
    return uiState.currentChapters
        .sortedBy { it.startPosition }
        .lastOrNull { chapter -> currentPositionMs >= chapter.startPosition }
        ?.name
}

private fun trackNames(player: Player, trackType: @C.TrackType Int): List<String> {
    return player.currentTracks.groups
        .filter { it.type == trackType && it.isSupported }
        .map { group ->
            group.getTrackFormat(0).label
                ?: group.getTrackFormat(0).language
                ?: "Unknown"
        }
}

private fun selectedTrackName(player: Player, trackType: @C.TrackType Int): String? {
    return player.currentTracks.groups
        .firstOrNull { it.type == trackType && it.isSupported && groupIsSelected(it) }
        ?.let { group ->
            group.getTrackFormat(0).label ?: group.getTrackFormat(0).language
        }
}

private fun selectedTrackLanguage(player: Player, trackType: @C.TrackType Int): String? {
    return player.currentTracks.groups
        .firstOrNull { it.type == trackType && it.isSupported && groupIsSelected(it) }
        ?.getTrackFormat(0)
        ?.language
        ?.takeUnless { it.equals("und", ignoreCase = true) }
}

private fun groupIsSelected(group: androidx.media3.common.Tracks.Group): Boolean {
    return (0 until group.length).any { group.isTrackSelected(it) }
}

@Composable
private fun VoiceSearchDialogContent(
    query: String,
    loading: Boolean,
    error: String?,
    results: List<SpatialFinItem>,
    currentItemTitle: String,
    onPlayResult: (SpatialFinItem) -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(36.dp),
        color = Color.Black.copy(alpha = 0.92f),
        modifier = Modifier.width(900.dp).height(760.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Voice Search",
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White,
                )
                TextButton(onClick = onDismiss) { Text("Close") }
            }
            Text(
                text = "Query: $query",
                style = MaterialTheme.typography.titleLarge,
                color = Color.White.copy(alpha = 0.9f),
            )
            Text(
                text = "Current playback stays active. Close this panel to continue $currentItemTitle.",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.7f),
            )
            when {
                loading -> Text("Searching...", color = Color.White)
                error != null -> Text(error, color = Color(0xFFEF5350))
                results.isEmpty() -> Text("No results found", color = Color.White.copy(alpha = 0.75f))
                else -> {
                    Column(
                        modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        results.forEach { item ->
                            Surface(
                                shape = RoundedCornerShape(24.dp),
                                color = Color.White.copy(alpha = 0.08f),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(18.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = item.name,
                                            color = Color.White,
                                            style = MaterialTheme.typography.titleMedium,
                                        )
                                        Text(
                                            text = searchResultTypeLabel(item),
                                            color = Color.White.copy(alpha = 0.7f),
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                    }
                                    if (canPlayFromVoiceSearch(item)) {
                                        Button(onClick = { onPlayResult(item) }) {
                                            Text("Play")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = onDismiss) { Text("Resume Current Video") }
            }
        }
    }
}

private fun searchResultTypeLabel(item: SpatialFinItem): String =
    when (item) {
        is SpatialFinMovie -> "Movie"
        is SpatialFinEpisode -> "Episode"
        is SpatialFinSeason -> "Season"
        is SpatialFinShow -> "Series"
        else -> "Item"
    }

private fun canPlayFromVoiceSearch(item: SpatialFinItem): Boolean {
    return item.canPlay && (item is SpatialFinMovie || item is SpatialFinEpisode || item is SpatialFinSeason || item is SpatialFinShow)
}

// ── Control Panel UI ──────────────────────────────────────────────────────────────
// Simplified — audio/subtitle/speed moved to the right-side Orbiter so this surface
// stays focused on the core playback experience.
@Composable
private fun ControlPanelUI(
    viewModel: PlayerViewModel,
    player: Player,
    uiState: PlayerViewModel.UiState,
    isPlaying: Boolean,
    currentPosition: Long,
    duration: Long,
    isLocked: Boolean,
    spatialAudioAvailable: Boolean,
    onLockToggle: () -> Unit,
    onChaptersClick: () -> Unit,
    onBackClick: () -> Unit,
    resetAutoHide: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(48.dp),
        color = Color.Black.copy(alpha = 0.9f),
        tonalElevation = 4.dp,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(modifier = Modifier.padding(60.dp)) {
            // ── Top row: back / title / indicator / lock ──
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                IconButton(onClick = onBackClick, modifier = Modifier.size(100.dp)) {
                    Icon(
                        painter = painterResource(CoreR.drawable.ic_arrow_left),
                        contentDescription = "Back",
                        tint = Color.White,
                        modifier = Modifier.size(64.dp),
                    )
                }
                Spacer(Modifier.width(32.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = uiState.currentItemTitle,
                        style = MaterialTheme.typography.displayMedium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = when {
                            isLocked -> "Controls Locked"
                            spatialAudioAvailable -> "Spatial Playback • Spatial Audio"
                            else -> "Spatial Playback"
                        },
                        style = MaterialTheme.typography.headlineMedium,
                        color = if (spatialAudioAvailable && !isLocked)
                            Color(0xFF4FC3F7).copy(alpha = 0.8f)
                        else
                            Color.White.copy(alpha = 0.6f),
                    )
                }
                IconButton(
                    onClick = { onLockToggle(); resetAutoHide() },
                    modifier = Modifier.size(100.dp),
                ) {
                    Icon(
                        painter = painterResource(
                            if (isLocked) CoreR.drawable.ic_lock else CoreR.drawable.ic_unlock,
                        ),
                        contentDescription = "Lock Controls",
                        tint = if (isLocked) Color.Red else Color.White,
                        modifier = Modifier.size(64.dp),
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            if (!isLocked) {
                ProgressSection(
                    uiState = uiState,
                    player = player,
                    currentPosition = currentPosition,
                    duration = duration,
                    resetAutoHide = resetAutoHide,
                )
            }

            Spacer(Modifier.height(48.dp))

            // ── Playback controls ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!isLocked) {
                    IconButton(
                        onClick = { player.seekBack(); resetAutoHide() },
                        modifier = Modifier.size(140.dp),
                    ) {
                        Icon(
                            painterResource(CoreR.drawable.ic_rewind),
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(80.dp),
                        )
                    }
                    Spacer(Modifier.width(64.dp))
                }

                FilledIconButton(
                    onClick = {
                        if (isPlaying) player.pause() else player.play()
                        resetAutoHide()
                    },
                    modifier = Modifier.size(160.dp),
                ) {
                    Icon(
                        painter = painterResource(
                            if (isPlaying) CoreR.drawable.ic_pause else CoreR.drawable.ic_play,
                        ),
                        contentDescription = null,
                        modifier = Modifier.size(96.dp),
                    )
                }

                if (!isLocked) {
                    Spacer(Modifier.width(64.dp))
                    TextButton(
                        onClick = { onChaptersClick() },
                        modifier = Modifier.height(112.dp),
                    ) {
                        Text(
                            "Chapters",
                            style = MaterialTheme.typography.displaySmall,
                            color = Color.White,
                        )
                    }
                    Spacer(Modifier.width(32.dp))
                    IconButton(
                        onClick = { player.seekForward(); resetAutoHide() },
                        modifier = Modifier.size(140.dp),
                    ) {
                        Icon(
                            painterResource(CoreR.drawable.ic_fast_forward),
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(80.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChaptersDialogContent(
    chapters: List<PlayerChapter>,
    currentPosition: Long,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onSelectChapter: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val currentChapterIndex = chapters.indexOfLast { it.startPosition <= currentPosition }.coerceAtLeast(0)

    Surface(
        modifier = Modifier.width(760.dp).heightIn(max = 880.dp),
        shape = RoundedCornerShape(36.dp),
        color = Color(0xFF101114),
        tonalElevation = 12.dp,
    ) {
        Column(
            modifier = Modifier.padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text("Chapters", style = MaterialTheme.typography.displaySmall, color = Color.White)
            if (chapters.isEmpty()) {
                Text(
                    "No chapter markers for this item.",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White.copy(alpha = 0.75f),
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onPreviousChapter, modifier = Modifier.height(64.dp)) {
                        Text("Previous Chapter", style = MaterialTheme.typography.titleMedium)
                    }
                    Button(onClick = onNextChapter, modifier = Modifier.height(64.dp)) {
                        Text("Next Chapter", style = MaterialTheme.typography.titleMedium)
                    }
                }
                Column(
                    modifier = Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    chapters.forEachIndexed { index, chapter ->
                        Surface(
                            onClick = { onSelectChapter(index) },
                            shape = RoundedCornerShape(24.dp),
                            color =
                                if (index == currentChapterIndex) {
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                                } else {
                                    Color.White.copy(alpha = 0.06f)
                                },
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        chapter.name ?: "Chapter ${index + 1}",
                                        style = MaterialTheme.typography.titleLarge,
                                        color = Color.White,
                                    )
                                    Text(
                                        formatTime(chapter.startPosition),
                                        style = MaterialTheme.typography.titleMedium,
                                        color = Color.White.copy(alpha = 0.7f),
                                    )
                                }
                                if (index == currentChapterIndex) {
                                    Text(
                                        "Current",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = Color(0xFF4FC3F7),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Close")
                }
            }
        }
    }
}

// ── Track Selection Dialog ─────────────────────────────────────────────────────────
@Composable
private fun TrackSelectionDialogContent(
    title: String,
    player: Player,
    trackType: @C.TrackType Int,
    onTrackSelected: (Int) -> Unit,
    onDismiss: () -> Unit,
    onSearchSubtitles: (() -> Unit)? = null,
) {
    val trackGroups = player.currentTracks.groups.filter { it.type == trackType && it.isSupported }
    val trackNames = trackGroups.getTrackNames()
    val selectedIndex = trackGroups.indexOfFirst { it.isSelected }

    Surface(
        modifier = Modifier
            .width(600.dp)
            .heightIn(max = 560.dp),
        shape = RoundedCornerShape(32.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 12.dp,
    ) {
        Column(modifier = Modifier.padding(32.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(24.dp))
            Column(
                modifier = Modifier
                    .height(400.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onTrackSelected(-1); onDismiss() }
                        .padding(vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = selectedIndex == -1,
                        onClick = { onTrackSelected(-1); onDismiss() },
                        modifier = Modifier.size(48.dp),
                    )
                    Spacer(Modifier.width(16.dp))
                    Text(stringResource(LocalR.string.none), style = MaterialTheme.typography.titleLarge)
                }
                trackNames.forEachIndexed { index, name ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onTrackSelected(index); onDismiss() }
                            .padding(vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = index == selectedIndex,
                            onClick = { onTrackSelected(index); onDismiss() },
                            modifier = Modifier.size(48.dp),
                        )
                        Spacer(Modifier.width(16.dp))
                        Text(
                            name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                if (trackType == C.TRACK_TYPE_TEXT && onSearchSubtitles != null) {
                    TextButton(onClick = {
                        onDismiss()
                        onSearchSubtitles()
                    }) {
                        Text("SEARCH SUBTITLES", style = MaterialTheme.typography.labelLarge)
                    }
                } else {
                    Spacer(Modifier.weight(1f))
                }
                TextButton(onClick = onDismiss) {
                    Text("CLOSE", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

// ── Speed Selection Dialog ────────────────────────────────────────────────────────
@Composable
private fun SpeedDialogContent(
    currentSpeed: Float,
    onSpeedSelected: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
    Surface(
        modifier = Modifier.width(400.dp).heightIn(max = 560.dp),
        shape = RoundedCornerShape(32.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 12.dp,
    ) {
        Column(modifier = Modifier.padding(32.dp)) {
            Text(
                stringResource(LocalR.string.select_playback_speed),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(24.dp))
            Column {
                speeds.forEach { speed ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSpeedSelected(speed); onDismiss() }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = currentSpeed == speed,
                            onClick = { onSpeedSelected(speed); onDismiss() },
                            modifier = Modifier.size(48.dp),
                        )
                        Spacer(Modifier.width(16.dp))
                        Text("${speed}x", style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text("CLOSE", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun QualityDialogContent(
    currentMaxBitrate: Long,
    onQualitySelected: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val bitrates = listOf(
        0L to "Auto",
        120_000_000L to "120 Mbps",
        80_000_000L to "80 Mbps",
        60_000_000L to "60 Mbps",
        40_000_000L to "40 Mbps",
        30_000_000L to "30 Mbps",
        20_000_000L to "20 Mbps",
        15_000_000L to "15 Mbps",
        10_000_000L to "10 Mbps",
        8_000_000L to "8 Mbps",
        6_000_000L to "6 Mbps",
        5_000_000L to "5 Mbps",
        4_000_000L to "4 Mbps",
        3_000_000L to "3 Mbps",
        2_000_000L to "2 Mbps",
        1_500_000L to "1.5 Mbps",
        1_000_000L to "1 Mbps",
        720_000L to "720 Kbps",
        480_000L to "480 Kbps",
    )
    Surface(
        modifier = Modifier.width(400.dp).heightIn(max = 560.dp),
        shape = RoundedCornerShape(32.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 12.dp,
    ) {
        Column(modifier = Modifier.padding(32.dp)) {
            Text(
                "Select Playback Quality",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(24.dp))
            Column(
                modifier = Modifier
                    .height(400.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                bitrates.forEach { (bitrate, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onQualitySelected(bitrate); onDismiss() }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = currentMaxBitrate == bitrate,
                            onClick = { onQualitySelected(bitrate); onDismiss() },
                            modifier = Modifier.size(48.dp),
                        )
                        Spacer(Modifier.width(16.dp))
                        Text(label, style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text("CLOSE", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

// ── Shared cast content helpers ───────────────────────────────────────────────────

@Composable
private fun PersonPhoto(imageUri: String?, sizeDp: Int) {
    val shape = androidx.compose.foundation.shape.CircleShape
    if (imageUri != null) {
        AsyncImage(
            model = imageUri,
            contentDescription = null,
            modifier = Modifier.size(sizeDp.dp).clip(shape),
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            modifier = Modifier
                .size(sizeDp.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(CoreR.drawable.ic_user),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size((sizeDp * 0.55f).dp),
            )
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.headlineMedium,
        color = Color(0xFF90CAF9),   // light blue — clearly distinct from body text
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 36.dp, bottom = 16.dp),
    )
}

/** Compact row used for Directors, Writers and other crew. */
@Composable
private fun CrewRow(person: PlayerPerson) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        PersonPhoto(imageUri = person.imageUri, sizeDp = 96)
        Column {
            Text(
                text = person.name,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (person.role.isNotBlank()) {
                Text(
                    text = person.role,
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White.copy(alpha = 0.65f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Large card used for cast members — photo + name + character stacked. */
@Composable
private fun ActorCard(person: PlayerPerson, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PersonPhoto(imageUri = person.imageUri, sizeDp = 160)
        Spacer(Modifier.height(14.dp))
        Text(
            text = person.name,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        if (person.role.isNotBlank()) {
            Text(
                text = person.role,
                style = MaterialTheme.typography.titleLarge,
                color = Color.White.copy(alpha = 0.65f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// ── Full-size info panel shown beside the video when paused ───────────────────────
@Composable
private fun CastCrewPanelContent(
    title: String,
    overview: String,
    people: List<PlayerPerson>,
    onResume: () -> Unit,
) {
    val directors = people.filter { it.type == "Director" }
    val writers   = people.filter { it.type == "Writer" }
    val cast      = people.filter { it.type == "Actor" }
    val crew      = people.filter { it.type !in listOf("Director", "Writer", "Actor") }

    Surface(
        modifier = Modifier.fillMaxSize(),
        shape = RoundedCornerShape(32.dp),
        color = Color(0xFF1C1C26),
        tonalElevation = 16.dp,
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 36.dp, vertical = 28.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            // Header row: title + resume button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(24.dp))
                Button(onClick = onResume) {
                    Text("▶  Resume", style = MaterialTheme.typography.headlineSmall)
                }
            }

            // Overview
            if (overview.isNotBlank()) {
                Spacer(Modifier.height(28.dp))
                Text(
                    text = overview,
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White.copy(alpha = 0.85f),
                    lineHeight = MaterialTheme.typography.headlineSmall.fontSize * 1.55f,
                )
            }

            // Crew sections (Director, Writer, other)
            if (directors.isNotEmpty()) {
                SectionHeader("Direction")
                directors.forEach { CrewRow(it) }
            }
            if (writers.isNotEmpty()) {
                SectionHeader("Writing")
                writers.forEach { CrewRow(it) }
            }
            if (crew.isNotEmpty()) {
                SectionHeader("Crew")
                crew.forEach { CrewRow(it) }
            }

            // Cast grid — two cards per row
            if (cast.isNotEmpty()) {
                SectionHeader("Cast")
                cast.chunked(2).forEach { pair ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        ActorCard(
                            person = pair[0],
                            modifier = Modifier.weight(1f),
                        )
                        if (pair.size > 1) {
                            ActorCard(
                                person = pair[1],
                                modifier = Modifier.weight(1f),
                            )
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }

            if (people.isEmpty() && overview.isBlank()) {
                Spacer(Modifier.height(32.dp))
                Text(
                    text = "No information available.",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White.copy(alpha = 0.5f),
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

// ── Compact Cast & Crew Dialog (opened via button in controls) ────────────────────
@Composable
private fun CastCrewDialogContent(
    title: String,
    overview: String,
    people: List<PlayerPerson>,
    onDismiss: () -> Unit,
) {
    val directors = people.filter { it.type == "Director" }
    val writers   = people.filter { it.type == "Writer" }
    val cast      = people.filter { it.type == "Actor" }
    val crew      = people.filter { it.type !in listOf("Director", "Writer", "Actor") }

    Surface(
        modifier = Modifier
            .width(800.dp)
            .heightIn(max = 700.dp),
        shape = RoundedCornerShape(32.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 12.dp,
    ) {
        Column(
            modifier = Modifier
                .padding(36.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

            if (overview.isNotBlank()) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = overview,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (directors.isNotEmpty()) {
                SectionHeader("Direction")
                directors.forEach { CrewRow(it) }
            }
            if (writers.isNotEmpty()) {
                SectionHeader("Writing")
                writers.forEach { CrewRow(it) }
            }
            if (crew.isNotEmpty()) {
                SectionHeader("Crew")
                crew.forEach { CrewRow(it) }
            }
            if (cast.isNotEmpty()) {
                SectionHeader("Cast")
                cast.chunked(2).forEach { pair ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        ActorCard(person = pair[0], modifier = Modifier.weight(1f))
                        if (pair.size > 1) {
                            ActorCard(person = pair[1], modifier = Modifier.weight(1f))
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }

            if (people.isEmpty() && overview.isBlank()) {
                Spacer(Modifier.height(24.dp))
                Text(
                    text = "No information available.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }

            Spacer(Modifier.height(24.dp))
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text("CLOSE", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

// ── Next Episode Panel Content ────────────────────────────────────────────────────
@Composable
private fun NextEpisodePanelContent(
    nextEpisode: PlayerItem,
    onPlayNext: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(32.dp),
        color = Color.Black.copy(alpha = 0.92f),
        tonalElevation = 8.dp,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(modifier = Modifier.padding(32.dp)) {
            // Backdrop image
            if (nextEpisode.backdropImageUri != null) {
                AsyncImage(
                    model = nextEpisode.backdropImageUri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(20.dp)),
                )
                Spacer(Modifier.height(16.dp))
            }

            Text(
                text = "Up Next",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(4.dp))

            // Build display title: "Series Name — S1E2 Episode Name" for episodes
            val episodeLabel = nextEpisode.parentIndexNumber?.let { s ->
                nextEpisode.indexNumber?.let { ep -> "S${s}E${ep} " }
            } ?: ""
            val displayTitle = if (nextEpisode.seriesName != null) {
                "${nextEpisode.seriesName} — $episodeLabel${nextEpisode.name}"
            } else {
                nextEpisode.name
            }
            Text(
                text = displayTitle,
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.weight(1f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .weight(1f)
                        .height(72.dp),
                ) {
                    Text("Dismiss", style = MaterialTheme.typography.titleLarge)
                }
                Button(
                    onClick = onPlayNext,
                    modifier = Modifier
                        .weight(2f)
                        .height(72.dp),
                ) {
                    Icon(
                        painter = painterResource(CoreR.drawable.ic_play),
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text("Play Next", style = MaterialTheme.typography.titleLarge)
                }
            }
        }
    }
}

// ── Progress / Trickplay ──────────────────────────────────────────────────────────
@Composable
private fun ProgressSection(
    uiState: dev.jdtech.jellyfin.player.local.presentation.PlayerViewModel.UiState,
    player: Player,
    currentPosition: Long,
    duration: Long,
    resetAutoHide: () -> Unit,
) {
    val chapters = uiState.currentChapters
    var sliderValue by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }

    val progress = if (duration > 0) currentPosition.toFloat() / duration.toFloat() else 0f
    if (!isDragging) sliderValue = progress

    // Chapter resolved from the scrub target (when dragging) or playback position.
    val displayPositionMs = if (isDragging && duration > 0) (sliderValue * duration).toLong()
                            else currentPosition
    val currentChapterName = remember(displayPositionMs, chapters) {
        chapters.lastOrNull { it.startPosition <= displayPositionMs }?.name
    }

    Column {
        // Chapter title row — shown with full opacity while scrubbing, dimmed otherwise.
        if (currentChapterName != null) {
            Text(
                text = currentChapterName,
                style = MaterialTheme.typography.labelLarge,
                color = if (isDragging) Color.White else Color.White.copy(alpha = 0.55f),
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }

        // Trickplay thumbnail — only visible while the user is dragging the scrubber.
        if (isDragging && uiState.currentTrickplay != null) {
            val trickplay = uiState.currentTrickplay!!
            val totalThumbnails = trickplay.images.size
            val index = (sliderValue * (totalThumbnails - 1)).toInt().coerceIn(0, totalThumbnails - 1)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    bitmap = trickplay.images[index].asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .height(200.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.DarkGray),
                    contentScale = ContentScale.Fit,
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                formatTime(currentPosition),
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White.copy(alpha = 0.8f),
            )
            // Slider wrapped in a Box so we can overlay chapter tick marks on the track.
            Box(modifier = Modifier.weight(1f)) {
                // Chapter tick marks — drawn behind the slider so touch events pass through.
                if (chapters.isNotEmpty() && duration > 0) {
                    val sliderHPad = 24.dp
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(24.dp)
                            .align(Alignment.Center),
                    ) {
                        // The slider track occupies the width minus the horizontal padding on
                        // each side (same value as the Slider's own padding modifier below).
                        val padPx = sliderHPad.toPx()
                        val trackWidth = size.width - 2 * padPx
                        val markerH = 32f
                        val markerW = 8f
                        val centerY = size.height / 2f
                        chapters.forEach { chapter ->
                            val fraction = (chapter.startPosition.toFloat() / duration.toFloat())
                                .coerceIn(0f, 1f)
                            val x = padPx + fraction * trackWidth
                            drawRect(
                                color = Color(0xFF4FC3F7),
                                topLeft = Offset(x - markerW / 2, centerY - markerH / 2),
                                size = Size(markerW, markerH),
                            )
                        }
                    }
                }
                Slider(
                    value = sliderValue,
                    onValueChange = {
                        isDragging = true
                        sliderValue = it
                        resetAutoHide()
                    },
                    onValueChangeFinished = {
                        player.seekTo((sliderValue * duration).toLong())
                        isDragging = false
                        resetAutoHide()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                )
            }
            Text(
                formatTime(duration),
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White.copy(alpha = 0.8f),
            )
        }
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────────

private fun mapStereoMode(mode: String): SurfaceEntity.StereoMode? = when (mode) {
    "sbs" -> SurfaceEntity.StereoMode.SIDE_BY_SIDE
    "top_bottom" -> SurfaceEntity.StereoMode.TOP_BOTTOM
    "multiview" -> SurfaceEntity.StereoMode.MULTIVIEW_LEFT_PRIMARY
    else -> null
}

private fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) String.format("%d:%02d:%02d", hours, minutes, seconds)
    else String.format("%d:%02d", minutes, seconds)
}

private fun loadSavedPlayerRootPose(viewModel: PlayerViewModel): Pose {
    val prefs = viewModel.appPreferences
    return Pose(
        Vector3(
            prefs.getValue(prefs.xrPlayerPanelX),
            prefs.getValue(prefs.xrPlayerPanelY),
            prefs.getValue(prefs.xrPlayerPanelZ),
        ),
        Quaternion(
            prefs.getValue(prefs.xrPlayerPanelRotX),
            prefs.getValue(prefs.xrPlayerPanelRotY),
            prefs.getValue(prefs.xrPlayerPanelRotZ),
            prefs.getValue(prefs.xrPlayerPanelRotW),
        ),
    )
}

private fun savePlayerRootPose(viewModel: PlayerViewModel, pose: Pose) {
    val prefs = viewModel.appPreferences
    val translation = pose.translation
    val rotation = pose.rotation
    prefs.setValue(prefs.xrPlayerPanelX, translation.x)
    prefs.setValue(prefs.xrPlayerPanelY, translation.y)
    prefs.setValue(prefs.xrPlayerPanelZ, translation.z)
    prefs.setValue(prefs.xrPlayerPanelRotX, rotation.x)
    prefs.setValue(prefs.xrPlayerPanelRotY, rotation.y)
    prefs.setValue(prefs.xrPlayerPanelRotZ, rotation.z)
    prefs.setValue(prefs.xrPlayerPanelRotW, rotation.w)
}

private fun projectPoseFromOrigin(sourcePose: Pose, depthScale: Float): Pose {
    val translation = sourcePose.translation
    return Pose(
        Vector3(
            translation.x * depthScale,
            translation.y * depthScale,
            translation.z * depthScale,
        ),
        sourcePose.rotation,
    )
}

private fun syncProjectedOverlayRoots(
    videoPose: Pose,
    videoScale: Float,
    uiRoot: GroupEntity,
    subtitleRoot: GroupEntity,
    depthScale: Float,
) {
    val projectedPose = projectPoseFromOrigin(videoPose, depthScale)
    runCatching {
        uiRoot.setScale(videoScale)
        uiRoot.setPose(projectedPose)
    }
    runCatching {
        subtitleRoot.setScale(videoScale)
        subtitleRoot.setPose(projectedPose)
    }
}

private fun safeGetEntityPose(entity: androidx.xr.scenecore.Entity): Pose? {
    return runCatching { entity.getPose() }
        .onFailure { Timber.d("Skipping disposed XR player entity pose save") }
        .getOrNull()
}

private fun safelyToggleMascotEntity(
    mascot: GltfModelEntity,
    shouldShow: Boolean,
) {
    runCatching {
        if (shouldShow) {
            mascot.setEnabled(true)
            Timber.i("Paused mascot enabled")
        } else {
            mascot.setEnabled(false)
            Timber.d("Paused mascot disabled")
        }
    }.onFailure {
        Timber.d(it, "Skipping paused mascot visibility update for disposed entity")
    }
}

@Composable
private fun SubtitleSearchDialogContent(
    viewModel: dev.jdtech.jellyfin.player.local.presentation.PlayerViewModel,
    onDismiss: () -> Unit,
) {
    val searchState by viewModel.subtitleSearchState.collectAsStateWithLifecycle()
    var language by remember { mutableStateOf(java.util.Locale.getDefault().language) }

    Surface(
        modifier = Modifier
            .width(600.dp)
            .heightIn(max = 560.dp),
        shape = RoundedCornerShape(32.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 12.dp,
    ) {
        Column(modifier = Modifier.padding(32.dp)) {
            Text("Search Subtitles", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            
            OutlinedTextField(
                value = language,
                onValueChange = { language = it },
                label = { Text("Language Code (e.g. eng, spa, fre)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardActions = KeyboardActions(
                    onSearch = { viewModel.searchForSubtitles(language) }
                ),
                keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
            )
            
            Spacer(Modifier.height(16.dp))
            
            Button(
                onClick = { viewModel.searchForSubtitles(language) },
                modifier = Modifier.align(Alignment.End)
            ) {
                Text("Search")
            }
            
            Spacer(Modifier.height(16.dp))
            
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (val state = searchState) {
                    is dev.jdtech.jellyfin.player.local.presentation.PlayerViewModel.SubtitleSearchState.Idle -> {
                        Text("Enter a language and click search.", modifier = Modifier.align(Alignment.Center))
                    }
                    is dev.jdtech.jellyfin.player.local.presentation.PlayerViewModel.SubtitleSearchState.Searching -> {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    }
                    is dev.jdtech.jellyfin.player.local.presentation.PlayerViewModel.SubtitleSearchState.Downloading -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.align(Alignment.Center)) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(8.dp))
                            Text("Downloading...")
                        }
                    }
                    is dev.jdtech.jellyfin.player.local.presentation.PlayerViewModel.SubtitleSearchState.Error -> {
                        Text("Error: ${state.message}", color = MaterialTheme.colorScheme.error)
                    }
                    is dev.jdtech.jellyfin.player.local.presentation.PlayerViewModel.SubtitleSearchState.Success -> {
                        if (state.options.isEmpty()) {
                            Text("No subtitles found.", modifier = Modifier.align(Alignment.Center))
                        } else {
                            androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.fillMaxSize()) {
                                items(state.options) { option ->
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { viewModel.downloadAndSwitchSubtitles(option) }
                                            .padding(vertical = 12.dp, horizontal = 8.dp)
                                    ) {
                                        Text(option.name ?: "Unknown", style = MaterialTheme.typography.titleMedium)
                                        Text("Format: ${option.format} • Rating: ${option.communityRating ?: 0}", style = MaterialTheme.typography.bodyMedium)
                                    }
                                    HorizontalDivider()
                                }
                            }
                        }
                    }
                }
            }
            
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text("CLOSE", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
