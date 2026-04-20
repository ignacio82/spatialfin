package dev.jdtech.jellyfin.core.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Minimalist "floating pill" progress indicator used across XR, Beam, and TV to show resume
 * progress over poster artwork. The caller owns placement (padding, alignment, width).
 */
@Composable
fun FloatingProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    height: Dp = 4.dp,
    trackColor: Color = Color.White.copy(alpha = 0.35f),
    progressColor: Color = Color.White,
) {
    val clamped = progress.coerceIn(0f, 1f)
    Box(
        modifier =
            modifier
                .height(height)
                .clip(RoundedCornerShape(percent = 50))
                .background(trackColor),
    ) {
        Box(
            modifier =
                Modifier.fillMaxHeight().fillMaxWidth(fraction = clamped).background(progressColor),
        )
    }
}
