package dev.spatialfin.fcast.session

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import dev.jdtech.jellyfin.fcast.sender.FCastCastingController
import dev.jdtech.jellyfin.core.R as CoreR
import kotlinx.coroutines.launch

@Composable
fun FCastCinematicRemote(
    sessionManager: CastSessionManager,
    onDismiss: () -> Unit,
) {
    val pickedTarget by sessionManager.pickedTarget.collectAsState()
    val activeMediaState by sessionManager.activeMediaState.collectAsState()
    val status by sessionManager.status.collectAsState()
    val currentVolume by sessionManager.currentVolume.collectAsState()
    val title by sessionManager.activeItemTitle.collectAsState()
    val artworkUrl by sessionManager.activeItemArtworkUrl.collectAsState()
    val audioFormat by sessionManager.activeAudioFormat.collectAsState()
    
    val anyTarget = pickedTarget ?: return
    val scope = rememberCoroutineScope()

    val isPlaying = activeMediaState == dev.jdtech.jellyfin.cast.CastMediaState.Playing
    val isFCastSession = sessionManager.pickedReceiver.collectAsState().value != null
    val controlsEnabled = if (isFCastSession) {
        status == FCastCastingController.Status.Casting
    } else {
        activeMediaState != dev.jdtech.jellyfin.cast.CastMediaState.Idle
    }

    val tracksState by sessionManager.tracksState.collectAsState()
    var showAudioTrackSheet by remember { androidx.compose.runtime.mutableStateOf(false) }
    var showSubtitleTrackSheet by remember { androidx.compose.runtime.mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Ambient Cinematic Background
        if (!artworkUrl.isNullOrEmpty()) {
            AsyncImage(
                model = artworkUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(80.dp)
                    .alpha(0.3f)
            )
        }
        
        // Gradient Overlay
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0x00111318), // transparent surface-dim
                            Color(0xCC111318), // surface-dim
                            Color(0xE60C0E13)  // surface-container-lowest
                        )
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
        ) {
            // Top App Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        painter = painterResource(CoreR.drawable.ic_chevron_down),
                        contentDescription = "Dismiss",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Text(
                    text = "SpatialFin",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                IconButton(onClick = { scope.launch { sessionManager.stopCast(); onDismiss() } }) {
                    Icon(
                        painter = painterResource(CoreR.drawable.ic_cast),
                        contentDescription = "Stop Casting",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // Main Content Area
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 24.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Artwork Section
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(16.dp))
                ) {
                    if (!artworkUrl.isNullOrEmpty()) {
                        AsyncImage(
                            model = artworkUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        )
                    }
                    // Gloss Overlay
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(
                                        Color.White.copy(alpha = 0.1f),
                                        Color.Transparent
                                    )
                                )
                            )
                    )
                }
                
                Spacer(modifier = Modifier.height(32.dp))

                // Metadata Section
                Text(
                    text = title ?: "Resonance in the Void",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (audioFormat?.label?.isNotEmpty() == true) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = Color.Transparent,
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        ) {
                            Text(
                                text = audioFormat?.label?.uppercase() ?: "AUDIO",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                letterSpacing = 1.sp
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        text = "Casting to ${anyTarget.name}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(32.dp))

                // Primary Transport Controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { showAudioTrackSheet = true },
                        modifier = Modifier.size(48.dp),
                        enabled = tracksState != null && controlsEnabled
                    ) {
                        Icon(
                            painter = painterResource(dev.spatialfin.R.drawable.ic_audiotrack),
                            contentDescription = "Audio Tracks",
                            tint = if (tracksState != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    
                    Spacer(modifier = Modifier.width(16.dp))

                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.8f),
                        modifier = Modifier.size(64.dp),
                        onClick = { scope.launch { sessionManager.seekBy(-10.0) } },
                        enabled = controlsEnabled
                    ) {
                        Icon(
                            painter = painterResource(CoreR.drawable.ic_skip_back),
                            contentDescription = "Skip Back",
                            modifier = Modifier.padding(16.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(24.dp))

                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(96.dp),
                        onClick = {
                            scope.launch {
                                if (isPlaying) sessionManager.pause() else sessionManager.resume()
                            }
                        },
                        enabled = controlsEnabled
                    ) {
                        Icon(
                            painter = painterResource(
                                if (isPlaying) CoreR.drawable.ic_pause else CoreR.drawable.ic_play
                            ),
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(24.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(24.dp))

                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.8f),
                        modifier = Modifier.size(64.dp),
                        onClick = { scope.launch { sessionManager.seekBy(10.0) } },
                        enabled = controlsEnabled
                    ) {
                        Icon(
                            painter = painterResource(CoreR.drawable.ic_skip_forward),
                            contentDescription = "Skip Forward",
                            modifier = Modifier.padding(16.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    IconButton(
                        onClick = { showSubtitleTrackSheet = true },
                        modifier = Modifier.size(48.dp),
                        enabled = tracksState != null && controlsEnabled
                    ) {
                        Icon(
                            painter = painterResource(dev.spatialfin.R.drawable.ic_subtitles),
                            contentDescription = "Subtitle Tracks",
                            tint = if (tracksState != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))

                // Secondary Utility Row (Volume)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(CoreR.drawable.ic_volume_0),
                        contentDescription = "Volume",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    
                    Spacer(modifier = Modifier.width(16.dp))
                    
                    Slider(
                        value = currentVolume.toFloat(),
                        onValueChange = { v ->
                            scope.launch { sessionManager.setVolume(v.toDouble()) }
                        },
                        valueRange = 0f..1f,
                        enabled = controlsEnabled,
                        modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = MaterialTheme.colorScheme.onSurface,
                            inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    )

                    Spacer(modifier = Modifier.width(16.dp))
                    
                    // Queue Peek Pill
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.8f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                        modifier = Modifier.clickable { /* TODO View Queue */ }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                painter = painterResource(dev.spatialfin.R.drawable.ic_queue),
                                contentDescription = "Up Next",
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "UP NEXT",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAudioTrackSheet && tracksState != null) {
        dev.spatialfin.fcast.FCastTrackSelectionSheet(
            title = "Audio Tracks",
            tracks = tracksState!!.audioTracks,
            onDismiss = { showAudioTrackSheet = false },
            onTrackSelected = { trackId ->
                scope.launch {
                    sessionManager.setTrack(androidx.media3.common.C.TRACK_TYPE_AUDIO, trackId)
                }
                showAudioTrackSheet = false
            }
        )
    }

    if (showSubtitleTrackSheet && tracksState != null) {
        dev.spatialfin.fcast.FCastTrackSelectionSheet(
            title = "Subtitles",
            tracks = tracksState!!.subtitleTracks,
            onDismiss = { showSubtitleTrackSheet = false },
            onTrackSelected = { trackId ->
                scope.launch {
                    sessionManager.setTrack(androidx.media3.common.C.TRACK_TYPE_TEXT, trackId)
                }
                showSubtitleTrackSheet = false
            }
        )
    }
}
