package dev.spatialfin.companion.wear.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.OutlinedButton
import androidx.wear.compose.material3.Text
import dev.jdtech.jellyfin.fcast.sender.FCastReceiver
import dev.spatialfin.companion.protocol.WearChapterInfo
import dev.spatialfin.companion.protocol.WearPlayerAction
import dev.spatialfin.companion.protocol.WearStreamInfo
import dev.spatialfin.companion.wear.presentation.theme.WearDarkPrimary
import dev.spatialfin.companion.wear.presentation.theme.WearDarkPrimaryContainer
import dev.spatialfin.companion.wear.presentation.theme.WearDarkSurfaceVariant

@Composable
fun WearAudioTracksSheet(
    tracks: List<WearStreamInfo>,
    currentTrack: String?,
    onSelectTrack: (WearStreamInfo) -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(horizontal = 14.dp, vertical = 20.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Audio Streams",
            style = MaterialTheme.typography.titleMedium,
            color = WearDarkPrimary,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        if (tracks.isEmpty()) {
            Text(
                text = "No additional audio tracks",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray,
                modifier = Modifier.padding(vertical = 16.dp),
            )
        } else {
            tracks.forEach { track ->
                val isSelected = track.name == currentTrack || track.isSelected
                FilledTonalButton(
                    onClick = {
                        onSelectTrack(track)
                        onDismiss()
                    },
                    colors = if (isSelected) {
                        ButtonDefaults.filledTonalButtonColors(
                            containerColor = WearDarkPrimaryContainer,
                            contentColor = Color.White,
                        )
                    } else {
                        ButtonDefaults.filledTonalButtonColors(
                            containerColor = WearDarkSurfaceVariant,
                            contentColor = Color.LightGray,
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                ) {
                    Text(
                        text = if (isSelected) "✓ ${track.name}" else track.name,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(0.7f),
        ) {
            Text("Back")
        }
    }
}

@Composable
fun WearSubtitleTracksSheet(
    tracks: List<WearStreamInfo>,
    currentTrack: String?,
    onSelectTrack: (WearStreamInfo?) -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(horizontal = 14.dp, vertical = 20.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Subtitles",
            style = MaterialTheme.typography.titleMedium,
            color = WearDarkPrimary,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        // Subtitles Off button
        val isOff = currentTrack.isNullOrBlank()
        FilledTonalButton(
            onClick = {
                onSelectTrack(null)
                onDismiss()
            },
            colors = if (isOff) {
                ButtonDefaults.filledTonalButtonColors(
                    containerColor = WearDarkPrimaryContainer,
                    contentColor = Color.White,
                )
            } else {
                ButtonDefaults.filledTonalButtonColors(
                    containerColor = WearDarkSurfaceVariant,
                    contentColor = Color.LightGray,
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 3.dp),
        ) {
            Text(
                text = if (isOff) "✓ Subtitles Off" else "Subtitles Off",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        tracks.forEach { track ->
            val isSelected = track.name == currentTrack || track.isSelected
            FilledTonalButton(
                onClick = {
                    onSelectTrack(track)
                    onDismiss()
                },
                colors = if (isSelected) {
                    ButtonDefaults.filledTonalButtonColors(
                        containerColor = WearDarkPrimaryContainer,
                        contentColor = Color.White,
                    )
                } else {
                    ButtonDefaults.filledTonalButtonColors(
                        containerColor = WearDarkSurfaceVariant,
                        contentColor = Color.LightGray,
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp),
            ) {
                Text(
                    text = if (isSelected) "✓ ${track.name}" else track.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(0.7f),
        ) {
            Text("Back")
        }
    }
}

@Composable
fun WearChaptersSheet(
    chapters: List<WearChapterInfo>,
    onSelectChapter: (WearChapterInfo) -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(horizontal = 14.dp, vertical = 20.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Chapters",
            style = MaterialTheme.typography.titleMedium,
            color = WearDarkPrimary,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        if (chapters.isEmpty()) {
            Text(
                text = "No chapter marks available",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray,
                modifier = Modifier.padding(vertical = 16.dp),
            )
        } else {
            chapters.forEachIndexed { idx, chapter ->
                FilledTonalButton(
                    onClick = {
                        onSelectChapter(chapter)
                        onDismiss()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = chapter.name.ifBlank { "Chapter ${idx + 1}" },
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        val mins = chapter.startPositionSeconds / 60
                        val secs = chapter.startPositionSeconds % 60
                        Text(
                            text = String.format("%02d:%02d", mins, secs),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray,
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(0.7f),
        ) {
            Text("Back")
        }
    }
}

@Composable
fun WearSpatialControlsSheet(
    onDispatchAction: (WearPlayerAction) -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(horizontal = 14.dp, vertical = 20.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Spatial Controls",
            style = MaterialTheme.typography.titleMedium,
            color = WearDarkPrimary,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        FilledTonalButton(
            onClick = { onDispatchAction(WearPlayerAction.ResetScreenPlacement) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 3.dp),
        ) {
            Text("🎯 Recenter Panel")
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            FilledTonalButton(
                onClick = { onDispatchAction(WearPlayerAction.AdjustScale(delta = -0.2f)) },
                modifier = Modifier.weight(1f),
            ) {
                Text("Scale −")
            }
            FilledTonalButton(
                onClick = { onDispatchAction(WearPlayerAction.AdjustScale(delta = 0.2f)) },
                modifier = Modifier.weight(1f),
            ) {
                Text("Scale +")
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            FilledTonalButton(
                onClick = { onDispatchAction(WearPlayerAction.AdjustDistance(delta = -0.5f)) },
                modifier = Modifier.weight(1f),
            ) {
                Text("Dist −")
            }
            FilledTonalButton(
                onClick = { onDispatchAction(WearPlayerAction.AdjustDistance(delta = 0.5f)) },
                modifier = Modifier.weight(1f),
            ) {
                Text("Dist +")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(0.7f),
        ) {
            Text("Done")
        }
    }
}

@Composable
fun WearDevicePickerSheet(
    currentDeviceName: String,
    lanReceivers: List<FCastReceiver>,
    canFling: Boolean,
    onSelectLanReceiver: (FCastReceiver) -> Unit,
    onFlingToReceiver: (FCastReceiver) -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(horizontal = 14.dp, vertical = 20.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Target Device",
            style = MaterialTheme.typography.titleMedium,
            color = WearDarkPrimary,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        Text(
            text = "Active: $currentDeviceName",
            style = MaterialTheme.typography.bodySmall,
            color = Color.LightGray,
            modifier = Modifier.padding(bottom = 6.dp),
        )

        if (lanReceivers.isNotEmpty()) {
            Text(
                text = "LAN RECEIVERS",
                style = MaterialTheme.typography.labelSmall,
                color = WearDarkPrimary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp, bottom = 2.dp),
            )
            lanReceivers.forEach { recv ->
                FilledTonalButton(
                    onClick = {
                        onSelectLanReceiver(recv)
                        onDismiss()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                ) {
                    Text(
                        text = "📺 ${recv.name}",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // Only offered when the watch actually knows the current stream URL —
                // without one there is nothing to fling.
                if (canFling) {
                    OutlinedButton(
                        onClick = {
                            onFlingToReceiver(recv)
                            onDismiss()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 3.dp),
                    ) {
                        Text(
                            text = "↗ Fling here",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(0.7f),
        ) {
            Text("Close")
        }
    }
}
