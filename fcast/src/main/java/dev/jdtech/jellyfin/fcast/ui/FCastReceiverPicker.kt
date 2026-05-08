package dev.jdtech.jellyfin.fcast.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.fcast.discovery.FCastDiscovery
import dev.jdtech.jellyfin.fcast.protocol.FCAST_DEFAULT_PORT
import dev.jdtech.jellyfin.fcast.sender.FCastReceiver

/**
 * Surface-agnostic receiver picker sheet. Browses mDNS once when shown (caller can pass
 * `discoveryTimeoutMs` if it wants a longer scan), shows a manual host:port entry, and emits
 * the selected [FCastReceiver] via [onReceiverPicked].
 *
 * The sheet is a plain `Surface` so it composes inside any container (a `SpatialDialog` on XR,
 * an `AlertDialog` on Beam, a Leanback overlay on TV). Caller is responsible for the wrapping
 * dialog/sheet chrome.
 */
@Composable
fun FCastReceiverPickerSheet(
    onReceiverPicked: (FCastReceiver) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    discoveryTimeoutMs: Long = 4_000L,
) {
    val context = LocalContext.current
    val discovery = remember { FCastDiscovery(context) }
    var receivers by remember { mutableStateOf<List<FCastReceiver>>(emptyList()) }
    var isScanning by remember { mutableStateOf(true) }
    var manualHost by remember { mutableStateOf("") }
    var manualPort by remember { mutableStateOf(FCAST_DEFAULT_PORT.toString()) }

    LaunchedEffect(discovery) {
        isScanning = true
        receivers = runCatching { discovery.browse(discoveryTimeoutMs) }.getOrDefault(emptyList())
        isScanning = false
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text(
                text = "Cast to FCast receiver",
                style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Pick a receiver on your network or enter one manually. Streams use plain TCP — only cast on a trusted Wi-Fi.",
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(16.dp))

            if (isScanning) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.width(20.dp).height(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Scanning for receivers...")
                }
            } else if (receivers.isEmpty()) {
                Text("No receivers discovered. Add one manually below.")
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(receivers, key = { "${it.host}:${it.port}" }) { receiver ->
                        ReceiverRow(receiver = receiver, onClick = { onReceiverPicked(receiver) })
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            Text(
                text = "Manual host",
                style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
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

/**
 * AlertDialog convenience wrapper around [FCastReceiverPickerSheet]. Drop this in any 2D surface
 * (Beam, TV, debug screens) to get a working cast picker with one composable call. XR surfaces
 * should wrap the sheet in `SpatialDialog` instead so the parent panel pushes back correctly.
 */
@Composable
fun FCastReceiverPickerDialog(
    onReceiverPicked: (FCastReceiver) -> Unit,
    onDismiss: () -> Unit,
    discoveryTimeoutMs: Long = 4_000L,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = {},
        title = null,
        text = {
            FCastReceiverPickerSheet(
                onReceiverPicked = onReceiverPicked,
                onDismiss = onDismiss,
                discoveryTimeoutMs = discoveryTimeoutMs,
            )
        },
    )
}

@Composable
private fun ReceiverRow(receiver: FCastReceiver, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = receiver.name, fontWeight = FontWeight.Medium)
            Text(
                text = buildString {
                    append(receiver.host).append(':').append(receiver.port)
                    receiver.appName?.let { append(" • ").append(it) }
                },
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            )
        }
    }
}
