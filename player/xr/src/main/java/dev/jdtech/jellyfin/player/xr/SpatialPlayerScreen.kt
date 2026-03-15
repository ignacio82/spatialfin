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
import dev.jdtech.jellyfin.player.xr.voice.VoiceControlOverlay
import dev.jdtech.jellyfin.player.xr.voice.VoiceParseResult
import dev.jdtech.jellyfin.player.xr.voice.PlayerStateSnapshot
import dev.jdtech.jellyfin.player.xr.voice.SecondaryHandPinchDetector
import dev.jdtech.jellyfin.player.xr.voice.SpatialCommandCoordinator
import dev.jdtech.jellyfin.player.xr.voice.SpatialVoiceService
import dev.jdtech.jellyfin.player.xr.voice.VoiceCommandDispatcher
import dev.jdtech.jellyfin.player.xr.voice.VoiceState
import dev.jdtech.jellyfin.models.SpatialFinEpisode
import dev.jdtech.jellyfin.models.SpatialFinItem
import dev.jdtech.jellyfin.models.SpatialFinMovie
import dev.jdtech.jellyfin.models.SpatialFinSeason
import dev.jdtech.jellyfin.models.SpatialFinShow
import dev.jdtech.jellyfin.settings.voice.VoiceTelemetryEntry
import dev.jdtech.jellyfin.settings.voice.VoiceTelemetryStore
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import coil3.compose.AsyncImage
import dev.jdtech.jellyfin.player.core.domain.models.PlayerItem
import dev.jdtech.jellyfin.player.core.domain.models.PlayerPerson
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.player.local.R as LocalR

// ── Next-episode panel threshold ───────────────────────────────────────────────────
private const val NEXT_EPISODE_THRESHOLD_MS = 2 * 60 * 1_000L  // show in last 2 minutes
private const val HAND_TRACKING_PERMISSION = "android.permission.HAND_TRACKING"

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
    itemKind: String,
    startFromBeginning: Boolean,
    mediaSourceIndex: Int? = null,
    maxBitrate: Long? = null,
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
    val player by viewModel.playerFlow.collectAsState()
    val xrSubtitleSize = viewModel.appPreferences.getValue(viewModel.appPreferences.xrSubtitleSize).toFloat()
    val libassUsagePref = viewModel.appPreferences.getValue(viewModel.appPreferences.libassSubtitleUsage)
    val voiceControlEnabled = viewModel.appPreferences.getValue(viewModel.appPreferences.voiceControlEnabled)
    val voiceService = remember(context) { SpatialVoiceService(context.applicationContext) }
    val commandCoordinator = remember(context) { SpatialCommandCoordinator(context.applicationContext) }
    val voiceState by voiceService.state.collectAsState()
    val partialTranscript by voiceService.partialTranscript.collectAsState()
    var voiceFeedback by remember { mutableStateOf<String?>(null) }
    var shouldStartVoiceCapture by remember { mutableStateOf(false) }
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
        remember(session, activity) { activity?.let { SecondaryHandPinchDetector(session, it) } }

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
    var currentStereoMode by remember { mutableStateOf(initialStereoMode) }
    var videoWidth by remember { mutableFloatStateOf(10.0f) }
    var videoHeight by remember { mutableFloatStateOf(5.625f) }
    var currentCues by remember { mutableStateOf<List<Cue>>(emptyList()) }

    var showCastCrewPanel by remember { mutableStateOf(false) }
    LaunchedEffect(isPlaying) {
        if (!isPlaying) {
            delay(1500)
            showCastCrewPanel = true
        } else {
            showCastCrewPanel = false
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

    fun resetAutoHide() {
        hideTimestamp = System.currentTimeMillis()
    }

    fun openVoiceSearch(query: String) {
        voiceSearchQuery = query
        voiceSearchLoading = true
        voiceSearchError = null
        activeDialog = "voice_search"
        coroutineScope.launch {
            runCatching { onSearchQuery(query) }
                .onSuccess { results ->
                    voiceSearchResults = results
                    voiceSearchLoading = false
                    voiceSearchError = null
                }
                .onFailure {
                    voiceSearchResults = emptyList()
                    voiceSearchLoading = false
                    voiceSearchError = "Search failed"
                }
        }
    }

    val voiceDispatcher =
        remember(player, viewModel, activity) {
            VoiceCommandDispatcher(
                viewModel = viewModel,
                player = player,
                onControlsVisibilityChange = { controlsVisible = it },
                onNavigateBack = onBackClick,
                onSearch = ::openVoiceSearch,
                onGoHome = {
                    val launchIntent =
                        Intent().setClassName(context, "dev.spatialfin.MainActivity")
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    runCatching { context.startActivity(launchIntent) }
                        .onSuccess { activity?.finish() }
                        .onFailure { voiceFeedback = "Home unavailable" }
                },
                onCloseApp = { activity?.finishAffinity() },
            )
        }

    fun requestVoiceCommand() {
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
        startVoiceCapture(
            voiceService = voiceService,
            commandCoordinator = commandCoordinator,
            player = player,
            viewModel = viewModel,
            uiState = uiState,
            controlsVisible = controlsVisible,
            dispatcher = voiceDispatcher,
            telemetryStore = telemetryStore,
            onResult = { voiceFeedback = it },
            scope = coroutineScope,
        )
    }

    // Auto-hide controls after 10 s during playback
    LaunchedEffect(controlsVisible, hideTimestamp, isPlaying) {
        if (controlsVisible && isPlaying && !isLocked) {
            delay(10_000L)
            controlsVisible = false
        }
    }

    // Immersive environment: blackout during playback, passthrough when paused
    LaunchedEffect(isPlaying) {
        val environment = session.scene.spatialEnvironment
        try {
            environment.preferredSpatialEnvironment = if (isPlaying) {
                SpatialEnvironment.SpatialEnvironmentPreference(skybox = null, geometry = null)
            } else {
                null
            }
        } catch (_: Exception) {}
    }

    LaunchedEffect(voiceControlEnabled) {
        if (voiceControlEnabled) {
            commandCoordinator.initialize()
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
        pinchDetector?.pinchEvents?.collect { event ->
            when (event) {
                SecondaryHandPinchDetector.PinchEvent.STARTED -> requestVoiceCommand()
                SecondaryHandPinchDetector.PinchEvent.ENDED -> voiceService.stopListening()
            }
        }
    }

    LaunchedEffect(voiceState) {
        if (voiceState == VoiceState.LISTENING) {
            player.volume = 0.2f
        } else {
            player.volume = 1.0f
        }
    }

    LaunchedEffect(voiceFeedback) {
        if (voiceFeedback != null) {
            delay(2_000L)
            voiceFeedback = null
            voiceService.resetState()
        }
    }

    // Subtitle cue listener
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onCues(cueGroup: CueGroup) {
                currentCues = cueGroup.cues
                if (cueGroup.cues.isNotEmpty()) {
                    // Log cue summary only — individual cue spam floods logcat when the
                    // fallback renderer receives raw ASS drawing commands or karaoke chars.
                    // If useLibass is working, this callback should produce no visible subtitles.
                    val first = cueGroup.cues[0].text?.take(60)
                    Timber.d("subtitle: fallback onCues count=%d first='%s'", cueGroup.cues.size, first)
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
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // Initialize libass when track info becomes available
    LaunchedEffect(viewModel.player.currentTracks, currentStereoMode) {
        val stereoPlayback = currentStereoMode == "sbs" || currentStereoMode == "top_bottom"
        useLibass = !stereoPlayback &&
            libassRenderer != null &&
            LibassSubtitleHelper.shouldUseLibass(viewModel.player, emptyList(), libassUsagePref)
        Timber.i(
            "subtitle: useLibass=%b (renderer=%b pref=%s stereoMode=%s)",
            useLibass,
            libassRenderer != null,
            libassUsagePref,
            currentStereoMode,
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
            viewModel.updateCurrentSegment()
            delay(500)
        }
    }

    // --- SceneCore video entity ---
    val rootEntity = remember { mutableStateOf<GroupEntity?>(null) }
    val videoEntity = remember { mutableStateOf<SurfaceEntity?>(null) }
    // Separate entity for the cast panel — deliberately has NO MovableComponent so
    // scroll gestures inside the panel are never intercepted by the video's grab handle.
    val castPanelEntity = remember { mutableStateOf<GroupEntity?>(null) }
    val movableComponent = remember { mutableStateOf<androidx.xr.scenecore.MovableComponent?>(null) }

    LaunchedEffect(controlsVisible, rootEntity.value) {
        val root = rootEntity.value ?: return@LaunchedEffect
        if (controlsVisible) {
            if (movableComponent.value == null) {
                val movable = androidx.xr.scenecore.MovableComponent.createSystemMovable(session)
                root.addComponent(movable)
                movableComponent.value = movable
            }
        } else {
            movableComponent.value?.let {
                root.removeComponent(it)
                movableComponent.value = null
            }
        }
    }

    DisposableEffect(session) {
        val initialShape = SurfaceEntity.Shape.Quad(FloatSize2d(10.0f, 5.625f))
        try {
            // Place root at the origin. UIs will be offset manually relative to the root.
            val root = GroupEntity.create(session, "PlayerRoot", Pose(Vector3(0f, 0f, 0f), Quaternion.Identity))
            rootEntity.value = root

            // Place the video at -5.0f relative to the root.
            val entity = SurfaceEntity.create(
                session = session,
                pose = Pose(Vector3(0f, 0f, -5.0f), Quaternion.Identity),
                shape = initialShape,
                stereoMode = mapStereoMode(currentStereoMode) ?: SurfaceEntity.StereoMode.MONO,
            ).apply {
                mediaBlendingMode = SurfaceEntity.MediaBlendingMode.OPAQUE
            }
            root.addChild(entity)
            videoEntity.value = entity

            // Cast panel root: centered, 3.5 m in front, no movable component.
            val castRoot = GroupEntity.create(session, "CastPanelRoot", Pose(Vector3(0f, 0f, -3.5f), Quaternion.Identity))
            castPanelEntity.value = castRoot
        } catch (_: Exception) {}

        onDispose {
            voiceService.destroy()
            commandCoordinator.destroy()
            videoEntity.value?.dispose()
            videoEntity.value = null
            rootEntity.value?.dispose()
            rootEntity.value = null
            castPanelEntity.value?.dispose()
            castPanelEntity.value = null
            try { session.scene.spatialEnvironment.preferredSpatialEnvironment = null } catch (_: Exception) {}
        }
    }

            var playerInitialized by remember { mutableStateOf(false) }

            // Safety net: re-attach the surface whenever the player instance changes (e.g. if the
            // player is replaced for reasons outside the onPlayerReplaced callback path). Does not
            // run for dimension changes — videoWidth/videoHeight are not keys here.
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

        // 2. Apply the correct shape and pose to the entity.
        val entity = videoEntity.value ?: return@LaunchedEffect
        // Flat mode — simple quad with entity-level stereo mode.
        entity.shape = SurfaceEntity.Shape.Quad(FloatSize2d(videoWidth, videoHeight))
        entity.stereoMode = mapStereoMode(currentStereoMode) ?: SurfaceEntity.StereoMode.MONO
        entity.setPose(Pose(Vector3(0f, 0f, -5.0f), Quaternion.Identity))
    }

    // --- Layout calculations ---
    val videoDepth = 5.0f
    val uiDepth = 1.75f
    val uiScaleFactor = uiDepth / videoDepth
    val subtitleScaleFactor = uiDepth / videoDepth
    val scaledVideoWidthDp = videoWidth * subtitleScaleFactor * 1000f
    val scaledVideoHeightDp = videoHeight * subtitleScaleFactor * 1000f
    val subtitlePanelWidthDp = scaledVideoWidthDp
    val subtitlePanelHeightDp = scaledVideoHeightDp
    val finalSubtitleSize = xrSubtitleSize * (subtitlePanelHeightDp / 600f).coerceAtLeast(1f)
    val controlsReferenceHeightDp = videoHeight * uiScaleFactor * 1000f
    // Controls sit further below the video surface to prevent overlap
    val controlsPanelY = -(controlsReferenceHeightDp / 2f + 1400f)

    val subtitlePanelZDp = -uiDepth * 1000f
    // XR guide recommended spawn depth: 1.75 m (-1750 dp) from user.
    val uiAnchorZDp = -uiDepth * 1000f

    LaunchedEffect(subtitlePanelWidthDp, subtitlePanelHeightDp, density.density, player.videoSize) {
        val renderWidth = (subtitlePanelWidthDp * density.density).toInt().coerceIn(1280, 7680)
        val renderHeight = (subtitlePanelHeightDp * density.density).toInt().coerceIn(720, 4320)
        val videoW = player.videoSize.width
        val videoH = player.videoSize.height
        // Only resize when we have valid video dimensions so storage size is set correctly.
        // Without storage size, libass misscales fonts authored at a different resolution.
        if (videoW > 0 && videoH > 0) {
            libassRenderer?.resize(renderWidth, renderHeight, videoW, videoH)
        }
    }

    // Controls at same depth as UI anchor.
    val controlsZDp = -1750f

    Subspace {
        val root = rootEntity.value
        if (root != null) {
            SceneCoreEntity(factory = { root }, modifier = SubspaceModifier) {
        // ── Subtitle Panel ──────────────────────────────────────────────────────────
        // Spans the full projected video area so PGS cue positions map correctly.
        // In curved mode the panel sits at the front of the cylinder (uiAnchorZDp already
        // incorporates the depth; uiExtraZDp is 0).
        if (useLibass) {
            val currentBitmap = libassBitmap
            if (hasLibassContent && currentBitmap != null) {
                SpatialPanel(
                    modifier = SubspaceModifier
                        .width(subtitlePanelWidthDp.dp)
                        .height(subtitlePanelHeightDp.dp)
                        .offset(x = 0.dp, y = 0.dp, z = (subtitlePanelZDp + 50f).dp),
                ) {
                    key(libassFrameVersion) {
                        Image(
                            painter = BitmapPainter(currentBitmap.asImageBitmap(), filterQuality = FilterQuality.High),
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
                            // 4 % bottom padding keeps text off the very edge of the panel,
                            // matching the TV / cinema convention without pushing it to the middle.
                            setBottomPaddingFraction(0.04f)
                            setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, finalSubtitleSize)
                            // Apply user-selected style — do NOT call setUserDefaultStyle() here:
                            // that method overrides our fixed text size with a fractional one and
                            // forces the OS system caption style (black background box).
                            setStyle(captionStyle)
                        }
                    },
                    update = { view ->
                        // Re-apply style and size on every update to prevent the OS from
                        // silently re-applying the system caption style (GEMINI.md guidance).
                        view.setStyle(captionStyle)
                        view.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, finalSubtitleSize)
                        // Selective bottom-anchoring for the IMAX experience:
                        //
                        //  • Unpositioned cues (SRT/VTT, lineType == TYPE_UNSET):
                        //    left as-is — SubtitleView places them at the bottom via
                        //    setBottomPaddingFraction. ✓
                        //
                        //  • Top-positioned cues (line < 0.15, e.g. signs/credits at top):
                        //    left as-is — respect intentional top placement. ✓
                        //
                        //  • Bottom-positioned cues (line > 0.85, e.g. PGS for movies):
                        //    left as-is — already near the bottom, movies display correctly. ✓
                        //
                        //  • Middle-zone cues (0.15 ≤ line < 0.85, LINE_TYPE_FRACTION):
                        //    Media3's ASS/SSA decoder can produce these for anime dialogue
                        //    that should visually appear at the bottom.  Push them down.
                        val processedCues = currentCues.map { cue ->
                            val isMidZone = cue.lineType == Cue.LINE_TYPE_FRACTION &&
                                cue.line >= 0.15f && cue.line < 0.85f
                            if (isMidZone) {
                                cue.buildUpon()
                                    .setLine(0.95f, Cue.LINE_TYPE_FRACTION)
                                    .setLineAnchor(Cue.ANCHOR_TYPE_END)
                                    .build()
                            } else {
                                cue
                            }
                        }
                        view.setCues(processedCues)
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // ── Control Panel ────────────────────────────────────────────────────────────
        // Floats below the video.  Secondary controls are in an Orbiter on the right
        // so the main panel stays uncluttered (IMAX principle: screen first, UI second).
        SpatialPanel(
            modifier = SubspaceModifier
                .width(1400.dp)
                .height(600.dp)
                .offset(x = 0.dp, y = controlsPanelY.dp, z = controlsZDp.dp),
            resizePolicy = ResizePolicy(),
        ) {
            // ── Orbiter: secondary controls (right edge, hidden when locked/controls hidden) ──
            if (controlsVisible && !isLocked) {
                Orbiter(
                    position = ContentEdge.End,
                    alignment = Alignment.CenterVertically,
                    offset = 20.dp,
                ) {
                    SecondaryControlsOrbiter(
                        onAudioClick = { activeDialog = "audio"; resetAutoHide() },
                        onSubtitleClick = { activeDialog = "subtitle"; resetAutoHide() },
                        onSpeedClick = { activeDialog = "speed"; resetAutoHide() },
                        onQualityClick = { activeDialog = "quality"; resetAutoHide() },
                        onCastCrewClick = { activeDialog = "cast_crew"; resetAutoHide() },
                        onVoiceClick = {
                            requestVoiceCommand()
                            resetAutoHide()
                        },
                        voiceControlEnabled = voiceControlEnabled,
                        voiceAvailable = voiceService.isAvailable(),
                        voiceState = voiceState,
                    )
                }
            }

            // ── Main control content ──────────────────────────────────────────────
            // When controls are hidden the whole panel area is tappable.  A faint glass
            // background makes the tap target discoverable without cluttering the view.
            Box(modifier = Modifier.fillMaxSize()) {
                AnimatedVisibility(
                    visible = controlsVisible,
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
                        currentStereoMode = currentStereoMode,
                        isLocked = isLocked,
                        spatialAudioAvailable = spatialAudioAvailable,
                        onStereoModeChange = { currentStereoMode = it },
                        onLockToggle = { isLocked = !isLocked },
                        onHideClick = { controlsVisible = false },
                        onBackClick = onBackClick,
                        resetAutoHide = { resetAutoHide() },
                    )
                }

                if (!controlsVisible) {
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
                                onClick = { controlsVisible = true; resetAutoHide() },
                            ),
                    )
                }

                VoiceControlOverlay(
                    state = voiceState,
                    partialTranscript = partialTranscript,
                    feedback = voiceFeedback,
                )
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
            if (activeDialog == "voice_search") {
                SpatialDialog(onDismissRequest = { activeDialog = null }) {
                    VoiceSearchDialogContent(
                        query = voiceSearchQuery,
                        loading = voiceSearchLoading,
                        error = voiceSearchError,
                        results = voiceSearchResults,
                        currentItemTitle = uiState.currentItemTitle,
                        onPlayResult = {
                            onLaunchSearchResult(it)
                            activeDialog = null
                        },
                        onDismiss = { activeDialog = null },
                    )
                }
            }
        }

        // ── Contextual Skip Panel ────────────────────────────────────────────────
        uiState.currentSegment?.let { segment ->
            SpatialPanel(
                modifier = SubspaceModifier
                    .width(360.dp)
                    .height(120.dp)
                    .offset(x = 950.dp, y = controlsPanelY.dp, z = controlsZDp.dp),
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
                            modifier = Modifier.size(48.dp),
                        )
                        Spacer(Modifier.width(16.dp))
                        Text(
                            text = stringResource(uiState.currentSkipButtonStringRes),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }

        // ── Next Episode Panel ───────────────────────────────────────────────────
        // Floats to the LEFT of the control panel during the last 2 minutes of an episode.
        if (showNextEpisodePanel) {
            SpatialPanel(
                modifier = SubspaceModifier
                    .width(700.dp)
                    .height(440.dp)
                    .offset(x = (-1150).dp, y = controlsPanelY.dp, z = controlsZDp.dp),
            ) {
                NextEpisodePanelContent(
                    nextEpisode = uiState.nextEpisode!!,
                    onPlayNext = {
                        player.seekToNextMediaItem()
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
        val shouldShowCastPanel = showCastCrewPanel &&
            (uiState.currentPeople.isNotEmpty() || uiState.currentOverview.isNotBlank())
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
    onCastCrewClick: () -> Unit,
    onVoiceClick: () -> Unit,
    voiceControlEnabled: Boolean,
    voiceAvailable: Boolean,
    voiceState: VoiceState,
) {
    Surface(
        shape = RoundedCornerShape(40.dp),
        color = Color.Black.copy(alpha = 0.9f),
        tonalElevation = 4.dp,
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            IconButton(onClick = onAudioClick, modifier = Modifier.size(80.dp)) {
                Icon(
                    painterResource(CoreR.drawable.ic_speaker),
                    contentDescription = "Audio track",
                    tint = Color.White,
                    modifier = Modifier.size(48.dp),
                )
            }
            IconButton(onClick = onSubtitleClick, modifier = Modifier.size(80.dp)) {
                Icon(
                    painterResource(CoreR.drawable.ic_closed_caption),
                    contentDescription = "Subtitle track",
                    tint = Color.White,
                    modifier = Modifier.size(48.dp),
                )
            }
            IconButton(onClick = onSpeedClick, modifier = Modifier.size(80.dp)) {
                Icon(
                    painterResource(CoreR.drawable.ic_gauge),
                    contentDescription = "Playback speed",
                    tint = Color.White,
                    modifier = Modifier.size(48.dp),
                )
            }
            IconButton(onClick = onQualityClick, modifier = Modifier.size(80.dp)) {
                Icon(
                    painterResource(CoreR.drawable.ic_sparkles),
                    contentDescription = "Playback quality",
                    tint = Color.White,
                    modifier = Modifier.size(48.dp),
                )
            }
            IconButton(onClick = onCastCrewClick, modifier = Modifier.size(80.dp)) {
                Icon(
                    painterResource(CoreR.drawable.ic_user),
                    contentDescription = "Cast & crew",
                    tint = Color.White,
                    modifier = Modifier.size(48.dp),
                )
            }
            if (voiceControlEnabled) {
                IconButton(onClick = onVoiceClick, modifier = Modifier.size(80.dp)) {
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
                        modifier = Modifier.size(48.dp),
                    )
                }
            }
        }
    }
}

private fun startVoiceCapture(
    voiceService: SpatialVoiceService,
    commandCoordinator: SpatialCommandCoordinator,
    player: Player,
    viewModel: PlayerViewModel,
    uiState: PlayerViewModel.UiState,
    controlsVisible: Boolean,
    dispatcher: VoiceCommandDispatcher? = null,
    telemetryStore: VoiceTelemetryStore,
    onResult: (String) -> Unit,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val startedAtMs = System.currentTimeMillis()
    voiceService.startListening { transcript ->
        scope.launch {
            val snapshot =
                PlayerStateSnapshot(
                    isPlaying = player.isPlaying,
                    positionSeconds = player.currentPosition / 1_000L,
                    durationSeconds = player.duration.coerceAtLeast(0L) / 1_000L,
                    controlsVisible = controlsVisible,
                    currentItemTitle = uiState.currentItemTitle,
                    currentSegmentType = uiState.currentSegment?.type?.toString(),
                    currentChapterName = currentChapterName(uiState, player.currentPosition),
                    nextEpisodeTitle = uiState.nextEpisode?.name,
                    audioTrackNames = trackNames(player, C.TRACK_TYPE_AUDIO),
                    subtitleTrackNames = trackNames(player, C.TRACK_TYPE_TEXT),
                    chapterNames = uiState.currentChapters.mapNotNull { it.name },
                    currentAudioTrack = selectedTrackName(player, C.TRACK_TYPE_AUDIO),
                    currentSubtitleTrack = selectedTrackName(player, C.TRACK_TYPE_TEXT),
                )
            val parseResult = commandCoordinator.parse(transcript, snapshot)
            val feedback = dispatchVoiceParseResult(dispatcher, parseResult)
            telemetryStore.record(
                VoiceTelemetryEntry(
                    transcript = transcript,
                    action = parseResult.action::class.simpleName ?: "Unknown",
                    strategy = parseResult.strategy.name,
                    latencyMs = System.currentTimeMillis() - startedAtMs,
                    success = parseResult.action !is dev.jdtech.jellyfin.player.xr.voice.XrPlayerAction.Unrecognized,
                )
            )
            onResult(feedback)
        }
    }
}

private fun dispatchVoiceParseResult(
    dispatcher: VoiceCommandDispatcher?,
    parseResult: VoiceParseResult,
): String {
    return dispatcher?.dispatch(parseResult.action) ?: "Voice action unavailable"
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
            Text(
                text = "Voice Search",
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
            )
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
    currentStereoMode: String,
    isLocked: Boolean,
    spatialAudioAvailable: Boolean,
    onStereoModeChange: (String) -> Unit,
    onLockToggle: () -> Unit,
    onHideClick: () -> Unit,
    onBackClick: () -> Unit,
    resetAutoHide: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(48.dp),
        color = Color.Black.copy(alpha = 0.9f),
        tonalElevation = 4.dp,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(modifier = Modifier.padding(40.dp)) {
            // ── Top row: back / title / indicator / lock / hide ──
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                IconButton(onClick = onBackClick, modifier = Modifier.size(80.dp)) {
                    Icon(
                        painter = painterResource(CoreR.drawable.ic_arrow_left),
                        contentDescription = "Back",
                        tint = Color.White,
                        modifier = Modifier.size(48.dp),
                    )
                }
                Spacer(Modifier.width(24.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = uiState.currentItemTitle,
                        style = MaterialTheme.typography.displaySmall,
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
                        style = MaterialTheme.typography.headlineSmall,
                        color = if (spatialAudioAvailable && !isLocked)
                            Color(0xFF4FC3F7).copy(alpha = 0.8f)
                        else
                            Color.White.copy(alpha = 0.6f),
                    )
                }
                if (!isLocked) {
                    IconButton(onClick = { onHideClick() }, modifier = Modifier.size(80.dp)) {
                        Icon(
                            painterResource(CoreR.drawable.ic_eye_off),
                            contentDescription = "Hide Controls",
                            tint = Color.White,
                            modifier = Modifier.size(48.dp),
                        )
                    }
                }
                IconButton(
                    onClick = { onLockToggle(); resetAutoHide() },
                    modifier = Modifier.size(80.dp),
                ) {
                    Icon(
                        painter = painterResource(
                            if (isLocked) CoreR.drawable.ic_lock else CoreR.drawable.ic_unlock,
                        ),
                        contentDescription = "Lock Controls",
                        tint = if (isLocked) Color.Red else Color.White,
                        modifier = Modifier.size(48.dp),
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

            Spacer(Modifier.height(32.dp))

            // ── Playback controls ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!isLocked) {
                    IconButton(
                        onClick = { player.seekBack(); resetAutoHide() },
                        modifier = Modifier.size(96.dp),
                    ) {
                        Icon(
                            painterResource(CoreR.drawable.ic_rewind),
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(64.dp),
                        )
                    }
                    Spacer(Modifier.width(48.dp))
                }

                FilledIconButton(
                    onClick = {
                        if (isPlaying) player.pause() else player.play()
                        resetAutoHide()
                    },
                    modifier = Modifier.size(120.dp),
                ) {
                    Icon(
                        painter = painterResource(
                            if (isPlaying) CoreR.drawable.ic_pause else CoreR.drawable.ic_play,
                        ),
                        contentDescription = null,
                        modifier = Modifier.size(72.dp),
                    )
                }

                if (!isLocked) {
                    Spacer(Modifier.width(48.dp))
                    IconButton(
                        onClick = { player.seekForward(); resetAutoHide() },
                        modifier = Modifier.size(96.dp),
                    ) {
                        Icon(
                            painterResource(CoreR.drawable.ic_fast_forward),
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(64.dp),
                        )
                    }
                    Spacer(Modifier.width(80.dp))
                    // 2D / 3D stereo mode toggle
                    TextButton(
                        onClick = {
                            val modes = listOf("mono", "sbs", "top_bottom")
                            val next = modes[(modes.indexOf(currentStereoMode) + 1) % modes.size]
                            onStereoModeChange(next)
                            resetAutoHide()
                        },
                        modifier = Modifier.height(80.dp),
                    ) {
                        Icon(
                            painterResource(CoreR.drawable.ic_3d),
                            contentDescription = null,
                            tint = if (currentStereoMode != "mono") Color(0xFF4FC3F7) else Color.White,
                            modifier = Modifier.size(48.dp),
                        )
                        Spacer(Modifier.width(16.dp))
                        Text(
                            stereoModeDisplayName(currentStereoMode),
                            style = MaterialTheme.typography.headlineSmall,
                            color = if (currentStereoMode != "mono") Color(0xFF4FC3F7) else Color.White,
                        )
                    }
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
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text("CLOSE", style = MaterialTheme.typography.labelLarge)
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
                style = MaterialTheme.typography.headlineSmall,
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
                            .height(20.dp)
                            .align(Alignment.Center),
                    ) {
                        // The slider track occupies the width minus the horizontal padding on
                        // each side (same value as the Slider's own padding modifier below).
                        val padPx = sliderHPad.toPx()
                        val trackWidth = size.width - 2 * padPx
                        val markerH = 24f
                        val markerW = 6f
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
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White.copy(alpha = 0.8f),
            )
        }
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────────

private fun mapStereoMode(mode: String): SurfaceEntity.StereoMode? = when (mode) {
    "sbs" -> SurfaceEntity.StereoMode.SIDE_BY_SIDE
    "top_bottom" -> SurfaceEntity.StereoMode.TOP_BOTTOM
    else -> null
}

private fun stereoModeDisplayName(mode: String): String = when (mode) {
    "sbs" -> "3D SBS"
    "top_bottom" -> "3D T/B"
    else -> "2D"
}

private fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) String.format("%d:%02d:%02d", hours, minutes, seconds)
    else String.format("%d:%02d", minutes, seconds)
}
