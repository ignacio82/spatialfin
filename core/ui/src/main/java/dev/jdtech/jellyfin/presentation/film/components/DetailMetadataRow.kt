package dev.jdtech.jellyfin.presentation.film.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Hero metadata chips (year / runtime / rating / genres). Styled to the XR
 * design kit's outline `Pill`: a translucent fill with a hairline border and a
 * semibold label, so the row reads as a set of crisp glass pills rather than
 * flat blocks. Shared by the movie / show / network detail heroes.
 */
@Composable
fun DetailMetadataRow(
    items: List<String>,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return

    val pillShape = RoundedCornerShape(999.dp)
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { item ->
            Surface(
                shape = pillShape,
                color = Color.White.copy(alpha = 0.07f),
            ) {
                Text(
                    text = item,
                    modifier = Modifier
                        .border(1.dp, Color.White.copy(alpha = 0.18f), pillShape)
                        .padding(horizontal = 13.dp, vertical = 7.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White.copy(alpha = 0.92f),
                )
            }
        }
    }
}
