package dev.spatialfin.companion.wear.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import dev.spatialfin.companion.protocol.WearNextUpItem
import dev.spatialfin.companion.protocol.WearPlayerAction
import dev.spatialfin.companion.wear.presentation.theme.WearDarkPrimary
import dev.spatialfin.companion.wear.presentation.theme.WearDarkSurfaceVariant
import dev.spatialfin.companion.wear.transport.WearTransportManager
import kotlinx.coroutines.launch

@Composable
fun WearNextUpScreen(
    transportManager: WearTransportManager,
    onNavigateBack: () -> Unit,
) {
    val nextUpState by transportManager.nextUp.collectAsState()
    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()
    val items = nextUpState?.items.orEmpty()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(horizontal = 12.dp, vertical = 18.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Continue Watching",
            style = MaterialTheme.typography.titleMedium,
            color = WearDarkPrimary,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        if (items.isEmpty()) {
            Text(
                text = "No items in Next Up feed",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray,
                modifier = Modifier.padding(vertical = 24.dp),
            )
        } else {
            items.forEach { item ->
                NextUpCard(
                    item = item,
                    onClick = {
                        coroutineScope.launch {
                            transportManager.dispatchAction(
                                WearPlayerAction.PlayMediaItem(
                                    itemId = item.id,
                                    mediaType = item.mediaType,
                                    startPositionMs = item.playbackPositionSeconds * 1000L,
                                ),
                            )
                        }
                        onNavigateBack()
                    },
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
        Button(
            onClick = onNavigateBack,
            modifier = Modifier.fillMaxWidth(0.7f),
        ) {
            Text("Back")
        }
    }
}

@Composable
private fun NextUpCard(
    item: WearNextUpItem,
    onClick: () -> Unit,
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val series = item.seriesName
            if (!series.isNullOrBlank()) {
                val epText = if (item.seasonNumber != null && item.episodeNumber != null) {
                    "S${item.seasonNumber}:E${item.episodeNumber} • $series"
                } else {
                    series
                }
                Text(
                    text = epText,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.LightGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Progress bar if partially watched
            if (item.durationSeconds > 0 && item.playbackPositionSeconds > 0) {
                val progress = (item.playbackPositionSeconds.toFloat() / item.durationSeconds).coerceIn(0f, 1f)
                Spacer(modifier = Modifier.height(3.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(1.5.dp))
                        .background(Color(0xFF3C4858)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress)
                            .height(3.dp)
                            .clip(RoundedCornerShape(1.5.dp))
                            .background(WearDarkPrimary),
                    )
                }
            }
        }
    }
}
