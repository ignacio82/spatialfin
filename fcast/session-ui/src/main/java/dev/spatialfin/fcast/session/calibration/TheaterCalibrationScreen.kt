package dev.spatialfin.fcast.session.calibration

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import dev.jdtech.jellyfin.cast.CastReceiver
import java.util.Locale

/**
 * Pure-HTTP Theater Audio Rooms, Outputs & Calibration screen (mounted from cast picker / settings
 * on Beam and XR). Supports creating/renaming/deleting independent rooms on a single
 * `spatialfin-avr` server, assigning LAN Sendspin WS speakers, local PC HDMI/ALSA outputs, and
 * ESP32/Noise TCP sinks, speaker chime checks, Tier 2 USB-C OTG `UMIK-1` sweep capture, and
 * Tier 1 REW / Cavern QuickEQ bundle upload + apply/revert.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TheaterCalibrationScreen(
    receiver: CastReceiver,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenXrPinning: (() -> Unit)? = null,
    onRoomsChanged: (() -> Unit)? = null,
    viewModel: TheaterCalibrationViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(receiver.id) {
        viewModel.bindRoom(receiver)
    }

    val umikTxtPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            viewModel.persistUmikCalibrationUri(uri, uri.lastPathSegment)
        }
    }

    val tier1YamlPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            viewModel.uploadTier1Bundle(uri)
        }
    }

    var pendingMicAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val action = pendingMicAction
        pendingMicAction = null
        if (granted) {
            action?.invoke()
        }
    }

    val runWithMicPermission: (() -> Unit) -> Unit = { action ->
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            action()
        } else {
            pendingMicAction = action
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    val activeRoomTitle = state.roomProfile?.roomName
                        ?: state.rooms.firstOrNull { it.roomId == state.selectedRoomId }?.roomName
                        ?: receiver.name
                    Text(
                        text = "Theater Rooms · $activeRoomTitle",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "${receiver.host}:${receiver.port} · ${state.rooms.size.coerceAtLeast(1)} room(s)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { viewModel.refreshRoomState() }) {
                        Text("Refresh")
                    }
                    TextButton(onClick = onBack) {
                        Text("Close")
                    }
                }
            }

            if (state.isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            state.errorMessage?.let { err ->
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = err,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(14.dp),
                    )
                }
            }

            state.statusMessage?.let { msg ->
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = msg,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(14.dp),
                    )
                }
            }

            state.sweepWarning?.let { warn ->
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = warn,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            if (onOpenXrPinning != null) {
                Button(
                    onClick = onOpenXrPinning,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Pin Speakers in 3D (Galaxy XR 6DoF)")
                }
            }

            // Rooms Manager Card
            var showCreateRoomForm by remember { mutableStateOf(false) }
            var newRoomName by remember { mutableStateOf("") }
            var showRenameRoomForm by remember { mutableStateOf(false) }
            var renameRoomInput by remember { mutableStateOf("") }
            val activeRoom = state.rooms.firstOrNull { it.roomId == state.selectedRoomId }
                ?: state.rooms.firstOrNull()

            Surface(
                shape = RoundedCornerShape(20.dp),
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Rooms on This AVR Server",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = "Each room has its own speakers, layout, EQ, and independent cast target.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedButton(
                            enabled = !state.roomMutationBusy,
                            onClick = {
                                showCreateRoomForm = !showCreateRoomForm
                                showRenameRoomForm = false
                            },
                        ) {
                            Text(if (showCreateRoomForm) "Cancel" else "+ New Room")
                        }
                    }

                    if (state.rooms.isNotEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            state.rooms.forEach { room ->
                                FilterChip(
                                    selected = room.roomId == (state.selectedRoomId ?: activeRoom?.roomId),
                                    onClick = {
                                        showRenameRoomForm = false
                                        viewModel.selectRoom(room.roomId)
                                    },
                                    label = {
                                        val isPlaying = room.state.equals("playing", ignoreCase = true) || room.activeSessionId != null
                                        val suffix = if (isPlaying) " · Playing" else ""
                                        Text("${room.roomName} (${room.layout})$suffix")
                                    },
                                )
                            }
                        }
                    }

                    if (activeRoom != null) {
                        val activeRoomBusy = activeRoom.state.equals("playing", ignoreCase = true) || activeRoom.activeSessionId != null
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(
                                enabled = !state.roomMutationBusy,
                                onClick = {
                                    renameRoomInput = activeRoom.roomName
                                    showRenameRoomForm = !showRenameRoomForm
                                    showCreateRoomForm = false
                                },
                            ) {
                                Text(if (showRenameRoomForm) "Cancel Rename" else "Rename Room")
                            }
                            if (state.rooms.size > 1) {
                                TextButton(
                                    enabled = !state.roomMutationBusy && !activeRoomBusy,
                                    onClick = {
                                        showRenameRoomForm = false
                                        viewModel.deleteSelectedRoom { onRoomsChanged?.invoke() }
                                    },
                                ) {
                                    Text("Delete Room", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }

                    if (showCreateRoomForm) {
                        HorizontalDivider()
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedTextField(
                                value = newRoomName,
                                onValueChange = { newRoomName = it },
                                label = { Text("Room Name (e.g. Living Room, Family Room)") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            Button(
                                enabled = newRoomName.trim().isNotEmpty() && !state.roomMutationBusy,
                                onClick = {
                                    val nameToCreate = newRoomName.trim()
                                    newRoomName = ""
                                    showCreateRoomForm = false
                                    viewModel.createRoom(nameToCreate) { onRoomsChanged?.invoke() }
                                },
                            ) {
                                Text("Create")
                            }
                        }
                    }

                    if (showRenameRoomForm && activeRoom != null) {
                        HorizontalDivider()
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedTextField(
                                value = renameRoomInput,
                                onValueChange = { renameRoomInput = it },
                                label = { Text("New Name for ${activeRoom.roomName}") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            Button(
                                enabled = renameRoomInput.trim().isNotEmpty() && !state.roomMutationBusy,
                                onClick = {
                                    val clean = renameRoomInput.trim()
                                    showRenameRoomForm = false
                                    viewModel.renameSelectedRoom(clean) { onRoomsChanged?.invoke() }
                                },
                            ) {
                                Text("Save")
                            }
                        }
                    }
                }
            }

            // Section 0A: LAN Sendspin Speaker Discovery & Multi-Speaker Role Pairing
            var showManualSpeakerForm by remember { mutableStateOf(false) }
            var manualClientId by remember { mutableStateOf("") }
            var manualName by remember { mutableStateOf("") }
            var manualWsUrl by remember { mutableStateOf("ws://192.168.1.") }
            var manualRole by remember { mutableStateOf("FR") }
            var manualDelayMs by remember { mutableStateOf("15") }
            var editingSpeakerId by remember { mutableStateOf<String?>(null) }
            var editingRole by remember { mutableStateOf("FL") }
            var editingDelayMs by remember { mutableStateOf("15") }

            Surface(
                shape = RoundedCornerShape(20.dp),
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "LAN Sendspin Speakers",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            val layoutSummary = state.roomProfile?.let {
                                "${it.roomName} · Layout: ${it.layout} · ${it.speakers.size} channel(s)"
                            } ?: "Discover _sendspin._tcp speakers and assign roles"
                            Text(
                                text = layoutSummary,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                enabled = !state.isScanningSpeakers && !state.speakerMutationBusy,
                                onClick = { viewModel.discoverLanSpeakers() },
                            ) {
                                if (state.isScanningSpeakers) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.height(16.dp).width(16.dp),
                                        strokeWidth = 2.dp,
                                    )
                                    Spacer(Modifier.width(6.dp))
                                }
                                Text(if (state.isScanningSpeakers) "Scanning…" else "Scan LAN")
                            }
                            TextButton(onClick = { showManualSpeakerForm = !showManualSpeakerForm }) {
                                Text(if (showManualSpeakerForm) "Hide Manual" else "Manual URL")
                            }
                        }
                    }

                    if (state.discoveredSpeakers.isNotEmpty()) {
                        HorizontalDivider()
                        Text(
                            text = "Discovered on LAN (_sendspin._tcp)",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Medium,
                        )
                        state.discoveredSpeakers.forEach { disc ->
                            var selectedRole by remember(disc.clientId, disc.assignedRoles, state.selectedRoomId) {
                                mutableStateOf(
                                    disc.assignedRoles.firstOrNull()
                                        ?: if (state.pairedSpeakers.isEmpty()) "FL" else "FR",
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                tonalElevation = 2.dp,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = disc.name,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.SemiBold,
                                            )
                                            Text(
                                                text = "${disc.wsUrl} · ${if (disc.alreadyPaired) "Paired (${disc.assignedRoles.joinToString("/")})" else "Unpaired"}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        OutlinedButton(
                                            enabled = state.probingSpeakerUrl == null && !state.speakerMutationBusy,
                                            onClick = {
                                                viewModel.probeDiscoveredSpeaker(
                                                    wsUrl = disc.wsUrl,
                                                    clientId = disc.clientId,
                                                )
                                            },
                                        ) {
                                            Text(
                                                if (state.probingSpeakerUrl == disc.wsUrl) "Chiming…" else "Identify",
                                            )
                                        }
                                    }
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        TheaterCalibrationViewModel.AVAILABLE_SPEAKER_ROLES.forEach { role ->
                                            FilterChip(
                                                selected = selectedRole == role,
                                                onClick = { selectedRole = role },
                                                label = { Text(role) },
                                            )
                                        }
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End,
                                    ) {
                                        Button(
                                            enabled = !state.speakerMutationBusy,
                                            onClick = {
                                                viewModel.pairOrUpdateSpeaker(
                                                    clientId = disc.clientId,
                                                    name = disc.name,
                                                    wsUrl = disc.wsUrl,
                                                    roles = listOf(selectedRole),
                                                    onRoomsUpdated = { onRoomsChanged?.invoke() },
                                                )
                                            },
                                        ) {
                                            Text(if (disc.alreadyPaired) "Update Role ($selectedRole)" else "Pair as $selectedRole")
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (showManualSpeakerForm) {
                        HorizontalDivider()
                        Text(
                            text = "Add Speaker by Manual WebSocket URL",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Medium,
                        )
                        OutlinedTextField(
                            value = manualClientId,
                            onValueChange = { manualClientId = it },
                            label = { Text("Client ID (e.g. voice-pe-right)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = manualName,
                            onValueChange = { manualName = it },
                            label = { Text("Friendly Name (optional)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = manualWsUrl,
                                onValueChange = { manualWsUrl = it },
                                label = { Text("ws://ip:8928/sendspin") },
                                singleLine = true,
                                modifier = Modifier.weight(2f),
                            )
                            OutlinedTextField(
                                value = manualDelayMs,
                                onValueChange = { manualDelayMs = it.filter(Char::isDigit).take(3) },
                                label = { Text("HW Delay ms") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            TheaterCalibrationViewModel.AVAILABLE_SPEAKER_ROLES.forEach { role ->
                                FilterChip(
                                    selected = manualRole == role,
                                    onClick = { manualRole = role },
                                    label = { Text(role) },
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        ) {
                            OutlinedButton(
                                enabled = manualWsUrl.trim().isNotEmpty() && state.probingSpeakerUrl == null,
                                onClick = {
                                    viewModel.probeDiscoveredSpeaker(
                                        wsUrl = manualWsUrl.trim(),
                                        clientId = manualClientId.trim().takeIf { it.isNotEmpty() },
                                    )
                                },
                            ) {
                                Text("Identify")
                            }
                            Button(
                                enabled = manualClientId.trim().isNotEmpty() &&
                                    manualWsUrl.trim().isNotEmpty() &&
                                    !state.speakerMutationBusy,
                                onClick = {
                                    val delayUs = (manualDelayMs.toLongOrNull() ?: 15L) * 1_000L
                                    viewModel.pairOrUpdateSpeaker(
                                        clientId = manualClientId.trim(),
                                        name = manualName.trim().takeIf { it.isNotEmpty() },
                                        wsUrl = manualWsUrl.trim(),
                                        roles = listOf(manualRole),
                                        fixedHwDelayUs = delayUs,
                                        onRoomsUpdated = { onRoomsChanged?.invoke() },
                                    )
                                },
                            ) {
                                Text("Pair as $manualRole")
                            }
                        }
                    }
                }
            }

            // Section 0B: Direct PC HDMI / ALSA Outputs (e.g. Soundbar connected to AVR host PC)
            Surface(
                shape = RoundedCornerShape(20.dp),
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Local PC / HDMI Outputs (ALSA)",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = "Assign a soundbar or AVR wired directly to this PC's HDMI / Analog / USB output.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedButton(
                            enabled = !state.isScanningLocalOutputs && !state.speakerMutationBusy,
                            onClick = { viewModel.discoverLocalOutputs() },
                        ) {
                            if (state.isScanningLocalOutputs) {
                                CircularProgressIndicator(
                                    modifier = Modifier.height(16.dp).width(16.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(if (state.isScanningLocalOutputs) "Scanning…" else "Scan Local Outputs")
                        }
                    }

                    if (!state.localOutputsSupported) {
                        Text(
                            text = "Local ALSA outputs are not enabled on this spatialfin-avr build.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else if (state.localOutputs.isNotEmpty()) {
                        HorizontalDivider()
                        state.localOutputs.forEach { dev ->
                            val defaultPresetIdx = remember(dev.deviceId, dev.maxChannels) {
                                when {
                                    dev.maxChannels >= 8 -> 3
                                    dev.maxChannels >= 6 -> 2
                                    else -> 0
                                }
                            }
                            var selectedPresetIdx by remember(dev.deviceId, state.selectedRoomId) {
                                mutableStateOf(defaultPresetIdx)
                            }
                            var delayMsInput by remember(dev.deviceId) { mutableStateOf("12") }
                            val chosenPreset = TheaterCalibrationViewModel.LOCAL_LAYOUT_PRESETS
                                .getOrElse(selectedPresetIdx) { TheaterCalibrationViewModel.LOCAL_LAYOUT_PRESETS.first() }

                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                tonalElevation = 2.dp,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = dev.name,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.SemiBold,
                                            )
                                            val assignedNote = dev.assignedRoomId?.let { rid ->
                                                val rName = state.rooms.firstOrNull { it.roomId == rid }?.roomName ?: rid
                                                " · Assigned to $rName"
                                            } ?: ""
                                            Text(
                                                text = "${dev.deviceId} · ${dev.kind.uppercase()} · ${dev.minChannels}–${dev.maxChannels}ch${if (dev.inUse) " · In Use (cached)" else ""}$assignedNote",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            val capsParts = buildList {
                                                if (dev.supportedFormats.isNotEmpty()) {
                                                    add("PCM: ${dev.supportedFormats.joinToString("/")}")
                                                }
                                                if (dev.supportedChannelCounts.isNotEmpty()) {
                                                    add("Ch: ${dev.supportedChannelCounts.joinToString("/")}")
                                                }
                                            }
                                            if (capsParts.isNotEmpty()) {
                                                Text(
                                                    text = capsParts.joinToString(" · "),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.primary,
                                                )
                                            }
                                        }
                                        OutlinedButton(
                                            enabled = state.probingLocalDeviceId == null && !state.speakerMutationBusy,
                                            onClick = { viewModel.probeLocalOutput(dev.deviceId) },
                                        ) {
                                            Text(
                                                if (state.probingLocalDeviceId == dev.deviceId) "Chiming…" else "Identify",
                                            )
                                        }
                                    }

                                    dev.capabilityWarning?.takeIf { it.isNotBlank() }?.let { warn ->
                                        Surface(
                                            shape = RoundedCornerShape(10.dp),
                                            color = MaterialTheme.colorScheme.tertiaryContainer,
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            Text(
                                                text = warn,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                            )
                                        }
                                    }

                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        TheaterCalibrationViewModel.LOCAL_LAYOUT_PRESETS.forEachIndexed { idx, (label, roles) ->
                                            val supported = roles.size <= dev.maxChannels.coerceAtLeast(2)
                                            FilterChip(
                                                selected = selectedPresetIdx == idx,
                                                enabled = supported,
                                                onClick = { selectedPresetIdx = idx },
                                                label = { Text(label) },
                                            )
                                        }
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        OutlinedTextField(
                                            value = delayMsInput,
                                            onValueChange = { delayMsInput = it.filter(Char::isDigit).take(3) },
                                            label = { Text("HW Delay ms") },
                                            singleLine = true,
                                            modifier = Modifier.width(120.dp),
                                        )
                                        Button(
                                            enabled = !state.speakerMutationBusy,
                                            onClick = {
                                                val delayUs = (delayMsInput.toLongOrNull() ?: 12L) * 1_000L
                                                viewModel.pairLocalOutput(
                                                    deviceId = dev.deviceId,
                                                    name = dev.name,
                                                    roles = chosenPreset.second,
                                                    fixedHwDelayUs = delayUs,
                                                    onRoomsUpdated = { onRoomsChanged?.invoke() },
                                                )
                                            },
                                        ) {
                                            Text("Assign (${chosenPreset.first})")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Section 0C: ESP32 / Noise KKpsk2 TCP Sinks (Port 8937)
            var showNoiseProvisionForm by remember { mutableStateOf(false) }
            var noiseClientId by remember { mutableStateOf("esp32-family-71") }
            var noiseFriendlyName by remember { mutableStateOf("Family Room ESP32") }
            var noiseDelayMs by remember { mutableStateOf("14") }
            var noiseSelectedRoles by remember {
                mutableStateOf(listOf("FL", "FR", "FC", "LFE1", "SL", "SR", "BL", "BR"))
            }

            Surface(
                shape = RoundedCornerShape(20.dp),
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "ESP32 / Noise TCP Sinks (:8937)",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = "Provision encrypted Noise KKpsk2 PSK credentials for custom ESP32 or multi-channel DAC arrays.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedButton(onClick = { showNoiseProvisionForm = !showNoiseProvisionForm }) {
                            Text(if (showNoiseProvisionForm) "Hide" else "Add ESP32 Sink")
                        }
                    }

                    if (showNoiseProvisionForm) {
                        HorizontalDivider()
                        OutlinedTextField(
                            value = noiseClientId,
                            onValueChange = { noiseClientId = it },
                            label = { Text("Sink Client ID (e.g. esp32-family-fl or esp32-family-71)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = noiseFriendlyName,
                                onValueChange = { noiseFriendlyName = it },
                                label = { Text("Friendly Name") },
                                singleLine = true,
                                modifier = Modifier.weight(2f),
                            )
                            OutlinedTextField(
                                value = noiseDelayMs,
                                onValueChange = { noiseDelayMs = it.filter(Char::isDigit).take(3) },
                                label = { Text("HW Delay ms") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Text(
                            text = "Quick Layout Presets",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            TheaterCalibrationViewModel.LOCAL_LAYOUT_PRESETS.forEach { (label, presetRoles) ->
                                OutlinedButton(onClick = { noiseSelectedRoles = presetRoles }) {
                                    Text(label)
                                }
                            }
                        }
                        Text(
                            text = "Assigned Roles (${noiseSelectedRoles.size}ch)",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            TheaterCalibrationViewModel.AVAILABLE_SPEAKER_ROLES.forEach { role ->
                                val isSelected = role in noiseSelectedRoles
                                FilterChip(
                                    selected = isSelected,
                                    onClick = {
                                        noiseSelectedRoles = if (isSelected) {
                                            noiseSelectedRoles - role
                                        } else {
                                            noiseSelectedRoles + role
                                        }
                                    },
                                    label = { Text(role) },
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            Button(
                                enabled = noiseClientId.trim().isNotEmpty() &&
                                    noiseSelectedRoles.isNotEmpty() &&
                                    !state.speakerMutationBusy,
                                onClick = {
                                    val delayUs = (noiseDelayMs.toLongOrNull() ?: 14L) * 1_000L
                                    viewModel.provisionNoiseEndpoint(
                                        clientId = noiseClientId.trim(),
                                        name = noiseFriendlyName.trim().takeIf { it.isNotEmpty() },
                                        roles = noiseSelectedRoles,
                                        fixedHwDelayUs = delayUs,
                                        onRoomsUpdated = { onRoomsChanged?.invoke() },
                                    )
                                },
                            ) {
                                Text("Provision PSK (${noiseSelectedRoles.size}ch)")
                            }
                        }
                    }

                    state.lastProvisionedPskJson?.let { pskJson ->
                        HorizontalDivider()
                        Text(
                            text = "Provisioned Sink PSK JSON (copy to ESP32 / spatialfin-sink)",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            tonalElevation = 3.dp,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = pskJson,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            OutlinedButton(
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                    clipboard?.setPrimaryClip(ClipData.newPlainText("SpatialFin Sink PSK", pskJson))
                                },
                            ) {
                                Text("Copy PSK JSON")
                            }
                        }
                    }
                }
            }

            // Section 1: Connected Endpoints & Speaker Chime Verification
            Surface(
                shape = RoundedCornerShape(20.dp),
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "1. Paired Speakers, Roles & Chime Check",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    val endpoints = state.status?.connectedEndpoints.orEmpty()
                    if (endpoints.isNotEmpty()) {
                        Text(
                            text = "Connected endpoints: ${endpoints.joinToString { "${it.name} (${it.channelCount}ch, ${if (it.online) "online" else "offline"})" }}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TheaterCalibrationViewModel.GAIN_PRESETS_DBFS.forEach { preset ->
                            FilterChip(
                                selected = state.selectedGainDbfs == preset,
                                onClick = { viewModel.setGainDbfs(preset) },
                                label = { Text("${preset.toInt()} dBFS") },
                            )
                        }
                    }
                    val speakers = state.roomProfile?.speakers.orEmpty()
                    if (speakers.isEmpty()) {
                        Text(
                            text = "No outputs assigned to this room yet. Scan LAN Sendspin speakers, Local PC HDMI/ALSA outputs, or add an ESP32 sink above.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        val seenRemoveButtons = mutableSetOf<String>()
                        speakers.forEach { sp ->
                            val pairedMeta = state.pairedSpeakers.firstOrNull { it.clientId == sp.clientId }
                            val pairedWsUrl = pairedMeta?.wsUrl?.takeIf {
                                it.startsWith("ws://", ignoreCase = true) ||
                                    it.startsWith("wss://", ignoreCase = true)
                            }
                            val isEditingThis = editingSpeakerId == sp.clientId
                            val transportBadge = when {
                                sp.transportTier.contains("alsa", ignoreCase = true) ||
                                    pairedMeta?.transportTier?.contains("alsa", ignoreCase = true) == true -> "HDMI / ALSA"
                                sp.transportTier.contains("ws", ignoreCase = true) ||
                                    pairedWsUrl != null -> "Sendspin WS"
                                else -> "ESP32 / Noise TCP"
                            }
                            val showNonWsRemove = pairedWsUrl == null && seenRemoveButtons.add(sp.clientId)
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "${sp.role} · ${pairedMeta?.name ?: sp.clientId} (ch ${sp.channelIndex}) · $transportBadge",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium,
                                        )
                                        Text(
                                            text = "HW delay ${sp.fixedHwDelayUs / 1000} ms · Coord (${sp.coordM.joinToString(", ") { String.format(Locale.US, "%.2f", it) }} m)",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        OutlinedButton(onClick = { viewModel.playSpeakerChime(sp.role) }) {
                                            Text("Chime")
                                        }
                                        OutlinedButton(
                                            onClick = {
                                                viewModel.playSpeakerChime(
                                                    sp.role,
                                                    durationMs = 2000,
                                                    signal = "pink_noise",
                                                )
                                            },
                                        ) {
                                            Text("Noise")
                                        }
                                        if (pairedWsUrl != null) {
                                            TextButton(
                                                onClick = {
                                                    if (isEditingThis) {
                                                        editingSpeakerId = null
                                                    } else {
                                                        editingSpeakerId = sp.clientId
                                                        editingRole = sp.role
                                                        editingDelayMs = (sp.fixedHwDelayUs / 1000L).toString()
                                                    }
                                                },
                                            ) {
                                                Text(if (isEditingThis) "Done" else "Edit")
                                            }
                                        } else if (showNonWsRemove) {
                                            TextButton(
                                                enabled = !state.speakerMutationBusy,
                                                onClick = {
                                                    viewModel.unpairSpeaker(sp.clientId) {
                                                        onRoomsChanged?.invoke()
                                                    }
                                                },
                                            ) {
                                                Text("Remove", color = MaterialTheme.colorScheme.error)
                                            }
                                        }
                                    }
                                }
                                if (isEditingThis && pairedMeta != null && pairedWsUrl != null) {
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        tonalElevation = 2.dp,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(10.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            Text(
                                                text = "Assign Role & Hardware Delay ($pairedWsUrl)",
                                                style = MaterialTheme.typography.labelMedium,
                                            )
                                            FlowRow(
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                            ) {
                                                TheaterCalibrationViewModel.AVAILABLE_SPEAKER_ROLES.forEach { role ->
                                                    FilterChip(
                                                        selected = editingRole == role,
                                                        onClick = { editingRole = role },
                                                        label = { Text(role) },
                                                    )
                                                }
                                            }
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                            ) {
                                                OutlinedTextField(
                                                    value = editingDelayMs,
                                                    onValueChange = { editingDelayMs = it.filter(Char::isDigit).take(3) },
                                                    label = { Text("HW Delay (ms)") },
                                                    singleLine = true,
                                                    modifier = Modifier.width(130.dp),
                                                )
                                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                    TextButton(
                                                        enabled = !state.speakerMutationBusy,
                                                        onClick = {
                                                            editingSpeakerId = null
                                                            viewModel.unpairSpeaker(sp.clientId) {
                                                                onRoomsChanged?.invoke()
                                                            }
                                                        },
                                                    ) {
                                                        Text("Remove", color = MaterialTheme.colorScheme.error)
                                                    }
                                                    Button(
                                                        enabled = !state.speakerMutationBusy,
                                                        onClick = {
                                                            val delayUs = (editingDelayMs.toLongOrNull() ?: 15L) * 1_000L
                                                            editingSpeakerId = null
                                                            viewModel.pairOrUpdateSpeaker(
                                                                clientId = pairedMeta.clientId,
                                                                name = pairedMeta.name,
                                                                wsUrl = pairedWsUrl,
                                                                roles = listOf(editingRole),
                                                                fixedHwDelayUs = delayUs,
                                                                onRoomsUpdated = { onRoomsChanged?.invoke() },
                                                            )
                                                        },
                                                    ) {
                                                        Text("Save")
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Section 1B: Acoustic Lip-Sync Auto-Calibration (pure room audio hardware delay)
            Surface(
                shape = RoundedCornerShape(20.dp),
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "Acoustic Lip-Sync Auto-Calibration",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Hold this device at your main listening position (MLP) and measure pure room hardware delay automatically (subtracts speaker-to-MLP acoustic time-of-flight).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(
                            enabled = !state.lipSyncRunning &&
                                !state.lipSyncPreviewRunning &&
                                !state.sweepInProgress &&
                                state.roomProfile?.speakers?.isNotEmpty() == true,
                            onClick = {
                                runWithMicPermission { viewModel.runLipSyncAutoCalibration() }
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            if (state.lipSyncRunning) {
                                CircularProgressIndicator(
                                    modifier = Modifier.height(18.dp).width(18.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(if (state.lipSyncRunning) "Measuring 5 Chirps…" else "Auto-Calibrate Lip-Sync")
                        }
                        if (state.lipSyncRunning || state.lipSyncPreviewRunning) {
                            OutlinedButton(onClick = { viewModel.cancelLipSyncCalibration() }) {
                                Text("Cancel")
                            }
                        }
                    }

                    if (state.lipSyncPreviewRunning) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = if (state.lipSyncFlashActive) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxSize(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = if (state.lipSyncFlashActive) "FLASH!" else "Watch for Flash + Chirp Sync",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (state.lipSyncFlashActive) {
                                        MaterialTheme.colorScheme.onPrimary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                        }
                    }

                    state.lipSyncResult?.let { measured ->
                        val pendingMs = state.lipSyncPendingDelayMs ?: (measured.medianHwDelayUs / 1_000L)
                        HorizontalDivider()
                        Text(
                            text = "Measured Hardware Delay: $pendingMs ms",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = String.format(
                                Locale.US,
                                "Valid pulses: %d/5 · Spread (IQR): %.1f ms · Acoustic ToF subtracted: %.1f ms (%.2f m) · Clock unc: %.1f ms",
                                measured.validPulseCount,
                                measured.iqrUs / 1_000.0,
                                measured.acousticTofUs / 1_000.0,
                                measured.speakerDistanceMeters,
                                measured.clockUncertaintyUs / 1_000.0,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        measured.pulses.forEach { p ->
                            Text(
                                text = String.format(
                                    Locale.US,
                                    "  Pulse #%d: %.1f ms · SNR %.1f dB%s",
                                    p.pulseIndex,
                                    p.measuredHwDelayUs / 1_000.0,
                                    p.snrDb,
                                    if (p.kept) "" else " (outlier rejected)",
                                ),
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                color = if (p.kept) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedButton(onClick = { viewModel.nudgeLipSyncDelayMs(-5L) }) {
                                Text("-5 ms")
                            }
                            OutlinedButton(onClick = { viewModel.nudgeLipSyncDelayMs(+5L) }) {
                                Text("+5 ms")
                            }
                            OutlinedButton(
                                enabled = !state.lipSyncPreviewRunning && !state.lipSyncRunning,
                                onClick = { viewModel.playLipSyncConfirmationPreview() },
                            ) {
                                Text("Flash Preview")
                            }
                        }
                        Button(
                            enabled = !state.isLoading && !state.lipSyncRunning,
                            onClick = {
                                viewModel.saveLipSyncDelayToProfile { onRoomsChanged?.invoke() }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Save ${pendingMs} ms to Room Profile")
                        }
                    }
                }
            }

            // Section 2: Tier 2 UMIK-1 Capture & SPL Headroom
            Surface(
                shape = RoundedCornerShape(20.dp),
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "2. Tier 2 USB-C UMIK-1 Sweep",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val micSummary = state.usbMicrophones.firstOrNull { it.id == state.selectedUsbMicId }
                            ?.productName
                            ?: "No USB mic detected (using fallback/disconnected)"
                        Text(
                            text = micSummary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { viewModel.refreshUsbMicrophones() }) {
                            Text("Rescan USB")
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = { umikTxtPicker.launch(arrayOf("text/plain", "*/*")) },
                        ) {
                            Text(state.umikCalFileName ?: "Select UMIK .txt")
                        }
                        FilterChip(
                            selected = state.micAngleDeg == 0,
                            onClick = { viewModel.setMicAngleDeg(0) },
                            label = { Text("0°") },
                        )
                        FilterChip(
                            selected = state.micAngleDeg == 90,
                            onClick = { viewModel.setMicAngleDeg(90) },
                            label = { Text("90°") },
                        )
                    }

                    val normalizedPeak = ((state.livePeakDbfs + 60f) / 60f).coerceIn(0f, 1f)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OutlinedButton(
                            onClick = {
                                runWithMicPermission { viewModel.toggleSplHeadroomMeter() }
                            },
                        ) {
                            Text(if (state.splMeterRunning) "Stop SPL Meter" else "Check SPL Headroom")
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = String.format(Locale.US, "Peak: %.1f dBFS", state.livePeakDbfs),
                                style = MaterialTheme.typography.labelMedium,
                            )
                            LinearProgressIndicator(
                                progress = { normalizedPeak },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(
                            enabled = !state.sweepInProgress && state.roomProfile != null,
                            onClick = {
                                runWithMicPermission { viewModel.runTier2Sweep() }
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            if (state.sweepInProgress) {
                                CircularProgressIndicator(modifier = Modifier.height(18.dp).width(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(if (state.sweepInProgress) "Recording Sweep…" else "Start Full Room Sweep")
                        }
                        if (state.sweepInProgress) {
                            OutlinedButton(onClick = { viewModel.cancelActiveSweep() }) {
                                Text("Cancel")
                            }
                        }
                    }

                    state.latestSweepResult?.let { result ->
                        HorizontalDivider()
                        Text(
                            text = "Sweep Results · ${result.calibrationId} (delay ${result.dspGroupDelayUs / 1000.0} ms)",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        result.channels.forEach { ch ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${ch.role} · SNR ${String.format(Locale.US, "%.1f", ch.snrDb)} dB · Trim ${String.format(Locale.US, "%+.1f", ch.trimDb)} dB",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Medium,
                                    )
                                    val flags = buildList {
                                        if (ch.clipped) add("CLIPPED")
                                        if (ch.needsRemeasure) add("NEEDS RE-MEASURE")
                                        if (ch.polarityInvert) add("POLARITY INV")
                                    }
                                    if (flags.isNotEmpty()) {
                                        Text(
                                            text = flags.joinToString(" · "),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                                TextButton(
                                    enabled = !state.sweepInProgress,
                                    onClick = {
                                        runWithMicPermission {
                                            viewModel.runTier2Sweep(
                                                rolesOverride = listOf(ch.role),
                                                existingCalibrationId = result.calibrationId,
                                            )
                                        }
                                    },
                                ) {
                                    Text("Re-measure")
                                }
                            }
                        }
                        Button(
                            enabled = result.readyToApply && !state.isLoading,
                            onClick = { viewModel.applyCalibration(result.calibrationId) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Apply Staged Room EQ")
                        }
                    }
                }
            }

            // Section 3: Tier 1 REW / Cavern QuickEQ Bundle Upload & Revert
            Surface(
                shape = RoundedCornerShape(20.dp),
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "3. Tier 1 REW / Cavern Bundle & Rollback",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedButton(
                            enabled = !state.isLoading,
                            onClick = { tier1YamlPicker.launch(arrayOf("text/yaml", "text/plain", "*/*")) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Upload CamillaDSP YAML")
                        }
                        OutlinedButton(
                            enabled = !state.isLoading,
                            onClick = { viewModel.revertCalibration() },
                        ) {
                            Text("Revert EQ")
                        }
                    }
                    state.latestBundleResult?.let { bundle ->
                        Button(
                            enabled = bundle.readyToApply && !state.isLoading,
                            onClick = { viewModel.applyCalibration(bundle.calibrationId) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Apply Bundle (${bundle.calibrationId})")
                        }
                    }
                }
            }
        }
    }
}
