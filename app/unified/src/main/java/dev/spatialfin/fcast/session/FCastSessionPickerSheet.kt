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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.draw.alpha
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
    val airPlayReceivers by sessionManager.airPlayReceivers.collectAsState()
    val isScanning by sessionManager.isScanning.collectAsState()
    // PR 6: per-protocol visibility toggles. Read once per sheet open — not collected as a
    // flow because the prefs sheet would be modal anyway.
    val showFCast = remember(sessionManager) {
        sessionManager.shouldShowProtocol(dev.jdtech.jellyfin.cast.CastProtocol.FCast)
    }
    val showGoogleCast = remember(sessionManager) {
        sessionManager.shouldShowProtocol(dev.jdtech.jellyfin.cast.CastProtocol.GoogleCast)
    }
    val showAirPlay = remember(sessionManager) {
        sessionManager.shouldShowProtocol(dev.jdtech.jellyfin.cast.CastProtocol.AirPlay)
    }
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
        // Width: phone fills the dialog naturally; XR caps at 720dp so text stays readable
        // at the 1.75 m spawn distance. heightIn(max = 720.dp) is *still* required on XR
        // because SpatialDialog otherwise propagates Constraints.Infinity down through the
        // outer verticalScroll, which crashes the measure pass. (Same GEMINI.md rule that
        // governed the previous LazyColumn — outer-scroll containers have the same need.)
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = 720.dp)
            .heightIn(max = 720.dp),
        shape = RoundedCornerShape(28.dp),
    ) {
        var helpVisible by remember { mutableStateOf(false) }
        val scrollState = rememberScrollState()
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp, vertical = 24.dp)
                .verticalScroll(scrollState),
        ) {
            // Header — title + scanning spinner. Help button moves to its own row at narrow
            // widths so it doesn't squish the title/description; this is fine on XR too
            // because the cinema-scale cap is wider than the longest line anyway.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Cast to a receiver",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (isScanning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.5.dp,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Pick a device on your network. Streams use plain TCP, so " +
                        "only cast on a trusted Wi-Fi.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { helpVisible = true },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 12.dp, vertical = 4.dp,
                    ),
                ) {
                    Text("Help", style = MaterialTheme.typography.labelLarge)
                }
            }
            if (helpVisible) {
                CastProtocolHelpRow(onDismiss = { helpVisible = false })
            }
            Spacer(Modifier.height(16.dp))

            if (showSplitAvOption) {
                SplitAvToggleRow(
                    enabled = splitAvMode,
                    onToggle = onSplitAvToggle,
                    permissionDenied = splitAvPermissionDenied,
                )
                Spacer(Modifier.height(20.dp))
            }

            if (showFCast) {
                if (entries.isEmpty() && !isScanning) {
                    Text(
                        text = "No receivers found yet. Add one manually below.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    // Plain Column (not LazyColumn) so the outer verticalScroll is the sole
                    // scroller. Capped at 16 by RememberedReceiversStore so the linear render
                    // cost is negligible.
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        entries.forEach { entry ->
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
            }

            if (showGoogleCast && googleCastReceivers.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                SectionHeader(
                    title = "Chromecast",
                    subtitle = "Styled subtitles burn in server-side.",
                )
                Spacer(Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    googleCastReceivers.forEach { castReceiver ->
                        CastReceiverRow(
                            receiver = castReceiver,
                            badge = "Chromecast",
                            enabled = true,
                            unsupportedHint = null,
                            onClick = { onCastReceiverPicked(castReceiver) },
                        )
                    }
                }
            }

            if (showAirPlay && airPlayReceivers.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                SectionHeader(
                    title = "AirPlay",
                    subtitle = "Apple TVs and AV receivers. " +
                        "AirPlay 2 devices need pairing (shown disabled).",
                )
                Spacer(Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    airPlayReceivers.forEach { airReceiver ->
                        val isPairingRequired = airReceiver.appName?.contains(
                            "pairing required", ignoreCase = true,
                        ) == true
                        CastReceiverRow(
                            receiver = airReceiver,
                            badge = if (isPairingRequired) "AirPlay 2" else "AirPlay",
                            enabled = !isPairingRequired,
                            unsupportedHint = if (isPairingRequired) {
                                "Pairing not supported yet"
                            } else null,
                            onClick = { onCastReceiverPicked(airReceiver) },
                        )
                    }
                }
            }

            // "Add manually" creates an FCast receiver, so hide it whenever FCast is toggled
            // off — otherwise a user who explicitly hid FCast still sees the entry input.
            if (showFCast) {
                Spacer(Modifier.height(20.dp))
                HorizontalDivider()
                Spacer(Modifier.height(16.dp))

                SectionHeader(title = "Add manually", subtitle = null)
                Spacer(Modifier.height(10.dp))
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
            } else {
                // FCast hidden: still need a Cancel affordance so users can close the sheet.
                Spacer(Modifier.height(24.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Close", style = MaterialTheme.typography.titleMedium)
                    }
                }
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
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Split A/V",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "Video here, audio on the receiver. First use calibrates sync.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (permissionDenied) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Microphone permission required for sync calibration.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}

/**
 * Compact section header used by every protocol section in the picker. The title sits flush-
 * left with no top padding (sections control their own spacing via `Spacer.height(20.dp)`
 * before the header). Subtitle is optional — sections that don't need a one-liner skip it.
 */
@Composable
private fun SectionHeader(title: String, subtitle: String?) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )
    if (subtitle != null) {
        Spacer(Modifier.height(2.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ReachabilityDot(state = entry.state)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.receiver.name,
                        style = MaterialTheme.typography.titleMedium,
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
 * Minimal Cast / AirPlay receiver row. PR 5's redesign replaces this with the unified row used
 * for every protocol; for PR 3/4 this is just enough UI for users to actually pick a Chromecast
 * or AirPlay receiver without doing the full picker rework.
 *
 * Disabled rows render at half opacity with no click handler — used for AirPlay 2 devices we
 * detected but can't drive yet (pairing handshake ships in PR 6).
 */
/**
 * Help block that briefly explains each protocol in plain English. Inline (rather than a
 * dialog) so users don't have to context-switch on XR — the panel pushback would obscure the
 * picker itself. Closed via its own X.
 */
@Composable
private fun CastProtocolHelpRow(onDismiss: () -> Unit) {
    Spacer(Modifier.height(20.dp))
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        tonalElevation = 2.dp,
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "About the protocols",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismiss) {
                    Text("Close")
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = "FCast — an open casting protocol. SpatialFin → SpatialFin gives the best " +
                    "result (full ASS subtitle styling and Split A/V).",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Chromecast — Google's protocol. Works on Chromecasts, Google TVs, Nest Hubs. " +
                    "Styled subtitles burn into the video on the server automatically.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "AirPlay — Apple's protocol. Works on Apple TVs (≤ tvOS 9) and many AV " +
                    "receivers. HomePods and newer Apple TVs need pairing, which isn't supported yet.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun CastReceiverRow(
    receiver: dev.jdtech.jellyfin.cast.CastReceiver,
    badge: String,
    enabled: Boolean,
    unsupportedHint: String?,
    onClick: () -> Unit,
) {
    val rowAlpha = if (enabled) 1f else 0.5f
    Surface(
        modifier = Modifier.fillMaxWidth().alpha(rowAlpha),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        tonalElevation = 1.dp,
        // Surface.onClick disables the ripple when null; use that so disabled rows actively
        // can't be picked rather than relying on the caller honoring the hint.
        onClick = if (enabled) onClick else { -> },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (enabled) Color(0xFF34C759) else Color(0xFF8E8E93)),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = receiver.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
                val subtitle = unsupportedHint ?: receiver.modelName
                    ?: "${receiver.host}:${receiver.port}"
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
                    text = badge,
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
