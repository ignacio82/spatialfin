package dev.spatialfin.companion.wear.presentation.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.ScrollIndicator
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.scrollTransform
import dev.spatialfin.companion.wear.presentation.theme.WearDarkError
import dev.spatialfin.companion.wear.presentation.theme.WearDarkOnPrimary
import dev.spatialfin.companion.wear.presentation.theme.WearDarkOnSurfaceVariant
import dev.spatialfin.companion.wear.presentation.theme.WearDarkOutline
import dev.spatialfin.companion.wear.presentation.theme.WearDarkPrimary
import dev.spatialfin.companion.wear.presentation.theme.WearDarkPrimaryContainer
import dev.spatialfin.companion.wear.presentation.theme.WearIcons
import dev.spatialfin.companion.wear.presentation.theme.WearTitleBright
import dev.spatialfin.companion.wear.presentation.theme.WearVectorIcon
import dev.spatialfin.companion.wear.voice.VoiceRecordingState

/**
 * Frames 11 and 12 — wrist voice.
 *
 * Listening is the one place the design system allows a decorative loop: three concentric rings
 * breathing with the normalised RMS the capture layer already emits. Everything else here is
 * static, because the answer is the point.
 *
 * Uses [TransformingLazyColumn] so that with larger accessibility font sizes or longer transcribed
 * phrases the action controls never get cut off by screen edges.
 */
@Composable
fun WearVoiceDialog(
    state: VoiceRecordingState,
    onStopCapture: () -> Unit,
    onRequestPermission: () -> Unit,
    onDismiss: () -> Unit,
) {
    // SpeechRecognizer RMS arrives pre-normalized to 0..1 from WearVoiceCapture.
    val rms =
        when (state) {
            is VoiceRecordingState.Recording -> state.amplitudeRms.coerceIn(0f, 1f)
            else -> 0f
        }
    val pulse by animateFloatAsState(targetValue = rms, label = "voice_pulse")
    val listening =
        state is VoiceRecordingState.Recording || state is VoiceRecordingState.Connecting
    val listState = rememberTransformingLazyColumnState()

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF06070A))) {
        Box(
            modifier =
                Modifier.fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors =
                                listOf(
                                    WearDarkPrimaryContainer.copy(
                                        alpha = if (listening) 0.5f else 0.28f
                                    ),
                                    Color.Transparent,
                                )
                        )
                    )
        )

        if (listening) {
            ListeningRings(amplitude = pulse, modifier = Modifier.fillMaxSize())
        }

        TransformingLazyColumn(
            state = listState,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.scrollTransform(this).padding(horizontal = 6.dp),
                ) {
                    if (listening) {
                        Box(
                            modifier =
                                Modifier.size((60 + pulse * 6).dp)
                                    .clip(CircleShape)
                                    .background(WearDarkPrimary)
                                    .clickable { onStopCapture() },
                            contentAlignment = Alignment.Center,
                        ) {
                            WearVectorIcon(
                                icon = WearIcons.Mic,
                                contentDescription = "Stop listening",
                                tint = WearDarkOnPrimary,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    } else {
                        WearVectorIcon(
                            icon = WearIcons.Mic,
                            contentDescription = null,
                            tint = WearDarkOutline,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }

                    Text(
                        text = state.headline(),
                        fontSize = if (listening) 13.5.sp else 11.sp,
                        lineHeight = if (listening) 17.sp else 14.sp,
                        fontWeight = if (listening) FontWeight.Medium else FontWeight.Normal,
                        color =
                            when (state) {
                                is VoiceRecordingState.Error,
                                is VoiceRecordingState.PermissionRequired -> WearDarkError
                                is VoiceRecordingState.Transcribed -> WearDarkOnSurfaceVariant
                                is VoiceRecordingState.Completed -> WearTitleBright
                                else -> WearTitleBright
                            },
                        textAlign = TextAlign.Center,
                    )
                }
            }

            if (state is VoiceRecordingState.PermissionRequired) {
                item {
                    VoiceButton(
                        label = "Grant access",
                        onClick = onRequestPermission,
                        modifier = Modifier.scrollTransform(this),
                    )
                }
            }

            item {
                VoiceButton(
                    label = if (listening) "Done" else "Close",
                    onClick = {
                        onStopCapture()
                        onDismiss()
                    },
                    modifier = Modifier.scrollTransform(this),
                )
            }
        }

        ScrollIndicator(state = listState, modifier = Modifier.align(Alignment.CenterEnd))
    }
}

/** Three rings breathing on the mic's RMS. Amplitude only scales — it never re-lays-out. */
@Composable
private fun ListeningRings(amplitude: Float, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val half = size.minDimension / 2f
        listOf(
                Triple(0.95f, 0.16f, 3.dp.toPx()),
                Triple(0.81f, 0.28f, 2.dp.toPx()),
                Triple(0.66f, 0.46f, 1.5.dp.toPx()),
            )
            .forEach { (base, alpha, stroke) ->
                drawCircle(
                    color = WearDarkPrimary.copy(alpha = alpha),
                    radius = half * base * (1f + amplitude * 0.06f),
                    style = Stroke(width = stroke),
                )
            }
    }
}

@Composable
private fun VoiceButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .size(width = 96.dp, height = 32.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(WearDarkPrimary)
                .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Medium,
            color = WearDarkOnPrimary,
        )
    }
}

private fun VoiceRecordingState.headline(): String =
    when (this) {
        is VoiceRecordingState.Idle -> "Tap to speak"
        is VoiceRecordingState.Connecting -> "Starting…"
        is VoiceRecordingState.Recording -> "Listening…"
        is VoiceRecordingState.Transcribed -> "“$transcript”"
        is VoiceRecordingState.Completed -> message
        is VoiceRecordingState.Error -> error
        is VoiceRecordingState.PermissionRequired -> "Microphone access is needed"
    }
