package dev.spatialfin.fcast.session

import androidx.compose.foundation.background
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.fcast.protocol.PlaybackState
import dev.jdtech.jellyfin.fcast.sender.FCastCastingController
import kotlinx.coroutines.launch

/**
 * Composition-local pointer to the process-singleton [FCastSessionManager]. The form-factor root
 * (NavigationRoot for XR, BeamNavigationRoot for Beam) installs it once via
 * `CompositionLocalProvider(LocalFCastSession provides fcastSession)` so that hero / detail
 * components below can drop a cast affordance next to their existing 3-dots overflow without
 * threading the manager through every screen signature. Null on form factors that don't cast (TV).
 */
val LocalFCastSession = compositionLocalOf<FCastSessionManager?> { null }

/**
 * Persistent cast affordance for any toolbar / nav surface. Renders the FCast globe icon, tints
 * it when [FCastSessionManager.hasCastIntent] is true (Google Cast UX: the icon stays "on" once
 * the user has picked a receiver, even before a stream is active), and opens the global picker
 * on click. Mount the picker host once per form-factor root via [FCastGlobalPickerHost].
 */
@Composable
fun FCastCastIconButton(
    sessionManager: FCastSessionManager,
    modifier: Modifier = Modifier,
) {
    val pickedReceiver by sessionManager.pickedReceiver.collectAsState()
    val tint = if (pickedReceiver != null) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    IconButton(
        onClick = { sessionManager.showPicker() },
        modifier = modifier,
    ) {
        Icon(
            painter = painterResource(CoreR.drawable.ic_cast),
            contentDescription = "Cast to FCast receiver",
            tint = tint,
        )
    }
}

/**
 * Global picker host — renders the FCast picker sheet inside an [AlertDialog]-style wrapper when
 * [FCastSessionManager.pickerVisible] is true. Mount once per form-factor root (Beam Scaffold,
 * XR NavigationRoot Box, XR FullSpace Subspace). The picker delegates the chosen receiver back
 * to the session manager via [FCastSessionManager.pickReceiver].
 *
 * Wrap this in a SpatialDialog from XR surfaces (parent panel pushback). For 2D surfaces (Beam,
 * TV), it self-renders as a plain dialog.
 */
@Composable
fun FCastGlobalPickerHost(
    sessionManager: FCastSessionManager,
    showSplitAvOption: Boolean = true,
) {
    // Calibration dialog is sibling to the picker — both subscribe to the session manager and
    // the calibration dialog renders only when calibrationState != Idle.
    SplitAvCalibrationDialog(sessionManager = sessionManager)

    val visible by sessionManager.pickerVisible.collectAsState()
    if (!visible) return
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { sessionManager.hidePicker() },
        confirmButton = {},
        dismissButton = {},
        title = null,
        text = {
            FCastSessionPickerSheet(
                sessionManager = sessionManager,
                onReceiverPicked = { receiver ->
                    sessionManager.pickReceiver(receiver)
                    sessionManager.hidePicker()
                },
                onDismiss = { sessionManager.hidePicker() },
                showSplitAvOption = showSplitAvOption,
            )
        },
    )
}

/**
 * "Now casting" mini-controller, rendered above the bottom navigation on Beam and as a floating
 * Surface on XR Home Space. Visible whenever there's a picked receiver (whether or not a stream
 * is currently active — gives the user a way to disconnect at any time, matching Google Cast).
 *
 * For Full Space, mount this inside an Orbiter (the caller wraps it).
 */
@Composable
fun FCastMiniController(
    sessionManager: FCastSessionManager,
    modifier: Modifier = Modifier,
) {
    val pickedReceiver by sessionManager.pickedReceiver.collectAsState()
    val status by sessionManager.status.collectAsState()
    val remoteState by sessionManager.remoteState.collectAsState()
    val receiver = pickedReceiver ?: return
    val scope = rememberCoroutineScope()

    val isPlaying = remoteState?.playbackState == PlaybackState.Playing
    val controlsEnabled = status == FCastCastingController.Status.Casting

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 4.dp,
        shape = RoundedCornerShape(16.dp),
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
                    Text(
                        text = when (status) {
                            FCastCastingController.Status.Casting -> "Casting to ${receiver.name}"
                            FCastCastingController.Status.Connecting -> "Connecting to ${receiver.name}…"
                            FCastCastingController.Status.Failed -> "Cast failed — ${receiver.name}"
                            FCastCastingController.Status.Idle -> "Ready to cast to ${receiver.name}"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "${receiver.host}:${receiver.port}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { scope.launch { sessionManager.stopCast() } }) {
                    Icon(
                        painter = painterResource(CoreR.drawable.ic_x),
                        contentDescription = "Stop casting",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
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
