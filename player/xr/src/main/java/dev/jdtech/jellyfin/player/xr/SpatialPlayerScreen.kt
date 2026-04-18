package dev.jdtech.jellyfin.player.xr

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import android.graphics.Bitmap
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
import dev.jdtech.jellyfin.player.xr.voice.GeminiCloudService
import dev.jdtech.jellyfin.player.xr.voice.GeminiNanoService
import dev.jdtech.jellyfin.player.xr.voice.RecommendationContext
import dev.jdtech.jellyfin.player.xr.capture.PlayerFrameCapture
import dev.jdtech.jellyfin.player.xr.voice.CharacterScanOverlay
import dev.jdtech.jellyfin.player.xr.voice.SecondaryHandPinchDetector
import dev.jdtech.jellyfin.player.xr.voice.VoiceControlOverlay
import dev.jdtech.jellyfin.player.xr.voice.VoiceParseResult
import dev.jdtech.jellyfin.settings.domain.llm.LlmDownloadManager
import dev.jdtech.jellyfin.player.xr.voice.SmartChatEngine
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.InstallIn
import dagger.hilt.EntryPoint
import dagger.hilt.components.SingletonComponent
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
    val assistantVoicePreference = viewModel.appPreferences.getValue(viewModel.appPreferences.voiceAssistantVoice) ?: "male"
    val voiceService = remember(context) { SpatialVoiceService(context.applicationContext) }
    val geminiNanoService = remember(context) { GeminiNanoService(context.applicationContext) }
    val geminiCloudService = remember(context) { GeminiCloudService(context.applicationContext, viewModel.appPreferences, viewModel.repository) }
    val llmEntryPoint = remember(context) {
        EntryPointAccessors.fromApplication(context.applicationContext, LlmEntryPoint::class.java)
    }
    val downloadManager = remember(context) { llmEntryPoint.downloadManager() }
    var commandCoordinator by remember(context) { mutableStateOf<SpatialCommandCoordinator?>(null) }
    var chatEngine by remember(context) { mutableStateOf<SmartChatEngine?>(null) }
    val tts = remember(context) { SpatialVoiceSynthesizer(context.applicationContext) }
    val isTtsSpeaking by tts.isSpeaking.collectAsState()
    val recentSubtitles = remember { mutableStateListOf<Pair<Long, String>>() }
    val assistantSubtitleHistory by viewModel.assistantSubtitleHistory.collectAsState()
    val assistantSubtitleLines =
        if (assistantSubtitleHistory.isNotEmpty()) assistantSubtitleHistory else recentSubtitles.toList()

    // Disk-cache fallback: reads the current item UUID from the ViewModel's StateFlow at call time,
    // so it always uses the correct item even if the composable hasn't recomposed yet.
    val subtitleCacheFallback: ((fromMs: Long, toMs: Long) -> List<Pair<Long, String>>) = remember(viewModel) {
        { fromMs, toMs ->
            val itemId = runCatching {
                java.util.UUID.fromString(viewModel.uiState.value.currentItemId)
            }.getOrNull()
            if (itemId != null) {
                viewModel.subtitleCacheManager.loadWindow(itemId, fromMs, toMs)
            } else {
                emptyList()
            }
        }
    }

    val voiceState by voiceService.state.collectAsState()
    val partialTranscript by voiceService.partialTranscript.collectAsState()
    var voiceFeedback by remember { mutableStateOf<String?>(null) }
    val conversationHistory = remember { mutableStateListOf<Pair<String, String>>() }
    var recommendationContext by remember { mutableStateOf<RecommendationContext?>(null) }
    var voiceGestureArmingProgress by remember { mutableFloatStateOf(0f) }
    var voiceGestureHint by remember { mutableStateOf<String?>(null) }
    var shouldStartVoiceCapture by remember { mutableStateOf(false) }
    var followUpPending by remember { mutableStateOf(false) }
    var followUpDeadlineMs by remember { mutableLongStateOf(0L) }
    val followUpListenWindowMs = 12_000L
    val followUpAutoStartDelayMs = 200L
    var characterScanActive by remember { mutableStateOf(false) }
    var voiceAssetsRequested by remember { mutableStateOf(false) }
    var exitRequested by remember { mutableStateOf(false) }
    var activeVoiceJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val voiceGestureHand =
        viewModel.appPreferences.getValue(viewModel.appPreferences.voiceGestureHand) ?: "left"

    fun armFollowUpWindow(reason: String) {
        followUpPending = true
        followUpDeadlineMs = System.currentTimeMillis() + followUpListenWindowMs
        voiceGestureHint = "Answer now"
        Timber.i(
            "VOICE: player follow-up armed reason=%s windowMs=%d",
            reason,
            followUpListenWindowMs,
        )
    }

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
        commandCoordinator
            ?: SpatialCommandCoordinator(
                context.applicationContext,
                geminiNanoService,
                geminiCloudService,
                viewModel.appPreferences,
                llmEntryPoint.modelManager(),
            ).also { commandCoordinator = it }

    fun requireChatEngine(): SmartChatEngine =
        chatEngine
            ?: SmartChatEngine(
                geminiNanoService,
                geminiCloudService,
                viewModel.appPreferences,
                llmEntryPoint.modelManager(),
                viewModel.repository,
            ).also { chatEngine = it }
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
                shouldStartVoiceCapture = false
                voiceFeedback = "Microphone permission required"
                voiceService.resetState()
            }
        }
    val handTrackingPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            hasHandTrackingPermission = granted
            if (!granted) {
                voiceGestureArmingProgress = 0f
                voiceGestureHint = null
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
    var videoWidth by remember { mutableFloatStateOf(DEFAULT_VIDEO_WIDTH_METERS) }
    var videoHeight by remember { mutableFloatStateOf(DEFAULT_VIDEO_HEIGHT_METERS) }
    var currentCues by remember { mutableStateOf<List<Cue>>(emptyList()) }
    var subtitleTrackSelected by remember { mutableStateOf(false) }
    var subtitleTrackVersion by remember { mutableIntStateOf(0) }
    val passthroughEnabled = passthroughOverrideEnabled ?: !isPlaying
    var resumePlaybackAfterAssistantSpeech by remember { mutableStateOf(false) }
    var assistantSpeechPendingStart by remember { mutableStateOf(false) }
    var assistantSpeechStarted by remember { mutableStateOf(false) }

    // A voice turn is "busy" when a recognition/processing/speech cycle is in flight.
    // The same predicate answers both "is it safe to start a new turn?" (callers negate)
    // and "is there something to interrupt right now?" (callers use directly).
    fun isVoiceTurnBusy(): Boolean {
        return voiceState != VoiceState.IDLE ||
            activeVoiceJob?.isActive == true ||
            assistantSpeechPendingStart ||
            assistantSpeechStarted ||
            isTtsSpeaking
    }

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
                        !isVoiceTurnBusy()
                },
                shouldDetectInterrupt = {
                    voiceControlEnabled &&
                        hasHandTrackingPermission &&
                        isVoiceTurnBusy()
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
    // Reset dismissal whenever the title changes (user started a new episode).
    LaunchedEffect(uiState.currentItemTitle) { nextEpisodePanelDismissed = false }
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
            useLibass,
        )
        viewModel.updatePlaybackProgress()
        useLibass = false
        subtitleTrackSelected = false
        hasLibassContent = false
        libassBitmap = null
        currentCues = emptyList()
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

    fun updateVoiceSearchItem(updatedItem: SpatialFinItem) {
        voiceSearchResults =
            voiceSearchResults.map { item ->
                if (item.id == updatedItem.id) updatedItem else item
            }
    }

    fun currentRecommendationSnapshot(): PlayerStateSnapshot =
        PlayerStateSnapshot(
            screenContext = dev.jdtech.jellyfin.player.session.voice.VoiceScreenContext.PLAYER,
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
            castNames = uiState.currentPeople.filter { it.type.equals("Actor", ignoreCase = true) }.map { it.name },
            directors = uiState.currentPeople.filter { it.type.equals("Director", ignoreCase = true) }.map { it.name },
            writers = uiState.currentPeople.filter { it.type.equals("Writer", ignoreCase = true) }.map { it.name },
            castWithCharacters = uiState.currentPeople
                .filter { it.type.equals("Actor", ignoreCase = true) && it.role.isNotBlank() }
                .map { it.name to it.role },
            productionYear = uiState.currentProductionYear,
            officialRating = uiState.currentOfficialRating,
            currentAudioTrack = selectedTrackName(player, C.TRACK_TYPE_AUDIO),
            currentSubtitleTrack = selectedTrackName(player, C.TRACK_TYPE_TEXT),
            currentAudioLanguageCode = selectedTrackLanguage(player, C.TRACK_TYPE_AUDIO),
            currentSubtitleLanguageCode = selectedTrackLanguage(player, C.TRACK_TYPE_TEXT),
            inVoiceSearch = voiceSearchOpen,
            voiceSearchQuery = voiceSearchQuery.ifBlank { null },
            voiceSearchResultsCount = voiceSearchResults.size,
            lastRecommendationQuery = recommendationContext?.query,
            lastRecommendationCount = recommendationContext?.items?.size ?: 0,
            lastRecommendationTitles = recommendationContext?.items?.take(6)?.map { it.name } ?: emptyList(),
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
        videoOverlayAttachmentVersion++
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
        if (!assistantSpokenReplies || !tts.canSpeak()) {
            Timber.w("VOICE: speakAssistantReply skipped spokenReplies=%b canSpeak=%b chars=%d", assistantSpokenReplies, tts.canSpeak(), text.length)
            return
        }
        resumePlaybackAfterAssistantSpeech = player.isPlaying
        assistantSpeechPendingStart = true
        assistantSpeechStarted = false
        if (followUpPending) {
            followUpDeadlineMs = 0L
            voiceGestureHint = "Wait for the reply, then answer"
            Timber.i("VOICE: player follow-up scheduled pending spoken reply")
        }
        if (resumePlaybackAfterAssistantSpeech) {
            player.pause()
        }
        tts.speak(text, languageHint, assistantVoicePreference)
    }

    fun requestVoiceAssetsWarmup() {
        if (voiceAssetsRequested) return
        voiceAssetsRequested = true
        if (viewModel.appPreferences.getValue(viewModel.appPreferences.voiceAssistantGemmaEnabled)) {
            coroutineScope.launch {
                Timber.i("VOICE: requesting XR voice assets on demand")
                downloadManager.downloadModel()
            }
        }
    }

    fun requestVoiceCommand(source: String = "manual") {
        if (source != "manual" && isVoiceTurnBusy()) {
            Timber.i(
                "VOICE: player request ignored source=%s state=%s speaking=%b pendingSpeech=%b startedSpeech=%b jobActive=%b",
                source,
                voiceState,
                isTtsSpeaking,
                assistantSpeechPendingStart,
                assistantSpeechStarted,
                activeVoiceJob?.isActive == true,
            )
            return
        }
        // Cancel any in-flight inference from the previous voice command before starting a new one.
        activeVoiceJob?.cancel()
        activeVoiceJob = null
        if (voiceState == VoiceState.LISTENING || voiceState == VoiceState.PROCESSING) {
            Timber.i("VOICE: player request ignored source=%s state=%s", source, voiceState)
            return
        }
        if (isTtsSpeaking) {
            tts.stop()
            assistantSpeechPendingStart = false
            assistantSpeechStarted = false
            resumePlaybackAfterAssistantSpeech = false
        }
        if (!voiceControlEnabled) {
            voiceFeedback = "Voice control disabled"
            return
        }
        requestVoiceAssetsWarmup()
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
        Timber.i(
            "VOICE: player request starting source=%s followUpPending=%b followUpDeadlineMs=%d",
            source,
            followUpPending,
            followUpDeadlineMs,
        )
        followUpPending = false
        followUpDeadlineMs = 0L
        voiceGestureHint = null
        voiceFeedback = null
        revealControls("voice-command")
        startVoiceCapture(
            voiceService = voiceService,
            commandCoordinatorProvider = ::requireCommandCoordinator,
            chatEngineProvider = ::requireChatEngine,
            recentSubtitles = assistantSubtitleLines,
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
            voiceSearchOpen = voiceSearchOpen,
            voiceSearchQuery = voiceSearchQuery,
            voiceSearchResults = voiceSearchResults,
            recommendationContext = recommendationContext,
            onRecommendationContextUpdated = { recommendationContext = it },
            onScheduleFollowUp = {
                armFollowUpWindow("chat-reply")
            },
            onGetSuggestions = { viewModel.repository.getSuggestions() },
            onResult = { voiceFeedback = it },
            onSpokenReply = { text, lang -> speakAssistantReply(text, lang) },
            onCharacterScanActiveChanged = { characterScanActive = it },
            subtitleCacheFallback = subtitleCacheFallback,
            scope = coroutineScope,
            lastPointerPosition = lastPointerPosition,
            onJobStarted = { activeVoiceJob = it },
            )
            }

    fun interruptVoiceCommand(reason: String) {
        if (!isVoiceTurnBusy()) return
        val shouldResumeFollowUp =
            followUpPending &&
                (isTtsSpeaking || assistantSpeechPendingStart || assistantSpeechStarted)
        Timber.i(
            "VOICE: player interrupt requested reason=%s state=%s speaking=%b jobActive=%b resumeFollowUp=%b",
            reason,
            voiceState,
            isTtsSpeaking,
            activeVoiceJob?.isActive == true,
            shouldResumeFollowUp,
        )
        voiceGestureArmingProgress = 0f
        voiceGestureHint = null
        voiceFeedback = null
        assistantSpeechPendingStart = false
        assistantSpeechStarted = false
        resumePlaybackAfterAssistantSpeech = false
        activeVoiceJob?.cancel()
        activeVoiceJob = null
        tts.stop()
        voiceService.cancelListening()
        voiceService.resetState()
        if (shouldResumeFollowUp) {
            armFollowUpWindow("speech-interrupted")
            coroutineScope.launch {
                delay(followUpAutoStartDelayMs)
                requestVoiceCommand("follow-up-interrupt")
            }
        } else {
            followUpPending = false
            followUpDeadlineMs = 0L
        }
    }

    // Reset conversation history when the playing item changes
    LaunchedEffect(uiState.currentItemTitle) {
        conversationHistory.clear()
        recommendationContext = null
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

    LaunchedEffect(hasAudioPermission, shouldStartVoiceCapture) {
        if (hasAudioPermission && shouldStartVoiceCapture) {
            shouldStartVoiceCapture = false
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
                    voiceGestureArmingProgress = event.progress
                    voiceGestureHint = event.hint
                }
                is SecondaryHandPinchDetector.GestureState.Started -> {
                    voiceGestureArmingProgress = 1f
                    voiceGestureHint = null
                    when (event.gestureType) {
                        SecondaryHandPinchDetector.GestureType.ACTIVATE ->
                            requestVoiceCommand("gesture-activate")
                        SecondaryHandPinchDetector.GestureType.INTERRUPT ->
                            interruptVoiceCommand("fist-gesture")
                    }
                }
                is SecondaryHandPinchDetector.GestureState.Ended -> {
                    voiceGestureArmingProgress = 0f
                    if (voiceState != VoiceState.LISTENING) {
                        voiceGestureHint = null
                    }
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
        // Auto-dismiss any transient feedback once TTS has finished speaking it.
        // The "Thinking..." sentinel that previously guarded this check came from a
        // dead dispatch branch (now throws) — nothing assigns it anymore, and the
        // live "thinking" indicator is driven by VoiceControlOverlay from voiceState.
        if (voiceFeedback != null && !isTtsSpeaking) {
            delay(4_000L)
            if (!isTtsSpeaking) {
                voiceFeedback = null
            }
        }
    }

    LaunchedEffect(voiceState) {
        if (voiceState == VoiceState.ERROR) {
            delay(2_000L)
            if (voiceState == VoiceState.ERROR) {
                voiceService.resetState()
            }
        }
    }

    LaunchedEffect(
        followUpPending,
        followUpDeadlineMs,
        isTtsSpeaking,
        assistantSpeechPendingStart,
        assistantSpeechStarted,
        voiceState,
        activeVoiceJob,
    ) {
        if (
            !followUpPending ||
            followUpDeadlineMs == 0L ||
            isTtsSpeaking ||
            assistantSpeechPendingStart ||
            assistantSpeechStarted ||
            voiceState != VoiceState.IDLE ||
            activeVoiceJob?.isActive == true
        ) {
            return@LaunchedEffect
        }
        val remaining = followUpDeadlineMs - System.currentTimeMillis()
        if (remaining <= 0L) {
            followUpPending = false
            voiceGestureHint = null
            return@LaunchedEffect
        }
        delay(followUpAutoStartDelayMs)
        if (
            followUpPending &&
            followUpDeadlineMs > 0L &&
            !isTtsSpeaking &&
            !assistantSpeechPendingStart &&
            !assistantSpeechStarted &&
            voiceState == VoiceState.IDLE &&
            activeVoiceJob?.isActive != true
        ) {
            Timber.i("VOICE: player auto-starting follow-up listening remainingMs=%d", followUpDeadlineMs - System.currentTimeMillis())
            requestVoiceCommand("follow-up-auto")
        }
    }

    LaunchedEffect(isTtsSpeaking, assistantSpeechPendingStart, assistantSpeechStarted, followUpPending) {
        if (assistantSpeechPendingStart && isTtsSpeaking) {
            assistantSpeechPendingStart = false
            assistantSpeechStarted = true
        } else if (assistantSpeechPendingStart && !isTtsSpeaking) {
            delay(1_500L)
            if (assistantSpeechPendingStart && !isTtsSpeaking) {
                assistantSpeechPendingStart = false
                assistantSpeechStarted = false
                if (resumePlaybackAfterAssistantSpeech) {
                    player.play()
                }
                resumePlaybackAfterAssistantSpeech = false
                if (followUpPending && followUpDeadlineMs == 0L) {
                    armFollowUpWindow("spoken-reply-did-not-start")
                }
            }
        } else if (!isTtsSpeaking && assistantSpeechStarted) {
            if (resumePlaybackAfterAssistantSpeech) {
                player.play()
            }
            resumePlaybackAfterAssistantSpeech = false
            assistantSpeechStarted = false
            if (followUpPending && followUpDeadlineMs == 0L) {
                armFollowUpWindow("spoken-reply-finished")
            }
        }
    }

    // Subtitle cue listener
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onCues(cueGroup: CueGroup) {
                currentCues = if (subtitleTrackSelected) cueGroup.cues else emptyList()
                if (cueGroup.cues.isNotEmpty()) {
                    val first = cueGroup.cues[0].text?.toString()
                    if (first != null) {
                        val pos = player.currentPosition
                        Timber.d("AI Subtitle Buffer: adding cue at %d: %s", pos, first)
                        recentSubtitles.add(pos to first)
                        recentSubtitles.removeAll { pos - it.first > 1_200_000L }
                    }
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                subtitleTrackSelected =
                    tracks.groups.any { group ->
                        group.type == C.TRACK_TYPE_TEXT &&
                            group.isSupported &&
                            groupIsSelected(group)
                    }

                // AI context optimization: find and record the first available SDH track for assistant context,
                // even if not visually selected.  This gives the AI non-verbal cues (e.g. [Door Slams]).
                val aiTrack =
                    tracks.groups
                        .filter { it.type == C.TRACK_TYPE_TEXT && it.isSupported }
                        .sortedByDescending { group ->
                            val label = group.mediaTrackGroup.getFormat(0).label?.lowercase() ?: ""
                            val roleFlags = group.mediaTrackGroup.getFormat(0).roleFlags
                            var score = 0
                            if (label.contains("sdh") || label.contains("cc")) score += 100
                            if (roleFlags and C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND != 0) score += 50
                            if (roleFlags and C.ROLE_FLAG_TRANSCRIBES_DIALOG != 0) score += 50
                            score
                        }
                        .firstOrNull()

                if (aiTrack != null) {
                    val format = aiTrack.mediaTrackGroup.getFormat(0)
                    Timber.i(
                        "subtitle: XR assistant track candidate label=%s lang=%s roleFlags=%d",
                        format.label,
                        format.language,
                        format.roleFlags,
                    )
                }

                if (!subtitleTrackSelected) {
                    currentCues = emptyList()
                }
                subtitleTrackVersion++
                Timber.i(
                    "subtitle: XR track snapshot updated version=%d textGroups=%d selected=%b",
                    subtitleTrackVersion,
                    tracks.groups.count { it.type == C.TRACK_TYPE_TEXT },
                    subtitleTrackSelected,
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
                        videoOverlayAttachmentVersion++
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
            // screen starts in the IMAX baseline instead of snapping there on first tap.
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
            voiceService.destroy()
            // Release voice/AI services so their background work (OkHttp dispatcher,
            // LiteRT/AICore handles, coroutine scopes) doesn't leak past the screen.
            runCatching { commandCoordinator?.destroy() }
            commandCoordinator = null
            runCatching { chatEngine?.destroy() }
            chatEngine = null
            runCatching { geminiNanoService.destroy() }
            runCatching { geminiCloudService.destroy() }
            tts.destroy()
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
        // Only commit to SharedPreferences when pose or scale actually changes. Writing
        // every 1 s wears flash, burns battery, and can race the MovableComponent's
        // final pose with a stale tick.
        var lastSavedPose: Pose? = null
        var lastSavedScale: Float = Float.NaN
        while (true) {
            val poseToSave = lastReportedMovePose.value ?: safeGetEntityPose(root)
            if (poseToSave != null) {
                lastReportedMovePose.value = poseToSave
                if (!posesApproximatelyEqual(poseToSave, lastSavedPose)) {
                    savePlayerRootPose(viewModel, poseToSave)
                    lastSavedPose = poseToSave
                }
            }
            if (kotlin.math.abs(videoPanelScale - lastSavedScale) > 1e-4f) {
                savePlayerRootScale(viewModel, videoPanelScale)
                lastSavedScale = videoPanelScale
            }
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
    val hasSubtitleContent = uiState.visualSubtitlesEnabled && (
        (useLibass && hasLibassContent && libassBitmap != null) ||
        (!useLibass && subtitleTrackSelected && currentCues.isNotEmpty())
    )
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
                                view.setCues(currentCues)
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
                    feedbackText = if (characterScanActive) null else voiceFeedback,
                    gestureArmingProgress = voiceGestureArmingProgress,
                    gestureHint = voiceGestureHint,
                )
                CharacterScanOverlay(visible = characterScanActive)
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
                // so the main panel stays uncluttered (IMAX principle: screen first, UI second).
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
                                            voiceFeedback = "Opening trailer for ${item.name}"
                                        }.onFailure {
                                            voiceFeedback = "Couldn't open trailer for ${item.name}"
                                        }
                                    } ?: run {
                                        voiceFeedback = "No trailer found for ${item.name}"
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
                                                recentSubtitleLines = assistantSubtitleLines,
                                                currentPositionMs = player.currentPosition,
                                                assistantPreferences =
                                                    AssistantPreferences(
                                                        verbosity = assistantVerbosity,
                                                        spoilerPolicy = assistantSpoilerPolicy,
                                                        spokenRepliesEnabled = assistantSpokenReplies,
                                                    ),
                                                onSearchQuery = onSearchQuery,
                                                conversationHistory = conversationHistory,
                                                onGetSuggestions = { viewModel.repository.getSuggestions() },
                                                recommendationContext = RecommendationContext(item.name, listOf(item)),
                                                subtitleCacheFallback = subtitleCacheFallback,
                                            )
                                        voiceSearchLoading = false
                                        response.recommendedItems
                                            .takeIf { it.isNotEmpty() }
                                            ?.let {
                                                recommendationContext = RecommendationContext(item.name, it)
                                                voiceSearchResults = it
                                            }
                                            ?: run {
                                                voiceSearchError = "No similar titles found"
                                            }
                                        voiceFeedback = response.text
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
                                            recommendationContext =
                                                recommendationContext?.let { context ->
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
                                            voiceFeedback =
                                                if (updated.favorite) {
                                                    "Saved ${updated.name}"
                                                } else {
                                                    "Removed ${updated.name} from favorites"
                                                }
                                        }.onFailure {
                                            voiceFeedback = "Couldn't update favorite for ${item.name}"
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

private fun startVoiceCapture(
    voiceService: SpatialVoiceService,
    commandCoordinatorProvider: () -> SpatialCommandCoordinator,
    chatEngineProvider: () -> SmartChatEngine,
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
    voiceSearchOpen: Boolean,
    voiceSearchQuery: String,
    voiceSearchResults: List<SpatialFinItem>,
    recommendationContext: RecommendationContext?,
    onRecommendationContextUpdated: (RecommendationContext) -> Unit,
    onScheduleFollowUp: () -> Unit,
    onGetSuggestions: suspend () -> List<SpatialFinItem>,
    onResult: (String) -> Unit,
    onSpokenReply: (String, String?) -> Unit,
    onCharacterScanActiveChanged: ((Boolean) -> Unit)? = null,
    subtitleCacheFallback: ((fromMs: Long, toMs: Long) -> List<Pair<Long, String>>)? = null,
    scope: kotlinx.coroutines.CoroutineScope,
    lastPointerPosition: androidx.compose.ui.geometry.Offset?,
    onJobStarted: ((kotlinx.coroutines.Job) -> Unit)? = null,
) {
    val startedAtMs = System.currentTimeMillis()
    voiceService.startListening { transcript ->
        val job = scope.launch {
            try {
                val snapshot =
                    PlayerStateSnapshot(
                        screenContext = dev.jdtech.jellyfin.player.session.voice.VoiceScreenContext.PLAYER,
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
                        inVoiceSearch = voiceSearchOpen,
                        voiceSearchQuery = voiceSearchQuery.ifBlank { null },
                        voiceSearchResultsCount = voiceSearchResults.size,
                        lastRecommendationQuery = recommendationContext?.query,
                        lastRecommendationCount = recommendationContext?.items?.size ?: 0,
                        lastRecommendationTitles = recommendationContext?.items?.take(6)?.map { it.name } ?: emptyList(),
                        passthroughEnabled = passthroughEnabled,
                        castWithCharacters = uiState.currentPeople
                            .filter { it.type.equals("Actor", ignoreCase = true) && it.role.isNotBlank() }
                            .map { it.name to it.role },
                    )
                val commandCoordinator = commandCoordinatorProvider()
                val parseResult = commandCoordinator.parse(transcript, snapshot)
                val action = parseResult.action
                if (action is XrPlayerAction.ChatQuery) {
                    onResult("…")
                    val chatEngine = chatEngineProvider()
                    
                    val visualContexts = mutableListOf<android.graphics.Bitmap>()
                    val ownedBitmaps = mutableListOf<android.graphics.Bitmap>()
                    val trickplay = uiState.currentTrickplay

                    // Detect "who is this/him/her/the character" style queries.
                    val normalizedQuery = action.query.lowercase()
                    val isCharacterIDQuery = (normalizedQuery.startsWith("who is") || normalizedQuery.startsWith("who was")) &&
                        run {
                            val afterWho = normalizedQuery.removePrefix("who is ").removePrefix("who was ").trim()
                            afterWho in setOf(
                                "this", "that", "him", "her", "he", "she", "they",
                                "this character", "this person", "this actor", "this actress",
                                "the character", "this guy", "this man", "this woman",
                                "this girl", "this boy",
                            ) || afterWho.startsWith("this ") || afterWho.startsWith("the ")
                        }

                    if (isCharacterIDQuery) {
                        // Single high-res frame via MediaMetadataRetriever; falls back to trickplay.
                        val streamUri = player.currentMediaItem?.localConfiguration?.uri?.toString()
                        val frame = PlayerFrameCapture.bestFrameForCharacterID(
                            streamUri = streamUri,
                            positionMs = player.currentPosition,
                            trickplayImages = trickplay?.images.orEmpty(),
                            trickplayIntervalSeconds = trickplay?.interval?.toLong() ?: 0L,
                            ownedBitmapOut = ownedBitmaps,
                        )
                        if (frame != null) visualContexts.add(frame)
                        onCharacterScanActiveChanged?.invoke(true)
                    } else if (trickplay != null && trickplay.images.isNotEmpty() && trickplay.interval > 0) {
                        // Temporal sequence of trickplay frames for general queries.
                        val currentIdx = (player.currentPosition / 1000 / trickplay.interval).toInt()
                            .coerceIn(0, trickplay.images.size - 1)
                        val indices = listOf(
                            (currentIdx - 3).coerceAtLeast(0),
                            (currentIdx - 1).coerceAtLeast(0),
                            currentIdx,
                        ).distinct()
                        indices.forEach { idx -> visualContexts.add(trickplay.images[idx]) }
                    }

                    val isGpu = chatEngine.modelManager.instance?.backendName == "GPU"
                    val shouldPauseForGemma = isGpu && chatEngine.shouldUseGemma()
                    var wasPlaying = false
                    if (shouldPauseForGemma) {
                        wasPlaying = player.isPlaying
                        if (wasPlaying) {
                            player.pause()
                        }
                    }

                    val response = try {
                        chatEngine.query(
                            question = action.query,
                            playerState = snapshot,
                            storySoFarContext = uiState.storySoFarContext,
                            recentSubtitleLines = recentSubtitles,
                            currentPositionMs = player.currentPosition,
                            assistantPreferences = assistantPreferences,
                            onSearchQuery = onSearchQuery,
                            conversationHistory = conversationHistory,
                            recommendationContext = recommendationContext,
                            onGetSuggestions = onGetSuggestions,
                            visualContexts = visualContexts,
                            lastPointerPosition = lastPointerPosition,
                            subtitleCacheFallback = subtitleCacheFallback,
                            onTokenStream = { partial -> onResult(partial) },
                        )
                    } finally {
                        if (isCharacterIDQuery) onCharacterScanActiveChanged?.invoke(false)
                        ownedBitmaps.forEach { it.recycle() }
                        ownedBitmaps.clear()
                    }

                    if (wasPlaying) {
                        player.play()
                    }

                    if (response.text != null) {
                        Timber.i(
                            "VOICE: chat reply strategy=%s skill=%s recommendations=%d disposition=%s spokenReplies=%b",
                            response.strategy,
                            response.selectedSkill,
                            response.recommendedItems.size,
                            response.resultDisposition,
                            assistantPreferences.spokenRepliesEnabled,
                        )
                        onResult(response.text)
                        onConversationTurn(action.query, response.text)
                        onScheduleFollowUp()
                        if (assistantPreferences.spokenRepliesEnabled) {
                            Timber.i("VOICE: speaking chat reply chars=%d", response.text.length)
                            onSpokenReply(response.text, responseLanguageHint)
                        }
                        if (response.recommendedItems.isNotEmpty()) {
                            Timber.i(
                                "VOICE: showing recommendation results query=%s count=%d first=%s",
                                action.query,
                                response.recommendedItems.size,
                                response.recommendedItems.firstOrNull()?.name,
                            )
                            onRecommendationContextUpdated(
                                RecommendationContext(
                                    query = action.query,
                                    items = response.recommendedItems,
                                ),
                            )
                            controller.showRecommendations(action.query, response.recommendedItems)
                        }
                    } else {
                        Timber.w("VOICE: chat reply was null")
                        onResult("Sorry, I couldn't process that.")
                    }
                    telemetryStore.record(
                        VoiceTelemetryEntry(
                            transcript = transcript,
                            normalizedTranscript = parseResult.normalizedTranscript,
                            action = "ChatQuery",
                            strategy = response.strategy,
                            latencyMs = System.currentTimeMillis() - startedAtMs,
                            success = response.text != null,
                            selectedSkill = response.selectedSkill,
                            validatedInput = response.validatedInput,
                            resultDisposition = response.resultDisposition,
                            details = "parse=${parseResult.debugInfo}; reply=${response.debugInfo}",
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
                            normalizedTranscript = parseResult.normalizedTranscript,
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
        onJobStarted?.invoke(job)
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

