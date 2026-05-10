package dev.spatialfin.fcast.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
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

/**
 * Modal dialog that surfaces the [FCastSessionManager.calibrationState] flow as user-visible
 * feedback. The calibration itself runs inside [FCastSessionManager.castSpatialItemSplitAv];
 * this composable just renders state transitions:
 *
 *  - Idle: nothing shown.
 *  - Running: an indeterminate progress dialog. No cancel button — calibration is short
 *    (~6 s) and cancelling it cleanly is more code than it's worth.
 *  - Success: a brief confirmation with the measured latency. Tapping OK dismisses.
 *  - Failed: an error dialog with the reason. Retry is a re-tap of the play button — we don't
 *    auto-retry because the failure is usually environmental (noisy room, missing receiver).
 *
 * Mount this once per Compose root that participates in casting (NavigationRoot for XR,
 * BeamNavigationRoot for Beam). The TV root doesn't need it since split mode is hidden there.
 */
@Composable
fun SplitAvCalibrationDialog(
    sessionManager: FCastSessionManager,
) {
    val state by sessionManager.calibrationState.collectAsState()
    when (val s = state) {
        is FCastSessionManager.CalibrationState.Idle -> Unit
        is FCastSessionManager.CalibrationState.Running -> RunningContent(s.receiverName)
        is FCastSessionManager.CalibrationState.Success -> SuccessContent(
            latencyMs = s.latencyMs,
            onDismiss = { sessionManager.dismissCalibrationResult() },
        )
        is FCastSessionManager.CalibrationState.Failed -> FailedContent(
            reason = s.reason,
            onDismiss = { sessionManager.dismissCalibrationResult() },
        )
    }
}

@Composable
private fun RunningContent(receiverName: String) {
    AlertDialog(
        onDismissRequest = {},  // not cancellable
        confirmButton = {},
        title = {
            Text("Calibrating audio sync…", fontWeight = FontWeight.SemiBold)
        },
        text = {
            Column {
                Text("Playing a calibration tone through $receiverName and listening for it.")
                Spacer(Modifier.height(12.dp))
                Text(
                    "Be in a quiet room and don't speak. ~6 seconds.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(36.dp))
                }
            }
        },
        shape = RoundedCornerShape(20.dp),
    )
}

@Composable
private fun SuccessContent(latencyMs: Int, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("OK") }
        },
        title = {
            Text("Audio sync calibrated", fontWeight = FontWeight.SemiBold)
        },
        text = {
            Text("Measured audio latency: $latencyMs ms. Starting playback…")
        },
        shape = RoundedCornerShape(20.dp),
    )
}

@Composable
private fun FailedContent(reason: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        },
        title = {
            Text("Calibration failed", fontWeight = FontWeight.SemiBold)
        },
        text = {
            Surface(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(0.dp)) {
                    Text(reason)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Try again from a quieter room, with the receiver's volume audible " +
                            "but not loud enough to clip.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        shape = RoundedCornerShape(20.dp),
    )
}
