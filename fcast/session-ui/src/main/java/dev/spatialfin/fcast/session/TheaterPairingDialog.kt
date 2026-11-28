package dev.spatialfin.fcast.session

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.cast.CastReceiver
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.PairPinResponse
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.SendspinAvrDiscoveryBrowser
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.SendspinAvrHttpClient
import kotlinx.coroutines.launch

/**
 * Pending pairing request surfaced by [CastSessionManager] when tapping an unpaired `spatialfin-avr`
 * room, choosing manual Theater Audio setup, scanning a `spatialfin-avr://pair?...` QR code, or
 * recovering from an `auth_invalid` (`401`) token revocation.
 */
data class TheaterPairingRequest(
    val host: String = "",
    val port: Int = SendspinAvrDiscoveryBrowser.SENDSPIN_AVR_DEFAULT_PORT,
    val useTls: Boolean = false,
    val initialPin: String = "",
    val serverIdHint: String? = null,
    val roomNameHint: String? = null,
    val repairReason: String? = null,
    val tlsSpkiSha256: String? = null,
)

/**
 * Dialog for pairing a new or revoked `spatialfin-avr` room via 6-digit PIN, QR scan URI, or
 * manual `host:port` + TLS toggle.
 */
@Composable
fun TheaterPairingDialog(
    sessionManager: CastSessionManager,
    onRequestQrScan: (((String) -> Unit) -> Unit)? = null,
) {
    val request by sessionManager.pendingTheaterPairing.collectAsState()
    val activeRequest = request ?: return

    var host by remember(activeRequest) { mutableStateOf(activeRequest.host) }
    var portText by remember(activeRequest) { mutableStateOf(activeRequest.port.toString()) }
    var useTls by remember(activeRequest) { mutableStateOf(activeRequest.useTls) }
    var tlsSpkiSha256 by remember(activeRequest) { mutableStateOf(activeRequest.tlsSpkiSha256) }
    var pin by remember(activeRequest) { mutableStateOf(activeRequest.initialPin) }
    var submitting by remember(activeRequest) { mutableStateOf(false) }
    var errorMessage by remember(activeRequest) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    androidx.xr.compose.spatial.SpatialDialog(
        onDismissRequest = { if (!submitting) sessionManager.dismissTheaterPairing() },
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 560.dp)
                .heightIn(max = 680.dp),
            shape = RoundedCornerShape(28.dp),
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 24.dp, vertical = 24.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = activeRequest.roomNameHint?.takeIf { it.isNotBlank() }?.let {
                        "Pair $it"
                    } ?: "Pair Theater Audio Room",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = activeRequest.repairReason
                        ?: "Enter the 6-digit PIN shown by spatialfin-avr, or scan its pairing QR code.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (activeRequest.repairReason != null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Spacer(Modifier.height(16.dp))

                if (onRequestQrScan != null) {
                    OutlinedButton(
                        onClick = {
                            onRequestQrScan { rawUri ->
                                val parsed = sessionManager.parseTheaterPairQrUri(rawUri)
                                if (parsed != null) {
                                    host = parsed.host
                                    portText = parsed.port.toString()
                                    useTls = parsed.useTls
                                    tlsSpkiSha256 = parsed.tlsSpkiSha256
                                    if (parsed.initialPin.isNotBlank()) {
                                        pin = parsed.initialPin
                                    }
                                    errorMessage = null
                                    if (parsed.host.isNotBlank() && parsed.port in 1..65535 && parsed.initialPin.length == 6) {
                                        submitting = true
                                        scope.launch {
                                            val res = sessionManager.pairTheaterRoom(
                                                host = parsed.host,
                                                port = parsed.port,
                                                useTls = parsed.useTls,
                                                pin = parsed.initialPin,
                                                roomNameHint = parsed.roomNameHint ?: activeRequest.roomNameHint,
                                                tlsSpkiSha256 = parsed.tlsSpkiSha256,
                                            )
                                            submitting = false
                                            res.onFailure { err ->
                                                errorMessage = err.message ?: "Pairing failed."
                                            }
                                        }
                                    }
                                } else {
                                    errorMessage = "Unrecognized spatialfin-avr QR code."
                                }
                            }
                        },
                        enabled = !submitting,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Scan Pairing QR Code")
                    }
                    Spacer(Modifier.height(14.dp))
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it; errorMessage = null },
                        label = { Text("Host or IP") },
                        singleLine = true,
                        enabled = !submitting,
                        modifier = Modifier.weight(2f),
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = portText,
                        onValueChange = { portText = it.filter(Char::isDigit).take(5); errorMessage = null },
                        label = { Text("Port") },
                        singleLine = true,
                        enabled = !submitting,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Use TLS (HTTPS / WSS)",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = "Enable when fronted by a LAN TLS reverse proxy.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = useTls,
                        onCheckedChange = { useTls = it },
                        enabled = !submitting,
                    )
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter(Char::isDigit).take(6); errorMessage = null },
                    label = { Text("6-Digit Pairing PIN") },
                    singleLine = true,
                    enabled = !submitting,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (errorMessage != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = errorMessage!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Spacer(Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = { sessionManager.dismissTheaterPairing() },
                        enabled = !submitting,
                    ) {
                        Text("Cancel")
                    }
                    Spacer(Modifier.width(12.dp))
                    val parsedPort = portText.toIntOrNull() ?: 0
                    val canSubmit = !submitting &&
                        host.trim().isNotEmpty() &&
                        parsedPort in 1..65535 &&
                        pin.length == 6
                    Button(
                        enabled = canSubmit,
                        onClick = {
                            submitting = true
                            errorMessage = null
                            scope.launch {
                                val res = sessionManager.pairTheaterRoom(
                                    host = host.trim(),
                                    port = parsedPort,
                                    useTls = useTls,
                                    pin = pin,
                                    roomNameHint = activeRequest.roomNameHint,
                                    tlsSpkiSha256 = tlsSpkiSha256,
                                )
                                submitting = false
                                res.onFailure { err ->
                                    errorMessage = err.message ?: "Pairing failed."
                                }
                            }
                        },
                    ) {
                        if (submitting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("Pair Room")
                    }
                }
            }
        }
    }
}

/**
 * Management dialog for an already-paired `spatialfin-avr` room: arms a secondary-device PIN
 * (`POST /api/v1/pair/pin`), launches room calibration, or revokes this client's token
 * (`DELETE /api/v1/clients/{client_id}`).
 */
@Composable
fun TheaterRoomManageDialog(
    receiver: CastReceiver,
    sessionManager: CastSessionManager,
    onOpenCalibration: ((CastReceiver) -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var armedPin by remember(receiver.id) { mutableStateOf<PairPinResponse?>(null) }
    var busy by remember(receiver.id) { mutableStateOf(false) }
    var statusText by remember(receiver.id) { mutableStateOf<String?>(null) }

    androidx.xr.compose.spatial.SpatialDialog(
        onDismissRequest = { if (!busy) onDismiss() },
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 520.dp),
            shape = RoundedCornerShape(28.dp),
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = receiver.name,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${receiver.host}:${receiver.port} · ${if (receiver.useTls) "TLS" else "LAN HTTP"}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))

                if (onOpenCalibration != null) {
                    Button(
                        onClick = {
                            onDismiss()
                            onOpenCalibration(receiver)
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Room Calibration & Speaker Setup")
                    }
                    Spacer(Modifier.height(10.dp))
                }

                OutlinedButton(
                    onClick = {
                        busy = true
                        statusText = null
                        scope.launch {
                            when (val res = sessionManager.armTheaterPairPin(receiver)) {
                                is SendspinAvrHttpClient.HttpResult.Success -> {
                                    armedPin = res.value
                                    statusText = "Share this PIN with another device to pair."
                                }
                                is SendspinAvrHttpClient.HttpResult.Error -> {
                                    statusText = "Could not arm pairing PIN: ${res.message}"
                                }
                            }
                            busy = false
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Pair Another Device (Arm PIN)")
                }

                armedPin?.let { pinResp ->
                    Spacer(Modifier.height(12.dp))
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "Pairing PIN: ${pinResp.pin}",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = pinResp.qrUri,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }

                statusText?.let { msg ->
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        enabled = !busy,
                        onClick = {
                            busy = true
                            scope.launch {
                                sessionManager.unpairTheaterRoom(receiver)
                                busy = false
                                onDismiss()
                            }
                        },
                    ) {
                        Text(
                            text = "Unpair Room",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    TextButton(enabled = !busy, onClick = onDismiss) {
                        Text("Close")
                    }
                }
            }
        }
    }
}

/**
 * Confirmation prompt shown when `spatialfin-avr` returns `session_busy` (`409` / `rpc.error`).
 * Retries `session.start(force_takeover = true)` only on explicit user confirmation per Spec 0.
 */
@Composable
fun TheaterTakeoverDialog(
    sessionManager: CastSessionManager,
) {
    val snapshot by sessionManager.theaterSnapshot.collectAsState()
    val banner = snapshot?.banner?.takeIf { it.requiresTakeoverConfirm } ?: return
    val scope = rememberCoroutineScope()

    androidx.xr.compose.spatial.SpatialDialog(
        onDismissRequest = {
            scope.launch { sessionManager.confirmTheaterTakeover(false) }
        },
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 480.dp),
            shape = RoundedCornerShape(28.dp),
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = "Room In Use — Take Over?",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = banner.message.ifBlank {
                        "Another device is currently streaming to ${snapshot?.serverName ?: "this Theater Audio room"}."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        onClick = { scope.launch { sessionManager.confirmTheaterTakeover(false) } },
                    ) {
                        Text("Cancel")
                    }
                    Spacer(Modifier.width(12.dp))
                    Button(
                        onClick = { scope.launch { sessionManager.confirmTheaterTakeover(true) } },
                    ) {
                        Text("Take Over Room")
                    }
                }
            }
        }
    }
}
