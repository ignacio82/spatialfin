package dev.jdtech.jellyfin.player.xr

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import android.util.TypedValue
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import androidx.media3.exoplayer.ExoPlayer
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
import androidx.xr.runtime.HandTrackingMode
import androidx.xr.runtime.Session
import androidx.xr.runtime.math.FloatSize2d
import androidx.xr.runtime.math.FloatSize3d
import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Quaternion
import androidx.xr.runtime.math.Vector3
import androidx.xr.scenecore.GltfModel
import androidx.xr.scenecore.GltfModelEntity
import androidx.xr.scenecore.SpatialCapability
import androidx.xr.scenecore.SpatialEnvironment
import androidx.xr.scenecore.SurfaceEntity
import androidx.xr.scenecore.GroupEntity
import androidx.xr.scenecore.Space
import androidx.xr.compose.subspace.SceneCoreEntity
import androidx.xr.scenecore.scene
import androidx.core.content.ContextCompat
import dev.jdtech.jellyfin.player.local.domain.getTrackNames
import dev.jdtech.jellyfin.player.local.presentation.PlayerEvents
import dev.jdtech.jellyfin.player.local.presentation.PlayerViewModel
import dev.jdtech.jellyfin.player.session.voice.PlayerSessionController
import dev.jdtech.jellyfin.player.session.voice.PlayerStateSnapshot
import dev.jdtech.jellyfin.player.xr.voice.AssistantPreferences
import dev.jdtech.jellyfin.player.xr.voice.RecommendationContext
import dev.jdtech.jellyfin.player.xr.voice.CharacterScanOverlay
import dev.jdtech.jellyfin.player.xr.voice.SecondaryHandPinchDetector
import dev.jdtech.jellyfin.player.xr.voice.VoiceControlOverlay
import dev.jdtech.jellyfin.settings.domain.llm.LlmDownloadManager
import dev.jdtech.jellyfin.player.xr.voice.SmartChatEngine
import dagger.hilt.InstallIn
import dagger.hilt.EntryPoint
import dagger.hilt.components.SingletonComponent
import dev.jdtech.jellyfin.player.xr.voice.SpatialCommandCoordinator
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
import dev.jdtech.jellyfin.player.xr.mcp.McpBridge
import dev.jdtech.jellyfin.player.xr.mcp.PlaybackState
import dev.jdtech.jellyfin.player.xr.mcp.SceneState

import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.player.local.R as LocalR

// ── Next-episode panel threshold ───────────────────────────────────────────────────
private const val NEXT_EPISODE_THRESHOLD_MS = 2 * 60 * 1_000L  // show in last 2 minutes
private const val PAUSED_MASCOT_DELAY_MS = 1_000L
private const val HAND_TRACKING_PERMISSION = "android.permission.HAND_TRACKING"

@EntryPoint
@InstallIn(SingletonComponent::class)
interface LlmEntryPoint {
    fun downloadManager(): LlmDownloadManager
    fun modelManager(): dev.jdtech.jellyfin.core.llm.LlmModelManager
}
// VIDEO_DEPTH_METERS, UI_DEPTH_METERS, DEFAULT_VIDEO_PANEL_SCALE → see PlayerGeometry.kt
// MIN/MAX_RESTORABLE_VIDEO_* and XR_PLAYER_POSE_VERSION_VIDEO_CENTER → see PlayerPoseStorage.kt
private const val DEFAULT_VIDEO_WIDTH_METERS = 8.0f
private const val DEFAULT_VIDEO_HEIGHT_METERS = 4.5f
private const val VIDEO_MOVE_HANDLE_MARGIN_METERS = 0.35f
private const val VIDEO_MOVE_HANDLE_DEPTH_METERS = 0.5f

private val PausedMascotPose =
    Pose(
        Vector3(-2.15f, -0.98f, 0.95f),
        Quaternion(0f, 0.9914f, 0f, 0.1305f),
    )

private fun movableVideoBounds(videoWidth: Float, videoHeight: Float): FloatSize3d =
    FloatSize3d(
        width = (videoWidth + VIDEO_MOVE_HANDLE_MARGIN_METERS * 2f).coerceAtLeast(1f),
        height = (videoHeight + VIDEO_MOVE_HANDLE_MARGIN_METERS * 2f).coerceAtLeast(1f),
        depth = VIDEO_MOVE_HANDLE_DEPTH_METERS,
    )

private fun Cue.debugSummary(): String {
    val preview = text?.toString()?.replace('\n', ' ')?.replace('\r', ' ')?.take(60) ?: "<bitmap>"
    return "text=\"$preview\" line=$line lineType=$lineType lineAnchor=$lineAnchor position=$position positionAnchor=$positionAnchor size=$size textSize=$textSize verticalType=$verticalType"
}

/**
 * SpatialPlayerScreen — cinematic immersive XR playback experience.
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
    val savedPlayerPose = remember(viewModel) { loadSavedPlayerRootPose(viewModel) }
    val savedPlayerScale = remember(viewModel) { loadSavedPlayerRootScale(viewModel) }

    val videoRootEntity = remember { mutableStateOf<androidx.xr.scenecore.GroupEntity?>(null) }
    val uiRootEntity = remember { mutableStateOf<GroupEntity?>(null) }
    val subtitleRootEntity = remember { mutableStateOf<GroupEntity?>(null) }
    val videoEntity = remember { mutableStateOf<SurfaceEntity?>(null) }
    val mascotEntity = remember { mutableStateOf<GltfModelEntity?>(null) }
    val mascotModel = remember { mutableStateOf<GltfModel?>(null) }
    val movableComponent = remember { mutableStateOf<androidx.xr.scenecore.MovableComponent?>(null) }
    val lastReportedMovePose = remember { mutableStateOf<Pose?>(null) }
    var videoDepth by remember(savedPlayerPose) {
        mutableFloatStateOf(extractVideoDepth(savedPlayerPose, VIDEO_DEPTH_METERS))
    }
    var videoPanelScale by remember(savedPlayerScale) { mutableFloatStateOf(savedPlayerScale) }
    var lastPointerPosition by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
    var startupPoseGuardActive by remember { mutableStateOf(true) }
    var moveInProgress by remember { mutableStateOf(false) }

    val xrSubtitleSize = viewModel.appPreferences.getValue(viewModel.appPreferences.xrSubtitleSize).toFloat()
    val libassUsagePref = viewModel.appPreferences.getValue(viewModel.appPreferences.libassSubtitleUsage)
    val voiceControlEnabled = viewModel.appPreferences.getValue(viewModel.appPreferences.voiceControlEnabled)
    val assistantVerbosity = viewModel.appPreferences.getValue(viewModel.appPreferences.voiceAssistantVerbosity)
    val assistantSpoilerPolicy = viewModel.appPreferences.getValue(viewModel.appPreferences.voiceAssistantSpoilerPolicy)
    val assistantSpokenReplies = viewModel.appPreferences.getValue(viewModel.appPreferences.voiceAssistantSpokenReplies)
    val assistantVoiceName = viewModel.appPreferences.getValue(viewModel.appPreferences.voiceAssistantVoice)
    val voiceServices = rememberPlayerVoiceServices(viewModel)
    val voiceService = voiceServices.voiceService
    val geminiNanoService = voiceServices.geminiNanoService
    val geminiCloudService = voiceServices.geminiCloudService
    val llmEntryPoint = voiceServices.llmEntryPoint
    val downloadManager = voiceServices.downloadManager
    val tts = voiceServices.tts
    val isTtsSpeaking by tts.isSpeaking.collectAsState()
    val subtitleContext = rememberPlayerSubtitleContext(viewModel)

    val voiceState by voiceService.state.collectAsState()
    val partialTranscript by voiceService.partialTranscript.collectAsState()
    val voiceMicLevel by voiceService.micLevel.collectAsState()
    val followUpListenWindowMs = 12_000L
    val followUpAutoStartDelayMs = 200L
    val voice = rememberPlayerVoiceCoordinator(
        player = player,
        voiceService = voiceService,
        tts = tts,
        followUpListenWindowMs = followUpListenWindowMs,
    )
    var exitRequested by remember { mutableStateOf(false) }
    val voiceGestureHand =
        viewModel.appPreferences.getValue(viewModel.appPreferences.voiceGestureHand) ?: "left"

    LaunchedEffect(Unit) {
        Timber.i("VOICE: deferring XR voice startup work until explicit activation")
        // Warm-up: if the model file is already on disk and Gemma is enabled, begin
        // engine initialization now in the background so the first interaction is instant
        // instead of waiting 30–120 s after the user first presses the mic button.
        if (viewModel.appPreferences.getValue(viewModel.appPreferences.voiceAssistantGemmaEnabled)) {
            llmEntryPoint.modelManager().ensureInitialized()
        }
    }

    fun requireCommandCoordinator(): SpatialCommandCoordinator =
        voiceServices.requireCommandCoordinator()

    fun requireChatEngine(): SmartChatEngine = voiceServices.requireChatEngine()
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
            if (!granted) {
                voice.shouldStartVoiceCapture = false
                voice.voiceFeedback = "Microphone permission required"
                voiceService.resetState()
            }
        }
    val handTrackingPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            hasHandTrackingPermission = granted
            if (!granted) {
                voice.voiceGestureArmingProgress = 0f
                voice.voiceGestureHint = null
            }
        }
    
    // --- Libass state ---
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
    var videoWidth by remember { mutableFloatStateOf(DEFAULT_VIDEO_WIDTH_METERS) }
    var videoHeight by remember { mutableFloatStateOf(DEFAULT_VIDEO_HEIGHT_METERS) }

    val libass = rememberLibassRenderer(
        player = player,
        currentStereoMode = currentStereoMode,
        libassUsagePref = libassUsagePref,
        libassRenderer = libassRenderer,
        onCueRecorded = subtitleContext::recordCueLine,
    )
    val passthroughEnabled = passthroughOverrideEnabled ?: !isPlaying

    val pinchDetector = remember(session, activity, voiceGestureHand) {
        activity?.let {
            SecondaryHandPinchDetector(
                session = session,
                activity = it,
                preferredHand = voiceGestureHand,
                shouldDetectActivation = {
                    voiceControlEnabled &&
                        hasHandTrackingPermission &&
                        hasAudioPermission &&
                        voiceService.isAvailable() &&
                        !voice.isVoiceTurnBusy(voiceState, isTtsSpeaking)
                },
                shouldDetectInterrupt = {
                    voiceControlEnabled &&
                        hasHandTrackingPermission &&
                        voice.isVoiceTurnBusy(voiceState, isTtsSpeaking)
                },
            )
        }
    }

    // --- MCP Bridge integration ---
    var showPausedMascot by remember { mutableStateOf(false) }
    val isActuallyPaused = playbackState == Player.STATE_READY && !isPlaying
    // --- Controls UI state ---
    var controlsVisible by remember { mutableStateOf(true) }
    var isLocked by remember { mutableStateOf(false) }
    var hideTimestamp by remember { mutableLongStateOf(System.currentTimeMillis()) }

    // --- Next episode panel state ---
    var nextEpisodePanelDismissed by remember { mutableStateOf(false) }
    // Reset dismissal whenever the playing item changes (user started a new episode).
    // Keyed on item id, not title — back-to-back episodes can share a title
    // ("Pilot", "Part 1") and the panel must still re-arm.
    LaunchedEffect(uiState.currentItemId ?: uiState.currentItemTitle) {
        nextEpisodePanelDismissed = false
    }
    // Show the panel during the last NEXT_EPISODE_THRESHOLD_MS of an episode when a next
    // episode exists — but not for movies, very short content, or when controls are locked.
    val showNextEpisodePanel = !nextEpisodePanelDismissed &&
        !isLocked &&
        uiState.nextEpisode != null &&
        duration > NEXT_EPISODE_THRESHOLD_MS &&
        (duration - currentPosition) in 0L..NEXT_EPISODE_THRESHOLD_MS

    LaunchedEffect(controlsVisible, hideTimestamp, isPlaying) {
        if (controlsVisible && isPlaying) {
            val wait = (hideTimestamp + 5000L) - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            controlsVisible = false
        }
    }

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

    fun revealControls(reason: String) {
        if (!controlsVisible) {
            Timber.d("XrPlayer controls revealed reason=%s", reason)
        }
        controlsVisible = true
        resetAutoHide()
    }

    fun requestExit(reason: String) {
        if (exitRequested) {
            Timber.d("XrPlayer exit already requested reason=%s", reason)
            return
        }
        exitRequested = true
        Timber.i(
            "XrPlayer exit requested reason=%s mediaId=%s posMs=%d state=%d useLibass=%b",
            reason,
            player.currentMediaItem?.mediaId,
            player.currentPosition,
            player.playbackState,
            libass.useLibass,
        )
        viewModel.updatePlaybackProgress()
        libass.reset()
        runCatching { player.pause() }
        // Let the activity finish drive the single XR surface teardown path. Clearing the
        // video surface here races with SurfaceEntity disposal on some devices.
        coroutineScope.launch {
            delay(150L)
            onBackClick()
        }
    }

    BackHandler(enabled = true) {
        requestExit("system-back")
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

    fun updateVoiceSearchItem(updatedItem: SpatialFinItem) {
        voiceSearchResults =
            voiceSearchResults.map { item ->
                if (item.id == updatedItem.id) updatedItem else item
            }
    }

    fun currentRecommendationSnapshot(): PlayerStateSnapshot =
        buildPlayerStateSnapshot(
            player = player,
            uiState = uiState,
            controlsVisible = controlsVisible,
            voiceSearchOpen = voiceSearchOpen,
            voiceSearchQuery = voiceSearchQuery,
            voiceSearchResults = voiceSearchResults,
            recommendationContext = voice.recommendationContext,
            passthroughEnabled = passthroughEnabled,
        )

    fun resetScreenPlacementToDefault() {
        val defaultPose = Pose(Vector3(0f, 0f, -VIDEO_DEPTH_METERS), Quaternion.Identity)
        val projectedOverlayPose = projectPoseFromOrigin(defaultPose, UI_DEPTH_METERS / VIDEO_DEPTH_METERS)
        videoDepth = VIDEO_DEPTH_METERS
        videoPanelScale = DEFAULT_VIDEO_PANEL_SCALE
        lastReportedMovePose.value = defaultPose
        runCatching {
            videoRootEntity.value?.let { root ->
                root.setPose(defaultPose)
                root.setScale(DEFAULT_VIDEO_PANEL_SCALE)
            }
        }
        runCatching {
            uiRootEntity.value?.let { root ->
                root.setPose(projectedOverlayPose)
                root.setScale(DEFAULT_VIDEO_PANEL_SCALE)
            }
        }
        runCatching {
            subtitleRootEntity.value?.let { root ->
                root.setPose(projectedOverlayPose)
                root.setScale(DEFAULT_VIDEO_PANEL_SCALE)
            }
        }
        savePlayerRootPose(viewModel, defaultPose)
        savePlayerRootScale(viewModel, DEFAULT_VIDEO_PANEL_SCALE)
        libass.bumpOverlayAttachment()
    }

    fun applyCurrentLaunchPose() {
        val targetPose = Pose(Vector3(0f, 0f, -videoDepth), Quaternion.Identity)
        val projectedOverlayPose = projectPoseFromOrigin(targetPose, UI_DEPTH_METERS / videoDepth)
        runCatching {
            videoRootEntity.value?.let { root ->
                root.setPose(targetPose)
                root.setScale(videoPanelScale)
            }
        }
        runCatching {
            uiRootEntity.value?.let { root ->
                root.setPose(projectedOverlayPose)
                root.setScale(videoPanelScale)
            }
        }
        runCatching {
            subtitleRootEntity.value?.let { root ->
                root.setPose(projectedOverlayPose)
                root.setScale(videoPanelScale)
            }
        }
        lastReportedMovePose.value = targetPose
    }

    val sessionController =
        remember(player, viewModel, activity) {
            PlayerSessionController(
                viewModel = viewModel,
                player = player,
                onControlsVisibilityChange = { controlsVisible = it },
                onNavigateBack = { requestExit("voice-go-back") },
                onShowVoiceSearch = ::openVoiceSearch,
                onShowSyncPlayDialog = {
                    activeDialog = "syncplay"
                    viewModel.refreshSyncPlayGroups()
                },
                onGoHome = {
                    val launchIntent =
                        Intent().setClassName(context, "dev.spatialfin.unified.UnifiedMainActivity")
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
                onAdjustScale = { delta, reset ->
                    val updatedScale =
                        when {
                            reset -> DEFAULT_VIDEO_PANEL_SCALE
                            delta != null -> (videoPanelScale + delta).coerceIn(0.2f, 5.0f)
                            else -> videoPanelScale
                        }
                    videoPanelScale = updatedScale
                    savePlayerRootScale(viewModel, updatedScale)
                    videoRootEntity.value?.let { root ->
                        runCatching { root.setScale(updatedScale) }
                    }
                },
                onAdjustDistance = { delta, reset ->
                    if (reset) {
                        videoDepth = VIDEO_DEPTH_METERS
                    } else if (delta != null) {
                        videoDepth = (videoDepth + delta).coerceIn(2.0f, 15.0f)
                    }
                },
                onResetScreenPlacement = ::resetScreenPlacementToDefault,
            )
        }

    fun speakAssistantReply(text: String, languageHint: String?) {
        voice.speakAssistantReply(
            text = text,
            languageHint = languageHint,
            spokenRepliesEnabled = assistantSpokenReplies,
            assistantVoiceName = assistantVoiceName,
            player = player,
            tts = tts,
        )
    }

    fun requestVoiceAssetsWarmup() {
        if (voice.voiceAssetsRequested) return
        voice.voiceAssetsRequested = true
        if (viewModel.appPreferences.getValue(viewModel.appPreferences.voiceAssistantGemmaEnabled)) {
            coroutineScope.launch {
                Timber.i("VOICE: requesting XR voice assets on demand")
                downloadManager.downloadModel()
            }
        }
    }

    fun requestVoiceCommand(source: String = "manual") {
        if (source != "manual" && voice.isVoiceTurnBusy(voiceState, isTtsSpeaking)) {
            Timber.i(
                "VOICE: player request ignored source=%s state=%s speaking=%b pendingSpeech=%b startedSpeech=%b jobActive=%b",
                source,
                voiceState,
                isTtsSpeaking,
                voice.assistantSpeechPendingStart,
                voice.assistantSpeechStarted,
                voice.activeVoiceJob?.isActive == true,
            )
            return
        }
        // Cancel any in-flight inference from the previous voice command before starting a new one.
        voice.activeVoiceJob?.cancel()
        voice.activeVoiceJob = null
        if (voiceState == VoiceState.LISTENING || voiceState == VoiceState.PROCESSING) {
            Timber.i("VOICE: player request ignored source=%s state=%s", source, voiceState)
            return
        }
        if (isTtsSpeaking) {
            tts.stop()
            voice.cancelPendingSpeech()
        }
        if (!voiceControlEnabled) {
            voice.voiceFeedback = "Voice control disabled"
            return
        }
        requestVoiceAssetsWarmup()
        if (!voiceService.isAvailable()) {
            voice.voiceFeedback = "On-device speech unavailable"
            return
        }
        if (!hasAudioPermission) {
            voice.shouldStartVoiceCapture = true
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        voice.shouldStartVoiceCapture = false
        Timber.i(
            "VOICE: player request starting source=%s followUpPending=%b followUpDeadlineMs=%d",
            source,
            voice.followUpPending,
            voice.followUpDeadlineMs,
        )
        voice.clearFollowUp()
        voice.voiceFeedback = null
        revealControls("voice-command")
        startVoiceCapture(
            voiceService = voiceService,
            commandCoordinatorProvider = ::requireCommandCoordinator,
            chatEngineProvider = ::requireChatEngine,
            recentSubtitles = subtitleContext.assistantLines,
            player = player,
            viewModel = viewModel,
            uiState = uiState,
            snapshot = buildPlayerStateSnapshot(
                player = player,
                uiState = uiState,
                controlsVisible = controlsVisible,
                voiceSearchOpen = voiceSearchOpen,
                voiceSearchQuery = voiceSearchQuery,
                voiceSearchResults = voiceSearchResults,
                recommendationContext = voice.recommendationContext,
                passthroughEnabled = passthroughEnabled,
            ),
            controller = sessionController,
            telemetryStore = telemetryStore,
            onSearchQuery = onSearchQuery,
            assistantPreferences = AssistantPreferences(
                verbosity = assistantVerbosity,
                spoilerPolicy = assistantSpoilerPolicy,
                spokenRepliesEnabled = assistantSpokenReplies,
            ),
            responseLanguageHint =
                selectedTrackLanguage(player, C.TRACK_TYPE_TEXT)
                    ?: selectedTrackLanguage(player, C.TRACK_TYPE_AUDIO)
                    ?: selectedTrackName(player, C.TRACK_TYPE_TEXT)
                    ?: selectedTrackName(player, C.TRACK_TYPE_AUDIO),
            conversationHistory = voice.conversationHistory,
            onConversationTurn = { q, a ->
                voice.conversationHistory.add(q to a)
                if (voice.conversationHistory.size > 6) voice.conversationHistory.removeAt(0)
            },
            recommendationContext = voice.recommendationContext,
            onRecommendationContextUpdated = { voice.recommendationContext = it },
            onScheduleFollowUp = {
                voice.armFollowUpWindow("chat-reply", followUpListenWindowMs)
            },
            onGetSuggestions = { viewModel.repository.getSuggestions() },
            onResult = { voice.voiceFeedback = it },
            onSpokenReply = { text, lang -> speakAssistantReply(text, lang) },
            onCharacterScanActiveChanged = { voice.characterScanActive = it },
            subtitleCacheFallback = subtitleContext.cacheFallback,
            scope = coroutineScope,
            lastPointerPosition = lastPointerPosition,
            onJobStarted = { voice.activeVoiceJob = it },
            )
            }

    fun interruptVoiceCommand(reason: String) {
        voice.interruptVoiceCommand(
            reason = reason,
            voiceState = voiceState,
            isTtsSpeaking = isTtsSpeaking,
            tts = tts,
            voiceService = voiceService,
            followUpListenWindowMs = followUpListenWindowMs,
            scope = coroutineScope,
            followUpAutoStartDelayMs = followUpAutoStartDelayMs,
            onResumeFollowUp = { requestVoiceCommand("follow-up-interrupt") },
        )
    }

    // Reset conversation history when the playing item changes. Key on item id
    // (with title fallback) — two adjacent episodes can share a title and the
    // assistant must drop stale context when the actual item flips.
    LaunchedEffect(uiState.currentItemId ?: uiState.currentItemTitle) {
        voice.conversationHistory.clear()
        voice.recommendationContext = null
    }

    LaunchedEffect(isActuallyPaused) {
        if (isActuallyPaused) {
            revealControls("playback-paused")
            showPausedMascot = false
            delay(PAUSED_MASCOT_DELAY_MS)
            showPausedMascot = true
            Timber.i("Paused mascot requested after pause delay")
        } else {
            showPausedMascot = false
            Timber.d("Paused mascot hidden because playback resumed")
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

    LaunchedEffect(hasAudioPermission, voice.shouldStartVoiceCapture) {
        if (hasAudioPermission && voice.shouldStartVoiceCapture) {
            voice.shouldStartVoiceCapture = false
            requestVoiceCommand()
        }
    }

    LaunchedEffect(voiceControlEnabled, hasHandTrackingPermission) {
        if (voiceControlEnabled && !hasHandTrackingPermission) {
            handTrackingPermissionLauncher.launch(HAND_TRACKING_PERMISSION)
        }
    }

    LaunchedEffect(session, hasHandTrackingPermission) {
        if (!hasHandTrackingPermission) return@LaunchedEffect
        runCatching {
            session.configure(session.config.copy(handTracking = HandTrackingMode.BOTH))
        }.onFailure { Timber.w(it, "VOICE: Player hand tracking not available") }
    }

    LaunchedEffect(pinchDetector, hasHandTrackingPermission) {
        val detector = pinchDetector ?: return@LaunchedEffect
        if (!hasHandTrackingPermission) return@LaunchedEffect
        detector.gestureStates.collect { event ->
            when (event) {
                is SecondaryHandPinchDetector.GestureState.Arming -> {
                    voice.voiceGestureArmingProgress = event.progress
                    voice.voiceGestureHint = event.hint
                }
                is SecondaryHandPinchDetector.GestureState.Started -> {
                    voice.voiceGestureArmingProgress = 1f
                    voice.voiceGestureHint = null
                    when (event.gestureType) {
                        SecondaryHandPinchDetector.GestureType.ACTIVATE ->
                            requestVoiceCommand("gesture-activate")
                        SecondaryHandPinchDetector.GestureType.INTERRUPT ->
                            interruptVoiceCommand("fist-gesture")
                    }
                }
                is SecondaryHandPinchDetector.GestureState.Ended -> {
                    voice.voiceGestureArmingProgress = 0f
                    if (voiceState != VoiceState.LISTENING) {
                        voice.voiceGestureHint = null
                    }
                }
                SecondaryHandPinchDetector.GestureState.Idle -> {
                    voice.voiceGestureArmingProgress = 0f
                    if (voiceState != VoiceState.LISTENING) {
                        voice.voiceGestureHint = null
                    }
                }
            }
        }
    }

    LaunchedEffect(
        voice.followUpPending,
        voice.followUpDeadlineMs,
        isTtsSpeaking,
        voice.assistantSpeechPendingStart,
        voice.assistantSpeechStarted,
        voiceState,
        voice.activeVoiceJob,
    ) {
        if (
            !voice.followUpPending ||
            voice.followUpDeadlineMs == 0L ||
            isTtsSpeaking ||
            voice.assistantSpeechPendingStart ||
            voice.assistantSpeechStarted ||
            voiceState != VoiceState.IDLE ||
            voice.activeVoiceJob?.isActive == true
        ) {
            return@LaunchedEffect
        }
        val remaining = voice.followUpDeadlineMs - System.currentTimeMillis()
        if (remaining <= 0L) {
            voice.clearFollowUp()
            return@LaunchedEffect
        }
        delay(followUpAutoStartDelayMs)
        if (
            voice.followUpPending &&
            voice.followUpDeadlineMs > 0L &&
            !isTtsSpeaking &&
            !voice.assistantSpeechPendingStart &&
            !voice.assistantSpeechStarted &&
            voiceState == VoiceState.IDLE &&
            voice.activeVoiceJob?.isActive != true
        ) {
            Timber.i("VOICE: player auto-starting follow-up listening remainingMs=%d", voice.followUpDeadlineMs - System.currentTimeMillis())
            requestVoiceCommand("follow-up-auto")
        }
    }


    // Navigate back when playback ends (movie finished or last episode of season)
    LaunchedEffect(Unit) {
        viewModel.eventsChannelFlow.collect { event ->
            if (event is PlayerEvents.NavigateBack) {
                requestExit("playback-ended")
            }
        }
    }

    // Event-driven playback state. The previous implementation polled every 500 ms
    // regardless of whether anything changed; here isPlaying / playbackState /
    // duration / segment-on-seek are all driven by Player.Listener events, and the
    // only cadence-based work left is the position tick which pauses when not playing.
    DisposableEffect(player) {
        // Seed from the current player state so late-bound composition sees
        // accurate values before the first event arrives.
        currentPosition = player.currentPosition
        duration = player.duration.coerceAtLeast(0L)
        isPlaying = player.isPlaying
        playbackState = player.playbackState
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlayingNow: Boolean) {
                isPlaying = isPlayingNow
            }
            override fun onPlaybackStateChanged(state: Int) {
                playbackState = state
                duration = player.duration.coerceAtLeast(0L)
            }
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                currentPosition = player.currentPosition
                viewModel.updateCurrentSegment()
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // Position tick only while playing — stops waking up when paused or buffering.
    LaunchedEffect(player, isPlaying) {
        if (!isPlaying) return@LaunchedEffect
        while (true) {
            currentPosition = player.currentPosition
            viewModel.updateCurrentSegment()
            delay(500)
        }
    }

    // --- SceneCore video entity ---
    val overlayProjectionScale = UI_DEPTH_METERS / videoDepth

    // Update root poses when depth changes.
    // This allows the + and - buttons to dynamically adjust the spatial layout.
    LaunchedEffect(
        videoDepth,
        videoPanelScale,
        videoRootEntity.value,
        uiRootEntity.value,
        subtitleRootEntity.value,
        moveInProgress,
    ) {
        if (moveInProgress) return@LaunchedEffect
        val videoRoot = videoRootEntity.value ?: return@LaunchedEffect
        val uiRoot = uiRootEntity.value ?: return@LaunchedEffect
        val subtitleRoot = subtitleRootEntity.value ?: return@LaunchedEffect

        // Calculate the new pose by preserving direction but scaling distance.
        // We assume the user is at the origin (0,0,0) and the initial pose defines the view vector.
        val currentVideoPose = lastReportedMovePose.value ?: safeGetEntityPose(videoRoot) ?: savedPlayerPose
        val t = currentVideoPose.translation
        val length = kotlin.math.sqrt(t.x * t.x + t.y * t.y + t.z * t.z)
        val viewDirection = if (length > 0f) {
            Vector3(t.x / length, t.y / length, t.z / length)
        } else {
            Vector3(0f, 0f, -1f) // Default forward
        }
        
        val newVideoPose = Pose(
            Vector3(viewDirection.x * videoDepth, viewDirection.y * videoDepth, viewDirection.z * videoDepth),
            currentVideoPose.rotation
        )

        // UI and subtitles stay projected at uiDepth along the same vector.
        val newUiPose = Pose(
            Vector3(
                viewDirection.x * UI_DEPTH_METERS,
                viewDirection.y * UI_DEPTH_METERS,
                viewDirection.z * UI_DEPTH_METERS,
            ),
            currentVideoPose.rotation
        )

        videoRoot.setPose(newVideoPose)
        videoRoot.setScale(videoPanelScale)
        uiRoot.setPose(newUiPose)
        uiRoot.setScale(videoPanelScale)
        subtitleRoot.setPose(newUiPose)
        subtitleRoot.setScale(videoPanelScale)
        lastReportedMovePose.value = newVideoPose

        // Persist the new base position.
        savePlayerRootPose(viewModel, newVideoPose)
    }

    LaunchedEffect(videoRootEntity.value, uiRootEntity.value, subtitleRootEntity.value, startupPoseGuardActive) {
        if (!startupPoseGuardActive) return@LaunchedEffect
        videoRootEntity.value ?: return@LaunchedEffect
        uiRootEntity.value ?: return@LaunchedEffect
        subtitleRootEntity.value ?: return@LaunchedEffect

        val guardPassDelays = listOf(0L, 250L, 1000L, 2500L, 5000L, 8000L)
        for (delayMs in guardPassDelays) {
            if (!startupPoseGuardActive) break
            if (delayMs > 0L) delay(delayMs)
            if (!startupPoseGuardActive) break
            applyCurrentLaunchPose()
        }
        startupPoseGuardActive = false
    }

    DisposableEffect(session) {
        val savedPose = savedPlayerPose
        val projectedOverlayPose = projectPoseFromOrigin(savedPose, overlayProjectionScale)
        val initialShape = SurfaceEntity.Shape.Quad(FloatSize2d(DEFAULT_VIDEO_WIDTH_METERS, DEFAULT_VIDEO_HEIGHT_METERS))
        try {
            val activitySpace = session.scene.activitySpace
            val videoRoot = GroupEntity.create(session, "PlayerVideoRoot", savedPose, activitySpace)
            val uiRoot = GroupEntity.create(session, "PlayerUiRoot", projectedOverlayPose, activitySpace)
            val subtitleRoot = GroupEntity.create(session, "PlayerSubtitleRoot", projectedOverlayPose, activitySpace)
            videoRoot.setScale(videoPanelScale)
            uiRoot.setScale(videoPanelScale)
            subtitleRoot.setScale(videoPanelScale)

            val entity = SurfaceEntity.create(
                session = session,
                pose = Pose.Identity,
                shape = initialShape,
                stereoMode = mapStereoMode(currentStereoMode) ?: SurfaceEntity.StereoMode.MONO,
            ).apply {
                mediaBlendingMode = SurfaceEntity.MediaBlendingMode.OPAQUE
            }
            videoRoot.addChild(entity)
            lastReportedMovePose.value = savedPose

            // createCustomMovable gives us full control over the entity pose during a grab.
            // createSystemMovable snaps the entity to the user's hand position (arm's reach
            // ~2m) even when the video is 6m away, causing the jarring "jump to nose" bug.
            // With a custom movable we preserve the video depth and only allow lateral
            // repositioning driven by the input-ray direction.
            var grabDepth = videoDepth // depth locked at the moment the user starts a grab
            val movable = androidx.xr.scenecore.MovableComponent.createCustomMovable(
                session,
                true, // scalingEnabled
                ContextCompat.getMainExecutor(context),
                object : androidx.xr.scenecore.EntityMoveListener {
                    override fun onMoveStart(
                        entity: androidx.xr.scenecore.Entity,
                        initialInputRay: androidx.xr.runtime.math.Ray,
                        initialPose: androidx.xr.runtime.math.Pose,
                        initialScale: Float,
                        initialParent: androidx.xr.scenecore.Entity,
                    ) {
                        startupPoseGuardActive = false
                        grabDepth = extractVideoDepth(initialPose, videoDepth)
                        androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
                            lastReportedMovePose.value = initialPose
                            // Do not update videoDepth yet to avoid triggering LaunchedEffect fights
                            moveInProgress = true
                        }
                        if (!controlsVisible && !isActuallyPaused) {
                            revealControls("video-move-start")
                        }
                        syncProjectedOverlayRoots(
                            initialPose,
                            videoPanelScale,
                            uiRoot,
                            subtitleRoot,
                            UI_DEPTH_METERS / grabDepth,
                        )
                        val t = initialPose.translation
                        Timber.i(
                            "subtitle: move start targetIsVideoRoot=%b targetClass=%s grabDepth=%.3f actualPos=(%.3f, %.3f, %.3f)",
                            entity == videoRoot,
                            entity.javaClass.simpleName,
                            grabDepth,
                            t.x, t.y, t.z
                        )
                    }

                    override fun onMoveUpdate(
                        entity: androidx.xr.scenecore.Entity,
                        currentInputRay: androidx.xr.runtime.math.Ray,
                        currentPose: androidx.xr.runtime.math.Pose,
                        currentScale: Float,
                    ) {
                        // Project the ray direction to the grab depth so the video moves
                        // laterally (left/right/up/down) without snapping to hand distance.
                        val dir = currentInputRay.direction
                        val dirLen = kotlin.math.sqrt(
                            dir.x * dir.x + dir.y * dir.y + dir.z * dir.z
                        ).coerceAtLeast(0.001f)
                        val newPos = androidx.xr.runtime.math.Vector3(
                            dir.x / dirLen * grabDepth,
                            dir.y / dirLen * grabDepth,
                            dir.z / dirLen * grabDepth,
                        )
                        val depthPreservedPose = androidx.xr.runtime.math.Pose(newPos, currentPose.rotation)
                        runCatching { entity.setPose(depthPreservedPose) }
                        androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
                            lastReportedMovePose.value = depthPreservedPose
                        }
                        syncProjectedOverlayRoots(
                            depthPreservedPose,
                            videoPanelScale,
                            uiRoot,
                            subtitleRoot,
                            UI_DEPTH_METERS / grabDepth,
                        )
                        if (System.currentTimeMillis() % 1000 < 50) { // Throttle logs to ~1fps
                            val t = depthPreservedPose.translation
                            Timber.d(
                                "subtitle: move update grabDepth=%.3f actualPos=(%.3f, %.3f, %.3f)",
                                grabDepth,
                                t.x, t.y, t.z
                            )
                        }
                    }

                    override fun onMoveEnd(
                        entity: androidx.xr.scenecore.Entity,
                        finalInputRay: androidx.xr.runtime.math.Ray,
                        finalPose: androidx.xr.runtime.math.Pose,
                        finalScale: Float,
                        updatedParent: androidx.xr.scenecore.Entity?,
                    ) {
                        // Use the depth-preserved pose we computed in onMoveUpdate rather than
                        // the system's finalPose (which would still be at hand position).
                        val savedPose = lastReportedMovePose.value ?: finalPose
                        val effectiveDepth = extractVideoDepth(savedPose, videoDepth)
                        runCatching { entity.setPose(savedPose) }
                        androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
                            lastReportedMovePose.value = savedPose
                            videoDepth = effectiveDepth
                            moveInProgress = false
                        }
                        syncProjectedOverlayRoots(
                            savedPose,
                            videoPanelScale,
                            uiRoot,
                            subtitleRoot,
                            UI_DEPTH_METERS / effectiveDepth,
                        )
                        libass.bumpOverlayAttachment()
                        savePlayerRootPose(viewModel, savedPose)
                        val t = savedPose.translation
                        Timber.i(
                            "subtitle: move end effectiveDepth=%.3f actualPos=(%.3f, %.3f, %.3f)",
                            effectiveDepth,
                            t.x, t.y, t.z
                        )
                    }
                },
            )
            videoRoot.addComponent(movable)
            movable.size = movableVideoBounds(DEFAULT_VIDEO_WIDTH_METERS, DEFAULT_VIDEO_HEIGHT_METERS)

            // SceneCore can briefly re-home the movable root until the first interaction.
            // Re-apply the intended launch layout after the movable component attaches so the
            // screen starts in the cinema baseline instead of snapping there on first tap.
            runCatching {
                videoRoot.setPose(savedPose)
                videoRoot.setScale(videoPanelScale)
            }
            syncProjectedOverlayRoots(
                savedPose,
                videoPanelScale,
                uiRoot,
                subtitleRoot,
                UI_DEPTH_METERS / videoDepth,
            )
            lastReportedMovePose.value = savedPose

            videoRootEntity.value = videoRoot
            uiRootEntity.value = uiRoot
            subtitleRootEntity.value = subtitleRoot
            videoEntity.value = entity
            movableComponent.value = movable

        } catch (_: Exception) {}

        onDispose {
            videoRootEntity.value?.let { root ->
                val finalPose = lastReportedMovePose.value ?: safeGetEntityPose(root)
                finalPose?.let { savePlayerRootPose(viewModel, it) }
            }
            savePlayerRootScale(viewModel, videoPanelScale)
            // Release voice/AI services so their background work (OkHttp dispatcher,
            // LiteRT/AICore handles, coroutine scopes) doesn't leak past the screen.
            voiceServices.destroy()
            runCatching { player.clearVideoSurface() }
            runCatching { videoEntity.value?.dispose() }
            videoEntity.value = null
            // Close the GltfModel BEFORE disposing the GltfModelEntity. Filament tracks
            // material instances on the model; if the entity is disposed first, Filament
            // walks its own instance list after the backing data is gone and hits
            // assertion 'pos != mMaterialInstances.cend()' → SIGABRT.
            runCatching { mascotModel.value?.close() }
            mascotModel.value = null
            runCatching { mascotEntity.value?.dispose() }
            mascotEntity.value = null
            runCatching { videoRootEntity.value?.dispose() }
            videoRootEntity.value = null
            runCatching { uiRootEntity.value?.dispose() }
            uiRootEntity.value = null
            runCatching { subtitleRootEntity.value?.dispose() }
            subtitleRootEntity.value = null
            movableComponent.value = null
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

    LaunchedEffect(videoRootEntity.value, uiRootEntity.value, subtitleRootEntity.value, videoPanelScale) {
        val videoRoot = videoRootEntity.value ?: return@LaunchedEffect
        val uiRoot = uiRootEntity.value ?: return@LaunchedEffect
        val subtitleRoot = subtitleRootEntity.value ?: return@LaunchedEffect
        while (true) {
            val poseToMirror = safeGetEntityPose(videoRoot) ?: lastReportedMovePose.value
            poseToMirror?.let { pose ->
                val effectiveVideoDepth = extractVideoDepth(pose, videoDepth)
                syncProjectedOverlayRoots(
                    pose,
                    videoPanelScale,
                    uiRoot,
                    subtitleRoot,
                    UI_DEPTH_METERS / effectiveVideoDepth,
                )
            }
            delay(100L)
        }
    }

    LaunchedEffect(videoRootEntity.value) {
        val root = videoRootEntity.value ?: return@LaunchedEffect
        // Debounce writes to the last-known-still pose: poll fast so we catch when
        // the user lets go, but only commit after the pose stops changing for a
        // short window. Writing on every tick raced MovableComponent's final pose
        // with a stale sample and could overwrite the resting position.
        var lastSampledPose: Pose? = null
        var lastSavedPose: Pose? = null
        var lastSavedScale: Float = Float.NaN
        var stillSinceMs: Long = 0L
        val stillDebounceMs = 300L
        while (true) {
            val poseToSave = lastReportedMovePose.value ?: safeGetEntityPose(root)
            if (poseToSave != null) {
                lastReportedMovePose.value = poseToSave
                if (!posesApproximatelyEqual(poseToSave, lastSampledPose)) {
                    lastSampledPose = poseToSave
                    stillSinceMs = System.currentTimeMillis()
                } else if (
                    !posesApproximatelyEqual(poseToSave, lastSavedPose) &&
                    System.currentTimeMillis() - stillSinceMs >= stillDebounceMs
                ) {
                    savePlayerRootPose(viewModel, poseToSave)
                    lastSavedPose = poseToSave
                }
            }
            if (kotlin.math.abs(videoPanelScale - lastSavedScale) > 1e-4f) {
                savePlayerRootScale(viewModel, videoPanelScale)
                lastSavedScale = videoPanelScale
            }
            delay(100L)
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
            videoWidth = DEFAULT_VIDEO_WIDTH_METERS
            videoHeight = videoWidth / aspectRatio
        } else {
            videoWidth = DEFAULT_VIDEO_WIDTH_METERS
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
        entity.setPose(Pose.Identity)
        movableComponent.value?.size = movableVideoBounds(videoWidth, videoHeight)
    }

    LaunchedEffect(videoRootEntity.value, movableComponent.value, isLocked) {
        val videoRoot = videoRootEntity.value ?: return@LaunchedEffect
        val movable = movableComponent.value ?: return@LaunchedEffect
        val hasMovable =
            runCatching {
                videoRoot
                    .getComponentsOfType(androidx.xr.scenecore.MovableComponent::class.java)
                    .any { it === movable }
            }.getOrElse {
                Timber.d(it, "Unable to inspect XR screen movable component state")
                false
            }

        runCatching {
            if (isLocked && hasMovable) {
                videoRoot.removeComponent(movable)
                moveInProgress = false
                Timber.i("XR screen movement locked")
            } else if (!isLocked && !hasMovable) {
                videoRoot.addComponent(movable)
                movable.size = movableVideoBounds(videoWidth, videoHeight)
                Timber.i("XR screen movement unlocked")
            }
        }.onFailure {
            Timber.w(it, "Unable to update XR screen movement lock state")
        }
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
    val uiScaleFactor = UI_DEPTH_METERS / videoDepth
    val subtitleScaleFactor = UI_DEPTH_METERS / videoDepth
    val scaledVideoWidthDp = videoWidth * subtitleScaleFactor * 1000f
    val scaledVideoHeightDp = videoHeight * subtitleScaleFactor * 1000f
    val subtitlePanelWidthDp = scaledVideoWidthDp
    val subtitlePanelHeightDp = scaledVideoHeightDp
    val finalSubtitleSize = xrSubtitleSize * (subtitlePanelHeightDp / 600f).coerceAtLeast(1f)
    val controlsReferenceHeightDp = videoHeight * uiScaleFactor * 1000f
    // Controls sit further below the video surface to prevent overlap.
    // Reduced offset from 1100f to 800f to bring them slightly higher/closer to video bottom.
    val controlsPanelY = -(controlsReferenceHeightDp / 2f + 800f)

    val subtitlePanelZDp = 0f
    // XR guide recommended spawn depth: 1.75 m (-1750 dp) from user.
    // We are bringing it closer to 1.75 m (sweet spot).
    val uiAnchorZDp = 0f

    LaunchedEffect(subtitlePanelWidthDp, subtitlePanelHeightDp, density.density, player.videoSize) {
        // Preserve panel aspect ratio in the render buffer. Clamping each axis
        // independently (coerceIn per-axis) silently distorts aspect for ultra-wide
        // or ultra-tall panels — and any bitmap/panel aspect mismatch shifts every
        // \pos/\an positioned subtitle because Image uses FillBounds downstream.
        val targetW = (subtitlePanelWidthDp * density.density).coerceAtLeast(1f)
        val targetH = (subtitlePanelHeightDp * density.density).coerceAtLeast(1f)
        val maxScale = minOf(7680f / targetW, 4320f / targetH)
        val minScale = maxOf(1280f / targetW, 720f / targetH)
        val scale = when {
            maxScale < 1f -> maxScale // oversize — shrink uniformly to stay under memory caps
            minScale > 1f -> minScale // undersize — enlarge uniformly to hit min quality floor
            else -> 1f
        }
        val renderWidth = (targetW * scale).toInt().coerceIn(1280, 7680)
        val renderHeight = (targetH * scale).toInt().coerceIn(720, 4320)
        val videoW = player.videoSize.width
        val videoH = player.videoSize.height
        Timber.d(
            "subtitle: panel geometry mode=%s useLibass=%b panel=%.1fdp x %.1fdp density=%.3f render=%dx%d video=%dx%d finalTextSp=%.1f z=%.1fdp",
            currentStereoMode,
            libass.useLibass,
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
        // Always resize so the frame buffer tracks the panel. When the video dimensions
        // aren't reported yet (pre-first-frame), reuse the render size as a neutral
        // storage size; nativeInit has already seeded this, and nativeResize will update
        // it once videoW/H arrive. Passing 0 here would leave storage_size stale.
        val storageW = if (videoW > 0) videoW else renderWidth
        val storageH = if (videoH > 0) videoH else renderHeight
        libassRenderer?.resize(renderWidth, renderHeight, storageW, storageH)
    }

    // Controls at same depth as UI anchor.
    val controlsZDp = 0f

    // Enable the subtitle root entity only when there is actual subtitle content.
    // The SpatialPanel must stay alive in composition at all times (to prevent a flash on first
    // appearance), but a disabled GroupEntity does not participate in SceneCore raycast
    // hit-testing.  When disabled, grab gestures pass straight through to the video entity's
    // MovableComponent so the user can move the video even when subtitles are present.
    val hasSubtitleContent = uiState.visualSubtitlesEnabled && libass.hasSubtitleContent
    LaunchedEffect(hasSubtitleContent, subtitleRootEntity.value) {
        subtitleRootEntity.value?.setEnabled(hasSubtitleContent)
    }

    Subspace {
        val subtitleRoot = subtitleRootEntity.value
        if (subtitleRoot != null) {
            SceneCoreEntity(factory = { subtitleRoot }, modifier = SubspaceModifier) {
                // Keep the subtitle panel entity alive even when there is no subtitle content.
                // Recreating the SpatialPanel when a new line appears causes SceneCore to
                // briefly render it at the subtitle root origin before the panel offset lands,
                // which shows up as a flash below the screen.
                if (libass.useLibass) {
                    val currentBitmap = libass.bitmap
                    val showLibassContent = libass.hasContent && currentBitmap != null
                    SpatialPanel(
                        modifier = SubspaceModifier
                            .width(subtitlePanelWidthDp.dp)
                            .height(subtitlePanelHeightDp.dp)
                            .offset(x = 0.dp, y = 0.dp, z = (subtitlePanelZDp + 50f).dp),
                    ) {
                        if (showLibassContent) {
                            key(libass.overlayAttachmentVersion, libass.frameVersion) {
                                Image(
                                    painter = BitmapPainter(currentBitmap.asImageBitmap(), filterQuality = FilterQuality.High),
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.FillBounds,
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
                                view.setCues(libass.currentCues)
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }

        val videoRoot = videoRootEntity.value
        if (videoRoot != null) {
            SceneCoreEntity(factory = { videoRoot }, modifier = SubspaceModifier) {
                // ── Voice & Gesture Interaction ──
                // Anchored to the Video root so it stays visible and centered even if panels are hidden.
                VoiceControlOverlay(
                    state = voiceState,
                    partialTranscript = partialTranscript,
                    feedbackText = if (voice.characterScanActive) null else voice.voiceFeedback,
                    gestureArmingProgress = voice.voiceGestureArmingProgress,
                    gestureHint = voice.voiceGestureHint,
                    micLevel = voiceMicLevel,
                )
                CharacterScanOverlay(visible = voice.characterScanActive)
            }
        }

        val uiRoot = uiRootEntity.value
        if (uiRoot != null) {
            SceneCoreEntity(factory = { uiRoot }, modifier = SubspaceModifier) {
                // XR hit testing against a fully transparent hidden panel is unreliable on
                // device. Keep a small dedicated reveal target near the bottom of the video
                // so controls can always be brought back after auto-hide.

                // ── Control Panel ────────────────────────────────────────────────────────────
                // Floats below the video. Secondary controls are in an Orbiter on the right
                // so the main panel stays uncluttered (cinema principle: screen first, UI second).
                SpatialPanel(
                    modifier = SubspaceModifier
                        .width(1800.dp)
                        .height(800.dp)
                        .offset(x = 0.dp, y = controlsPanelY.dp, z = controlsZDp.dp),
                    resizePolicy = ResizePolicy(),
                ) {
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
                                onMoveCloser = {
                                    videoPanelScale = (videoPanelScale + 0.08f).coerceAtMost(2.5f)
                                    savePlayerRootScale(viewModel, videoPanelScale)
                                    resetAutoHide()
                                },
                                onMoveFurther = {
                                    videoPanelScale = (videoPanelScale - 0.08f).coerceAtLeast(0.75f)
                                    savePlayerRootScale(viewModel, videoPanelScale)
                                    resetAutoHide()
                                },
                                onChaptersClick = {
                                    activeDialog = "chapters"
                                    resetAutoHide()
                                },
                                onBackClick = { requestExit("controls-back") },
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
                                                    revealControls("hidden-panel-hover")
                                                }
                                            }
                                        }
                                    }
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = {
                                            revealControls("hidden-panel-click")
                                        },
                                    ),
                            )
                        }
                    }

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
                                visualSubtitlesEnabled = uiState.visualSubtitlesEnabled,
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
                                },
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
                                onWatchTrailer = { item ->
                                    itemTrailerUrl(item)?.let { trailerUrl ->
                                        runCatching {
                                            context.startActivity(
                                                Intent(Intent.ACTION_VIEW, Uri.parse(trailerUrl)).apply {
                                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                },
                                            )
                                        }.onSuccess {
                                            voice.voiceFeedback = "Opening trailer for ${item.name}"
                                        }.onFailure {
                                            voice.voiceFeedback = "Couldn't open trailer for ${item.name}"
                                        }
                                    } ?: run {
                                        voice.voiceFeedback = "No trailer found for ${item.name}"
                                    }
                                },
                                onMoreLikeThis = { item ->
                                    coroutineScope.launch {
                                        voiceSearchLoading = true
                                        voiceSearchError = null
                                        voiceSearchQuery = "More like ${item.name}"
                                        val chatEngine = requireChatEngine()
                                        val response =
                                            chatEngine.query(
                                                question = "more like this",
                                                playerState = currentRecommendationSnapshot(),
                                                storySoFarContext = uiState.storySoFarContext,
                                                recentSubtitleLines = subtitleContext.assistantLines,
                                                currentPositionMs = player.currentPosition,
                                                assistantPreferences =
                                                    AssistantPreferences(
                                                        verbosity = assistantVerbosity,
                                                        spoilerPolicy = assistantSpoilerPolicy,
                                                        spokenRepliesEnabled = assistantSpokenReplies,
                                                    ),
                                                onSearchQuery = onSearchQuery,
                                                conversationHistory = voice.conversationHistory,
                                                onGetSuggestions = { viewModel.repository.getSuggestions() },
                                                recommendationContext = RecommendationContext(item.name, listOf(item)),
                                                subtitleCacheFallback = subtitleContext.cacheFallback,
                                            )
                                        voiceSearchLoading = false
                                        response.recommendedItems
                                            .takeIf { it.isNotEmpty() }
                                            ?.let {
                                                voice.recommendationContext = RecommendationContext(item.name, it)
                                                voiceSearchResults = it
                                            }
                                            ?: run {
                                                voiceSearchError = "No similar titles found"
                                            }
                                        voice.voiceFeedback = response.text
                                    }
                                },
                                onToggleFavorite = { item ->
                                    coroutineScope.launch {
                                        runCatching {
                                            if (item.favorite) {
                                                viewModel.repository.unmarkAsFavorite(item.id)
                                            } else {
                                                viewModel.repository.markAsFavorite(item.id)
                                            }
                                        }.onSuccess {
                                            val updated = item.withFavorite(!item.favorite)
                                            updateVoiceSearchItem(updated)
                                            voice.recommendationContext =
                                                voice.recommendationContext?.let { context ->
                                                    if (context.items.any { it.id == updated.id }) {
                                                        context.copy(
                                                            items = context.items.map { existing ->
                                                                if (existing.id == updated.id) updated else existing
                                                            },
                                                        )
                                                    } else {
                                                        context
                                                    }
                                                }
                                            voice.voiceFeedback =
                                                if (updated.favorite) {
                                                    "Saved ${updated.name}"
                                                } else {
                                                    "Removed ${updated.name} from favorites"
                                                }
                                        }.onFailure {
                                            voice.voiceFeedback = "Couldn't update favorite for ${item.name}"
                                        }
                                    }
                                },
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
    }
}

// SecondaryControlsOrbiter / SyncPlayDialogContent → PlayerSecondaryControls.kt

// startVoiceCapture / dispatchVoiceParseResult / shouldSpeakVoiceFeedback → PlayerVoiceCapture.kt

// currentChapterName, trackNames, selectedTrackName, selectedTrackLanguage,
//   groupIsSelected → PlayerTrackUtils.kt

// VoiceSearchDialogContent → PlayerSearchDialogs.kt

// searchResultTypeLabel / SearchResultPoster / itemPosterUri / itemTrailerUrl / withFavorite / canPlayFromVoiceSearch → PlayerSearchViews.kt

// ControlPanelUI → PlayerControlPanel.kt

// ChaptersDialogContent / TrackSelectionDialogContent / SpeedDialogContent / QualityDialogContent → PlayerDialogs.kt

// PersonPhoto / SectionHeader / CrewRow / ActorCard / CastCrewDialogContent → PlayerCastViews.kt

// NextEpisodePanelContent → PlayerNextEpisodePanel.kt

// ProgressSection → PlayerProgressSection.kt

// ── Helpers ───────────────────────────────────────────────────────────────────────

// mapStereoMode, formatTime → PlayerTrackUtils.kt
// extractVideoDepth, projectPoseFromOrigin, syncProjectedOverlayRoots,
//   constrainPoseToDepth, safeGetEntityPose → PlayerGeometry.kt
// loadSavedPlayerRootPose, loadSavedPlayerRootScale, savePlayerRootPose,
//   savePlayerRootScale, isRestorableVideoPose, posesApproximatelyEqual → PlayerPoseStorage.kt

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

