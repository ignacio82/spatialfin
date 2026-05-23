package dev.spatialfin.fcast.session

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jdtech.jellyfin.cast.SubtitleFidelity
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.fcast.protocol.PlaybackState
import dev.jdtech.jellyfin.fcast.sender.FCastCastingController
import kotlinx.coroutines.launch

/**
 * Composition-local pointer to the process-singleton [CastSessionManager]. The form-factor root
 * (NavigationRoot for XR, BeamNavigationRoot for Beam) installs it once via
 * `CompositionLocalProvider(LocalFCastSession provides fcastSession)` so that hero / detail
 * components below can drop a cast affordance next to their existing 3-dots overflow without
 * threading the manager through every screen signature. Null on form factors that don't cast (TV).
 */
val LocalFCastSession = compositionLocalOf<CastSessionManager?> { null }

/**
 * Persistent cast affordance for any toolbar / nav surface. Renders the FCast globe icon, tints
 * it when [CastSessionManager.hasCastIntent] is true (Google Cast UX: the icon stays "on" once
 * the user has picked a receiver, even before a stream is active), and opens the global picker
 * on click. Mount the picker host once per form-factor root via [FCastGlobalPickerHost].
 */
@Composable
fun FCastCastIconButton(
    sessionManager: CastSessionManager,
    modifier: Modifier = Modifier,
) {
    // Tint reflects "is anything picked" across all protocols — Cast / FCast / AirPlay.
    // Long-press disconnects immediately (skips picker) per §12 of the spec.
    val pickedTarget by sessionManager.pickedTarget.collectAsState()
    val scope = rememberCoroutineScope()
    val tint = if (pickedTarget != null) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    // Long-press = direct disconnect; short tap = open picker. Built via combinedClickable
    // because IconButton itself doesn't expose onLongClick.
    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .size(48.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .combinedClickableShim(
                onClick = { sessionManager.showPicker() },
                onLongClick = if (pickedTarget != null) {
                    { scope.launch { sessionManager.stopCast() } }
                } else null,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(CoreR.drawable.ic_cast),
            contentDescription = if (pickedTarget == null) {
                "Cast to receiver"
            } else {
                "Casting to ${pickedTarget?.name}. Long-press to disconnect."
            },
            tint = tint,
        )
        // Tiny protocol badge in the corner so power users see which protocol is active.
        pickedTarget?.protocol?.let { protocol ->
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(14.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = protocol.protocolMonogram(),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/** Single-letter monogram per protocol — used in the cast-button corner badge. */
private fun dev.jdtech.jellyfin.cast.CastProtocol.protocolMonogram(): String = when (this) {
    dev.jdtech.jellyfin.cast.CastProtocol.FCast -> "F"
    dev.jdtech.jellyfin.cast.CastProtocol.GoogleCast -> "C"
    dev.jdtech.jellyfin.cast.CastProtocol.AirPlay -> "A"
}

/**
 * Tiny shim around [androidx.compose.foundation.combinedClickable]. Wrapped so the call site
 * can pass a null [onLongClick] without re-importing the experimental annotation everywhere.
 */
@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableShim(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
): Modifier = this.combinedClickable(
    onClick = onClick,
    onLongClick = onLongClick,
)

/**
 * Global picker host — renders the FCast picker sheet inside an [AlertDialog]-style wrapper when
 * [CastSessionManager.pickerVisible] is true. Mount once per form-factor root (Beam Scaffold,
 * XR NavigationRoot Box, XR FullSpace Subspace). The picker delegates the chosen receiver back
 * to the session manager via [CastSessionManager.pickReceiver].
 *
 * Wrap this in a SpatialDialog from XR surfaces (parent panel pushback). For 2D surfaces (Beam,
 * TV), it self-renders as a plain dialog.
 */
@Composable
fun FCastGlobalPickerHost(
    sessionManager: CastSessionManager,
    showSplitAvOption: Boolean = true,
) {
    // Calibration dialog is sibling to the picker — both subscribe to the session manager and
    // the calibration dialog renders only when calibrationState != Idle.
    SplitAvCalibrationDialog(sessionManager = sessionManager)

    // Surface the once-per-session "styled subtitles will be simplified" toast. Mounted at the
    // form-factor root so it fires regardless of which screen kicked off the cast — the
    // session manager queues the receiver name and the UI consumes it after rendering.
    val warningReceiver by sessionManager.pendingSubtitleDegradationWarning.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(warningReceiver) {
        val name = warningReceiver ?: return@LaunchedEffect
        android.widget.Toast.makeText(
            context,
            context.getString(dev.spatialfin.R.string.cast_subtitle_fidelity_degraded, name),
            android.widget.Toast.LENGTH_LONG,
        ).show()
        sessionManager.consumeSubtitleDegradationWarning()
    }

    val visible by sessionManager.pickerVisible.collectAsState()
    androidx.compose.runtime.LaunchedEffect(visible) {
        timber.log.Timber.tag("FCastSession").i("picker host visible=%b", visible)
    }
    if (!visible) return
    // SpatialDialog renders centered in the user's vision on XR (with parent-panel pushback)
    // and falls back to a standard Material3 dialog on phone / TV. Plain AlertDialog inside an
    // XR Subspace placed the picker behind the playback panel — switching to SpatialDialog is
    // what makes the picker actually visible from the XR home/hero entry points.
    androidx.xr.compose.spatial.SpatialDialog(
        onDismissRequest = { sessionManager.hidePicker() },
    ) {
        FCastSessionPickerSheet(
            sessionManager = sessionManager,
            onReceiverPicked = { receiver ->
                sessionManager.pickReceiver(receiver)
                sessionManager.hidePicker()
            },
            onDismiss = { sessionManager.hidePicker() },
            showSplitAvOption = showSplitAvOption,
        )
    }
}

/**
 * "Now casting" mini-controller, rendered above the bottom navigation on Beam and as a floating
 * Surface on XR Home Space. Visible whenever there's a picked receiver (whether or not a stream
 * is currently active — gives the user a way to disconnect at any time, matching Google Cast).
 *
 * For Full Space, mount this inside an Orbiter (the caller wraps it).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FCastMiniController(
    sessionManager: CastSessionManager,
    modifier: Modifier = Modifier,
) {
    val pickedReceiver by sessionManager.pickedReceiver.collectAsState()
    val pickedTarget by sessionManager.pickedTarget.collectAsState()
    val status by sessionManager.status.collectAsState()
    // PR 5: unified state flow. activeMediaState reflects whichever protocol is currently
    // driving the cast — FCast pulls from `remoteState`, Cast/AirPlay pull from the
    // ProtocolAdapter event stream via bridgeAdapterEvents.
    val activeMediaState by sessionManager.activeMediaState.collectAsState()
    val anyTarget = pickedTarget ?: return
    val receiver = pickedReceiver
    val isFCastSession = receiver != null
    val scope = rememberCoroutineScope()

    val isPlaying = activeMediaState == dev.jdtech.jellyfin.cast.CastMediaState.Playing
    val controlsEnabled = if (isFCastSession) {
        status == FCastCastingController.Status.Casting
    } else {
        // For Cast/AirPlay we don't have FCast's connection-state, but pickedTarget being
        // non-null implies an adapter was created on the last play tap. Once the adapter
        // reports a non-Idle state, controls are live.
        activeMediaState != dev.jdtech.jellyfin.cast.CastMediaState.Idle
    }

    val showCinematicRemote = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

    if (showCinematicRemote.value) {
        androidx.compose.material3.ModalBottomSheet(
            onDismissRequest = { showCinematicRemote.value = false },
            sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
            dragHandle = null,
            contentWindowInsets = { androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0) },
            containerColor = Color.Transparent,
        ) {
            FCastCinematicRemote(
                sessionManager = sessionManager,
                onDismiss = { showCinematicRemote.value = false }
            )
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 4.dp,
        shape = RoundedCornerShape(16.dp),
        onClick = { showCinematicRemote.value = true }
    ) {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(CoreR.drawable.ic_cast),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(12.dp))
                androidx.compose.foundation.layout.Column(modifier = Modifier.weight(1f)) {
                    // For FCast we have a live status flow; Cast picks just say "Casting to X"
                    // until PR 5 plumbs the adapter event flow into a unified session-state.
                    val label = if (receiver != null) {
                        when (status) {
                            FCastCastingController.Status.Casting -> "Casting to ${receiver.name}"
                            FCastCastingController.Status.Connecting -> "Connecting to ${receiver.name}…"
                            FCastCastingController.Status.Failed -> "Cast failed — ${receiver.name}"
                            FCastCastingController.Status.Idle -> "Ready to cast to ${receiver.name}"
                        }
                    } else {
                        "Casting to ${anyTarget.name}"
                    }
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "${anyTarget.host}:${anyTarget.port} · ${anyTarget.protocol}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // SpatialFin → SpatialFin: receiver pushes the actually-decoded audio
                    // format on every beacon. Null until ExoPlayer resolves tracks (typically
                    // ~200ms post-Play); we hide the row entirely until then so the layout
                    // doesn't shift.
                    val audioFormat by sessionManager.activeAudioFormat.collectAsState()
                    val audioLabel = audioFormat?.label
                    if (!audioLabel.isNullOrBlank()) {
                        Text(
                            text = "Audio · $audioLabel",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val audioRoute by sessionManager.activeAudioRoute.collectAsState()
                    val routeLabel = audioRoute?.label
                    if (!routeLabel.isNullOrBlank()) {
                        Text(
                            text = "Route · $routeLabel",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(onClick = { scope.launch { sessionManager.stopCast() } }) {
                    Icon(
                        painter = painterResource(CoreR.drawable.ic_x),
                        contentDescription = "Stop casting",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            SubtitleFidelityChip(sessionManager)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = { scope.launch { sessionManager.seekBy(-10.0) } },
                    enabled = controlsEnabled,
                ) {
                    Icon(
                        painter = painterResource(CoreR.drawable.ic_skip_back),
                        contentDescription = "Skip back 10 seconds",
                    )
                }
                IconButton(
                    onClick = {
                        scope.launch {
                            if (isPlaying) sessionManager.pause() else sessionManager.resume()
                        }
                    },
                    enabled = controlsEnabled,
                ) {
                    Icon(
                        painter = painterResource(
                            if (isPlaying) CoreR.drawable.ic_pause else CoreR.drawable.ic_play,
                        ),
                        contentDescription = if (isPlaying) "Pause" else "Play",
                    )
                }
                IconButton(
                    onClick = { scope.launch { sessionManager.seekBy(10.0) } },
                    enabled = controlsEnabled,
                ) {
                    Icon(
                        painter = painterResource(CoreR.drawable.ic_skip_forward),
                        contentDescription = "Skip forward 10 seconds",
                    )
                }
            }
            // Volume row — slider gives the visual feedback the bare ±buttons lacked. The
            // ±icons stay because they're easier to tap than a 4dp slider thumb on phones.
            val currentVolume by sessionManager.currentVolume.collectAsState()
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = { scope.launch { sessionManager.adjustVolume(-0.1) } },
                    enabled = controlsEnabled,
                ) {
                    Icon(
                        painter = painterResource(CoreR.drawable.ic_volume_0),
                        contentDescription = "Volume down",
                    )
                }
                androidx.compose.material3.Slider(
                    value = currentVolume.toFloat(),
                    onValueChange = { v ->
                        scope.launch { sessionManager.setVolume(v.toDouble()) }
                    },
                    valueRange = 0f..1f,
                    enabled = controlsEnabled,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = { scope.launch { sessionManager.adjustVolume(0.1) } },
                    enabled = controlsEnabled,
                ) {
                    Icon(
                        painter = painterResource(CoreR.drawable.ic_volume),
                        contentDescription = "Volume up",
                    )
                }
                Text(
                    text = "${(currentVolume * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 6.dp).widthIn(min = 36.dp),
                )
            }
        }
    }
}

/**
 * Small inline chip rendered above the playback controls whenever the active cast session has
 * triggered a server-side subtitle burn-in transcode. Visibility is gated on the
 * [castShowTranscodingIndicator] preference so users who don't want the noise can hide it.
 *
 * PR 5's full now-playing-bar redesign will replace this with a polished pill; for PR 2 we keep
 * it minimal so the burn-in path is at least *visible* end-to-end.
 */
@Composable
private fun SubtitleFidelityChip(sessionManager: CastSessionManager) {
    val fidelity by sessionManager.subtitleFidelity.collectAsState()
    val showIndicator = remember(sessionManager) { sessionManager.shouldShowTranscodingIndicator() }
    if (fidelity != SubtitleFidelity.Transcoding || !showIndicator) return
    Surface(
        modifier = Modifier.padding(top = 4.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            text = androidx.compose.ui.res.stringResource(dev.spatialfin.R.string.cast_subtitle_transcoding),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}
