package dev.spatialfin.fcast.session.calibration

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import dev.jdtech.jellyfin.cast.CastReceiver
import java.util.Locale

/**
 * Pure-HTTP Theater Audio Room Calibration screen (mounted from cast picker / settings on Beam
 * and XR). Supports speaker chime verification, stepped SPL headroom checks (`<= -12 dBFS`),
 * Tier 2 USB-C OTG `UMIK-1` sweep capture + SAF `.txt` upload + single-channel re-measure,
 * Tier 1 REW / Cavern QuickEQ bundle upload, and apply/revert.
 */
@Composable
fun TheaterCalibrationScreen(
    receiver: CastReceiver,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenXrPinning: (() -> Unit)? = null,
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

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            viewModel.toggleSplHeadroomMeter()
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
                    Text(
                        text = "Theater Calibration · ${receiver.name}",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "${receiver.host}:${receiver.port} · Pure HTTP REST",
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
                        text = "1. Speakers & Chime Check",
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
                            text = "No speakers loaded from room profile yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        speakers.forEach { sp ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${sp.role} · ${sp.clientId} (ch ${sp.channelIndex})",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                    )
                                    Text(
                                        text = "Coord: (${sp.coordM.joinToString(", ") { String.format(Locale.US, "%.2f", it) }} m)",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                OutlinedButton(onClick = { viewModel.playSpeakerChime(sp.role) }) {
                                    Text("Chime")
                                }
                            }
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
                                val granted = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.RECORD_AUDIO,
                                ) == PackageManager.PERMISSION_GRANTED
                                if (granted) {
                                    viewModel.toggleSplHeadroomMeter()
                                } else {
                                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
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
                            onClick = { viewModel.runTier2Sweep() },
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
                                        viewModel.runTier2Sweep(
                                            rolesOverride = listOf(ch.role),
                                            existingCalibrationId = result.calibrationId,
                                        )
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
