package dev.jdtech.jellyfin.player.xr.voice

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.xr.compose.spatial.ContentEdge
import androidx.xr.compose.spatial.Orbiter
import dev.jdtech.jellyfin.core.R as CoreR
import kotlin.math.PI
import kotlin.math.sin

/**
 * Stateful spatial "voice orb" anchored above the active panel.
 *
 * - Idle / hidden: nothing rendered (or a gesture hint when armed).
 * - Listening: halo pulses at low amplitude; the waveform is driven by [micLevel].
 * - Processing: shimmer sweep across the orb while the assistant thinks.
 * - Result / error: orb flattens into a tinted message capsule.
 *
 * The orb replaces the earlier single-Text flicker where states all rendered
 * through the same label and users had no visual anchor for where the assistant's
 * attention lived.
 */
@Composable
fun VoiceControlOverlay(
    state: VoiceState,
    partialTranscript: String,
    feedbackText: String? = null,
    gestureArmingProgress: Float = 0f,
    gestureHint: String? = null,
    micLevel: Float = 0f,
    modifier: Modifier = Modifier,
) {
    val isVisible =
        state == VoiceState.LISTENING ||
            state == VoiceState.PROCESSING ||
            state == VoiceState.ERROR ||
            gestureHint != null ||
            !feedbackText.isNullOrBlank()

    val accentColor =
        when {
            state == VoiceState.LISTENING -> Color(0xFF4FC3F7)
            state == VoiceState.PROCESSING -> Color(0xFFFFB74D)
            state == VoiceState.ERROR -> Color(0xFFEF5350)
            !feedbackText.isNullOrBlank() -> Color(0xFF66BB6A)
            else -> Color(0xFF5E7486)
        }

    if (!isVisible) return

    Orbiter(
        position = ContentEdge.Top,
        alignment = Alignment.CenterHorizontally,
        offset = 40.dp,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = modifier.widthIn(max = 400.dp),
        ) {
            VoiceOrb(
                state = state,
                accentColor = accentColor,
                micLevel = micLevel,
            )

            val textToShow = when {
                state == VoiceState.LISTENING && partialTranscript.isNotEmpty() -> partialTranscript
                state == VoiceState.LISTENING -> "Listening"
                !feedbackText.isNullOrBlank() -> feedbackText
                state == VoiceState.PROCESSING -> "Thinking"
                state == VoiceState.ERROR -> "Couldn't hear that. Try again."
                gestureHint != null && gestureArmingProgress > 0f ->
                    "$gestureHint ${(gestureArmingProgress * 100).toInt()}%"
                gestureHint != null -> gestureHint
                else -> null
            }

            AnimatedVisibility(
                visible = textToShow != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                textToShow?.let { text ->
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = accentColor.copy(alpha = 0.22f),
                        modifier = Modifier.padding(top = 12.dp),
                    ) {
                        Text(
                            text = text,
                            color = Color.White,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceOrb(
    state: VoiceState,
    accentColor: Color,
    micLevel: Float,
) {
    val infinite = rememberInfiniteTransition(label = "voice-orb")
    val phase by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "voice-orb-phase",
    )
    // Smooth the raw recognizer level so the halo doesn't jitter frame-to-frame.
    val smoothedLevel by animateFloatAsState(
        targetValue = micLevel.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 120, easing = LinearEasing),
        label = "voice-orb-mic",
    )

    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(104.dp)) {
        Canvas(modifier = Modifier.size(104.dp)) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val baseRadius = size.minDimension * 0.32f

            when (state) {
                VoiceState.LISTENING -> {
                    // Halo pulse driven by mic amplitude — reads as "I hear you".
                    val haloRadius = baseRadius * (1.05f + smoothedLevel * 0.6f)
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                accentColor.copy(alpha = 0.55f),
                                accentColor.copy(alpha = 0f),
                            ),
                            center = center,
                            radius = haloRadius,
                        ),
                        radius = haloRadius,
                        center = center,
                    )
                    // A thin waveform ring that ripples while speaking.
                    val waveRadius = baseRadius + smoothedLevel * baseRadius * 0.35f +
                        sin(phase * 2f * PI.toFloat()) * 2f
                    drawCircle(
                        color = accentColor.copy(alpha = 0.85f),
                        radius = waveRadius,
                        center = center,
                        style = Stroke(width = 2.5f),
                    )
                }
                VoiceState.PROCESSING -> {
                    // Shimmer sweep: a conic-like rotating gradient stroke.
                    drawCircle(
                        color = accentColor.copy(alpha = 0.28f),
                        radius = baseRadius * 1.15f,
                        center = center,
                    )
                    val sweepStart = phase * 360f
                    drawArc(
                        brush = Brush.sweepGradient(
                            0f to accentColor.copy(alpha = 0f),
                            0.5f to accentColor.copy(alpha = 0.95f),
                            1f to accentColor.copy(alpha = 0f),
                            center = center,
                        ),
                        startAngle = sweepStart,
                        sweepAngle = 270f,
                        useCenter = false,
                        topLeft = Offset(
                            center.x - baseRadius * 1.15f,
                            center.y - baseRadius * 1.15f,
                        ),
                        size = androidx.compose.ui.geometry.Size(
                            baseRadius * 2.3f,
                            baseRadius * 2.3f,
                        ),
                        style = Stroke(width = 4f),
                    )
                }
                VoiceState.ERROR, VoiceState.IDLE -> {
                    // Static soft halo behind the mic glyph.
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                accentColor.copy(alpha = 0.40f),
                                accentColor.copy(alpha = 0f),
                            ),
                            center = center,
                            radius = baseRadius * 1.4f,
                        ),
                        radius = baseRadius * 1.4f,
                        center = center,
                    )
                }
            }
        }
        Surface(
            shape = CircleShape,
            color = accentColor,
            tonalElevation = 8.dp,
            modifier = Modifier.size(68.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(CoreR.drawable.ic_microphone),
                    contentDescription = "Voice command",
                    tint = Color.White,
                    modifier = Modifier.size(36.dp),
                )
            }
        }
    }
}
