package dev.spatialfin.fcast.session

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.jdtech.jellyfin.fcast.protocol.FCAST_DEFAULT_PORT
import dev.jdtech.jellyfin.fcast.sender.FCastReceiver
import dev.jdtech.jellyfin.fcast.sender.PickerEntry

/**
 * Session-aware cast picker. Reads the unified entries list from [CastSessionManager], so
 * remembered receivers render immediately on open with their probing → online/offline state
 * flipping in place as TCP probes and the mDNS scan complete. Saves the user from a 1.5–4s
 * blank wait every time they tap the cast icon.
 *
 * Falls back to the simple [dev.jdtech.jellyfin.fcast.ui.FCastReceiverPickerSheet] for callers
 * outside the CastSessionManager scope (the XR player still uses that one directly).
 */
@Composable
fun FCastSessionPickerSheet(
    sessionManager: CastSessionManager,
    onReceiverPicked: (FCastReceiver) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * When true, expose a "Split A/V" toggle that routes the picked receiver to a split
     * session (this device plays video, picked TV plays audio). Caller should pass false on
     * TV form factor — TV-as-video-master to itself doesn't make sense.
     */
    showSplitAvOption: Boolean = true,
    /**
     * Optional callback for non-FCast (Google Cast / AirPlay) picks. Defaults to dispatching
     * through [sessionManager.pickCastReceiver] which sets `_pickedTarget` so subsequent play
     * taps route through the [dev.jdtech.jellyfin.cast.ProtocolAdapter] pipeline. Callers that
     * want a different routing override this.
     */
    onCastReceiverPicked: (dev.jdtech.jellyfin.cast.CastReceiver) -> Unit = { receiver ->
        sessionManager.pickCastReceiver(receiver)
        onDismiss()
    },
) {
    val entries by sessionManager.pickerEntries.collectAsState()
    val googleCastReceivers by sessionManager.googleCastReceivers.collectAsState()
    val isScanning by sessionManager.isScanning.collectAsState()
    val splitAvMode by sessionManager.splitAvMode.collectAsState()
    val audioLatencies by sessionManager.audioLatencies.collectAsState()
    var manualHost by remember { mutableStateOf("") }
    var manualPort by remember { mutableStateOf(FCAST_DEFAULT_PORT.toString()) }
    var splitAvPermissionDenied by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val recordAudioLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            splitAvPermissionDenied = false
            sessionManager.setSplitAvMode(true)
        } else {
            splitAvPermissionDenied = true
        }
    }
    val onSplitAvToggle: (Boolean) -> Unit = { enabled ->
        if (!enabled) {
            sessionManager.setSplitAvMode(false)
            splitAvPermissionDenied = false
        } else {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) {
                splitAvPermissionDenied = false
                sessionManager.setSplitAvMode(true)
            } else {
                recordAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    Surface(
        // Fixed cinema-scale width on XR: a SpatialDialog renders centered in the user's
        // vision and constraining width here keeps text readable at the 1.75 m spawn distance.
        // heightIn is mandatory because SpatialDialog otherwise propagates Constraints.Infinity
        // down into the LazyColumn below, which crashes on measurement.
        // (See GEMINI.md "Spatial Dialogs & UI" — `LazyColumn inside SpatialDialog crashes
        // with 'infinite height' unless the dialog has Modifier.heightIn(max = …)`.)
        modifier = modifier
            .widthIn(min = 720.dp, max = 960.dp)
            .heightIn(max = 720.dp),
        shape = RoundedCornerShape(32.dp),
    ) {
        Column(modifier = Modifier.padding(40.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Cast to a receiver",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Pick a device on your network. Streams use plain TCP, so " +
                            "only cast on a trusted Wi-Fi.",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (isScanning) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                }
            }
            Spacer(Modifier.height(24.dp))

            if (showSplitAvOption) {
                SplitAvToggleRow(
                    enabled = splitAvMode,
                    onToggle = onSplitAvToggle,
                    permissionDenied = splitAvPermissionDenied,
                )
                Spacer(Modifier.height(20.dp))
            }

            if (entries.isEmpty() && !isScanning) {
                Text(
                    text = "No receivers found yet. Add one manually below.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(entries, key = { "${it.receiver.host}:${it.receiver.port}" }) { entry ->
                        SessionReceiverRow(
                            entry = entry,
                            onClick = { onReceiverPicked(entry.receiver) },
                            onForget = {
                                sessionManager.forgetReceiver(entry.receiver.host, entry.receiver.port)
                            },
                            audioLatencyMs = audioLatencies[
                                "${entry.receiver.host}:${entry.receiver.port}"
                            ],
                            showCalibration = splitAvMode,
                            onRecalibrate = {
                                // Hitting Recalibrate strongly implies the user wants THIS
                                // receiver as their cast target — also pick it so a subsequent
                                // Play tap goes to the right device instead of a stale pick.
                                sessionManager.pickReceiver(entry.receiver)
                                sessionManager.recalibrateReceiver(entry.receiver)
                            },
                        )
                    }
                }
            }

            if (googleCastReceivers.isNotEmpty()) {
                Spacer(Modifier.height(24.dp))
                Text(
                    text = "Chromecast",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Streams through Google's Default Media Receiver. " +
                        "Styled ASS subtitles burn in server-side automatically.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(googleCastReceivers, key = { it.id }) { castReceiver ->
                        CastReceiverRow(
                            receiver = castReceiver,
                            onClick = { onCastReceiverPicked(castReceiver) },
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(24.dp))

            Text(
                text = "Add manually",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = manualHost,
                    onValueChange = { manualHost = it },
                    label = { Text("Host or IP") },
                    singleLine = true,
                    modifier = Modifier.weight(2f),
                )
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = manualPort,
                    onValueChange = { manualPort = it.filter(Char::isDigit).take(5) },
                    label = { Text("Port") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel", style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.width(12.dp))
                Button(
                    enabled = manualHost.isNotBlank() && (manualPort.toIntOrNull() ?: 0) in 1..65535,
                    onClick = {
                        onReceiverPicked(
                            FCastReceiver(
                                host = manualHost.trim(),
                                port = manualPort.toIntOrNull() ?: FCAST_DEFAULT_PORT,
                                name = manualHost.trim(),
                                source = FCastReceiver.Source.Manual,
                            ),
                        )
                    },
                ) { Text("Cast", style = MaterialTheme.typography.titleMedium) }
            }
        }
    }
}

@Composable
private fun SplitAvToggleRow(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    permissionDenied: Boolean = false,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle(!enabled) },
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Split A/V",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Video stays on this device, audio plays on the picked receiver. " +
                        "First use calibrates audio sync (~6 s) in a quiet room.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (permissionDenied) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Microphone permission is required to calibrate audio sync. " +
                            "Grant it in System Settings to enable Split A/V.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun SessionReceiverRow(
    entry: PickerEntry,
    onClick: () -> Unit,
    onForget: () -> Unit,
    audioLatencyMs: Int? = null,
    showCalibration: Boolean = false,
    onRecalibrate: () -> Unit = {},
) {
    val rowEnabled = entry.state != PickerEntry.State.Offline
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (rowEnabled) Modifier.clickable(onClick = onClick) else Modifier),
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ReachabilityDot(state = entry.state)
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.receiver.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = if (rowEnabled) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = buildString {
                            append(entry.receiver.host).append(':').append(entry.receiver.port)
                            append(" • ")
                            append(
                                when (entry.state) {
                                    PickerEntry.State.Probing -> "Checking…"
                                    PickerEntry.State.Online -> "Online"
                                    PickerEntry.State.Offline -> "Offline"
                                },
                            )
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onForget) {
                    Text("Forget", style = MaterialTheme.typography.titleMedium)
                }
            }
            if (showCalibration) {
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = audioLatencyMs?.let { "Audio sync: $it ms calibrated" }
                            ?: "Audio sync: not calibrated yet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (audioLatencyMs != null) {
                        TextButton(onClick = onRecalibrate) {
                            Text("Recalibrate", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Minimal Cast receiver row. PR 5's redesign replaces this with the unified row used for every
 * protocol; for PR 3 this is just enough UI for users to actually pick a Chromecast without
 * doing the full picker rework.
 */
@Composable
private fun CastReceiverRow(
    receiver: dev.jdtech.jellyfin.cast.CastReceiver,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        tonalElevation = 2.dp,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF34C759)),
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = receiver.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
                val subtitle = receiver.modelName ?: "${receiver.host}:${receiver.port}"
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Tiny protocol badge so power users see at a glance which protocol they're picking.
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Text(
                    text = "Chromecast",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun ReachabilityDot(state: PickerEntry.State) {
    val color = when (state) {
        PickerEntry.State.Online -> Color(0xFF34C759)
        PickerEntry.State.Offline -> Color(0xFF8E8E93)
        PickerEntry.State.Probing -> MaterialTheme.colorScheme.primary
    }
    if (state == PickerEntry.State.Probing) {
        CircularProgressIndicator(
            modifier = Modifier.size(12.dp),
            strokeWidth = 2.dp,
        )
    } else {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(color),
        )
    }
}
