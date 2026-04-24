package dev.jdtech.jellyfin.player.xr

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import dev.jdtech.jellyfin.player.local.presentation.PlayerViewModel

/**
 * Timeline scrubber with chapter markers + trickplay preview. Pure presentation
 * except for the seek call + autohide reset, both of which come in as callbacks.
 *
 * Extracted from SpatialPlayerScreen.kt.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgressSection(
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
                style = MaterialTheme.typography.headlineSmall,
                color = if (isDragging) Color.White else Color.White.copy(alpha = 0.7f),
                modifier = Modifier.padding(bottom = 10.dp),
            )
        }

        if (isDragging && uiState.currentTrickplay != null) {
            val trickplay = uiState.currentTrickplay!!
            val totalThumbnails = trickplay.images.size
            val index = (sliderValue * (totalThumbnails - 1)).toInt().coerceIn(0, totalThumbnails - 1)
            // Spatial strip of thumbnails centered on the scrub position. Neighboring
            // frames are dimmed/shrunk so the user sees temporal context at a glance
            // instead of a single isolated image.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Spacer(Modifier.weight(1f))
                    for (offset in -2..2) {
                        val neighborIndex = index + offset
                        if (neighborIndex in 0 until totalThumbnails) {
                            val isCenter = offset == 0
                            val thumbHeight = if (isCenter) 200.dp else 120.dp
                            val alpha = if (isCenter) 1f else 0.55f
                            Image(
                                bitmap = trickplay.images[neighborIndex].asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier
                                    .height(thumbHeight)
                                    .clip(RoundedCornerShape(if (isCenter) 16.dp else 10.dp))
                                    .background(Color.DarkGray.copy(alpha = alpha)),
                                contentScale = ContentScale.Fit,
                                alpha = alpha,
                            )
                        } else {
                            // Keep layout stable at the ends of the trickplay range so the
                            // center thumbnail never jumps sideways mid-scrub.
                            Spacer(Modifier.width(if (offset == 0) 200.dp else 120.dp))
                        }
                    }
                    Spacer(Modifier.weight(1f))
                }
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
                            .height(56.dp)
                            .align(Alignment.Center),
                    ) {
                        val padPx = sliderHPad.toPx()
                        val trackWidth = size.width - 2 * padPx
                        val markerH = 72f
                        val markerW = 14f
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
                val sliderInteractionSource = remember { MutableInteractionSource() }
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
                    interactionSource = sliderInteractionSource,
                    thumb = {
                        SliderDefaults.Thumb(
                            interactionSource = sliderInteractionSource,
                            thumbSize = DpSize(48.dp, 48.dp),
                        )
                    },
                    track = { sliderState ->
                        SliderDefaults.Track(
                            sliderState = sliderState,
                            modifier = Modifier.height(16.dp),
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .height(56.dp),
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
