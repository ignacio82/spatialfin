package dev.jdtech.jellyfin.player.xr.voice

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.xr.compose.spatial.ContentEdge
import androidx.xr.compose.spatial.Orbiter

private val ScanColor = Color(0xFF26C6DA)

@Composable
fun CharacterScanOverlay(
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!visible) return

    val infiniteTransition = rememberInfiniteTransition(label = "char-scan")
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.72f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "alpha",
    )

    Orbiter(
        position = ContentEdge.End,
        alignment = Alignment.Top,
        offset = 20.dp,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = modifier.padding(horizontal = 12.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = Color(0xFF0D1F24),
                tonalElevation = 8.dp,
                modifier = Modifier.size(80.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Canvas(modifier = Modifier.size(60.dp)) {
                        val radius = size.minDimension / 2f * pulse
                        // Outer scanning ring
                        drawCircle(
                            color = ScanColor.copy(alpha = alpha),
                            radius = radius,
                            style = Stroke(width = 3.dp.toPx()),
                        )
                        // Inner ring
                        drawCircle(
                            color = ScanColor.copy(alpha = alpha * 0.45f),
                            radius = radius * 0.62f,
                            style = Stroke(width = 1.5.dp.toPx()),
                        )
                        // Cardinal-point tick marks
                        val center = Offset(size.width / 2f, size.height / 2f)
                        listOf(
                            Offset(center.x, center.y - radius),
                            Offset(center.x, center.y + radius),
                            Offset(center.x - radius, center.y),
                            Offset(center.x + radius, center.y),
                        ).forEach { pt ->
                            drawCircle(
                                color = ScanColor.copy(alpha = alpha),
                                radius = 2.5.dp.toPx(),
                                center = pt,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Surface(
                shape = MaterialTheme.shapes.small,
                color = Color(0xCC0D1F24),
                tonalElevation = 4.dp,
            ) {
                Text(
                    text = "Scanning...",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    color = ScanColor,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}
