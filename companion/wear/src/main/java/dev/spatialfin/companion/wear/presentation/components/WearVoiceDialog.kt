package dev.spatialfin.companion.wear.presentation.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import dev.spatialfin.companion.wear.presentation.theme.WearDarkPrimary
import dev.spatialfin.companion.wear.presentation.theme.WearDarkPrimaryContainer
import dev.spatialfin.companion.wear.voice.VoiceRecordingState

@Composable
fun WearVoiceDialog(
    state: VoiceRecordingState,
    onStopCapture: () -> Unit,
    onRequestPermission: () -> Unit,
    onDismiss: () -> Unit,
) {
    // SpeechRecognizer RMS arrives pre-normalized to 0..1 from WearVoiceCapture.
    val rms = when (state) {
        is VoiceRecordingState.Recording -> state.amplitudeRms.coerceIn(0f, 1f)
        else -> 0f
    }
    val animatedScale by animateFloatAsState(targetValue = 1f + rms * 0.5f, label = "mic_pulse")

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "Voice Assistant",
                style = MaterialTheme.typography.titleMedium,
                color = WearDarkPrimary,
            )
            Spacer(modifier = Modifier.height(12.dp))

            // Pulsing Mic Circle
            Box(
                modifier = Modifier
                    .size((64 * animatedScale).dp)
                    .clip(CircleShape)
                    .background(WearDarkPrimaryContainer)
                    .clickable { onStopCapture() },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "🎤",
                    fontSize = 28.sp,
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            val statusText = when (state) {
                is VoiceRecordingState.Idle -> "Tap to speak"
                is VoiceRecordingState.Connecting -> "Starting…"
                is VoiceRecordingState.Recording -> "Listening…"
                is VoiceRecordingState.Transcribed -> "“${state.transcript}”"
                is VoiceRecordingState.Completed -> state.message
                is VoiceRecordingState.Error -> state.error
                is VoiceRecordingState.PermissionRequired -> "Microphone access is needed"
            }

            Text(
                text = statusText,
                style = MaterialTheme.typography.bodyMedium,
                color = when (state) {
                    is VoiceRecordingState.Error -> Color(0xFFFFB4AB)
                    is VoiceRecordingState.PermissionRequired -> Color(0xFFFFB4AB)
                    else -> Color.LightGray
                },
                textAlign = TextAlign.Center,
            )

            if (state is VoiceRecordingState.PermissionRequired) {
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = onRequestPermission,
                    modifier = Modifier.size(width = 120.dp, height = 36.dp),
                ) {
                    Text("Grant access")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = {
                    onStopCapture()
                    onDismiss()
                },
                modifier = Modifier.size(width = 100.dp, height = 36.dp),
            ) {
                Text(if (state is VoiceRecordingState.Recording) "Done" else "Close")
            }
        }
    }
}
