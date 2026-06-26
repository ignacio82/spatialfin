package dev.spatialfin.fcast.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.xr.compose.spatial.SpatialDialog

/**
 * Modal dialog that surfaces the [CastSessionManager.calibrationState] flow as user-visible
 * feedback. Uses [SpatialDialog] so the panel renders centered in the user's vision on XR
 * (parent panel pushes back); on Beam / TV it falls back to a regular dialog.
 *
 * Three non-Idle states:
 *  - Running: indeterminate progress + "be quiet" instructions. No cancel — calibration is
 *    short (~6 s) and cancelling cleanly is more code than it's worth.
 *  - Success: brief confirmation with measured latency. Tap OK to dismiss.
 *  - Failed: error reason + Dismiss. Retry is a re-tap of Play.
 *
 * Mount once per Compose root that participates in casting (NavigationRoot for XR,
 * BeamNavigationRoot for Beam). FCastGlobalPickerHost mounts it as a sibling so XR and Beam
 * roots get it for free; TV doesn't mount the picker host so split mode is hidden there.
 */
@Composable
fun SplitAvCalibrationDialog(
    sessionManager: CastSessionManager,
) {
    val state by sessionManager.calibrationState.collectAsState()
    when (val s = state) {
        is CastSessionManager.CalibrationState.Idle -> Unit
        is CastSessionManager.CalibrationState.Running -> RunningContent(s.receiverName)
        is CastSessionManager.CalibrationState.Success -> SuccessContent(
            latencyMs = s.latencyMs,
            onDismiss = { sessionManager.dismissCalibrationResult() },
        )
        is CastSessionManager.CalibrationState.Failed -> FailedContent(
            reason = s.reason,
            onDismiss = { sessionManager.dismissCalibrationResult() },
        )
    }
}

@Composable
private fun CalibrationDialogShell(
    onDismiss: () -> Unit,
    cancellable: Boolean,
    title: String,
    body: @Composable () -> Unit,
    confirm: (@Composable () -> Unit)? = null,
) {
    SpatialDialog(onDismissRequest = if (cancellable) onDismiss else { -> }) {
        Surface(
            shape = RoundedCornerShape(32.dp),
            tonalElevation = 6.dp,
            modifier = Modifier.widthIn(min = 640.dp, max = 880.dp),
        ) {
            Column(modifier = Modifier.padding(40.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(20.dp))
                body()
                if (confirm != null) {
                    Spacer(Modifier.height(28.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        confirm()
                    }
                }
            }
        }
    }
}

@Composable
private fun RunningContent(receiverName: String) {
    CalibrationDialogShell(
        onDismiss = {},
        cancellable = false,
        title = "Calibrating audio sync…",
        body = {
            Text(
                text = "Playing a calibration tone through $receiverName and listening for it.",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Be in a quiet room and don't speak. ~6 seconds.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(28.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(56.dp))
            }
        },
    )
}

@Composable
private fun SuccessContent(latencyMs: Int, onDismiss: () -> Unit) {
    CalibrationDialogShell(
        onDismiss = onDismiss,
        cancellable = true,
        title = "Audio sync calibrated",
        body = {
            Text(
                text = "Measured audio latency: $latencyMs ms.",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Starting playback…",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        confirm = {
            TextButton(onClick = onDismiss) {
                Text("OK", style = MaterialTheme.typography.titleMedium)
            }
        },
    )
}

@Composable
private fun FailedContent(reason: String, onDismiss: () -> Unit) {
    CalibrationDialogShell(
        onDismiss = onDismiss,
        cancellable = true,
        title = "Calibration failed",
        body = {
            Text(
                text = reason,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Tap Play again on the same item to retry — quieter room and louder " +
                    "receiver volume help the headset mic pick up all five chirps.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        confirm = {
            TextButton(onClick = onDismiss) {
                Text("Dismiss", style = MaterialTheme.typography.titleMedium)
            }
        },
    )
}
