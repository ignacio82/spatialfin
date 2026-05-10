package dev.spatialfin.fcast.session

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.fcast.protocol.FCAST_DEFAULT_PORT
import dev.jdtech.jellyfin.fcast.sender.FCastReceiver
import dev.jdtech.jellyfin.fcast.sender.PickerEntry

/**
 * Session-aware cast picker. Reads the unified entries list from [FCastSessionManager], so
 * remembered receivers render immediately on open with their probing → online/offline state
 * flipping in place as TCP probes and the mDNS scan complete. Saves the user from a 1.5–4s
 * blank wait every time they tap the cast icon.
 *
 * Falls back to the simple [dev.jdtech.jellyfin.fcast.ui.FCastReceiverPickerSheet] for callers
 * outside the FCastSessionManager scope (the XR player still uses that one directly).
 */
@Composable
fun FCastSessionPickerSheet(
    sessionManager: FCastSessionManager,
    onReceiverPicked: (FCastReceiver) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val entries by sessionManager.pickerEntries.collectAsState()
    val isScanning by sessionManager.isScanning.collectAsState()
    var manualHost by remember { mutableStateOf("") }
    var manualPort by remember { mutableStateOf(FCAST_DEFAULT_PORT.toString()) }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Cast to FCast receiver",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Tap a remembered device — we'll check it's online while you choose. Streams use plain TCP — only cast on a trusted Wi-Fi.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (isScanning) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                }
            }
            Spacer(Modifier.height(16.dp))

            if (entries.isEmpty() && !isScanning) {
                Text("No receivers found yet. Add one manually below.")
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(entries, key = { "${it.receiver.host}:${it.receiver.port}" }) { entry ->
                        SessionReceiverRow(
                            entry = entry,
                            onClick = { onReceiverPicked(entry.receiver) },
                            onForget = {
                                sessionManager.forgetReceiver(entry.receiver.host, entry.receiver.port)
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            Text(
                text = "Add manually",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(8.dp))
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
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
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
                ) { Text("Cast") }
            }
        }
    }
}

@Composable
private fun SessionReceiverRow(
    entry: PickerEntry,
    onClick: () -> Unit,
    onForget: () -> Unit,
) {
    val rowEnabled = entry.state != PickerEntry.State.Offline
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (rowEnabled) Modifier.clickable(onClick = onClick) else Modifier),
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ReachabilityDot(state = entry.state)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.receiver.name,
                    fontWeight = FontWeight.Medium,
                    color = if (rowEnabled) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onForget) { Text("Forget") }
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
