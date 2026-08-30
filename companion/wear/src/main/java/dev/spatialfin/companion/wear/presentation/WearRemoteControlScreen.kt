package dev.spatialfin.companion.wear.presentation

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Text
import dev.spatialfin.companion.protocol.WearPlayerAction
import dev.spatialfin.companion.protocol.WearVitalsState
import dev.spatialfin.companion.wear.ambient.LocalAmbientMode
import dev.spatialfin.companion.wear.pairing.WearPairingManager
import dev.spatialfin.companion.wear.presentation.components.ArcTimeline
import dev.spatialfin.companion.wear.presentation.components.ArcTimelineState
import dev.spatialfin.companion.wear.presentation.components.ArcVolumeRing
import dev.spatialfin.companion.wear.presentation.components.WearTvPairingDialog
import dev.spatialfin.companion.wear.presentation.theme.WearDarkOnPrimary
import dev.spatialfin.companion.wear.presentation.theme.WearDarkOnSurfaceVariant
import dev.spatialfin.companion.wear.presentation.theme.WearDarkOnPrimaryContainer
import dev.spatialfin.companion.wear.presentation.theme.WearDarkOutline
import dev.spatialfin.companion.wear.presentation.theme.WearDarkPrimary
import dev.spatialfin.companion.wear.presentation.theme.WearDarkPrimaryContainer
import dev.spatialfin.companion.wear.presentation.theme.WearGlassBorder
import dev.spatialfin.companion.wear.presentation.theme.WearGlassFill
import dev.spatialfin.companion.wear.presentation.theme.WearIcons
import dev.spatialfin.companion.wear.presentation.theme.WearScrubAmber
import dev.spatialfin.companion.wear.presentation.theme.WearScrubAmberBright
import dev.spatialfin.companion.wear.presentation.theme.WearTitleBright
import dev.spatialfin.companion.wear.presentation.theme.WearVectorIcon
import dev.spatialfin.companion.wear.rotary.CrownMode
import dev.spatialfin.companion.wear.rotary.rememberRotaryScrubState
import dev.spatialfin.companion.wear.rotary.rememberRotaryVolumeState
import dev.spatialfin.companion.wear.rotary.rotaryControl
import dev.spatialfin.companion.wear.transport.TransportState
import dev.spatialfin.companion.wear.transport.WearTransportManager
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * The player.
 *
 * The screen *is* the transport: the bezel arc is the timeline, the middle of the
 * watch is one 70dp play target, and the flanks are the two seek steps. Nothing
 * scrolls — every switcher lives on the action ring, one swipe up.
 */
@Composable
fun WearRemoteControlScreen(
    transportManager: WearTransportManager,
    voiceCapture: dev.spatialfin.companion.wear.voice.WearVoiceCapture,
    pairingManager: WearPairingManager,
    onNavigateToActions: () -> Unit,
    onNavigateToDevicePicker: () -> Unit,
) {
    val isAmbient = LocalAmbientMode.current
    val coroutineScope = rememberCoroutineScope()

    val nowPlaying by transportManager.nowPlaying.collectAsState()
    val transportState by transportManager.transportState.collectAsState()
    val vitals by transportManager.vitals.collectAsState()
    val coverArt by transportManager.coverArt.collectAsState()
    val pendingPairing by pairingManager.pendingPairingRequest.collectAsState()

    val focusRequester = remember { FocusRequester() }

    val currentPos = nowPlaying?.positionSeconds ?: 0L
    val duration = nowPlaying?.durationSeconds ?: 0L
    val isPlaying = nowPlaying?.isPlaying ?: false

    val scrubState = rememberRotaryScrubState(
        positionSeconds = currentPos,
        durationSeconds = duration,
        onSeek = { target ->
            coroutineScope.launch { transportManager.dispatchAction(WearPlayerAction.SeekTo(target)) }
        },
    )
    val volumeState = rememberRotaryVolumeState(
        volume = nowPlaying?.volume ?: 1f,
        onVolumeChange = { level ->
            coroutineScope.launch {
                transportManager.dispatchAction(WearPlayerAction.AdjustVolume(percentage = level))
            }
        },
    )

    var crownMode by remember { mutableStateOf(CrownMode.Scrub) }
    val toggleCrownMode = {
        crownMode = if (crownMode == CrownMode.Volume) CrownMode.Scrub else CrownMode.Volume
    }

    if (pendingPairing != null) {
        WearTvPairingDialog(
            request = pendingPairing!!,
            onApprove = { pairingManager.approvePairing(pendingPairing!!) },
            onReject = { pairingManager.rejectPairing(pendingPairing!!) },
        )
        return
    }

    if (isAmbient) {
        AmbientPlayerSurface(
            positionSeconds = currentPos,
            durationSeconds = duration,
            title = nowPlaying?.title?.ifBlank { "SpatialFin" } ?: "SpatialFin",
        )
        return
    }

    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    val progress = if (duration > 0) {
        (scrubState.currentScrubPositionSeconds.toFloat() / duration).coerceIn(0f, 1f)
    } else {
        0f
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable()
            .rotaryControl(
                scrubState = scrubState,
                volumeState = volumeState,
                mode = crownMode,
                scrollState = null,
                enabled = duration > 0,
            )
            // The crown press is the documented way to swap what the crown drives.
            // Not every watch delivers STEM_PRIMARY to a foreground app, so a
            // long-press on the face does the same thing — without it, volume
            // would be unreachable on those devices.
            .onKeyEvent { event ->
                val isStem = event.key == Key(AndroidKeyEvent.KEYCODE_STEM_PRIMARY)
                if (isStem && event.type == KeyEventType.KeyUp) {
                    toggleCrownMode()
                    true
                } else {
                    false
                }
            }
            .pointerInput(Unit) {
                var travel = 0f
                detectVerticalDragGestures(
                    onDragStart = { travel = 0f },
                    onDragEnd = { if (travel < -SWIPE_UP_THRESHOLD_PX) onNavigateToActions() },
                ) { _, dragAmount -> travel += dragAmount }
            },
    ) {
        PlayerBackdrop(
            art = coverArt?.let { remember(it) { BitmapPainter(it.asImageBitmap()) } },
            scrubbing = scrubState.isScrubbing,
        )

        ArcTimeline(
            progress = progress,
            state = when {
                scrubState.isScrubbing -> ArcTimelineState.Scrubbing
                // Volume mode keeps the timeline on screen but demotes it: the
                // crown is driving the inner ring, and two equally loud arcs read
                // as one thick smear at arm's length.
                crownMode == CrownMode.Volume -> ArcTimelineState.Volume
                else -> ArcTimelineState.Idle
            },
        )
        if (crownMode == CrownMode.Volume) {
            ArcVolumeRing(volume = volumeState.currentVolume)
        }

        when {
            scrubState.isScrubbing -> ScrubbingOverlay(
                positionSeconds = scrubState.currentScrubPositionSeconds,
                deltaSeconds = scrubState.currentScrubPositionSeconds - currentPos,
                title = nowPlaying?.title?.ifBlank { "SpatialFin" } ?: "SpatialFin",
            )

            crownMode == CrownMode.Volume -> VolumeOverlay(
                volume = volumeState.currentVolume,
                onSwapToScrub = toggleCrownMode,
            )

            else -> PlayerFace(
                targetName = nowPlaying?.targetDeviceName ?: "SpatialFin",
                transportState = transportState,
                vitals = vitals,
                title = nowPlaying?.title?.ifBlank { "SpatialFin" } ?: "SpatialFin",
                subtitle = nowPlaying.metadataLine(),
                positionSeconds = currentPos,
                durationSeconds = duration,
                isPlaying = isPlaying,
                showSkipIntro = nowPlaying?.segmentType?.contains("intro", ignoreCase = true) == true,
                onPlayPause = {
                    coroutineScope.launch {
                        transportManager.dispatchAction(WearPlayerAction.TogglePlayPause)
                    }
                },
                onSeekBack = {
                    coroutineScope.launch {
                        transportManager.dispatchAction(WearPlayerAction.SeekBackward(SEEK_STEP_SECONDS))
                    }
                },
                onSeekForward = {
                    coroutineScope.launch {
                        transportManager.dispatchAction(WearPlayerAction.SeekForward(SEEK_STEP_SECONDS))
                    }
                },
                onSkipIntro = {
                    coroutineScope.launch { transportManager.dispatchAction(WearPlayerAction.SkipIntro) }
                },
                onDeviceClick = onNavigateToDevicePicker,
                onLongPress = toggleCrownMode,
                onActionsClick = onNavigateToActions,
            )
        }
    }
}

/**
 * Cover art plus its scrim.
 *
 * The scrim is not decoration — white 15sp metadata over an arbitrary poster is
 * unreadable without it, and it deepens while scrubbing so the amber timecode
 * carries the screen.
 *
 * Takes a [Painter] rather than a Bitmap so the debug screenshot harness can hand
 * it a drawable and compose the exact same backdrop the player draws.
 */
@Composable
internal fun PlayerBackdrop(art: Painter?, scrubbing: Boolean) {
    if (art != null) {
        Image(
            painter = art,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = if (scrubbing) {
                        listOf(Color(0xF004060A), Color(0xD604060A), Color(0x9904060A))
                    } else {
                        listOf(Color(0xE004060A), Color(0xBD04060A), Color(0x8504060A))
                    },
                ),
            ),
    )
}

/**
 * Frame 1 — the resting player.
 *
 * `internal`, not private, so the debug-only store-screenshot harness can render the
 * real composable rather than a re-drawn lookalike. Nothing in `main` calls it from
 * outside this file.
 */
@Composable
internal fun BoxScope.PlayerFace(
    targetName: String,
    transportState: TransportState,
    vitals: WearVitalsState?,
    title: String,
    subtitle: String?,
    positionSeconds: Long,
    durationSeconds: Long,
    isPlaying: Boolean,
    showSkipIntro: Boolean,
    onPlayPause: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onSkipIntro: () -> Unit,
    onDeviceClick: () -> Unit,
    onLongPress: () -> Unit,
    onActionsClick: () -> Unit,
) {
    DevicePill(
        targetName = targetName,
        transportState = transportState,
        vitals = vitals,
        onClick = onDeviceClick,
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = 19.dp),
    )

    SeekFlank(
        icon = WearIcons.RotateCcw,
        label = "$SEEK_STEP_SECONDS",
        contentDescription = "Back $SEEK_STEP_SECONDS seconds",
        onClick = onSeekBack,
        modifier = Modifier
            .align(Alignment.CenterStart)
            .padding(start = 10.dp),
    )
    SeekFlank(
        icon = WearIcons.RotateCw,
        label = "$SEEK_STEP_SECONDS",
        contentDescription = "Forward $SEEK_STEP_SECONDS seconds",
        onClick = onSeekForward,
        modifier = Modifier
            .align(Alignment.CenterEnd)
            .padding(end = 10.dp),
    )

    Column(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = 65.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(70.dp)
                .clip(CircleShape)
                .background(WearDarkPrimary)
                .combinedPress(onClick = onPlayPause, onLongClick = onLongPress),
            contentAlignment = Alignment.Center,
        ) {
            WearVectorIcon(
                icon = if (isPlaying) WearIcons.Pause else WearIcons.Play,
                contentDescription = if (isPlaying) "Pause" else "Play",
                tint = WearDarkOnPrimary,
                modifier = Modifier.size(28.dp),
            )
        }
    }

    Column(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = 145.dp)
            .padding(horizontal = 44.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            fontSize = 15.sp,
            lineHeight = 19.sp,
            fontWeight = FontWeight.SemiBold,
            color = WearTitleBright,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                fontSize = 10.sp,
                lineHeight = 13.sp,
                color = WearDarkOnSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
        Row {
            Text(
                text = formatSeconds(positionSeconds),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                color = WearDarkPrimary,
            )
            // The separator is deliberately NOT monospace. Monospace is here for
            // tabular digits, so the timecode does not jitter as it counts; on Wear
            // font sets that lack a monospace "/" the fallback resolves to a CJK
            // glyph, which is what shipped until an emulator capture caught it.
            Text(
                text = " / ",
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                color = WearDarkOutline,
            )
            Text(
                text = formatSeconds(durationSeconds),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                color = WearDarkOutline,
            )
        }

        if (showSkipIntro) {
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(11.dp))
                    .background(WearDarkPrimaryContainer)
                    .clickable(onClick = onSkipIntro)
                    .padding(horizontal = 10.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Skip intro",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium,
                    color = WearDarkOnPrimaryContainer,
                )
            }
        }
    }

    // The only navigation affordance on the face. It is a hint, not a button —
    // but it is also tappable, because a swipe-up hint nobody discovers is a
    // feature nobody uses.
    Column(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 11.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onActionsClick)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        WearVectorIcon(
            icon = WearIcons.ChevronUp,
            contentDescription = null,
            tint = WearDarkOutline,
            modifier = Modifier.size(11.dp),
        )
        Text(
            text = "ACTIONS",
            fontSize = 8.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.08.em,
            color = WearDarkOutline,
        )
    }
}

/** Frame 2 — the crown is moving you. */
@Composable
internal fun BoxScope.ScrubbingOverlay(
    positionSeconds: Long,
    deltaSeconds: Long,
    title: String,
) {
    Row(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = 22.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(WearScrubAmber.copy(alpha = 0.14f))
            .border(1.dp, WearScrubAmber.copy(alpha = 0.4f), RoundedCornerShape(11.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WearVectorIcon(
            icon = WearIcons.Clock,
            contentDescription = null,
            tint = WearScrubAmber,
            modifier = Modifier.size(9.dp),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = "SCRUBBING",
            fontSize = 8.5.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.06.em,
            color = WearScrubAmber,
        )
    }

    Column(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = 76.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = formatSeconds(positionSeconds),
            fontSize = 38.sp,
            lineHeight = 38.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            color = WearScrubAmberBright,
        )
        Spacer(modifier = Modifier.height(3.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(11.dp))
                .background(WearScrubAmber.copy(alpha = 0.16f))
                .padding(horizontal = 9.dp, vertical = 4.dp),
        ) {
            Text(
                text = formatDelta(deltaSeconds),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                color = WearScrubAmber,
            )
        }
    }

    Column(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = 156.dp)
            .padding(horizontal = 45.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = WearDarkOutline,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            WearVectorIcon(
                icon = WearIcons.Detent,
                contentDescription = null,
                tint = WearDarkOutline,
                modifier = Modifier.size(9.dp),
            )
            Spacer(modifier = Modifier.width(3.dp))
            Text(
                text = "5s per detent",
                fontSize = 8.sp,
                fontWeight = FontWeight.Medium,
                color = WearDarkOutline,
            )
        }
    }
}

/** Frame 3 — the crown is on volume. */
@Composable
internal fun BoxScope.VolumeOverlay(
    volume: Float,
    onSwapToScrub: () -> Unit,
) {
    Column(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = 75.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        WearVectorIcon(
            icon = WearIcons.Volume,
            contentDescription = "Volume",
            tint = WearDarkOnSurfaceVariant,
            modifier = Modifier.size(28.dp),
        )
        Spacer(modifier = Modifier.height(5.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = "${(volume * 100).toInt()}",
                fontSize = 34.sp,
                lineHeight = 34.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                color = WearTitleBright,
            )
            Text(
                text = "%",
                fontSize = 17.sp,
                fontFamily = FontFamily.Monospace,
                color = WearDarkOutline,
            )
        }
    }

    Column(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = 154.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(13.dp))
                .background(WearDarkPrimaryContainer)
                .clickable(onClick = onSwapToScrub)
                .padding(horizontal = 11.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WearVectorIcon(
                icon = WearIcons.Crown,
                contentDescription = null,
                tint = WearDarkOnPrimaryContainer,
                modifier = Modifier.size(10.dp),
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = "Crown: volume",
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                color = WearDarkOnPrimaryContainer,
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Press to scrub",
            fontSize = 9.5.sp,
            fontWeight = FontWeight.Medium,
            color = WearDarkOnSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Frame 4 — always-on.
 *
 * No artwork, no fills, hairline arc. This is the burn-in branch: everything drawn
 * here has to survive two hours parked in one position on an OLED.
 */
@Composable
internal fun AmbientPlayerSurface(
    positionSeconds: Long,
    durationSeconds: Long,
    title: String,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        val progress = if (durationSeconds > 0) {
            (positionSeconds.toFloat() / durationSeconds).coerceIn(0f, 1f)
        } else {
            0f
        }
        ArcTimeline(progress = progress, state = ArcTimelineState.Ambient)

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            WearVectorIcon(
                icon = WearIcons.PlayOutline,
                contentDescription = null,
                tint = WearDarkOutline,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.height(7.dp))
            Text(
                text = formatSeconds(positionSeconds),
                fontSize = 28.sp,
                lineHeight = 28.sp,
                fontFamily = FontFamily.Monospace,
                color = WearDarkOnSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(7.dp))
            Text(
                text = title,
                fontSize = 11.sp,
                color = WearDarkOutline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 40.dp),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun DevicePill(
    targetName: String,
    transportState: TransportState,
    vitals: WearVitalsState?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(WearGlassFill)
            .border(1.dp, WearGlassBorder, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The transport is the icon. A watch that has silently fallen back from the
        // Data Layer to the Jellyfin relay behaves differently enough (latency,
        // no local discovery) that hiding it would be a lie.
        WearVectorIcon(
            icon = when (transportState) {
                is TransportState.ConnectedViaDataLayer -> WearIcons.Glasses
                is TransportState.ConnectedViaFCastLan -> WearIcons.Tv
                is TransportState.ConnectedViaJellyfinRelay -> WearIcons.Target
                is TransportState.Disconnected -> WearIcons.Close
            },
            contentDescription = null,
            tint = if (transportState is TransportState.Disconnected) {
                WearScrubAmber
            } else {
                WearDarkOnSurfaceVariant
            },
            modifier = Modifier.size(11.dp),
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            text = targetName,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            color = WearTitleBright,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 74.dp),
        )
        if (vitals != null && vitals.batteryPercent >= 0) {
            Spacer(modifier = Modifier.width(5.dp))
            Box(
                modifier = Modifier
                    .size(width = 1.dp, height = 10.dp)
                    .background(WearGlassBorder),
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = "${vitals.batteryPercent}%",
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                color = if (vitals.batteryPercent <= LOW_BATTERY_PERCENT) {
                    WearScrubAmber
                } else {
                    WearDarkPrimary
                },
            )
        }
    }
}

@Composable
private fun SeekFlank(
    icon: dev.spatialfin.companion.wear.presentation.theme.WearIcon,
    label: String,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .size(42.dp)
            .clip(CircleShape)
            .background(WearGlassFill)
            .border(1.dp, WearGlassBorder, CircleShape)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        WearVectorIcon(
            icon = icon,
            contentDescription = contentDescription,
            tint = WearTitleBright,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = label,
            fontSize = 7.5.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            color = WearDarkOnSurfaceVariant,
        )
    }
}

/** Play/pause needs a long-press too; [combinedClickable] without a ripple. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Modifier.combinedPress(onClick: () -> Unit, onLongClick: () -> Unit): Modifier =
    combinedClickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onLongClick = onLongClick,
        onClick = onClick,
    )

/** "S2:E4 · The Expanse", or the bare series name when the numbering is missing. */
private fun dev.spatialfin.companion.protocol.WearNowPlayingState?.metadataLine(): String? {
    val series = this?.seriesName
    if (series.isNullOrBlank()) return null
    return if (seasonNumber != null && episodeNumber != null) {
        "S$seasonNumber:E$episodeNumber · $series"
    } else {
        series
    }
}

internal fun formatSeconds(totalSeconds: Long): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format("%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}

/** Signed offset from the live position, e.g. "+1:33". */
private fun formatDelta(deltaSeconds: Long): String {
    val sign = if (deltaSeconds < 0) "-" else "+"
    val abs = abs(deltaSeconds)
    return "$sign${abs / 60}:${String.format("%02d", abs % 60)}"
}

private const val SEEK_STEP_SECONDS = 10
private const val LOW_BATTERY_PERCENT = 20
private const val SWIPE_UP_THRESHOLD_PX = 40f
