package dev.jdtech.jellyfin.player.xr
import dev.jdtech.jellyfin.player.core.ProjectionModeDetector
import dev.jdtech.jellyfin.player.core.transcode.OnDeviceTranscodeState

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
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import com.skydoves.compose.stability.runtime.TraceRecomposition
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import androidx.xr.compose.spatial.Orbiter
import androidx.xr.compose.spatial.OrbiterAlignment
import androidx.xr.compose.unit.DpVolumeOffset
import androidx.xr.compose.spatial.OrbiterDefaults
import androidx.xr.compose.spatial.SpatialDialog
import androidx.xr.compose.spatial.Subspace
import androidx.xr.compose.subspace.layout.resizable
import androidx.xr.compose.subspace.SpatialPanel
import androidx.xr.compose.subspace.SpatialGltfModel
import androidx.xr.compose.subspace.SpatialGltfModelSource
import androidx.xr.compose.subspace.draw.scale
import androidx.xr.compose.subspace.rememberSpatialGltfModelState
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
import androidx.xr.scenecore.SpatialCapability
import androidx.xr.scenecore.SpatialEnvironment
import androidx.xr.scenecore.SurfaceEntity
import androidx.xr.scenecore.Entity
import androidx.xr.scenecore.Space
import androidx.xr.compose.subspace.SceneCoreEntity
import androidx.xr.compose.subspace.ExperimentalSpatialGltfModelApi
import androidx.xr.scenecore.scene
import androidx.core.content.ContextCompat
import dev.jdtech.jellyfin.player.local.domain.getTrackNames
import dev.jdtech.jellyfin.player.local.presentation.PlayerEvents
import dev.jdtech.jellyfin.player.local.presentation.PlayerViewModel
import dev.jdtech.jellyfin.player.session.voice.ActivePlayerSessionHolder
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
private const val NEXT_EPISODE_THRESHOLD_MS = 30 * 1_000L  // show in last 30 seconds (or during outro)
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
private const val VIDEO_MOVE_AFFORDANCE_MARGIN_METERS = 0.40f
private const val VIDEO_MOVE_AFFORDANCE_DEPTH_METERS = 0.01f
private const val XR_VIDEO_FIRST_FRAME_TIMEOUT_MS = 3_000L

private val PausedMascotPose =
    Pose(
        Vector3(-2.15f, -0.98f, 0.95f),
        Quaternion(0f, 0.9914f, 0f, 0.1305f),
    )

private fun videoMoveAffordanceBounds(videoWidth: Float, videoHeight: Float): FloatSize3d =
    FloatSize3d(
        width = (videoWidth + VIDEO_MOVE_AFFORDANCE_MARGIN_METERS * 2f).coerceAtLeast(1f),
        height = (videoHeight + VIDEO_MOVE_AFFORDANCE_MARGIN_METERS * 2f).coerceAtLeast(1f),
        depth = VIDEO_MOVE_AFFORDANCE_DEPTH_METERS,
    )

private fun Cue.debugSummary(): String {
    val preview = text?.toString()?.replace('\n', ' ')?.replace('\r', ' ')?.take(60) ?: "<bitmap>"
    return "text=\"$preview\" line=$line lineType=$lineType lineAnchor=$lineAnchor position=$position positionAnchor=$positionAnchor size=$size textSize=$textSize verticalType=$verticalType"
}

private fun playerStateName(state: Int): String =
    when (state) {
        Player.STATE_IDLE -> "IDLE"
        Player.STATE_BUFFERING -> "BUFFERING"
        Player.STATE_READY -> "READY"
        Player.STATE_ENDED -> "ENDED"
        else -> state.toString()
    }

private fun Tracks.videoGroupCount(): Int =
    groups.count { group -> group.type == C.TRACK_TYPE_VIDEO }

private fun Tracks.hasVideoTrack(): Boolean = videoGroupCount() > 0

/**
 * SpatialPlayerScreen — cinematic immersive XR playback experience.
 *
 * Architecture:
 *  - SurfaceEntity (SceneCore): high-fidelity video surface with the native move affordance
 *  - Entity (SceneCore): logical overlay root mirrored from the translating video surface
 *  - Subtitle SpatialPanel: perspective-scaled, follows video pose via state
 *  - Control SpatialPanel: floats below the video with:
 *      • Orbiter (End): secondary controls (audio / subtitle / speed)
 *      • Orbiter (Bottom): reveal button when controls are hidden
 *      • SpatialDialog: track & speed selection (pushes panel back 125dp for depth)
 *  - Skip SpatialPanel: contextual, right of controls
 */
@Composable
@TraceRecomposition(tag = "xr-player", threshold = 3)
fun SpatialPlayerScreen(
    viewModel: PlayerViewModel,
    session: Session,
    initialStereoMode: String,
    initialProjection: String = ProjectionModeDetector.PROJECTION_FLAT,
    itemId: UUID?,
    localMediaId: Long?,
    networkVideoId: String? = null,
    universalPluginId: String? = null,
    universalItemId: String? = null,
    universalVideoUrl: String? = null,
    universalTitle: String? = null,
    mediaUri: Uri? = null,
    mediaTitle: String? = null,
    itemKind: String,
    startFromBeginning: Boolean,
    mediaSourceIndex: Int? = null,
    maxBitrate: Long? = null,
    audioStreamIndex: Int? = null,
    subtitleStreamIndex: Int? = null,
    subtitlesDisabled: Boolean = false,
    openSyncPlayDialogOnStart: Boolean = false,
    libassRenderer: LibassRenderer?,
    onSearchQuery: suspend (String) -> List<SpatialFinItem>,
    onLaunchSearchResult: (SpatialFinItem) -> Unit,
    telemetryStore: VoiceTelemetryStore,
    fcastController: dev.jdtech.jellyfin.fcast.sender.FCastCastingController,
    onBackClick: () -> Unit,
    /**
     * True when this Activity was launched as the video master of a split-A/V session. The
     * voice-ducking effect must respect mute in that mode — see [PlayerVoiceCoordinator].
     */
    splitAvVideoRole: Boolean = false,
    /**
     * Fold split-A/V audio back to this headset. Must reach the main-process
     * [SplitAvController] (via the IPC bridge) — the `:xrplayer`-local FCast controller has
     * no session, so calling it here is a no-op. Default no-op for non-split callers.
     */
    onEndSplitAvFromMaster: () -> Unit = {},
    spatialAudioController: XrSpatialAudioController? = null,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = context as? Activity
    val coroutineScope = rememberCoroutineScope()
    val uiState by viewModel.uiState.collectAsState()
    val transcodeState by viewModel.onDeviceTranscodeState.collectAsState()
    val syncPlayState by viewModel.syncPlayState.collectAsState()
    val player by viewModel.playerFlow.collectAsState()
    val savedPlayerPose = remember(viewModel) { loadSavedPlayerRootPose(viewModel) }
    val savedPlayerScale = remember(viewModel) { loadSavedPlayerRootScale(viewModel) }
    val savedPlayerDepth = remember(viewModel) { loadSavedPlayerRootDepth(viewModel) }

    val videoRootEntity = remember { mutableStateOf<Entity?>(null) }
    val uiRootEntity = remember { mutableStateOf<Entity?>(null) }
    val subtitleRootEntity = remember { mutableStateOf<Entity?>(null) }
    val videoEntity = remember { mutableStateOf<SurfaceEntity?>(null) }
    var pausedMascotAvailable by remember { mutableStateOf(false) }
    val movableComponent = remember { mutableStateOf<androidx.xr.scenecore.MovableComponent?>(null) }
    val lastReportedMovePose = remember { mutableStateOf<Pose?>(null) }
    val lastAnchorHeadPos = remember { mutableStateOf<Vector3?>(null) }
    var preMoveSnapshotPose by remember { mutableStateOf<Pose?>(null) }
    var preMoveSnapshotHeadPos by remember { mutableStateOf<Vector3?>(null) }
    var videoDepth by remember(savedPlayerPose, savedPlayerDepth) {
        mutableFloatStateOf(savedPlayerDepth)
    }
    var videoPanelScale by remember(savedPlayerScale) { mutableFloatStateOf(savedPlayerScale) }
    var lastPointerPosition by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
    var moveInProgress by remember { mutableStateOf(false) }

    // --- Wall anchoring (pin the flat panel to a real wall) ---
    val wallAnchorSpace = remember { mutableStateOf<androidx.xr.scenecore.AnchorSpace?>(null) }
    // wallPinRequested is the user's intent for THIS session; wallPinActive is the
    // achieved state once an anchor is found and the panel is reparented onto it.
    // Deliberately starts false every launch (never auto-engages a saved pin): a stale
    // pin re-attaching on startup could strand the controls out of reach. The user
    // re-taps "Pin to wall" per session; the persisted anchor UUID still lets that
    // re-pin reuse the same wall quickly.
    var wallPinRequested by remember { mutableStateOf(false) }
    var wallPinActive by remember { mutableStateOf(false) }
    var wallPinStatus by remember { mutableStateOf<String?>(null) }
    var wallAutoRestoreAttempted by remember(session) { mutableStateOf(false) }
    var hasSceneUnderstandingPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, WallAnchor.SCENE_UNDERSTANDING_PERMISSION) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

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
        splitAvVideoRole = splitAvVideoRole,
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
    val sceneUnderstandingPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            hasSceneUnderstandingPermission = granted
            if (!granted) {
                wallPinRequested = false
                wallPinStatus = "Wall access denied"
                viewModel.appPreferences.setValue(viewModel.appPreferences.xrPlayerWallPinned, false)
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
    val subtitleOffsetMs by viewModel.subtitleOffsetMs.collectAsState()
    val audioOffsetMs by viewModel.audioOffsetMs.collectAsState()
    var currentPosition by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    var playbackState by remember { mutableIntStateOf(Player.STATE_IDLE) }
    var savedPassthroughOpacity by remember {
        mutableFloatStateOf(
            XrPassthroughController.clampOpacity(
                viewModel.appPreferences.getValue(viewModel.appPreferences.xrPlayerPassthroughOpacity)
            )
        )
    }
    var passthroughOverrideOpacity by remember { mutableStateOf<Float?>(null) }
    var screenCurvePreset by remember {
        mutableStateOf(XrScreenCurvePreset.fromPreferences(viewModel.appPreferences))
    }
    val curvedScreenEnabled = screenCurvePreset != XrScreenCurvePreset.FLAT
    var statsForNerdsEnabled by remember { mutableStateOf(false) }
    var currentStereoMode by remember { mutableStateOf(initialStereoMode) }
    // Spatial projection axis (flat / 180° hemisphere / 360° sphere), orthogonal
    // to the stereo axis above. `projectionManuallySet` locks out the runtime
    // container-metadata auto-detect once the user picks from the player menu.
    var currentProjection by remember { mutableStateOf(initialProjection) }
    var projectionManuallySet by remember { mutableStateOf(false) }
    val immersiveProjection = isImmersiveProjection(currentProjection)
    var reportedVideoSize by remember { mutableStateOf(player.videoSize) }
    var hasVideoTrack by remember { mutableStateOf(player.currentTracks.hasVideoTrack()) }
    var activeVideoColorInfo by remember {
        mutableStateOf(XrSurfaceColorMetadata.extractActiveVideoColorInfo(player.currentTracks))
    }
    var firstVideoFrameRendered by remember { mutableStateOf(false) }
    var videoSurfaceBound by remember { mutableStateOf(false) }
    var videoWidth by remember { mutableFloatStateOf(DEFAULT_VIDEO_WIDTH_METERS) }
    var videoHeight by remember { mutableFloatStateOf(DEFAULT_VIDEO_HEIGHT_METERS) }

    val libass = rememberLibassRenderer(
        player = player,
        currentStereoMode = currentStereoMode,
        libassUsagePref = libassUsagePref,
        libassRenderer = libassRenderer,
        subtitleOffsetMs = subtitleOffsetMs,
        onCueRecorded = subtitleContext::recordCueLine,
    )

    fun updatePassthroughOpacity(newOpacity: Float) {
        val clamped = XrPassthroughController.clampOpacity(newOpacity)
        savedPassthroughOpacity = clamped
        passthroughOverrideOpacity = clamped
        viewModel.appPreferences.setValue(viewModel.appPreferences.xrPlayerPassthroughOpacity, clamped)
    }

    fun updateScreenCurvePreset(preset: XrScreenCurvePreset) {
        screenCurvePreset = preset
        val enabled = preset != XrScreenCurvePreset.FLAT
        viewModel.appPreferences.setValue(viewModel.appPreferences.xrPlayerScreenCurvePreset, preset.key)
        viewModel.appPreferences.setValue(viewModel.appPreferences.xrPlayerCurvedScreen, enabled)
    }

    fun updateCurvedScreen(enabled: Boolean) {
        val targetPreset = if (enabled) {
            if (screenCurvePreset != XrScreenCurvePreset.FLAT) screenCurvePreset else XrScreenCurvePreset.CINEMA
        } else {
            XrScreenCurvePreset.FLAT
        }
        updateScreenCurvePreset(targetPreset)
    }

    // Immersive 180/360 fills the field of view, so passthrough is always blacked
    // out (the sphere/hemisphere is the environment). Flat/curved panels use the
    // manual opacity override, or 100% when paused and savedPassthroughOpacity when playing.
    val effectivePassthroughOpacity = if (immersiveProjection) {
        0.0f
    } else {
        passthroughOverrideOpacity ?: if (!isPlaying) 1.0f else savedPassthroughOpacity
    }
    val passthroughEnabled = XrPassthroughController.isPassthroughActive(effectivePassthroughOpacity)

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
    val pausedMascotVisible = pausedMascotAvailable && isActuallyPaused && showPausedMascot
    // --- Controls UI state ---
    var controlsVisible by remember { mutableStateOf(true) }
    var isLocked by remember { mutableStateOf(false) }
    var controlsInputActive by remember { mutableStateOf(false) }
    var hideTimestamp by remember { mutableLongStateOf(System.currentTimeMillis()) }

    // --- Next episode panel state ---
    var nextEpisodePanelDismissed by remember { mutableStateOf(false) }
    // Reset dismissal whenever the playing item changes (user started a new episode).
    // Keyed on item id, not title — back-to-back episodes can share a title
    // ("Pilot", "Part 1") and the panel must still re-arm.
    LaunchedEffect(uiState.currentItemId ?: uiState.currentItemTitle) {
        nextEpisodePanelDismissed = false
    }
    val isInOutro = uiState.currentSegment?.type == dev.jdtech.jellyfin.models.SpatialFinSegmentType.OUTRO
    val remainingMs = (duration - currentPosition).coerceAtLeast(0L)
    // Show the panel during the last NEXT_EPISODE_THRESHOLD_MS of an episode (or during outro credits)
    // when a next episode exists — but not for movies, very short content, or when controls are locked.
    val showNextEpisodePanel = !nextEpisodePanelDismissed &&
        !isLocked &&
        uiState.nextEpisode != null &&
        duration > NEXT_EPISODE_THRESHOLD_MS * 2 &&
        ((isInOutro && remainingMs <= 60_000L) || remainingMs in 0L..NEXT_EPISODE_THRESHOLD_MS)

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
        Timber.tag("SplitAvPauseTrace").w("pause source=requestExit")
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
                    "pause" -> { Timber.tag("SplitAvPauseTrace").w("pause source=mcp"); player.pause() }
                    "skip_forward" -> player.seekForward()
                    "skip_backward" -> player.seekBack()
                    "next_episode" -> viewModel.skipToNextItem()
                    "toggle_controls" -> controlsVisible = !controlsVisible
                    "toggle_passthrough" -> updatePassthroughOpacity(
                        XrPassthroughController.nextSteppedPreset(effectivePassthroughOpacity),
                    )
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
            mediaStreams = uiState.currentMediaStreams,
        )

    // Re-anchor the immersive 180/360 surface onto the viewer's current head
    // position with horizontal yaw-only orientation and aligned overlays.
    fun recenterImmersiveSurface() {
        if (!immersiveProjection) return
        val headPose = computeHeadCenteredPose(session)
        val (spherePose, overlayPose) = computeImmersiveViewerPoses(headPose, UI_DEPTH_METERS)
        lastAnchorHeadPos.value = headPose?.translation ?: Vector3.Zero
        runCatching {
            videoRootEntity.value?.setPose(spherePose)
            videoEntity.value?.setPose(spherePose)
            uiRootEntity.value?.let { root ->
                root.setPose(overlayPose)
                root.setScale(videoPanelScale)
            }
            subtitleRootEntity.value?.let { root ->
                root.setPose(overlayPose)
                root.setScale(videoPanelScale)
            }
        }
        lastReportedMovePose.value = spherePose
        libass.bumpOverlayAttachment()
    }

    fun recenterScreenToViewer(
        manualPitch: Boolean,
        resetScaleAndDepth: Boolean = false,
    ) {
        if (immersiveProjection) {
            recenterImmersiveSurface()
            return
        }
        if (manualPitch && (wallPinActive || wallPinRequested)) {
            wallPinRequested = false
            wallPinStatus = null
            WallAnchor.unpersist(
                session,
                viewModel.appPreferences.getValue(viewModel.appPreferences.xrPlayerWallAnchorUuid),
            )
            viewModel.appPreferences.setValue(viewModel.appPreferences.xrPlayerWallAnchorUuid, "")
            viewModel.appPreferences.setValue(viewModel.appPreferences.xrPlayerWallPinned, false)
        }
        val targetDepth = if (resetScaleAndDepth) VIDEO_DEPTH_METERS else videoDepth
        val targetScale = if (resetScaleAndDepth) DEFAULT_VIDEO_PANEL_SCALE else videoPanelScale
        if (resetScaleAndDepth) {
            videoDepth = targetDepth
            videoPanelScale = targetScale
        }
        val headPose = computeHeadCenteredPose(session)
        val headPos = headPose?.translation
        lastAnchorHeadPos.value = headPos ?: Vector3.Zero
        val targetPose = computeViewerCenteredPanelPose(headPose, targetDepth, manualPitch)
        val projectedOverlayPose = projectOverlayPoseTowardViewer(targetPose, headPos, UI_DEPTH_METERS)
        lastReportedMovePose.value = targetPose
        runCatching {
            videoRootEntity.value?.let { root ->
                root.setPose(targetPose)
                root.setScale(targetScale)
            }
        }
        runCatching {
            videoEntity.value?.let { surface ->
                surface.setPose(targetPose)
                surface.setScale(targetScale)
            }
        }
        runCatching {
            uiRootEntity.value?.let { root ->
                root.setPose(projectedOverlayPose)
                root.setScale(targetScale)
            }
        }
        runCatching {
            subtitleRootEntity.value?.let { root ->
                root.setPose(projectedOverlayPose)
                root.setScale(targetScale)
            }
        }
        savePlayerRootPose(viewModel, targetPose)
        if (resetScaleAndDepth) {
            savePlayerRootScale(viewModel, targetScale)
            savePlayerRootDepth(viewModel, targetDepth)
        }
        libass.bumpOverlayAttachment()
    }

    // FCast casting controller — process-singleton so an active cast keeps streaming across
    // screen / Activity boundaries. We never call shutdown() here; that's process-scope only.
    val fcastCastingController = fcastController
    val fcastCastingState by fcastCastingController.status.collectAsState()

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
                setPassthroughEnabled = { enabled ->
                    updatePassthroughOpacity(if (enabled) 1.0f else 0.0f)
                },
                getPassthroughEnabled = { passthroughEnabled },
                setPassthroughOpacity = { opacity -> updatePassthroughOpacity(opacity) },
                getPassthroughOpacity = { effectivePassthroughOpacity },
                setCurvedScreenEnabled = { enabled -> updateCurvedScreen(enabled) },
                getCurvedScreenEnabled = { curvedScreenEnabled },
                setScreenCurvePreset = { presetKey ->
                    updateScreenCurvePreset(XrScreenCurvePreset.fromKey(presetKey))
                },
                getScreenCurvePreset = { screenCurvePreset.key },
                setStatsForNerdsEnabled = { enabled -> statsForNerdsEnabled = enabled },
                getStatsForNerdsEnabled = { statsForNerdsEnabled },
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
                    videoEntity.value?.let { surface ->
                        runCatching { surface.setScale(updatedScale) }
                    }
                },
                onAdjustDistance = { delta, reset ->
                    val updatedDepth = if (reset) {
                        VIDEO_DEPTH_METERS
                    } else if (delta != null) {
                        (videoDepth + delta).coerceIn(2.0f, 15.0f)
                    } else {
                        videoDepth
                    }
                    videoDepth = updatedDepth
                    savePlayerRootDepth(viewModel, updatedDepth)
                },
                onResetScreenPlacement = { recenterScreenToViewer(manualPitch = true) },
                onCastToFCastReceiver = onCastToFCastReceiver@{ action ->
                    val item = player.currentMediaItem
                    val cfg = item?.localConfiguration
                    val url = cfg?.uri?.toString()
                        ?: return@onCastToFCastReceiver "Nothing is playing to cast"
                    val container = cfg.mimeType
                        ?: dev.jdtech.jellyfin.fcast.sender.PlayMessageBuilder.guessContainer(url)
                        ?: "video/mp4"
                    val play = dev.jdtech.jellyfin.fcast.sender.PlayMessageBuilder.build(
                        url = url,
                        container = container,
                        positionSeconds = (player.currentPosition.coerceAtLeast(0L)) / 1000.0,
                        title = uiState.currentItemTitle,
                    )
                    val host = action.host
                    val port = action.port
                    val receiver = if (host != null && port != null) {
                        dev.jdtech.jellyfin.fcast.sender.FCastReceiver(
                            host = host,
                            port = port,
                            name = action.name ?: host,
                        )
                    } else {
                        activeDialog = "fcast"
                        return@onCastToFCastReceiver "Open the cast picker to choose a receiver"
                    }
                    runCatching {
                        player.pause()
                        fcastCastingController.startCast(receiver, play)
                    }.fold(
                        onSuccess = { "Casting to ${receiver.name}" },
                        onFailure = { "Cast failed: ${it.message ?: "unknown error"}" },
                    )
                },
                onStopFCastCasting = {
                    runCatching { fcastCastingController.stopCast() }
                        .fold(
                            onSuccess = { "Casting stopped" },
                            onFailure = { "Stop failed: ${it.message ?: "unknown error"}" },
                        )
                },
            )
        }

    DisposableEffect(sessionController) {
        val activeSession = ActivePlayerSessionHolder.ActiveSession(
            controller = sessionController,
            snapshotProvider = { currentRecommendationSnapshot() },
            chaptersProvider = {
                viewModel.uiState.value.currentChapters.mapIndexed { index, chapter ->
                    (chapter.name ?: "Chapter ${index + 1}") to chapter.startPosition
                }
            },
            volumeProvider = { player.volume },
            speedProvider = { player.playbackParameters.speed },
            streamUrlProvider = { player.currentMediaItem?.localConfiguration?.uri?.toString() },
            currentItemIdProvider = { viewModel.uiState.value.currentItemId },
            // Resume position comes from Jellyfin's own playback state, which is what
            // "Continue Watching" means; startPositionMs is advisory only.
            onPlayMediaItem = { itemId, _, _ ->
                runCatching {
                    val item = viewModel.repository.getItem(java.util.UUID.fromString(itemId))
                        ?: return@runCatching "Item not found"
                    onLaunchSearchResult(item)
                    "Playing ${item.name}"
                }.getOrElse { "Could not start that item" }
            },
            onVoiceCommand = { transcript ->
                runCatching {
                    val parsed = requireCommandCoordinator()
                        .parse(transcript, currentRecommendationSnapshot())
                    dispatchVoiceParseResult(sessionController, parsed)
                }.getOrElse { "Sorry, I couldn't process that." }
            },
        )
        ActivePlayerSessionHolder.bind(activeSession)
        onDispose {
            ActivePlayerSessionHolder.unbind(activeSession)
        }
    }

    fun speakAssistantReply(text: String, languageHint: String?, queueMode: Int = android.speech.tts.TextToSpeech.QUEUE_FLUSH) {
        voice.speakAssistantReply(
            text = text,
            languageHint = languageHint,
            spokenRepliesEnabled = assistantSpokenReplies,
            assistantVoiceName = assistantVoiceName,
            player = player,
            tts = voiceServices.tts,
            queueMode = queueMode,
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
                mediaStreams = uiState.currentMediaStreams,
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
                selectedTrackLanguage(player, C.TRACK_TYPE_TEXT, uiState.currentMediaStreams)
                    ?: selectedTrackLanguage(player, C.TRACK_TYPE_AUDIO, uiState.currentMediaStreams)
                    ?: selectedTrackName(player, C.TRACK_TYPE_TEXT, uiState.currentMediaStreams)
                    ?: selectedTrackName(player, C.TRACK_TYPE_AUDIO, uiState.currentMediaStreams),
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
            onSpokenReply = { text, lang, queueMode -> speakAssistantReply(text, lang, queueMode) },
            onCharacterScanActiveChanged = { voice.characterScanActive = it },
            onCastCardMatched = { matchedPeople ->
                voice.activeCastCard = CastCardUiModel(
                    matchedPeople = matchedPeople,
                    selectedPersonIndex = 0,
                    isLoadingRelated = true,
                )
            },
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
        voice.activeCastCard = null
    }

    LaunchedEffect(voice.activeCastCard?.matchedPeople, voice.activeCastCard?.selectedPersonIndex) {
        val card = voice.activeCastCard ?: return@LaunchedEffect
        val selectedPerson = card.selectedPerson ?: return@LaunchedEffect
        val personUuid = selectedPerson.id?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        if (personUuid == null) {
            if (card.isLoadingRelated) {
                voice.activeCastCard = card.copy(relatedItems = emptyList(), isLoadingRelated = false)
            }
            return@LaunchedEffect
        }
        val related = runCatching {
            viewModel.repository.getPersonItems(
                personIds = listOf(personUuid),
                includeTypes = listOf(
                    org.jellyfin.sdk.model.api.BaseItemKind.MOVIE,
                    org.jellyfin.sdk.model.api.BaseItemKind.SERIES,
                ),
                recursive = true,
            )
        }.getOrDefault(emptyList())
            .filter { it.id.toString() != uiState.currentItemId }
            .take(6)

        val currentCard = voice.activeCastCard
        if (currentCard != null && currentCard.selectedPerson?.id == selectedPerson.id) {
            voice.activeCastCard = currentCard.copy(
                relatedItems = related,
                isLoadingRelated = false,
            )
        }
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

    // Immersive environment: blackout during playback unless passthrough is active.
    LaunchedEffect(effectivePassthroughOpacity, passthroughEnabled) {
        val environment = session.scene.spatialEnvironment
        try {
            environment.preferredSpatialEnvironment =
                if (passthroughEnabled) null
                else SpatialEnvironment.SpatialEnvironmentPreference(imageBasedLightingAsset = null, geometry = null)
        } catch (_: Exception) {}
        XrPassthroughController.applyOpacity(session, effectivePassthroughOpacity)
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

    LaunchedEffect(session) {
        ensureHeadTrackingConfigured(session)
    }

    LaunchedEffect(session, hasHandTrackingPermission) {
        if (!hasHandTrackingPermission) return@LaunchedEffect
        runCatching {
            session.configure(
                androidx.xr.runtime.Config.Builder(session.config)
                    .setHandTracking(HandTrackingMode.BOTH)
                    .setDeviceTracking(androidx.xr.runtime.DeviceTrackingMode.SPATIAL)
                    .build(),
            )
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
        reportedVideoSize = player.videoSize
        hasVideoTrack = player.currentTracks.hasVideoTrack()
        activeVideoColorInfo = XrSurfaceColorMetadata.extractActiveVideoColorInfo(player.currentTracks)
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlayingNow: Boolean) {
                isPlaying = isPlayingNow
            }
            override fun onPlaybackStateChanged(state: Int) {
                playbackState = state
                duration = player.duration.coerceAtLeast(0L)
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                firstVideoFrameRendered = false
                reportedVideoSize = player.videoSize
                hasVideoTrack = player.currentTracks.hasVideoTrack()
                activeVideoColorInfo = XrSurfaceColorMetadata.extractActiveVideoColorInfo(player.currentTracks)
                Timber.i(
                    "XR_VIDEO: media item transition reason=%d mediaId=%s stereo=%s hasVideoTrack=%b",
                    reason,
                    mediaItem?.mediaId,
                    currentStereoMode,
                    hasVideoTrack,
                )
            }
            override fun onTracksChanged(tracks: Tracks) {
                hasVideoTrack = tracks.hasVideoTrack()
                activeVideoColorInfo = XrSurfaceColorMetadata.extractActiveVideoColorInfo(tracks)
                // Authoritative projection tier: if the container's decoded video
                // Format carries spherical metadata, it overrides the filename
                // guess — unless the user has manually pinned the projection.
                if (!projectionManuallySet) {
                    val videoFormat = tracks.groups
                        .firstOrNull { it.type == C.TRACK_TYPE_VIDEO && it.length > 0 }
                        ?.getTrackFormat(0)
                    val detected = videoFormat?.let { projectionFromFormat(it) }
                    if (detected != null && detected != currentProjection) {
                        Timber.i(
                            "XR_VIDEO: container projection metadata detected=%s (was %s)",
                            detected,
                            currentProjection,
                        )
                        currentProjection = detected
                    }
                }
                Timber.i(
                    "XR_VIDEO: tracks changed videoGroups=%d hasVideoTrack=%b stereo=%s projection=%s playbackState=%s",
                    tracks.videoGroupCount(),
                    hasVideoTrack,
                    currentStereoMode,
                    currentProjection,
                    playerStateName(player.playbackState),
                )
            }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                reportedVideoSize = videoSize
                Timber.i(
                    "XR_VIDEO: video size changed %dx%d pixelRatio=%.3f stereo=%s surfaceBound=%b entityPresent=%b",
                    videoSize.width,
                    videoSize.height,
                    videoSize.pixelWidthHeightRatio,
                    currentStereoMode,
                    videoSurfaceBound,
                    videoEntity.value != null,
                )
            }
            override fun onRenderedFirstFrame() {
                if (!firstVideoFrameRendered) {
                    firstVideoFrameRendered = true
                    val videoSize = reportedVideoSize
                    Timber.i(
                        "XR_VIDEO: first rendered frame posMs=%d videoSize=%dx%d stereo=%s surfaceBound=%b entityPresent=%b",
                        player.currentPosition,
                        videoSize.width,
                        videoSize.height,
                        currentStereoMode,
                        videoSurfaceBound,
                        videoEntity.value != null,
                    )
                }
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

    LaunchedEffect(
        player,
        isPlaying,
        playbackState,
        hasVideoTrack,
        firstVideoFrameRendered,
        videoSurfaceBound,
        videoEntity.value,
        reportedVideoSize,
        currentStereoMode,
    ) {
        if (
            !isPlaying ||
            playbackState != Player.STATE_READY ||
            !hasVideoTrack ||
            firstVideoFrameRendered
        ) {
            return@LaunchedEffect
        }
        delay(XR_VIDEO_FIRST_FRAME_TIMEOUT_MS)
        if (
            player.isPlaying &&
            player.playbackState == Player.STATE_READY &&
            hasVideoTrack &&
            !firstVideoFrameRendered
        ) {
            val videoSize = reportedVideoSize
            val entity = videoEntity.value
            Timber.w(
                "XR_VIDEO: playback is audible/ready without first rendered frame timeoutMs=%d stereo=%s videoSize=%dx%d playbackState=%s surfaceBound=%b entityPresent=%b entityDisposed=%b surfaceValid=%b posMs=%d durationMs=%d videoGroups=%d",
                XR_VIDEO_FIRST_FRAME_TIMEOUT_MS,
                currentStereoMode,
                videoSize.width,
                videoSize.height,
                playerStateName(player.playbackState),
                videoSurfaceBound,
                entity != null,
                entity?.isDisposed == true,
                runCatching { entity?.getSurface()?.isValid == true }.getOrDefault(false),
                player.currentPosition,
                player.duration,
                player.currentTracks.videoGroupCount(),
            )
        }
    }

    // --- SceneCore video entity ---
    // Update root poses when depth changes.
    // This allows the + and - buttons to dynamically adjust the spatial layout.
    LaunchedEffect(
        videoDepth,
        videoPanelScale,
        videoRootEntity.value,
        uiRootEntity.value,
        subtitleRootEntity.value,
        moveInProgress,
        wallPinActive,
    ) {
        if (moveInProgress) return@LaunchedEffect
        // Immersive 180/360 is head-centred with a fixed forward overlay — the
        // flat depth/scale projection math doesn't apply.
        if (immersiveProjection) return@LaunchedEffect
        // Wall-pinned: the panel pose is owned by the wall AnchorSpace, not the
        // origin-relative depth projection. Leave it alone.
        if (wallPinActive) return@LaunchedEffect
        val videoRoot = videoRootEntity.value ?: return@LaunchedEffect
        val uiRoot = uiRootEntity.value ?: return@LaunchedEffect
        val subtitleRoot = subtitleRootEntity.value ?: return@LaunchedEffect

        val headPos = computeHeadCenteredPose(session)?.translation ?: lastAnchorHeadPos.value ?: Vector3.Zero
        lastAnchorHeadPos.value = headPos
        val currentVideoPose = lastReportedMovePose.value ?: safeGetEntityPose(videoRoot) ?: savedPlayerPose
        val relX = currentVideoPose.translation.x - headPos.x
        val relY = currentVideoPose.translation.y - headPos.y
        val relZ = currentVideoPose.translation.z - headPos.z
        val length = kotlin.math.sqrt(relX * relX + relY * relY + relZ * relZ)
        val viewDirection = if (length > 1e-3f) {
            Vector3(relX / length, relY / length, relZ / length)
        } else {
            Vector3(0f, 0f, -1f)
        }
        val newPos = Vector3(
            headPos.x + viewDirection.x * videoDepth,
            headPos.y + viewDirection.y * videoDepth,
            headPos.z + viewDirection.z * videoDepth,
        )
        val newVideoPose = Pose(
            newPos,
            viewerFacingRotation(newPos, headPos, allowPitch = true),
        )
        val newUiPose = projectOverlayPoseTowardViewer(newVideoPose, headPos, UI_DEPTH_METERS)

        videoRoot.setPose(newVideoPose)
        videoRoot.setScale(videoPanelScale)
        videoEntity.value?.let { surface ->
            surface.setPose(newVideoPose)
            surface.setScale(videoPanelScale)
        }
        uiRoot.setPose(newUiPose)
        uiRoot.setScale(videoPanelScale)
        subtitleRoot.setPose(newUiPose)
        subtitleRoot.setScale(videoPanelScale)
        lastReportedMovePose.value = newVideoPose

        // Persist the new base position.
        savePlayerRootPose(viewModel, newVideoPose)
    }

    SpatialPlayerRecoveryEffect(
        session = session,
        lifecycleOwner = lifecycleOwner,
        enabled = !wallPinActive &&
            videoRootEntity.value != null &&
            uiRootEntity.value != null &&
            subtitleRootEntity.value != null,
        moveInProgress = moveInProgress,
        onCancelMove = {
            val rollbackPose = preMoveSnapshotPose ?: savedPlayerPose
            val rollbackHeadPos = preMoveSnapshotHeadPos ?: lastAnchorHeadPos.value
            lastAnchorHeadPos.value = rollbackHeadPos
            runCatching {
                videoRootEntity.value?.let { root ->
                    root.setPose(rollbackPose)
                    root.setScale(videoPanelScale)
                }
                videoEntity.value?.let { surface ->
                    surface.setPose(rollbackPose)
                    surface.setScale(videoPanelScale)
                }
                val uiRoot = uiRootEntity.value
                val subRoot = subtitleRootEntity.value
                if (uiRoot != null && subRoot != null) {
                    syncViewerProjectedOverlayRoots(
                        rollbackPose,
                        videoPanelScale,
                        rollbackHeadPos,
                        uiRoot,
                        subRoot,
                        UI_DEPTH_METERS,
                    )
                }
            }
            lastReportedMovePose.value = rollbackPose
            preMoveSnapshotPose = null
            preMoveSnapshotHeadPos = null
            moveInProgress = false
        },
        onStabilizationTick = { headPose ->
            if (immersiveProjection) {
                recenterImmersiveSurface()
            } else {
                val currentPose =
                    lastReportedMovePose.value ?: videoRootEntity.value?.let(::safeGetEntityPose)
                if (currentPose == null || isPanelOutsideComfortableFov(headPose, currentPose)) {
                    recenterScreenToViewer(manualPitch = false, resetScaleAndDepth = false)
                }
            }
        },
    )

    DisposableEffect(session) {
        val savedPose = savedPlayerPose
        val initialHeadPose = computeHeadCenteredPose(session)
        lastAnchorHeadPos.value = initialHeadPose?.translation
        // Immersive 180/360 centres the surface on the viewer and positions the
        // controls/subtitle panels at a fixed, reachable distance along the viewer's
        // horizontal yaw. Flat keeps the saved cinema pose unless it is outside the
        // viewer's comfortable field of view.
        val (videoCreatePose, overlayCreatePose) = if (immersiveProjection) {
            computeImmersiveViewerPoses(initialHeadPose, UI_DEPTH_METERS)
        } else {
            val flatPose = if (initialHeadPose != null && isPanelOutsideComfortableFov(initialHeadPose, savedPose)) {
                computeViewerCenteredPanelPose(initialHeadPose, videoDepth, manualPitch = false)
            } else {
                savedPose
            }
            flatPose to projectOverlayPoseTowardViewer(flatPose, initialHeadPose?.translation, UI_DEPTH_METERS)
        }
        val initialQuad = FloatSize2d(DEFAULT_VIDEO_WIDTH_METERS, DEFAULT_VIDEO_HEIGHT_METERS)
        val initialShape = runCatching {
            mapProjectionShape(
                projection = currentProjection,
                radiusMeters = VR_SPHERE_RADIUS_METERS,
                quad = initialQuad,
                curvedScreenEnabled = curvedScreenEnabled,
                wallPinActive = wallPinActive,
                curveRadiusMeters = screenCurvePreset.radiusMeters,
            )
        }.getOrElse { SurfaceEntity.Shape.Quad(initialQuad) }
        try {
            val activitySpace = session.scene.activitySpace
            val videoRoot = Entity.create(session, "PlayerVideoRoot", videoCreatePose, activitySpace)
            val uiRoot = Entity.create(session, "PlayerUiRoot", overlayCreatePose, activitySpace)
            val subtitleRoot = Entity.create(session, "PlayerSubtitleRoot", overlayCreatePose, activitySpace)
            if (!immersiveProjection) {
                videoRoot.setScale(videoPanelScale)
                uiRoot.setScale(videoPanelScale)
                subtitleRoot.setScale(videoPanelScale)
            }

            val sceneStereoMode = mapStereoMode(currentStereoMode) ?: SurfaceEntity.StereoMode.MONO
            Timber.i(
                "XR_VIDEO: creating SurfaceEntity stereo=%s requestedMode=%s shape=%.3fm x %.3fm rootPose=(%.3f, %.3f, %.3f)",
                sceneStereoMode,
                currentStereoMode,
                DEFAULT_VIDEO_WIDTH_METERS,
                DEFAULT_VIDEO_HEIGHT_METERS,
                videoCreatePose.translation.x,
                videoCreatePose.translation.y,
                videoCreatePose.translation.z,
            )
            val surfaceEntity = runCatching {
                SurfaceEntity.create(
                    session,
                    videoCreatePose,
                    initialShape,
                    sceneStereoMode,
                    SurfaceEntity.MediaBlendingMode.OPAQUE,
                    SurfaceEntity.SuperSampling.PENTAGON,
                    SurfaceEntity.SurfaceProtection.NONE,
                    activitySpace,
                )
            }.getOrElse {
                SurfaceEntity.create(
                    session,
                    videoCreatePose,
                    SurfaceEntity.Shape.Quad(initialQuad),
                    sceneStereoMode,
                    SurfaceEntity.MediaBlendingMode.OPAQUE,
                    SurfaceEntity.SuperSampling.PENTAGON,
                    SurfaceEntity.SurfaceProtection.NONE,
                    activitySpace,
                )
            }.apply {
                if (!immersiveProjection) setScale(videoPanelScale)
                // Soften the VR180 hemisphere seam so it blends into the blackout
                // environment. A full 360° sphere has no visible edge, so leave it.
                if (currentProjection == ProjectionModeDetector.PROJECTION_180) {
                    runCatching {
                        edgeFeatheringParams =
                            SurfaceEntity.EdgeFeatheringParams.RectangleFeather(0.1f, 0.1f)
                    }
                }
            }
            Timber.i(
                "XR_VIDEO: SurfaceEntity.create succeeded stereo=%s surfaceValid=%b entityDisposed=%b directPose=(%.3f, %.3f, %.3f)",
                surfaceEntity.stereoMode,
                runCatching { surfaceEntity.getSurface().isValid }.getOrDefault(false),
                surfaceEntity.isDisposed,
                videoCreatePose.translation.x,
                videoCreatePose.translation.y,
                videoCreatePose.translation.z,
            )
            lastReportedMovePose.value = videoCreatePose

            // Bind the native move affordance to the visible SurfaceEntity so its system glow
            // follows the video frame, while the surface remains a direct activitySpace child.
            // createCustomMovable gives us full control over the entity pose during a grab.
            // createSystemMovable snaps the entity to the user's hand position (arm's reach
            // ~2m) even when the video is 6m away, causing the jarring "jump to nose" bug.
            // With a custom movable we preserve depth and orient the panel toward the viewer.
            var grabDepth = videoDepth // depth locked at the moment the user starts a grab
            val movable = androidx.xr.scenecore.MovableComponent.createCustomMovable(
                session,
                false, // Never let a move gesture change apparent size.
                ContextCompat.getMainExecutor(context),
                object : androidx.xr.scenecore.EntityMoveListener {
                    override fun onMoveStart(
                        entity: androidx.xr.scenecore.Entity,
                        initialInputRay: androidx.xr.runtime.math.Ray,
                        initialPose: androidx.xr.runtime.math.Pose,
                        initialScale: Float,
                        initialParent: androidx.xr.scenecore.Entity,
                    ) {
                        preMoveSnapshotPose = lastReportedMovePose.value ?: safeGetEntityPose(videoRoot) ?: savedPose
                        preMoveSnapshotHeadPos = lastAnchorHeadPos.value
                        val headPos = computeHeadCenteredPose(session)?.translation ?: lastAnchorHeadPos.value
                        lastAnchorHeadPos.value = headPos
                        grabDepth = extractVideoDepth(initialPose, videoDepth, headPos)
                        val startPose = Pose(
                            initialPose.translation,
                            viewerFacingRotation(initialPose.translation, headPos ?: Vector3.Zero, allowPitch = true),
                        )
                        runCatching {
                            entity.setPose(startPose)
                            entity.setScale(videoPanelScale)
                            videoRoot.setPose(startPose)
                            videoRoot.setScale(videoPanelScale)
                        }
                        androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
                            lastReportedMovePose.value = startPose
                            // Do not update videoDepth yet to avoid triggering LaunchedEffect fights
                            moveInProgress = true
                        }
                        if (!controlsVisible && !isActuallyPaused) {
                            revealControls("video-move-start")
                        }
                        syncViewerProjectedOverlayRoots(
                            startPose,
                            videoPanelScale,
                            headPos,
                            uiRoot,
                            subtitleRoot,
                            UI_DEPTH_METERS,
                        )
                        val t = startPose.translation
                        Timber.i(
                            "subtitle: move start targetIsVideoSurface=%b targetClass=%s grabDepth=%.3f actualPos=(%.3f, %.3f, %.3f)",
                            entity == surfaceEntity,
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
                        if (!moveInProgress) return
                        val headPos = computeHeadCenteredPose(session)?.translation ?: lastAnchorHeadPos.value
                        lastAnchorHeadPos.value = headPos
                        val depthPreservedPose = computeDragPose(
                            rayOrigin = currentInputRay.origin,
                            rayDirection = currentInputRay.direction,
                            headPos = headPos,
                            grabDepthMeters = grabDepth,
                        )
                        runCatching {
                            entity.setPose(depthPreservedPose)
                            entity.setScale(videoPanelScale)
                            videoRoot.setPose(depthPreservedPose)
                            videoRoot.setScale(videoPanelScale)
                        }
                        androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
                            lastReportedMovePose.value = depthPreservedPose
                        }
                        syncViewerProjectedOverlayRoots(
                            depthPreservedPose,
                            videoPanelScale,
                            headPos,
                            uiRoot,
                            subtitleRoot,
                            UI_DEPTH_METERS,
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
                        if (!moveInProgress) {
                            preMoveSnapshotPose = null
                            preMoveSnapshotHeadPos = null
                            return
                        }
                        val headPos = computeHeadCenteredPose(session)?.translation ?: lastAnchorHeadPos.value
                        lastAnchorHeadPos.value = headPos
                        val finalTranslation = (lastReportedMovePose.value ?: finalPose).translation
                        val endPose = Pose(
                            finalTranslation,
                            viewerFacingRotation(finalTranslation, headPos ?: Vector3.Zero, allowPitch = true),
                        )
                        val effectiveDepth = extractVideoDepth(endPose, videoDepth, headPos)
                        runCatching {
                            entity.setPose(endPose)
                            entity.setScale(videoPanelScale)
                            videoRoot.setPose(endPose)
                            videoRoot.setScale(videoPanelScale)
                        }
                        androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
                            lastReportedMovePose.value = endPose
                            videoDepth = effectiveDepth
                            moveInProgress = false
                        }
                        preMoveSnapshotPose = null
                        preMoveSnapshotHeadPos = null
                        savePlayerRootDepth(viewModel, effectiveDepth)
                        syncViewerProjectedOverlayRoots(
                            endPose,
                            videoPanelScale,
                            headPos,
                            uiRoot,
                            subtitleRoot,
                            UI_DEPTH_METERS,
                        )
                        libass.bumpOverlayAttachment()
                        savePlayerRootPose(viewModel, endPose)
                        val t = endPose.translation
                        Timber.i(
                            "subtitle: move end effectiveDepth=%.3f actualPos=(%.3f, %.3f, %.3f)",
                            effectiveDepth,
                            t.x, t.y, t.z
                        )
                    }
                },
            )
            // Grab/move only makes sense for a flat panel — you can't drag a sphere
            // you're standing inside, and ResizableComponent isn't supported on the
            // hemisphere/sphere shapes. Immersive modes re-anchor via the Recenter
            // action instead, so we skip attaching the movable entirely.
            if (!immersiveProjection) {
                surfaceEntity.addComponent(movable)
                movable.size = videoMoveAffordanceBounds(DEFAULT_VIDEO_WIDTH_METERS, DEFAULT_VIDEO_HEIGHT_METERS)

                // SceneCore can briefly re-home a movable entity until the first interaction.
                // Re-apply the intended launch layout after the movable component attaches so the
                // screen starts in the cinema baseline instead of snapping there on first tap.
                runCatching {
                    videoRoot.setPose(videoCreatePose)
                    videoRoot.setScale(videoPanelScale)
                    surfaceEntity.setPose(videoCreatePose)
                    surfaceEntity.setScale(videoPanelScale)
                }
                syncViewerProjectedOverlayRoots(
                    videoCreatePose,
                    videoPanelScale,
                    lastAnchorHeadPos.value,
                    uiRoot,
                    subtitleRoot,
                    UI_DEPTH_METERS,
                )
                lastReportedMovePose.value = videoCreatePose
            } else {
                // Immersive: lock the surface centred on the head and the overlays
                // a fixed distance straight ahead.
                runCatching {
                    videoRoot.setPose(videoCreatePose)
                    surfaceEntity.setPose(videoCreatePose)
                    uiRoot.setPose(overlayCreatePose)
                    subtitleRoot.setPose(overlayCreatePose)
                }
                lastReportedMovePose.value = videoCreatePose
            }

            videoRootEntity.value = videoRoot
            uiRootEntity.value = uiRoot
            subtitleRootEntity.value = subtitleRoot
            videoEntity.value = surfaceEntity
            movableComponent.value = if (immersiveProjection) null else movable

        } catch (e: Exception) {
            Timber.e(e, "XR_VIDEO: failed to create SurfaceEntity path")
        }

        onDispose {
            Timber.i(
                "XR_VIDEO: disposing spatial player surface surfaceBound=%b firstFrame=%b entityPresent=%b playbackState=%s",
                videoSurfaceBound,
                firstVideoFrameRendered,
                videoEntity.value != null,
                playerStateName(player.playbackState),
            )
            if (!immersiveProjection && !wallPinActive) {
                videoRootEntity.value?.let { root ->
                    val finalPose = lastReportedMovePose.value ?: safeGetEntityPose(root)
                    finalPose?.let { savePlayerRootPose(viewModel, it) }
                }
            }
            savePlayerRootScale(viewModel, videoPanelScale)
            // Release voice/AI services so their background work (OkHttp dispatcher,
            // LiteRT/AICore handles, coroutine scopes) doesn't leak past the screen.
            voiceServices.destroy()
            dev.jdtech.jellyfin.player.core.video.SurfaceFrameRateController.clearFrameRate(
                runCatching { videoEntity.value?.getSurface() }.getOrNull(),
                (context as? Activity)?.window,
            )
            runCatching { player.clearVideoSurface() }
                .onSuccess {
                    videoSurfaceBound = false
                    Timber.i("XR_VIDEO: ExoPlayer video surface cleared")
                }
                .onFailure { Timber.w(it, "XR_VIDEO: failed to clear ExoPlayer video surface") }
            runCatching { videoEntity.value?.parent = null }
                .onSuccess { Timber.i("XR_VIDEO: SurfaceEntity detached") }
                .onFailure { Timber.w(it, "XR_VIDEO: failed to detach SurfaceEntity") }
            videoEntity.value = null
            runCatching { videoRootEntity.value?.parent = null }
            videoRootEntity.value = null
            runCatching { uiRootEntity.value?.parent = null }
            uiRootEntity.value = null
            runCatching { subtitleRootEntity.value?.parent = null }
            subtitleRootEntity.value = null
            runCatching { wallAnchorSpace.value?.parent = null }
            wallAnchorSpace.value = null
            movableComponent.value = null
            try { session.scene.spatialEnvironment.preferredSpatialEnvironment = null } catch (_: Exception) {}
            Timber.i("XR_VIDEO: spatial player surface disposal complete")
        }
    }

    LaunchedEffect(session, videoRootEntity.value) {
        if (videoRootEntity.value == null) return@LaunchedEffect
        pausedMascotAvailable =
            session.scene.spatialCapabilities.contains(SpatialCapability.SPATIAL_3D_CONTENT)
        if (!pausedMascotAvailable) {
            Timber.i("Skipping paused mascot: SPATIAL_3D_CONTENT not available")
        }
    }

    LaunchedEffect(videoRootEntity.value, uiRootEntity.value, subtitleRootEntity.value, videoEntity.value, pausedMascotAvailable, pausedMascotVisible) {
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
            if (pausedMascotAvailable) {
                val p = PausedMascotPose.translation
                entities.add(EntityInfo("Mascot", "SpatialGltfModel", p.x, p.y, p.z, 1.35f, pausedMascotVisible))
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
            // While pinned or immersive, the wall AnchorSpace or head-centered sphere drives poses.
            if (wallPinActive || immersiveProjection) { delay(200L); continue }
            val poseToMirror = lastReportedMovePose.value ?: safeGetEntityPose(videoRoot)
            poseToMirror?.let { pose ->
                videoEntity.value?.let { surface ->
                    runCatching {
                        surface.setPose(pose)
                        surface.setScale(videoPanelScale)
                    }
                }
                syncViewerProjectedOverlayRoots(
                    pose,
                    videoPanelScale,
                    lastAnchorHeadPos.value,
                    uiRoot,
                    subtitleRoot,
                    UI_DEPTH_METERS,
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
            // Don't persist the wall-local or immersive sphere pose over the saved free-move cinema pose.
            if (wallPinActive || immersiveProjection) { delay(250L); continue }
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

    val playerMediaKey = when {
        mediaUri != null -> "uri:$mediaUri:start=$startFromBeginning"
        localMediaId != null -> "local:$localMediaId:start=$startFromBeginning"
        networkVideoId != null -> "network:$networkVideoId:start=$startFromBeginning"
        itemId != null ->
            "jellyfin:$itemId:kind=$itemKind:start=$startFromBeginning:source=${mediaSourceIndex ?: -1}" +
                ":bitrate=${maxBitrate ?: 0L}:audio=${audioStreamIndex ?: -1}" +
                ":sub=${subtitleStreamIndex ?: -1}:subOff=$subtitlesDisabled"
        universalVideoUrl != null && universalItemId != null && universalPluginId != null ->
            "universal:$universalPluginId:$universalItemId:$universalVideoUrl"
        else -> null
    }
    var initializedPlayerMediaKey by remember(player) { mutableStateOf<String?>(null) }
    LaunchedEffect(player, videoEntity.value, playerMediaKey) {
        val entity = videoEntity.value ?: run {
            videoSurfaceBound = false
            return@LaunchedEffect
        }
        val surface = entity.getSurface()
        Timber.i(
            "XR_VIDEO: binding ExoPlayer video surface surfaceValid=%b stereo=%s playerState=%s videoSize=%dx%d",
            surface.isValid,
            currentStereoMode,
            playerStateName(player.playbackState),
            reportedVideoSize.width,
            reportedVideoSize.height,
        )
        player.setVideoSurface(surface)
        videoSurfaceBound = true
        Timber.i("XR_VIDEO: setVideoSurface complete")
        if (playerMediaKey == null) {
            Timber.w("XR_VIDEO: no media id available after SurfaceEntity creation")
            return@LaunchedEffect
        }
        if (initializedPlayerMediaKey != playerMediaKey) {
            firstVideoFrameRendered = false
            Timber.i("XR_VIDEO: initializing player media key=%s", playerMediaKey)
            when {
                mediaUri != null -> {
                    viewModel.initializeUriPlayer(
                        uri = mediaUri,
                        title = mediaTitle,
                        startFromBeginning = startFromBeginning,
                    )
                }
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
                        audioStreamIndex = audioStreamIndex,
                        subtitleStreamIndex = subtitleStreamIndex,
                        subtitlesDisabled = subtitlesDisabled,
                    )
                }
                universalVideoUrl != null && universalItemId != null && universalPluginId != null -> {
                    viewModel.initializePlayerForUniversal(
                        pluginId = universalPluginId,
                        itemId = universalItemId,
                        videoUrl = universalVideoUrl,
                        title = universalTitle ?: "External Video",
                    )
                }
            }
            initializedPlayerMediaKey = playerMediaKey
        }
    }
    // Update SurfaceEntity shape when video dimensions, stereo mode, projection,
    // curved-screen toggle, or wall-pin state changes. All are consolidated here
    // to avoid races between separate effects.
    LaunchedEffect(
        reportedVideoSize,
        currentStereoMode,
        currentProjection,
        screenCurvePreset,
        wallPinActive,
        videoEntity.value,
    ) {
        val entityForProjection = videoEntity.value
        // Immersive 180/360 — apply the sphere/hemisphere shape (and the live
        // stereo layout) head-centred; skip all the flat quad aspect-ratio math.
        if (immersiveProjection) {
            if (entityForProjection != null) {
                // If the surface was created flat (e.g. container metadata upgraded
                // it to spherical at onTracksChanged), detach the now-meaningless
                // move affordance so it doesn't linger on the sphere.
                movableComponent.value?.let { mc ->
                    runCatching { entityForProjection.removeComponent(mc) }
                    movableComponent.value = null
                }
                val headPose = computeHeadCenteredPose(session)
                    ?: lastReportedMovePose.value
                    ?: Pose(Vector3(0f, 0f, 0f), Quaternion.Identity)
                val (spherePose, overlayPose) = computeImmersiveViewerPoses(headPose, UI_DEPTH_METERS)
                runCatching {
                    entityForProjection.shape = mapProjectionShape(
                        currentProjection,
                        VR_SPHERE_RADIUS_METERS,
                        FloatSize2d(DEFAULT_VIDEO_WIDTH_METERS, DEFAULT_VIDEO_HEIGHT_METERS),
                    )
                    entityForProjection.stereoMode =
                        mapStereoMode(currentStereoMode) ?: SurfaceEntity.StereoMode.MONO
                    entityForProjection.setScale(1.0f)
                    entityForProjection.setPose(spherePose)
                    videoRootEntity.value?.let {
                        it.setScale(1.0f)
                        it.setPose(spherePose)
                    }
                    uiRootEntity.value?.let {
                        it.setScale(1.0f)
                        it.setPose(overlayPose)
                    }
                    subtitleRootEntity.value?.let {
                        it.setScale(1.0f)
                        it.setPose(overlayPose)
                    }
                    entityForProjection.edgeFeatheringParams =
                        if (currentProjection == ProjectionModeDetector.PROJECTION_180) {
                            SurfaceEntity.EdgeFeatheringParams.RectangleFeather(0.1f, 0.1f)
                        } else {
                            SurfaceEntity.EdgeFeatheringParams.NoFeathering()
                        }
                }
                lastReportedMovePose.value = spherePose
            }
            return@LaunchedEffect
        }
        // 1. Recalculate video dimensions from the player if available.
        val videoSize = reportedVideoSize

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
        val sceneStereoMode = mapStereoMode(currentStereoMode) ?: SurfaceEntity.StereoMode.MONO
        Timber.i(
            "XR_VIDEO: video geometry source=%dx%d ratio=%.4f requestedStereo=%s sceneStereo=%s world=%.3fm x %.3fm entityPresent=%b",
            videoSize.width,
            videoSize.height,
            if (videoHeight > 0f) videoWidth / videoHeight else 0f,
            currentStereoMode,
            sceneStereoMode,
            videoWidth,
            videoHeight,
            videoEntity.value != null,
        )

        // 2. Apply the correct shape and pose to the entity.
        val entity = videoEntity.value ?: return@LaunchedEffect
        // Flat or curved cinema panel with entity-level stereo mode.
        applySurfaceShapeWithFallback(
            entity = entity,
            projection = currentProjection,
            radiusMeters = VR_SPHERE_RADIUS_METERS,
            quad = FloatSize2d(videoWidth, videoHeight),
            curvedScreenEnabled = curvedScreenEnabled,
            wallPinActive = wallPinActive,
            curveRadiusMeters = screenCurvePreset.radiusMeters,
        )
        entity.stereoMode = sceneStereoMode
        entity.setPose(lastReportedMovePose.value ?: savedPlayerPose)
        entity.setScale(videoPanelScale)
        movableComponent.value?.size = videoMoveAffordanceBounds(videoWidth, videoHeight)
    }

    LaunchedEffect(
        videoEntity.value,
        movableComponent.value,
        isLocked,
        controlsInputActive,
        controlsVisible,
        isActuallyPaused,
        moveInProgress,
        wallPinActive,
    ) {
        val videoSurface = videoEntity.value ?: return@LaunchedEffect
        val movable = movableComponent.value ?: return@LaunchedEffect
        val hasMovable =
            runCatching {
                videoSurface
                    .getComponentsOfType(androidx.xr.scenecore.MovableComponent::class.java)
                    .any { it === movable }
            }.getOrElse {
                Timber.d(it, "Unable to inspect XR screen movable component state")
                false
            }
        // A control press must not be interpreted as a grab on the video surface
        // underneath. The floating orbiters overlap the video's move-affordance
        // region, so while *any* chrome is on screen we detach the movable entirely
        // — otherwise a tap on the (top) orbiter grabs the screen instead of the
        // button. An in-progress grab keeps the movable attached so an active move
        // isn't cancelled when it reveals the controls. With chrome hidden the
        // movable returns, so the user grabs the picture to reposition it.
        val chromeShown = controlsVisible || isActuallyPaused
        // Free-move is suspended entirely while the panel is wall-pinned.
        val allowMovement = !isLocked && !controlsInputActive && (!chromeShown || moveInProgress) && !wallPinActive

        runCatching {
            if (!allowMovement && hasMovable) {
                videoSurface.removeComponent(movable)
                if (moveInProgress) {
                    val rollbackPose = preMoveSnapshotPose ?: savedPlayerPose
                    val rollbackHeadPos = preMoveSnapshotHeadPos ?: lastAnchorHeadPos.value
                    lastAnchorHeadPos.value = rollbackHeadPos
                    videoRootEntity.value?.let { root ->
                        root.setPose(rollbackPose)
                        root.setScale(videoPanelScale)
                    }
                    videoSurface.setPose(rollbackPose)
                    videoSurface.setScale(videoPanelScale)
                    uiRootEntity.value?.let { uiRoot ->
                        subtitleRootEntity.value?.let { subRoot ->
                            syncViewerProjectedOverlayRoots(
                                rollbackPose,
                                videoPanelScale,
                                rollbackHeadPos,
                                uiRoot,
                                subRoot,
                                UI_DEPTH_METERS,
                            )
                        }
                    }
                    lastReportedMovePose.value = rollbackPose
                    preMoveSnapshotPose = null
                    preMoveSnapshotHeadPos = null
                }
                moveInProgress = false
                Timber.i("XR screen movement suspended during control input")
            } else if (allowMovement && !hasMovable) {
                videoSurface.addComponent(movable)
                movable.size = videoMoveAffordanceBounds(videoWidth, videoHeight)
                Timber.i("XR screen movement enabled")
            }
        }.onFailure {
            Timber.w(it, "Unable to update XR screen movement lock state")
        }
    }

    // Pin-to-wall driver: world-locks the flat panel onto a real wall via an ARCore
    // AnchorSpace, or tears the pin down. Flat projection only (immersive surfaces are
    // head-centred). Reparenting the surface/overlay roots under the wall anchor makes
    // them coplanar with the wall and inherit its orientation, so a side wall ends up
    // facing perpendicular into the room with no manual rotation. The conflicting
    // depth-projection / move / save effects above are gated on wallPinActive.
    LaunchedEffect(
        wallPinRequested,
        session,
        videoEntity.value,
        immersiveProjection,
        hasSceneUnderstandingPermission,
    ) {
        val surface = videoEntity.value
        val videoRoot = videoRootEntity.value
        val uiRoot = uiRootEntity.value
        val subtitleRoot = subtitleRootEntity.value

        // Move ONLY the video surface + subtitles onto the wall. The controls overlay
        // (uiRoot) is deliberately LEFT where it is (head-relative, in front of the
        // viewer) so the player chrome — including the unpin/back affordances — always
        // stays reachable even if the wall is off to the side. Orientation is computed
        // to face the viewer and stay upright; the anchor is used only for position, so
        // its arbitrary/tilted frame can't roll or skew the picture.
        fun reparentToWall(anchor: androidx.xr.scenecore.AnchorSpace) {
            val scale = WallAnchor.panelScale(DEFAULT_VIDEO_WIDTH_METERS)
            val anchorWorld = runCatching {
                anchor.getPose(androidx.xr.scenecore.Space.ACTIVITY)
            }.getOrNull()
            val anchorPos = anchorWorld?.translation ?: Vector3(0f, 0f, -VIDEO_DEPTH_METERS)
            val headPos = computeHeadCenteredPose(session)?.translation
            val forward = WallAnchor.facingTowardViewer(anchorPos, headPos)
            val rot = WallAnchor.lookRotation(forward)
            val panelPos = Vector3(
                anchorPos.x + forward.x * WallAnchor.PANEL_STANDOFF_METERS,
                anchorPos.y,
                anchorPos.z + forward.z * WallAnchor.PANEL_STANDOFF_METERS,
            )
            val subtitlePos = Vector3(
                anchorPos.x + forward.x * WallAnchor.OVERLAY_FORWARD_METERS,
                anchorPos.y,
                anchorPos.z + forward.z * WallAnchor.OVERLAY_FORWARD_METERS,
            )
            val panelPose = Pose(panelPos, rot)
            val subtitlePose = Pose(subtitlePos, rot)
            runCatching {
                surface?.apply {
                    parent = anchor
                    setPose(panelPose, androidx.xr.scenecore.Space.ACTIVITY)
                    setScale(scale)
                }
                videoRoot?.apply {
                    parent = anchor
                    setPose(panelPose, androidx.xr.scenecore.Space.ACTIVITY)
                    setScale(scale)
                }
                subtitleRoot?.apply {
                    parent = anchor
                    setPose(subtitlePose, androidx.xr.scenecore.Space.ACTIVITY)
                    setScale(scale)
                }
            }.onFailure { Timber.w(it, "WALL_ANCHOR: reparent onto wall failed") }
            libass.bumpOverlayAttachment()
        }

        fun detachFromWall() {
            val activitySpace = session.scene.activitySpace
            runCatching {
                surface?.parent = activitySpace
                videoRoot?.parent = activitySpace
                subtitleRoot?.parent = activitySpace
            }.onFailure { Timber.w(it, "WALL_ANCHOR: detach from wall failed") }
            runCatching { wallAnchorSpace.value?.parent = null }
            wallAnchorSpace.value = null
            wallPinActive = false
            // Hand the panel back to the free-move cinema layout. The movement-gate
            // effect re-attaches the MovableComponent now that wallPinActive is false.
            recenterScreenToViewer(manualPitch = false, resetScaleAndDepth = false)
            libass.bumpOverlayAttachment()
        }

        // Cold-start persisted wall auto-restore (runs at most once per session before
        // any manual pin request, without prompting for permissions).
        if (!wallPinRequested && !wallAutoRestoreAttempted && !immersiveProjection && hasSceneUnderstandingPermission && surface != null) {
            wallAutoRestoreAttempted = true
            val savedPinned = viewModel.appPreferences.getValue(viewModel.appPreferences.xrPlayerWallPinned)
            val savedUuid = viewModel.appPreferences.getValue(viewModel.appPreferences.xrPlayerWallAnchorUuid)
            if (savedPinned && savedUuid.isNotBlank() && WallAnchor.enableSceneUnderstanding(session)) {
                val restored = WallAnchor.loadAndAwaitPersisted(
                    session = session,
                    uuid = savedUuid,
                    timeoutMs = WallAnchor.AUTO_RESTORE_WAIT_MS,
                )
                if (restored != null) {
                    val anchorPose = runCatching {
                        restored.getPose(androidx.xr.scenecore.Space.ACTIVITY)
                    }.getOrNull()
                    val headPose = computeHeadCenteredPose(session)
                    if (WallAnchor.shouldAutoRestorePersistedAnchor(headPose, anchorPose)) {
                        WallAnchor.logAnchorPose(restored)
                        movableComponent.value?.let { mc -> runCatching { surface.removeComponent(mc) } }
                        wallAnchorSpace.value = restored
                        reparentToWall(restored)
                        wallPinActive = true
                        wallPinRequested = true
                        wallPinStatus = "Pinned to wall"
                        Timber.i("WALL_ANCHOR: auto-restored uuid=%s", savedUuid)
                        return@LaunchedEffect
                    } else {
                        Timber.i("WALL_ANCHOR: skipped auto-restore outside FOV uuid=%s", savedUuid)
                        runCatching { restored.parent = null }
                    }
                }
            }
        }

        // Not pinned / not eligible → ensure any prior pin is undone.
        if (!wallPinRequested || immersiveProjection || surface == null) {
            if (wallPinActive || wallAnchorSpace.value != null) detachFromWall()
            return@LaunchedEffect
        }
        if (wallPinActive) return@LaunchedEffect

        if (!hasSceneUnderstandingPermission) {
            wallPinStatus = "Requesting wall access…"
            sceneUnderstandingPermissionLauncher.launch(WallAnchor.SCENE_UNDERSTANDING_PERMISSION)
            return@LaunchedEffect
        }

        wallPinStatus = "Look at the wall…"
        if (!WallAnchor.enableSceneUnderstanding(session)) {
            wallPinStatus = "Wall tracking unavailable"
            wallPinRequested = false
            return@LaunchedEffect
        }

        // Pick the wall the user is actually facing (not whatever the runtime returns).
        val anchor = WallAnchor.findWallInFront(session, WallAnchor.ANCHOR_WAIT_MS)
        if (anchor == null) {
            wallPinStatus = "No wall in front — face a wall and retry"
            wallPinRequested = false
            return@LaunchedEffect
        }
        wallAnchorSpace.value = anchor

        when (WallAnchor.awaitAnchored(anchor, 5_000L)) {
            androidx.xr.scenecore.AnchorSpace.State.ANCHORED -> {
                WallAnchor.logAnchorPose(anchor)
                // Detach the move affordance so a grab can't fight the wall pose.
                movableComponent.value?.let { mc -> runCatching { surface.removeComponent(mc) } }
                reparentToWall(anchor)
                wallPinActive = true
                wallPinStatus = "Pinned to wall"
                WallAnchor.persist(anchor)?.let { persistedUuid ->
                    viewModel.appPreferences.setValue(
                        viewModel.appPreferences.xrPlayerWallAnchorUuid,
                        persistedUuid,
                    )
                    viewModel.appPreferences.setValue(
                        viewModel.appPreferences.xrPlayerWallPinned,
                        true,
                    )
                    Timber.i("WALL_ANCHOR: persisted uuid=%s", persistedUuid)
                }
            }
            else -> {
                wallPinStatus = "Couldn't lock the wall — try again"
                wallPinRequested = false
                runCatching { anchor.parent = null }
                wallAnchorSpace.value = null
            }
        }
    }

    LaunchedEffect(
        session,
        videoEntity.value,
        uiRootEntity.value,
        immersiveProjection,
        splitAvVideoRole,
        spatialAudioAvailable,
        wallPinActive,
    ) {
        spatialAudioController?.updateTarget(
            session = session,
            targetEntity = videoEntity.value,
            horizonEntity = uiRootEntity.value,
            immersiveProjection = immersiveProjection,
            splitAvMuted = splitAvVideoRole,
            spatialAudioCapable = spatialAudioAvailable,
        )
    }

    LaunchedEffect(videoEntity.value, activeVideoColorInfo) {
        XrSurfaceColorMetadata.applyToSurfaceEntity(videoEntity.value, activeVideoColorInfo)
    }

    // Frame rate matching: apply content frame rate to the video surface and UI compositing layer.
    val currentFrameRate by viewModel.currentFrameRate.collectAsState()
    LaunchedEffect(currentFrameRate, videoEntity.value) {
        val surface = runCatching { videoEntity.value?.getSurface() }.getOrNull()
        dev.jdtech.jellyfin.player.core.video.SurfaceFrameRateController.applyFrameRate(
            surface = surface,
            window = (context as? Activity)?.window,
            frameRate = currentFrameRate,
            allowNonSeamlessSwitch = true,
        )
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

    LaunchedEffect(subtitlePanelWidthDp, subtitlePanelHeightDp, density.density, reportedVideoSize) {
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
        val videoW = reportedVideoSize.width
        val videoH = reportedVideoSize.height
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
    // appearance), but a disabled Entity does not participate in SceneCore raycast
    // hit-testing.  When disabled, grab gestures pass straight through to the video entity's
    // MovableComponent so the user can move the video even when subtitles are present.
    val hasSubtitleContent = uiState.visualSubtitlesEnabled && libass.hasSubtitleContent
    LaunchedEffect(hasSubtitleContent, subtitleRootEntity.value) {
        subtitleRootEntity.value?.setEnabled(hasSubtitleContent)
    }

    Subspace {
        if (pausedMascotVisible) {
            @OptIn(ExperimentalSpatialGltfModelApi::class)
            val mascotState = rememberSpatialGltfModelState(
                source = SpatialGltfModelSource.fromPath(Paths.get("models", "spatialfin.glb")),
            )
            @OptIn(ExperimentalSpatialGltfModelApi::class)
            SpatialGltfModel(
                state = mascotState,
                modifier = SubspaceModifier
                    .offset(
                        x = (PausedMascotPose.translation.x * 1000f).dp,
                        y = (PausedMascotPose.translation.y * 1000f).dp,
                        z = (PausedMascotPose.translation.z * 1000f).dp,
                    )
                    .rotate(PausedMascotPose.rotation)
                    .scale(1.35f),
            )
        }

        val subtitleRoot = subtitleRootEntity.value
        if (subtitleRoot != null) {
            SceneCoreEntity(factory = { subtitleRoot }, modifier = SubspaceModifier) {
                // Keep the subtitle panel entity alive even when there is no subtitle content.
                // Recreating the SpatialPanel when a new line appears causes SceneCore to
                // briefly render it at the subtitle root origin before the panel offset lands,
                // which shows up as a flash below the screen.
                if (libass.useLibass) {
                    SpatialPanel(
                        modifier = SubspaceModifier
                            .width(subtitlePanelWidthDp.dp)
                            .height(subtitlePanelHeightDp.dp)
                            .offset(x = 0.dp, y = 0.dp, z = (subtitlePanelZDp + 50f).dp),
                    ) {
                        // Read libass.bitmap / libass.hasContent / libass.frameVersion
                        // INSIDE the panel's content lambda, not at the SceneCoreEntity
                        // scope above. The libass render loop bumps these on every
                        // frame; reading at the outer scope used to recompose the
                        // SpatialPanel's siblings (the Media3 fallback branch) too.
                        val currentBitmap = libass.bitmap
                        val showLibassContent = libass.hasContent && currentBitmap != null
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
                // ── Pause Overlays (logo top-left, wall-clock top-right) ─────────────────────
                // Show / movie logo + wall-clock tuck into the corners of the video while paused.
                // Matches the Jellyfin Android TV top-panel affordance — a visual breadcrumb of
                // what's playing plus a quick glanceable clock + ETA when the viewer steps away.
                //
                // The `scaledVideoWidthDp` / `scaledVideoHeightDp` values are computed in the
                // uiRoot's *unscaled* dp space. The video surface is scaled visually by
                // `videoPanelScale` via `videoRoot.setScale`, but the Subspace layout does not
                // apply the parent entity's scale to child SpatialPanels — their dp offsets and
                // sizes remain in unscaled units. To have our overlay panels line up with the
                // video's real visual bounds, we pre-multiply by `videoPanelScale` here.
                val visualVideoWidthDp = scaledVideoWidthDp * videoPanelScale
                val visualVideoHeightDp = scaledVideoHeightDp * videoPanelScale
                val cornerInsetDp = 12f

                val logoUri = uiState.currentItemLogoUri
                if (logoUri != null) {
                    val logoPanelWidthDp = (visualVideoWidthDp * 0.26f).coerceAtLeast(240f)
                    val logoPanelHeightDp = (visualVideoHeightDp * 0.12f).coerceAtLeast(120f)
                    val logoPanelXDp = -(visualVideoWidthDp / 2f - logoPanelWidthDp / 2f - cornerInsetDp)
                    val logoPanelYDp = visualVideoHeightDp / 2f - logoPanelHeightDp / 2f - cornerInsetDp
                    SpatialPanel(
                        modifier = SubspaceModifier
                            .width(logoPanelWidthDp.dp)
                            .height(logoPanelHeightDp.dp)
                            .offset(x = logoPanelXDp.dp, y = logoPanelYDp.dp, z = 80.dp),
                    ) {
                        AnimatedVisibility(
                            visible = isActuallyPaused,
                            enter = fadeIn(),
                            exit = fadeOut(),
                        ) {
                            AsyncImage(
                                model = logoUri,
                                contentDescription = uiState.currentItemTitle,
                                contentScale = ContentScale.Fit,
                                alignment = Alignment.TopStart,
                                modifier = Modifier.fillMaxSize().padding(12.dp),
                            )
                        }
                    }
                }

                val clockPanelWidthDp = (visualVideoWidthDp * 0.20f).coerceAtLeast(320f)
                val clockPanelHeightDp = (visualVideoHeightDp * 0.10f).coerceAtLeast(140f)
                val clockPanelXDp = visualVideoWidthDp / 2f - clockPanelWidthDp / 2f - cornerInsetDp
                val clockPanelYDp = visualVideoHeightDp / 2f - clockPanelHeightDp / 2f - cornerInsetDp
                SpatialPanel(
                    modifier = SubspaceModifier
                        .width(clockPanelWidthDp.dp)
                        .height(clockPanelHeightDp.dp)
                        .offset(x = clockPanelXDp.dp, y = clockPanelYDp.dp, z = 80.dp),
                ) {
                    AnimatedVisibility(
                        visible = isActuallyPaused,
                        enter = fadeIn(),
                        exit = fadeOut(),
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize().padding(12.dp),
                            contentAlignment = Alignment.TopEnd,
                        ) {
                            dev.jdtech.jellyfin.core.presentation.components.PauseClockContent(
                                visible = isActuallyPaused,
                                positionMs = currentPosition,
                                durationMs = duration,
                                clockFontSize = 64.sp,
                                etaFontSize = 36.sp,
                            )
                        }
                    }
                }

                val statsSnapshot = rememberPlaybackStatsSnapshot(
                    enabled = statsForNerdsEnabled,
                    player = player,
                    viewModel = viewModel,
                    audioModeSummaryOverride = if (spatialAudioAvailable) {
                        "XR 3D Spatial Audio (Point Source)"
                    } else if (splitAvVideoRole) {
                        "Split-A/V Companion Receiver"
                    } else {
                        null
                    },
                    subtitleEngineSummaryOverride = when {
                        !uiState.visualSubtitlesEnabled -> "Off"
                        libass.useLibass -> "libass JNI (60fps)"
                        else -> "Media3 Cue Overlay"
                    },
                )
                if (statsSnapshot != null) {
                    val statsPanelWidthDp = 460f
                    val statsPanelHeightDp = 340f
                    val statsPanelXDp = -(visualVideoWidthDp / 2f - statsPanelWidthDp / 2f - cornerInsetDp)
                    val statsPanelYDp = visualVideoHeightDp / 2f - statsPanelHeightDp / 2f - cornerInsetDp
                    SpatialPanel(
                        modifier = SubspaceModifier
                            .width(statsPanelWidthDp.dp)
                            .height(statsPanelHeightDp.dp)
                            .offset(x = statsPanelXDp.dp, y = statsPanelYDp.dp, z = 90.dp),
                    ) {
                        PlaybackStatsOverlay(
                            snapshot = statsSnapshot,
                            onClose = { statsForNerdsEnabled = false },
                        )
                    }
                }

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
                        .offset(x = 0.dp, y = controlsPanelY.dp, z = controlsZDp.dp)
                        .resizable(),
                ) {
                    if (controlsVisible || isActuallyPaused) {
                        // Pointing at an orbiter keeps the chrome from auto-hiding —
                        // the orbiters sit outside the bottom panel's own hover area,
                        // so without this they'd vanish mid-aim.
                        val keepControlsAlive = Modifier.pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    if (ev.type == PointerEventType.Enter ||
                                        ev.type == PointerEventType.Move ||
                                        ev.type == PointerEventType.Press
                                    ) {
                                        resetAutoHide()
                                    }
                                }
                            }
                        }
                        // ── Stage orbiter (top) — controls for the cinema screen itself.
                        // Size −/reset/+, passthrough (theater) toggle, and lock. The lock
                        // stays reachable even while locked; the rest collapse away.
                        Orbiter(
                            alignment = OrbiterAlignment.TopCenter(
                                offset = DpVolumeOffset(y = 40.dp, z = OrbiterDefaults.Elevation),
                            ),
                        ) {
                            StageControlsOrbiter(
                                isLocked = isLocked,
                                sizeLabel = "${Math.round(videoPanelScale / DEFAULT_VIDEO_PANEL_SCALE * 100f)}%",
                                smallerEnabled = videoPanelScale > 0.75f + 1e-3f,
                                biggerEnabled = videoPanelScale < 2.5f - 1e-3f,
                                passthroughEnabled = passthroughEnabled,
                                onSmaller = {
                                    videoPanelScale = (videoPanelScale - 0.08f).coerceAtLeast(0.75f)
                                    savePlayerRootScale(viewModel, videoPanelScale)
                                    resetAutoHide()
                                },
                                onResetSize = {
                                    recenterScreenToViewer(manualPitch = true, resetScaleAndDepth = true)
                                    resetAutoHide()
                                },
                                onBigger = {
                                    videoPanelScale = (videoPanelScale + 0.08f).coerceAtMost(2.5f)
                                    savePlayerRootScale(viewModel, videoPanelScale)
                                    resetAutoHide()
                                },
                                onRecenter = {
                                    recenterScreenToViewer(manualPitch = true, resetScaleAndDepth = false)
                                    resetAutoHide()
                                },
                                onPassthroughToggle = {
                                    updatePassthroughOpacity(
                                        XrPassthroughController.nextSteppedPreset(effectivePassthroughOpacity),
                                    )
                                    resetAutoHide()
                                },
                                onLockToggle = { isLocked = !isLocked; resetAutoHide() },
                                modifier = keepControlsAlive,
                                passthroughOpacity = effectivePassthroughOpacity,
                                curvedScreenEnabled = curvedScreenEnabled && !wallPinActive,
                                curvePreset = if (wallPinActive) XrScreenCurvePreset.FLAT else screenCurvePreset,
                                onCurvedScreenToggle = if (!immersiveProjection && !wallPinActive) {
                                    {
                                        updateScreenCurvePreset(screenCurvePreset.nextSteppedPreset())
                                        resetAutoHide()
                                    }
                                } else {
                                    null
                                },
                            )
                        }
                        if (!isLocked) {
                            // ── Track-options orbiter (left) — subtitles / audio / quality / speed.
                            Orbiter(
                                alignment = OrbiterAlignment.CenterStart(
                                    offset = DpVolumeOffset(x = 40.dp, z = OrbiterDefaults.Elevation),
                                ),
                            ) {
                                TrackOptionsOrbiter(
                                    onSubtitleClick = { activeDialog = "subtitle"; resetAutoHide() },
                                    onAudioClick = { activeDialog = "audio"; resetAutoHide() },
                                    onQualityClick = { activeDialog = "quality"; resetAutoHide() },
                                    onSpeedClick = { activeDialog = "speed"; resetAutoHide() },
                                    onProjectionClick = { activeDialog = "projection"; resetAutoHide() },
                                    onSyncClick = { activeDialog = "sync"; resetAutoHide() },
                                    syncActive = subtitleOffsetMs != 0L || audioOffsetMs != 0L,
                                    modifier = keepControlsAlive,
                                )
                            }
                            // ── Session orbiter (right) — cast / SyncPlay / cast & crew / voice.
                            Orbiter(
                                alignment = OrbiterAlignment.CenterEnd(
                                    offset = DpVolumeOffset(x = 40.dp, z = OrbiterDefaults.Elevation),
                                ),
                            ) {
                                SessionOrbiter(
                                    onCastClick = { activeDialog = "fcast"; resetAutoHide() },
                                    castActive = fcastCastingState ==
                                        dev.jdtech.jellyfin.fcast.sender.FCastCastingController.Status.Casting,
                                    onSyncPlayClick = {
                                        activeDialog = "syncplay"
                                        viewModel.refreshSyncPlayGroups()
                                        resetAutoHide()
                                    },
                                    syncPlayActive = syncPlayState.activeGroup != null,
                                    onCastCrewClick = { activeDialog = "cast_crew"; resetAutoHide() },
                                    onVoiceClick = {
                                        requestVoiceCommand()
                                        resetAutoHide()
                                    },
                                    voiceControlEnabled = voiceControlEnabled,
                                    voiceAvailable = voiceService.isAvailable(),
                                    voiceState = voiceState,
                                    modifier = keepControlsAlive,
                                )
                            }
                        }
                    }

                    Box(modifier = Modifier.fillMaxSize()) {
                        AnimatedVisibility(
                            visible = controlsVisible || isActuallyPaused,
                            enter = fadeIn(),
                            exit = fadeOut(),
                        ) {
                            ControlPanelUI(
                                player = player,
                                uiState = uiState,
                                isPlaying = isPlaying,
                                currentPosition = currentPosition,
                                duration = duration,
                                isLocked = isLocked,
                                spatialAudioAvailable = spatialAudioAvailable,
                                onControlInputActiveChange = { controlsInputActive = it },
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
                                mediaStreams = uiState.currentMediaStreams,
                                activeAudioStreamIndex = uiState.currentAudioStreamIndex,
                                onAudioStreamSelected = { streamIndex ->
                                    viewModel.changeAudioStream(streamIndex)
                                },
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
                                mediaStreams = uiState.currentMediaStreams,
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
                    if (activeDialog == "projection") {
                        SpatialDialog(onDismissRequest = { activeDialog = null }) {
                            ProjectionDialogContent(
                                currentProjection = currentProjection,
                                onProjectionSelected = { projection ->
                                    projectionManuallySet = true
                                    currentProjection = projection
                                    resetAutoHide()
                                },
                                onRecenter = {
                                    recenterImmersiveSurface()
                                    resetAutoHide()
                                },
                                onDismiss = { activeDialog = null },
                                wallPinned = wallPinActive,
                                wallPinStatus = wallPinStatus,
                                onToggleWallPin = {
                                    val newRequested = !wallPinRequested
                                    wallPinRequested = newRequested
                                    if (!newRequested) {
                                        // Clear the persisted anchor on an explicit unpin.
                                        WallAnchor.unpersist(
                                            session,
                                            viewModel.appPreferences.getValue(viewModel.appPreferences.xrPlayerWallAnchorUuid),
                                        )
                                        viewModel.appPreferences.setValue(viewModel.appPreferences.xrPlayerWallAnchorUuid, "")
                                        wallPinStatus = null
                                    }
                                    viewModel.appPreferences.setValue(viewModel.appPreferences.xrPlayerWallPinned, newRequested)
                                    resetAutoHide()
                                },
                                curvedScreenEnabled = curvedScreenEnabled,
                                onToggleCurvedScreen = {
                                    updateCurvedScreen(!curvedScreenEnabled)
                                    resetAutoHide()
                                },
                                currentCurvePreset = screenCurvePreset,
                                onSelectCurvePreset = { preset ->
                                    updateScreenCurvePreset(preset)
                                    resetAutoHide()
                                },
                            )
                        }
                    }
                    if (activeDialog == "sync") {
                        SpatialDialog(onDismissRequest = { activeDialog = null }) {
                            SyncOffsetDialogContent(
                                subtitleOffsetMs = subtitleOffsetMs,
                                audioOffsetMs = audioOffsetMs,
                                onAdjustSubtitleOffsetMs = { delta ->
                                    viewModel.adjustSubtitleOffsetMs(delta)
                                    resetAutoHide()
                                },
                                onResetSubtitleOffset = {
                                    viewModel.setSubtitleOffsetMs(0L)
                                    resetAutoHide()
                                },
                                onAdjustAudioOffsetMs = { delta ->
                                    viewModel.adjustAudioOffsetMs(delta)
                                    resetAutoHide()
                                },
                                onResetAudioOffset = {
                                    viewModel.setAudioOffsetMs(0L)
                                    resetAutoHide()
                                },
                                onDismiss = { activeDialog = null },
                                showPassthroughControls = !immersiveProjection,
                                passthroughOpacity = effectivePassthroughOpacity,
                                onPassthroughOpacityChange = { opacity ->
                                    updatePassthroughOpacity(opacity)
                                    resetAutoHide()
                                },
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
                                transcodeReason = uiState.currentTranscodeReason,
                                onQualitySelected = { bitrate ->
                                    if (itemId != null) {
                                        viewModel.changeQuality(itemId, itemKind, bitrate)
                                    }
                                },
                                onDismiss = { activeDialog = null },
                                statsForNerdsEnabled = statsForNerdsEnabled,
                                onToggleStatsForNerds = {
                                    statsForNerdsEnabled = !statsForNerdsEnabled
                                    resetAutoHide()
                                },
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
                    if (activeDialog == "fcast") {
                        SpatialDialog(onDismissRequest = { activeDialog = null }) {
                            Surface(shape = RoundedCornerShape(32.dp)) {
                                androidx.compose.foundation.layout.Column {
                                    if (splitAvVideoRole) {
                                        // Sender-side fold: ask the receiver to stop and let
                                        // the cascade in SplitAvController.endByReceiver
                                        // unmute the master so audio comes back to the XR.
                                        androidx.compose.material3.Button(
                                            onClick = {
                                                // Route through the IPC bridge to the
                                                // main-process SplitAvController; the
                                                // :xrplayer-local FCast controller has no
                                                // session so stopCast() here did nothing.
                                                onEndSplitAvFromMaster()
                                                activeDialog = null
                                            },
                                            modifier = androidx.compose.ui.Modifier
                                                .fillMaxWidth()
                                                .padding(
                                                    start = 40.dp,
                                                    end = 40.dp,
                                                    top = 40.dp,
                                                ),
                                        ) {
                                            androidx.compose.material3.Text(
                                                text = "Stop split-A/V — play audio on this headset",
                                            )
                                        }
                                    }
                                dev.jdtech.jellyfin.fcast.ui.FCastReceiverPickerSheet(
                                    onReceiverPicked = { receiver ->
                                        val item = player.currentMediaItem
                                        val cfg = item?.localConfiguration
                                        val url = cfg?.uri?.toString()
                                        if (url == null) {
                                            activeDialog = null
                                            return@FCastReceiverPickerSheet
                                        }
                                        val container =
                                            cfg.mimeType
                                                ?: dev.jdtech.jellyfin.fcast.sender.PlayMessageBuilder.guessContainer(url)
                                                ?: "video/mp4"
                                        val play =
                                            dev.jdtech.jellyfin.fcast.sender.PlayMessageBuilder.build(
                                                url = url,
                                                container = container,
                                                positionSeconds = (player.currentPosition.coerceAtLeast(0L)) / 1000.0,
                                                title = uiState.currentItemTitle,
                                            )
                                        coroutineScope.launch {
                                            runCatching {
                                                // Pause-on-cast: hand off to the receiver and stop
                                                // burning the local decoder. Matches Google Cast UX.
                                                player.pause()
                                                fcastCastingController.startCast(receiver, play)
                                            }
                                        }
                                        activeDialog = null
                                    },
                                    onDismiss = { activeDialog = null },
                                )
                                }
                            }
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

                    // Playback failure surface (e.g. an 8K stream a 4K decoder
                    // can't handle): without it the cinema panel would stay black
                    // with no explanation. Dismissing exits the player.
                    uiState.playbackError?.let { error ->
                        SpatialDialog(
                            onDismissRequest = {
                                viewModel.dismissPlaybackError()
                                requestExit("playback-error")
                            },
                        ) {
                            Column(
                                modifier = Modifier.padding(32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    text = stringResource(LocalR.string.player_error_title),
                                    style = MaterialTheme.typography.headlineSmall,
                                )
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    text = error.message,
                                    style = MaterialTheme.typography.bodyLarge,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                )
                                Spacer(Modifier.height(24.dp))
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (error.canTranscodeOffline) {
                                        Button(
                                            onClick = {
                                                viewModel.transcodeCurrentItemForOffline()
                                            },
                                        ) {
                                            Text("Transcode for Offline")
                                        }
                                    }
                                    Button(
                                        onClick = {
                                            viewModel.dismissPlaybackError()
                                            requestExit("playback-error")
                                        },
                                    ) {
                                        Text(stringResource(CoreR.string.close))
                                    }
                                }
                            }
                        }
                    }

                    (transcodeState as? OnDeviceTranscodeState.InProgress)?.let { inProgress ->
                        SpatialDialog(
                            onDismissRequest = { viewModel.cancelOnDeviceTranscode() },
                        ) {
                            Column(
                                modifier = Modifier.padding(32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    text = "Transcoding for Offline Playback",
                                    style = MaterialTheme.typography.headlineSmall,
                                )
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    text = inProgress.title.orEmpty(),
                                    style = MaterialTheme.typography.bodyLarge,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                )
                                Spacer(Modifier.height(16.dp))
                                LinearProgressIndicator(
                                    progress = { inProgress.progressPercent / 100f },
                                    modifier = Modifier.fillMaxWidth().height(8.dp),
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = "${inProgress.progressPercent}%",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(24.dp))
                                Button(
                                    onClick = { viewModel.cancelOnDeviceTranscode() },
                                ) {
                                    Text(stringResource(CoreR.string.cancel))
                                }
                            }
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

                val castCardModel = voice.activeCastCard
                if (!voiceSearchOpen && castCardModel != null) {
                    SpatialPanel(
                        modifier = SubspaceModifier
                            .width(560.dp)
                            .height(480.dp)
                            .offset(x = 1220.dp, y = (controlsPanelY + 380f).dp, z = controlsZDp.dp),
                    ) {
                        SpatialCastCardOverlay(
                            model = castCardModel,
                            onSelectPersonIndex = { idx ->
                                voice.activeCastCard = castCardModel.copy(
                                    selectedPersonIndex = idx,
                                    isLoadingRelated = true,
                                )
                            },
                            onPlayItem = { item ->
                                voice.activeCastCard = null
                                onLaunchSearchResult(item)
                            },
                            onDismiss = { voice.activeCastCard = null },
                        )
                    }
                }
            }
        }
    }
}

// StageControlsOrbiter / TrackOptionsOrbiter / SessionOrbiter / SyncPlayDialogContent → PlayerSecondaryControls.kt

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
// extractVideoDepth, viewerFacingRotation, computeViewerCenteredPanelPose,
//   computeImmersiveViewerPoses, computeDragPose, projectOverlayPoseTowardViewer,
//   syncViewerProjectedOverlayRoots, isPanelOutsideComfortableFov,
//   SpatialPlayerRecoveryEffect, safeGetEntityPose → PlayerGeometry.kt
// loadSavedPlayerRootPose, loadSavedPlayerRootScale, savePlayerRootPose,
//   savePlayerRootScale, isRestorableVideoPose, posesApproximatelyEqual → PlayerPoseStorage.kt
