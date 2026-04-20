package dev.jdtech.jellyfin.core.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Non-focusable rounded pill used across XR/Beam/TV for static metadata (year, runtime, rating…).
 *
 * Kept free of Material dependencies so `:core` stays Compose-foundation-only. The caller supplies
 * a [content] slot (typically a `Text`) with the desired theme-aware typography/color.
 */
@Composable
fun MetadataPill(
    modifier: Modifier = Modifier,
    backgroundColor: Color = Color.Black.copy(alpha = 0.28f),
    horizontalPadding: Dp = 10.dp,
    verticalPadding: Dp = 5.dp,
    content: @Composable () -> Unit,
) {
    Box(
        modifier =
            modifier
                .background(color = backgroundColor, shape = RoundedCornerShape(999.dp))
                .padding(horizontal = horizontalPadding, vertical = verticalPadding)
    ) {
        content()
    }
}
