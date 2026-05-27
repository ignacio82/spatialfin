package dev.jdtech.jellyfin.player.xr

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.TrackSelectionDialogBuilder
import androidx.xr.compose.spatial.Subspace
import androidx.xr.compose.spatial.Orbiter
import androidx.xr.compose.spatial.OrbiterAnchorPoint
import androidx.xr.compose.spatial.OrbiterDefaults
import androidx.xr.compose.subspace.SceneCoreEntity
import androidx.xr.compose.subspace.SpatialPanel
import androidx.xr.compose.subspace.layout.SubspaceModifier
import androidx.xr.compose.subspace.layout.height
import androidx.xr.compose.subspace.layout.offset
import androidx.xr.compose.subspace.layout.width
import androidx.xr.compose.unit.DpVolumeOffset
import androidx.xr.runtime.Session
import androidx.xr.runtime.math.FloatSize2d
import androidx.xr.runtime.math.FloatSize3d
import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Quaternion
import androidx.xr.runtime.math.Vector3
import androidx.xr.scenecore.Entity
import androidx.xr.scenecore.EntityMoveListener
import androidx.xr.scenecore.MovableComponent
import androidx.xr.scenecore.SpatialEnvironment
import androidx.xr.scenecore.SurfaceEntity
import androidx.xr.scenecore.scene
import coil3.compose.AsyncImage
import dev.jdtech.jellyfin.fcast.receiver.ExternalStreamRequest
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.player.local.presentation.PlayerViewModel
import dev.jdtech.jellyfin.player.xr.voice.VoiceState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber

// Use the same base dimensions as the native player.
private const val DEFAULT_VIDEO_WIDTH_METERS = 8.0f
private const val DEFAULT_VIDEO_HEIGHT_METERS = 4.5f
private const val VIDEO_MOVE_AFFORDANCE_MARGIN_METERS = 0.40f
private const val VIDEO_MOVE_AFFORDANCE_DEPTH_METERS = 0.01f

private fun inboundVideoMoveAffordanceBounds(videoWidth: Float, videoHeight: Float): FloatSize3d =
    FloatSize3d(
        width = (videoWidth + VIDEO_MOVE_AFFORDANCE_MARGIN_METERS * 2f).coerceAtLeast(1f),
        height = (videoHeight + VIDEO_MOVE_AFFORDANCE_MARGIN_METERS * 2f).coerceAtLeast(1f),
        depth = VIDEO_MOVE_AFFORDANCE_DEPTH_METERS,
    )

/**
 * External-cast theater UI. It intentionally exposes no library, recommendation, SyncPlay,
 * favorites, or progress-writing controls.
 */
@OptIn(UnstableApi::class)
@Composable
internal fun XrFCastInboundPlayerScreen(
    session: Session,
    player: ExoPlayer,
    requestState: StateFlow<ExternalStreamRequest?>,
    cuesState: StateFlow<List<androidx.media3.common.text.Cue>>,
    libassBitmapState: StateFlow<Bitmap?>,
    libassFrameVersionState: StateFlow<Int>,
    libassEnabled: Boolean,
    preferences: AppPreferences,
    onSurfaceAttached: () -> Unit,
    onExit: () -> Unit,
    onSubtitleResize: (Int, Int, Int, Int) -> Unit,
) {
    val context = LocalContext.current
    val request by requestState.collectAsState()
    val cues by cuesState.collectAsState()
    val libassBitmap by libassBitmapState.collectAsState()
    val libassFrameVersion by libassFrameVersionState.collectAsState()
    var videoEntity by remember { mutableStateOf<SurfaceEntity?>(null) }
    var uiRoot by remember { mutableStateOf<Entity?>(null) }
    var subtitleRoot by remember { mutableStateOf<Entity?>(null) }
    var movableComponent by remember { mutableStateOf<MovableComponent?>(null) }
    var lastPose by remember { mutableStateOf(centeredInboundPose(preferences)) }
    var videoSize by remember { mutableStateOf(VideoSize.UNKNOWN) }
    // Match native player: use DEFAULT_VIDEO_PANEL_SCALE as baseline, then apply user pref
    var panelScale by remember {
        mutableFloatStateOf(
            preferences.getValue(preferences.xrPlayerPanelScale)
                .takeIf { it in 0.2f..5f }
                ?: DEFAULT_VIDEO_PANEL_SCALE
        )
    }
    var videoDepth by remember { mutableFloatStateOf(extractVideoDepth(lastPose, VIDEO_DEPTH_METERS)) }
    var videoWidth by remember { mutableFloatStateOf(DEFAULT_VIDEO_WIDTH_METERS) }
    var videoHeight by remember { mutableFloatStateOf(DEFAULT_VIDEO_HEIGHT_METERS) }
    var playing by remember { mutableStateOf(player.isPlaying) }
    var positionMs by remember { mutableLongStateOf(player.currentPosition) }
    var durationMs by remember { mutableLongStateOf(player.duration.coerceAtLeast(0L)) }
    var volume by remember { mutableFloatStateOf(player.volume) }
    var speed by remember { mutableFloatStateOf(player.playbackParameters.speed) }

    var controlsVisible by remember { mutableStateOf(true) }
    var hideTimestamp by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var controlsInputActive by remember { mutableStateOf(false) }
    var startupPoseGuardActive by remember { mutableStateOf(true) }

    val environment = session.scene.spatialEnvironment

    LaunchedEffect(playing) {
        if (!playing) {
            try { environment.preferredSpatialEnvironment = null } catch (_: Exception) {}
        } else {
            try {
                environment.preferredSpatialEnvironment =
                    SpatialEnvironment.SpatialEnvironmentPreference(skybox = null, geometry = null)
            } catch (_: Exception) {}
        }
    }

    LaunchedEffect(videoEntity, uiRoot, subtitleRoot, startupPoseGuardActive) {
        if (!startupPoseGuardActive) return@LaunchedEffect
        val surface = videoEntity ?: return@LaunchedEffect
        val controlsRoot = uiRoot ?: return@LaunchedEffect
        val subtitleRootEntity = subtitleRoot ?: return@LaunchedEffect

        val guardPassDelays = listOf(0L, 250L, 1000L, 2500L, 5000L, 8000L)
        for (delayMs in guardPassDelays) {
            if (!startupPoseGuardActive) break
            if (delayMs > 0L) delay(delayMs)
            if (!startupPoseGuardActive) break
            
            val pose = lastPose
            runCatching {
                surface.setPose(pose)
                surface.setScale(panelScale)
            }
            syncProjectedOverlayRoots(
                videoPose = pose,
                videoScale = panelScale,
                uiRoot = controlsRoot,
                subtitleRoot = subtitleRootEntity,
                depthScale = UI_DEPTH_METERS / videoDepth,
            )
        }
        startupPoseGuardActive = false
    }

    LaunchedEffect(controlsVisible, hideTimestamp, playing, controlsInputActive) {
        if (controlsVisible && playing && !controlsInputActive) {
            val wait = (hideTimestamp + 5000L) - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            controlsVisible = false
        }
    }

    fun resetAutoHide() {
        hideTimestamp = System.currentTimeMillis()
        controlsVisible = true
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onPlaybackStateChanged(playbackState: Int) {
                durationMs = player.duration.coerceAtLeast(0L)
            }
            override fun onVideoSizeChanged(size: VideoSize) { videoSize = size }
            override fun onVolumeChanged(value: Float) { volume = value }
            override fun onPlaybackParametersChanged(params: androidx.media3.common.PlaybackParameters) {
                speed = params.speed
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    LaunchedEffect(player) {
        while (true) {
            positionMs = player.currentPosition
            durationMs = player.duration.coerceAtLeast(0L)
            delay(250L)
        }
    }

    // Create 3 root entities like the native player: video root, UI root, subtitle root.
    // Video lives at VIDEO_DEPTH_METERS, UI and subtitles at UI_DEPTH_METERS (projected).
    DisposableEffect(session) {
        val pose = lastPose
        val overlayProjectionScale = UI_DEPTH_METERS / videoDepth
        val projectedOverlayPose = projectPoseFromOrigin(pose, overlayProjectionScale)
        val activitySpace = session.scene.activitySpace

        val controlsRoot = Entity.create(
            session,
            "FCastInboundUiRoot",
            projectedOverlayPose,
            activitySpace,
        ).apply { setScale(panelScale) }

        val subtitleRootEntity = Entity.create(
            session,
            "FCastInboundSubtitleRoot",
            projectedOverlayPose,
            activitySpace,
        ).apply { setScale(panelScale) }

        val surface = SurfaceEntity.create(
            session,
            pose,
            SurfaceEntity.Shape.Quad(FloatSize2d(DEFAULT_VIDEO_WIDTH_METERS, DEFAULT_VIDEO_HEIGHT_METERS)),
            SurfaceEntity.StereoMode.MONO,
            SurfaceEntity.MediaBlendingMode.OPAQUE,
            SurfaceEntity.SuperSampling.NONE,
            SurfaceEntity.SurfaceProtection.NONE,
            activitySpace,
        ).apply { setScale(panelScale) }

        // Use createCustomMovable like the native player (NOT createSystemMovable).
        // scaleWithDistance = false — never let a move gesture change apparent size.
        var grabDepth = videoDepth // depth locked at the moment the user starts a grab
        val movable = MovableComponent.createCustomMovable(
            session,
            false, // scaleWithDistance = false, matching native player
            ContextCompat.getMainExecutor(context),
            object : EntityMoveListener {
                override fun onMoveStart(
                    entity: Entity,
                    initialInputRay: androidx.xr.runtime.math.Ray,
                    initialPose: Pose,
                    initialScale: Float,
                    initialParent: Entity,
                ) {
                    startupPoseGuardActive = false
                    grabDepth = extractVideoDepth(initialPose, videoDepth)
                    val translationOnlyPose = Pose(initialPose.translation, Quaternion.Identity)
                    runCatching {
                        entity.setPose(translationOnlyPose)
                        entity.setScale(panelScale)
                    }
                    androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
                        lastPose = translationOnlyPose
                    }
                    syncProjectedOverlayRoots(
                        videoPose = translationOnlyPose,
                        videoScale = panelScale,
                        uiRoot = controlsRoot,
                        subtitleRoot = subtitleRootEntity,
                        depthScale = UI_DEPTH_METERS / grabDepth,
                    )
                }

                override fun onMoveUpdate(
                    entity: Entity,
                    currentInputRay: androidx.xr.runtime.math.Ray,
                    currentPose: Pose,
                    currentScale: Float,
                ) {
                    val direction = currentInputRay.direction
                    val length = kotlin.math.sqrt(
                        direction.x * direction.x + direction.y * direction.y + direction.z * direction.z,
                    ).coerceAtLeast(0.001f)
                    val updated = Pose(
                        Vector3(
                            direction.x / length * grabDepth,
                            direction.y / length * grabDepth,
                            direction.z / length * grabDepth,
                        ),
                        Quaternion.Identity,
                    )
                    runCatching {
                        entity.setPose(updated)
                        entity.setScale(panelScale)
                    }
                    androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
                        lastPose = updated
                    }
                    syncProjectedOverlayRoots(
                        videoPose = updated,
                        videoScale = panelScale,
                        uiRoot = controlsRoot,
                        subtitleRoot = subtitleRootEntity,
                        depthScale = UI_DEPTH_METERS / grabDepth,
                    )
                }

                override fun onMoveEnd(
                    entity: Entity,
                    finalInputRay: androidx.xr.runtime.math.Ray,
                    finalPose: Pose,
                    finalScale: Float,
                    updatedParent: Entity?,
                ) {
                    val savedPose = Pose(
                        lastPose.translation,
                        Quaternion.Identity,
                    )
                    val effectiveDepth = extractVideoDepth(savedPose, videoDepth)
                    runCatching {
                        entity.setPose(savedPose)
                        entity.setScale(panelScale)
                    }
                    androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
                        lastPose = savedPose
                        videoDepth = effectiveDepth
                    }
                    syncProjectedOverlayRoots(
                        videoPose = savedPose,
                        videoScale = panelScale,
                        uiRoot = controlsRoot,
                        subtitleRoot = subtitleRootEntity,
                        depthScale = UI_DEPTH_METERS / effectiveDepth,
                    )
                    saveInboundPose(preferences, savedPose)
                }
            },
        )
        surface.addComponent(movable)
        // Set movable size to match the native player's affordance bounds
        movable.size = inboundVideoMoveAffordanceBounds(DEFAULT_VIDEO_WIDTH_METERS, DEFAULT_VIDEO_HEIGHT_METERS)
        movableComponent = movable

        surface.setPose(pose)
        controlsRoot.setPose(projectedOverlayPose)
        subtitleRootEntity.setPose(projectedOverlayPose)
        player.setVideoSurface(surface.getSurface())
        uiRoot = controlsRoot
        subtitleRoot = subtitleRootEntity
        videoEntity = surface
        onSurfaceAttached()
        Timber.tag("XrFCastInbound").i("Created direct immersive inbound SurfaceEntity")

        onDispose {
            runCatching { player.clearVideoSurface() }
            runCatching { surface.parent = null }
            runCatching { controlsRoot.parent = null }
            runCatching { subtitleRootEntity.parent = null }
            videoEntity = null
            uiRoot = null
            subtitleRoot = null
            movableComponent = null
            Timber.tag("XrFCastInbound").i("Detached immersive inbound SurfaceEntity")
        }
    }

    // Update video shape on video size change (same logic as native player)
    LaunchedEffect(videoSize, videoEntity) {
        val entity = videoEntity ?: return@LaunchedEffect
        val ratio = if (videoSize.width > 0 && videoSize.height > 0) {
            videoSize.width.toFloat() / videoSize.height.toFloat()
        } else {
            16f / 9f
        }
        videoWidth = DEFAULT_VIDEO_WIDTH_METERS
        videoHeight = videoWidth / ratio
        entity.shape = SurfaceEntity.Shape.Quad(FloatSize2d(videoWidth, videoHeight))
        movableComponent?.size = inboundVideoMoveAffordanceBounds(videoWidth, videoHeight)
    }

    // Sync scale across all entities and mirror pose continuously
    LaunchedEffect(panelScale, videoEntity, uiRoot, subtitleRoot) {
        val surface = videoEntity ?: return@LaunchedEffect
        val controlsRoot = uiRoot ?: return@LaunchedEffect
        val subtitleRootEntity = subtitleRoot ?: return@LaunchedEffect
        
        while (true) {
            val poseToMirror = lastPose
            val effectiveVideoDepth = extractVideoDepth(poseToMirror, videoDepth)
            
            runCatching {
                surface.setPose(poseToMirror)
                surface.setScale(panelScale)
            }
            syncProjectedOverlayRoots(
                videoPose = poseToMirror,
                videoScale = panelScale,
                uiRoot = controlsRoot,
                subtitleRoot = subtitleRootEntity,
                depthScale = UI_DEPTH_METERS / effectiveVideoDepth,
            )
            preferences.setValue(preferences.xrPlayerPanelScale, panelScale)
            delay(100L)
        }
    }

    LaunchedEffect(
        videoEntity,
        movableComponent,
        controlsInputActive,
    ) {
        val videoSurface = videoEntity ?: return@LaunchedEffect
        val movable = movableComponent ?: return@LaunchedEffect
        val hasMovable = runCatching {
            videoSurface.getComponentsOfType(MovableComponent::class.java).any { it === movable }
        }.getOrDefault(false)

        val allowMovement = !controlsInputActive

        runCatching {
            if (!allowMovement && hasMovable) {
                videoSurface.removeComponent(movable)
                Timber.i("XR FCast inbound screen movement suspended during control input")
            } else if (allowMovement && !hasMovable) {
                videoSurface.addComponent(movable)
                movable.size = inboundVideoMoveAffordanceBounds(videoWidth, videoHeight)
                Timber.i("XR FCast inbound screen movement enabled")
            }
        }.onFailure {
            Timber.w(it, "Unable to update XR FCast inbound screen movement lock state")
        }
    }

    // --- Layout calculations matching native player exactly ---
    val uiScaleFactor = UI_DEPTH_METERS / videoDepth
    val subtitleScaleFactor = UI_DEPTH_METERS / videoDepth
    val scaledVideoWidthDp = videoWidth * subtitleScaleFactor * 1000f
    val scaledVideoHeightDp = videoHeight * subtitleScaleFactor * 1000f
    val subtitlePanelWidthDp = scaledVideoWidthDp
    val subtitlePanelHeightDp = scaledVideoHeightDp
    val subtitlePanelZDp = 0f
    val controlsReferenceHeightDp = videoHeight * uiScaleFactor * 1000f
    val controlsPanelY = -(controlsReferenceHeightDp / 2f + 800f)
    val controlsZDp = 0f

    val density = androidx.compose.ui.platform.LocalDensity.current
    LaunchedEffect(subtitlePanelWidthDp, subtitlePanelHeightDp, density.density, videoSize) {
        val targetW = (subtitlePanelWidthDp * density.density).coerceAtLeast(1f)
        val targetH = (subtitlePanelHeightDp * density.density).coerceAtLeast(1f)
        val maxScale = minOf(7680f / targetW, 4320f / targetH)
        val minScale = maxOf(1280f / targetW, 720f / targetH)
        val scale = when {
            maxScale < 1f -> maxScale
            minScale > 1f -> minScale
            else -> 1f
        }
        val renderWidth = (targetW * scale).toInt().coerceIn(1280, 7680)
        val renderHeight = (targetH * scale).toInt().coerceIn(720, 4320)
        val videoW = videoSize.width
        val videoH = videoSize.height
        val storageW = if (videoW > 0) videoW else renderWidth
        val storageH = if (videoH > 0) videoH else renderHeight
        onSubtitleResize(renderWidth, renderHeight, storageW, storageH)
    }

    MaterialTheme(colorScheme = darkColorScheme()) {
        Subspace {
            // --- Subtitle panel (on its own root, matching native player) ---
            val subRoot = subtitleRoot
            if (subRoot != null) {
                SceneCoreEntity(factory = { subRoot }, modifier = SubspaceModifier) {
                    val hasAss = remember(player.currentTracks) {
                        player.currentTracks.groups.any { group ->
                            group.type == androidx.media3.common.C.TRACK_TYPE_TEXT &&
                            group.isSelected &&
                            group.mediaTrackGroup.length > 0 &&
                            (group.mediaTrackGroup.getFormat(0).sampleMimeType == androidx.media3.common.MimeTypes.TEXT_SSA ||
                             group.mediaTrackGroup.getFormat(0).sampleMimeType == androidx.media3.common.MimeTypes.TEXT_VTT)
                        }
                    }
                    val useLibass = libassEnabled && hasAss

                    if (useLibass) {
                        SpatialPanel(
                            modifier = SubspaceModifier
                                .width(subtitlePanelWidthDp.dp)
                                .height(subtitlePanelHeightDp.dp)
                                .offset(x = 0.dp, y = 0.dp, z = (subtitlePanelZDp + 50f).dp),
                        ) {
                            libassBitmap?.let { bitmap ->
                                key(libassFrameVersion) {
                                    Image(
                                        painter = androidx.compose.ui.graphics.painter.BitmapPainter(bitmap.asImageBitmap(), filterQuality = androidx.compose.ui.graphics.FilterQuality.High),
                                        contentDescription = null,
                                        contentScale = ContentScale.FillBounds,
                                        modifier = Modifier.fillMaxSize(),
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
                            androidx.compose.ui.viewinterop.AndroidView(
                                factory = { context ->
                                    androidx.media3.ui.SubtitleView(context).apply {
                                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                                        setBottomPaddingFraction(0.04f)
                                        setFixedTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 30f)
                                        val style = androidx.media3.ui.CaptionStyleCompat(
                                            android.graphics.Color.WHITE,
                                            android.graphics.Color.TRANSPARENT,
                                            android.graphics.Color.TRANSPARENT,
                                            androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW,
                                            android.graphics.Color.BLACK,
                                            null,
                                        )
                                        setStyle(style)
                                    }
                                },
                                update = { view ->
                                    view.setCues(cues)
                                },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }

            // --- Controls panel (on its own root, matching native player) ---
            val root = uiRoot
            if (root != null) {
                SceneCoreEntity(factory = { root }, modifier = SubspaceModifier) {
                    // Control Panel — same 1800×800 dp dimensions as native player
                    SpatialPanel(
                        modifier = SubspaceModifier
                            .width(1800.dp)
                            .height(800.dp)
                            .offset(x = 0.dp, y = controlsPanelY.dp, z = controlsZDp.dp),
                        resizePolicy = androidx.xr.compose.subspace.ResizePolicy(),
                    ) {
                        if (controlsVisible || !playing) {
                            Orbiter(
                                anchorPoint = OrbiterAnchorPoint.End,
                                offset = DpVolumeOffset(x = 40.dp, z = OrbiterDefaults.Elevation),
                            ) {
                                SecondaryControlsOrbiter(
                                    onAudioClick = { TrackSelectionDialogBuilder(context, "Audio", player, C.TRACK_TYPE_AUDIO).build().show() },
                                    onSubtitleClick = { TrackSelectionDialogBuilder(context, "Subtitles", player, C.TRACK_TYPE_TEXT).build().show() },
                                    onSpeedClick = { },
                                    onQualityClick = { TrackSelectionDialogBuilder(context, "Quality", player, C.TRACK_TYPE_VIDEO).build().show() },
                                    onSyncPlayClick = { },
                                    onCastCrewClick = { },
                                    onVoiceClick = { },
                                    voiceControlEnabled = false,
                                    voiceAvailable = false,
                                    voiceState = VoiceState.IDLE,
                                    syncPlayActive = false,
                                    showSyncPlayButton = false,
                                    showCastCrewButton = false,
                                )
                            }
                        }

                        Box(modifier = Modifier.fillMaxSize()) {
                            AnimatedVisibility(
                                visible = controlsVisible || !playing,
                                enter = fadeIn(),
                                exit = fadeOut(),
                            ) {
                                val mockUiState = PlayerViewModel.UiState(
                                    currentItemTitle = request?.title ?: "Incoming FCast video",
                                    currentSegment = null,
                                    currentSkipButtonStringRes = 0,
                                    currentTrickplay = null,
                                    currentChapters = emptyList(),
                                    currentPeople = emptyList(),
                                    currentOverview = "",
                                    nextEpisode = null,
                                    fileLoaded = true,
                                )
                                ControlPanelUI(
                                    player = player,
                                    uiState = mockUiState,
                                    isPlaying = playing,
                                    currentPosition = positionMs,
                                    duration = durationMs,
                                    isLocked = false,
                                    spatialAudioAvailable = false,
                                    onLockToggle = { },
                                    onControlInputActiveChange = { controlsInputActive = it },
                                    onMoveCloser = {
                                        panelScale = (panelScale + 0.08f).coerceAtMost(2.5f)
                                    },
                                    onMoveFurther = {
                                        panelScale = (panelScale - 0.08f).coerceAtLeast(0.2f)
                                    },
                                    onChaptersClick = { },
                                    onBackClick = onExit,
                                    resetAutoHide = { resetAutoHide() },
                                    showChaptersButton = false,
                                )
                            }

                            if (!controlsVisible && playing) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color.Transparent)
                                        .pointerInput(Unit) {
                                            awaitPointerEventScope {
                                                while (true) {
                                                    val event = awaitPointerEvent()
                                                    if (event.type == androidx.compose.ui.input.pointer.PointerEventType.Enter ||
                                                        event.type == androidx.compose.ui.input.pointer.PointerEventType.Move) {
                                                        resetAutoHide()
                                                    }
                                                }
                                            }
                                        }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun centeredInboundPose(preferences: AppPreferences): Pose {
    val version = preferences.getValue(preferences.xrPlayerPanelPoseVersion)
    if (version == XR_PLAYER_POSE_VERSION_VIDEO_CENTER) {
        val px = preferences.getValue(preferences.xrPlayerPanelX)
        val py = preferences.getValue(preferences.xrPlayerPanelY)
        val pz = preferences.getValue(preferences.xrPlayerPanelZ)
        val rx = preferences.getValue(preferences.xrPlayerPanelRotX)
        val ry = preferences.getValue(preferences.xrPlayerPanelRotY)
        val rz = preferences.getValue(preferences.xrPlayerPanelRotZ)
        val rw = preferences.getValue(preferences.xrPlayerPanelRotW)
        
        // Sanity check to avoid restoring corrupted values
        if (!px.isNaN() && !py.isNaN() && !pz.isNaN() && !rx.isNaN() && !ry.isNaN() && !rz.isNaN() && !rw.isNaN()) {
             // Constrain depth like the native player
             val distance = kotlin.math.sqrt(px * px + py * py + pz * pz)
             if (distance in MIN_RESTORABLE_VIDEO_DEPTH_METERS..15.0f) {
                 return Pose(Vector3(px, py, pz), Quaternion(rx, ry, rz, rw))
             }
        }
    }
    
    return Pose(Vector3(0f, 0f, -VIDEO_DEPTH_METERS), Quaternion.Identity).also {
        saveInboundPose(preferences, it)
    }
}

private fun saveInboundPose(preferences: AppPreferences, pose: Pose) {
    val t = pose.translation
    val r = pose.rotation
    preferences.setValue(preferences.xrPlayerPanelX, t.x)
    preferences.setValue(preferences.xrPlayerPanelY, t.y)
    preferences.setValue(preferences.xrPlayerPanelZ, t.z)
    preferences.setValue(preferences.xrPlayerPanelRotX, r.x)
    preferences.setValue(preferences.xrPlayerPanelRotY, r.y)
    preferences.setValue(preferences.xrPlayerPanelRotZ, r.z)
    preferences.setValue(preferences.xrPlayerPanelRotW, r.w)
    preferences.setValue(preferences.xrPlayerPanelPoseVersion, XR_PLAYER_POSE_VERSION_VIDEO_CENTER)
}
