package dev.jdtech.jellyfin.player.xr

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import dev.jdtech.jellyfin.player.local.presentation.PlayerViewModel

/**
 * Timeline scrubber with chapter markers + trickplay preview. Pure presentation
 * except for the seek call + autohide reset, both of which come in as callbacks.
 *
 * Extracted from SpatialPlayerScreen.kt.
 */
@Composable
internal fun ProgressSection(
    uiState: PlayerViewModel.UiState,
    player: Player,
    currentPosition: Long,
    duration: Long,
    resetAutoHide: () -> Unit,
) {
    val chapters = uiState.currentChapters
    var sliderValue by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }

    val progress = if (duration > 0) currentPosition.toFloat() / duration.toFloat() else 0f
    if (!isDragging) sliderValue = progress

    // Chapter resolved from the scrub target (when dragging) or playback position.
    val displayPositionMs = if (isDragging && duration > 0) (sliderValue * duration).toLong()
                            else currentPosition
    val currentChapterName = remember(displayPositionMs, chapters) {
        chapters.lastOrNull { it.startPosition <= displayPositionMs }?.name
    }

    Column {
        if (currentChapterName != null) {
            Text(
                text = currentChapterName,
                style = MaterialTheme.typography.labelLarge,
                color = if (isDragging) Color.White else Color.White.copy(alpha = 0.55f),
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }

        if (isDragging && uiState.currentTrickplay != null) {
            val trickplay = uiState.currentTrickplay!!
            val totalThumbnails = trickplay.images.size
            val index = (sliderValue * (totalThumbnails - 1)).toInt().coerceIn(0, totalThumbnails - 1)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    bitmap = trickplay.images[index].asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .height(200.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.DarkGray),
                    contentScale = ContentScale.Fit,
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                formatTime(currentPosition),
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White.copy(alpha = 0.8f),
            )
            Box(modifier = Modifier.weight(1f)) {
                if (chapters.isNotEmpty() && duration > 0) {
                    val sliderHPad = 24.dp
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(24.dp)
                            .align(Alignment.Center),
                    ) {
                        val padPx = sliderHPad.toPx()
                        val trackWidth = size.width - 2 * padPx
                        val markerH = 32f
                        val markerW = 8f
                        val centerY = size.height / 2f
                        chapters.forEach { chapter ->
                            val fraction = (chapter.startPosition.toFloat() / duration.toFloat())
                                .coerceIn(0f, 1f)
                            val x = padPx + fraction * trackWidth
                            drawRect(
                                color = Color(0xFF4FC3F7),
                                topLeft = Offset(x - markerW / 2, centerY - markerH / 2),
                                size = Size(markerW, markerH),
                            )
                        }
                    }
                }
                Slider(
                    value = sliderValue,
                    onValueChange = {
                        isDragging = true
                        sliderValue = it
                        resetAutoHide()
                    },
                    onValueChangeFinished = {
                        player.seekTo((sliderValue * duration).toLong())
                        isDragging = false
                        resetAutoHide()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                )
            }
            Text(
                formatTime(duration),
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White.copy(alpha = 0.8f),
            )
        }
    }
}
