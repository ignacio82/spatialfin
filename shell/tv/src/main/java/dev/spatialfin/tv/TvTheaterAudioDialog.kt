package dev.spatialfin.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SpeakerGroup
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import dev.jdtech.jellyfin.cast.CastProtocol
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.SendspinAvrDiscoveryBrowser
import dev.jdtech.jellyfin.fcast.sender.PickerEntry
import dev.spatialfin.fcast.session.CastSessionManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * D-pad-navigable Android TV Theater Audio room selector and manual PIN + SAS pairing dialog.
 *
 * Reuses the shared [CastSessionManager] singleton from `:fcast:session-ui` so paired rooms,
 * SPKI pins, sticky anti-downgrade, and multi-room lists stay synchronized with XR and Beam.
 */
@Composable
internal fun TvTheaterAudioDialog(
    sessionManager: CastSessionManager,
    onDismiss: () -> Unit,
) {
    val theaterReceivers by sessionManager.theaterReceivers.collectAsStateWithLifecycle()
    val pickedTarget by sessionManager.pickedTarget.collectAsStateWithLifecycle()
    val isScanning by sessionManager.isScanning.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var host by remember { mutableStateOf("") }
    var portText by remember { mutableStateOf(SendspinAvrDiscoveryBrowser.SENDSPIN_AVR_DEFAULT_PORT.toString()) }
    var useTls by remember { mutableStateOf(false) }
    var pin by remember { mutableStateOf("") }
    var probedSpki by remember { mutableStateOf<String?>(null) }
    var probedSas by remember { mutableStateOf<String?>(null) }
    var sasConfirmed by remember { mutableStateOf(false) }
    var probingSas by remember { mutableStateOf(false) }
    var pairingBusy by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val initialFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        sessionManager.rescanReceivers()
        delay(80L)
        runCatching { initialFocus.requestFocus() }
    }

    Dialog(
        onDismissRequest = { if (!pairingBusy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.72f)),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(min = 520.dp, max = 720.dp)
                    .heightIn(max = 640.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0xFF0F1720))
                    .padding(28.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Theater Audio (SpatialFin AVR)",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                        )
                        Text(
                            text = "Route movie audio to a paired spatialfin-avr room while playing video on this TV.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.72f),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    OutlinedButton(
                        onClick = { sessionManager.rescanReceivers() },
                        enabled = !isScanning && !pairingBusy,
                    ) {
                        if (isScanning) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(if (isScanning) "Scanning" else "Rescan")
                    }
                }

                // Local TV audio option
                val isLocalActive = pickedTarget?.protocol != CastProtocol.SendspinAvr
                TvTheaterSelectableRow(
                    modifier = Modifier.focusRequester(initialFocus),
                    title = "This TV (Local Audio)",
                    subtitle = "Decode and play audio directly through this TV / HDMI ARC",
                    selected = isLocalActive,
                    isLocal = true,
                    onClick = {
                        scope.launch {
                            sessionManager.stopCast()
                            onDismiss()
                        }
                    },
                )

                if (theaterReceivers.isNotEmpty()) {
                    Text(
                        text = "Discovered & Paired Rooms",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color(0xFF4FC3F7),
                        fontWeight = FontWeight.SemiBold,
                    )
                    theaterReceivers.forEach { entry ->
                        val rec = entry.receiver
                        val isPicked = pickedTarget?.id == rec.id
                        val stateBadge = when (entry.state) {
                            PickerEntry.State.Online -> "Online"
                            PickerEntry.State.Probing -> "Checking"
                            PickerEntry.State.Offline -> "Offline"
                        }
                        val layoutInfo = rec.modelName?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
                        val pairStatus = if (entry.isPaired) "Paired" else "Tap to pair"
                        val tlsTag = if (rec.useTls) " · TLS" else ""
                        TvTheaterSelectableRow(
                            title = rec.name,
                            subtitle = "$stateBadge$layoutInfo$tlsTag · $pairStatus (${rec.host}:${rec.port})",
                            selected = isPicked,
                            isLocal = false,
                            onClick = {
                                if (entry.isPaired) {
                                    sessionManager.pickCastReceiver(rec)
                                    onDismiss()
                                } else {
                                    host = rec.host
                                    portText = rec.port.toString()
                                    useTls = rec.useTls
                                    probedSpki = null
                                    probedSas = null
                                    sasConfirmed = false
                                    errorMessage = null
                                    statusMessage = "Enter the 6-digit PIN from ${rec.name} below to pair."
                                }
                            },
                        )
                    }
                }

                HorizontalDivider(color = Color.White.copy(alpha = 0.14f))

                Text(
                    text = "Pair Room by Host & 6-Digit PIN",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                )

                val fieldColors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFF4FC3F7),
                    unfocusedBorderColor = Color.White.copy(alpha = 0.35f),
                    focusedLabelColor = Color(0xFF4FC3F7),
                    unfocusedLabelColor = Color.White.copy(alpha = 0.7f),
                )

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = host,
                        onValueChange = {
                            host = it
                            probedSpki = null
                            probedSas = null
                            sasConfirmed = false
                            errorMessage = null
                        },
                        label = { Text("AVR Host / IP") },
                        singleLine = true,
                        colors = fieldColors,
                        modifier = Modifier.weight(2f),
                    )
                    OutlinedTextField(
                        value = portText,
                        onValueChange = {
                            portText = it.filter(Char::isDigit).take(5)
                            probedSpki = null
                            probedSas = null
                            sasConfirmed = false
                            errorMessage = null
                        },
                        label = { Text("Port") },
                        singleLine = true,
                        colors = fieldColors,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = pin,
                        onValueChange = {
                            pin = it.filter(Char::isDigit).take(6)
                            errorMessage = null
                        },
                        label = { Text("6-Digit PIN") },
                        singleLine = true,
                        colors = fieldColors,
                        modifier = Modifier.weight(1.2f),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = {
                            useTls = !useTls
                            probedSpki = null
                            probedSas = null
                            sasConfirmed = false
                            errorMessage = null
                        },
                        enabled = !pairingBusy,
                    ) {
                        Text(if (useTls) "TLS: Enabled (HTTPS/WSS)" else "TLS: Off (LAN HTTP)")
                    }
                    if (useTls) {
                        OutlinedButton(
                            enabled = !probingSas && !pairingBusy && host.trim().isNotEmpty() && (portText.toIntOrNull() ?: 0) in 1..65535,
                            onClick = {
                                val parsedPort = portText.toIntOrNull() ?: return@OutlinedButton
                                probingSas = true
                                errorMessage = null
                                scope.launch {
                                    val res = sessionManager.probeTheaterTlsSas(host.trim(), parsedPort)
                                    probingSas = false
                                    res.onSuccess { (spki, sas) ->
                                        probedSpki = spki
                                        probedSas = sas
                                        sasConfirmed = false
                                        statusMessage = "Verify this 8-char SAS matches spatialfin-avr: $sas"
                                    }.onFailure { err ->
                                        errorMessage = err.message ?: "Could not probe TLS certificate."
                                    }
                                }
                            },
                        ) {
                            Text(if (probingSas) "Probing SAS..." else "Verify Self-Signed SAS")
                        }
                    }
                }

                if (useTls && probedSas != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF1A2836))
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Certificate SAS: $probedSas",
                                style = MaterialTheme.typography.titleMedium,
                                color = Color(0xFF4FC3F7),
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = "Confirm this matches spatialfin-avr before pinning.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.78f),
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Button(onClick = { sasConfirmed = !sasConfirmed }) {
                            Text(if (sasConfirmed) "SAS Confirmed ✓" else "Confirm SAS Match")
                        }
                    }
                }

                statusMessage?.let { msg ->
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF81C784),
                    )
                }
                errorMessage?.let { err ->
                    Text(
                        text = err,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFEF9A9A),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        enabled = !pairingBusy,
                    ) {
                        Text("Close")
                    }
                    Spacer(Modifier.width(12.dp))
                    val parsedPort = portText.toIntOrNull() ?: 0
                    val tlsPinReady = !useTls || probedSpki == null || sasConfirmed
                    val canPair = !pairingBusy && host.trim().isNotEmpty() && parsedPort in 1..65535 && pin.length == 6 && tlsPinReady
                    Button(
                        enabled = canPair,
                        onClick = {
                            pairingBusy = true
                            errorMessage = null
                            scope.launch {
                                val res = sessionManager.pairTheaterRoom(
                                    host = host.trim(),
                                    port = parsedPort,
                                    useTls = useTls,
                                    pin = pin,
                                    tlsSpkiSha256 = if (useTls && sasConfirmed) probedSpki else null,
                                )
                                pairingBusy = false
                                res.onSuccess {
                                    onDismiss()
                                }.onFailure { err ->
                                    errorMessage = err.message ?: "Pairing failed."
                                }
                            }
                        },
                    ) {
                        if (pairingBusy) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("Pair & Select Room")
                    }
                }
            }
        }
    }
}

@Composable
private fun TvTheaterSelectableRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    isLocal: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .tvFocus(focused, shape)
            .clip(shape)
            .background(
                when {
                    selected -> Color(0xFF4FC3F7).copy(alpha = 0.22f)
                    focused -> Color.White.copy(alpha = 0.14f)
                    else -> Color.White.copy(alpha = 0.06f)
                },
                shape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (isLocal) Icons.Rounded.Tv else Icons.Rounded.SpeakerGroup,
            contentDescription = null,
            tint = if (selected) Color(0xFF4FC3F7) else Color.White.copy(alpha = 0.85f),
            modifier = Modifier.size(24.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.7f),
            )
        }
        if (selected) {
            Icon(
                imageVector = Icons.Rounded.CheckCircle,
                contentDescription = "Selected",
                tint = Color(0xFF4FC3F7),
                modifier = Modifier.size(22.dp),
            )
        }
    }
}
