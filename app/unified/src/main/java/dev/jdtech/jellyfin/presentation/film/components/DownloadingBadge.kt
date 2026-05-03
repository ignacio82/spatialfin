package dev.jdtech.jellyfin.presentation.film.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.spatialfin.presentation.theme.SpatialFinTheme

@Composable
fun DownloadingBadge(modifier: Modifier = Modifier) {
    Box(
        modifier =
            modifier
                .defaultMinSize(minWidth = 24.dp, minHeight = 24.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.tertiary),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp).align(Alignment.Center),
            color = MaterialTheme.colorScheme.onTertiary,
            strokeWidth = 2.dp,
        )
    }
}

@Composable
@Preview
private fun DownloadingBadgePreview() {
    SpatialFinTheme { DownloadingBadge() }
}
