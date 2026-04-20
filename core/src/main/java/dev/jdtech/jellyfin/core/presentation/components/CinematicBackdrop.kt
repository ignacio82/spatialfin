package dev.jdtech.jellyfin.core.presentation.components

import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect as AndroidRenderEffect
import android.graphics.Shader
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Shared "cinematic backdrop" recipe. Wraps [backdrop] with an optional blur, a saturation
 * boost, and a vertical gradient scrim so foreground UI stays legible.
 *
 * [blurRadius] defaults to `0.dp` — live blur on large XR panels is GPU-heavy. Turn it on
 * deliberately per platform after measuring (Beam phone is usually safe; XR SpatialPanels are not).
 */
@Composable
fun CinematicBackdrop(
    modifier: Modifier = Modifier,
    blurRadius: Dp = 0.dp,
    saturation: Float = 1.15f,
    scrimColor: Color = Color.Black,
    scrimTopAlpha: Float = 0.1f,
    scrimBottomAlpha: Float = 0.85f,
    backdrop: @Composable () -> Unit,
) {
    val colorMatrixValues =
        remember(saturation) { ColorMatrix().apply { setToSaturation(saturation) }.values }

    Box(modifier = modifier) {
        Box(
            modifier =
                Modifier.fillMaxSize().graphicsLayer {
                    val blurPx = blurRadius.toPx()
                    val colorFx =
                        AndroidRenderEffect.createColorFilterEffect(
                            ColorMatrixColorFilter(colorMatrixValues)
                        )
                    renderEffect =
                        if (blurPx > 0f) {
                                AndroidRenderEffect.createChainEffect(
                                    colorFx,
                                    AndroidRenderEffect.createBlurEffect(
                                        blurPx,
                                        blurPx,
                                        Shader.TileMode.CLAMP,
                                    ),
                                )
                            } else {
                                colorFx
                            }
                            .asComposeRenderEffect()
                    clip = true
                }
        ) {
            backdrop()
        }
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(
                brush =
                    Brush.verticalGradient(
                        colors =
                            listOf(
                                scrimColor.copy(alpha = scrimTopAlpha),
                                scrimColor.copy(alpha = scrimBottomAlpha),
                            )
                    )
            )
        }
    }
}
