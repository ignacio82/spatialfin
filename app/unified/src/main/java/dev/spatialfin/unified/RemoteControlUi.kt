package dev.spatialfin.unified

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import dev.jdtech.jellyfin.models.SpatialFinMediaStream
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.GeneralCommandType
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.PlaystateCommand
import org.jellyfin.sdk.model.api.SessionInfoDto
import java.util.UUID

@Composable
fun RemoteControlView(
    session: SessionInfoDto,
    availableSessions: List<SessionInfoDto>,
    baseUrl: String,
    accessToken: String?,
    mediaStreams: List<SpatialFinMediaStream>,
    onSelectSession: (String) -> Unit,
    onPlayStateCommand: (PlaystateCommand) -> Unit,
    onGeneralCommand: (GeneralCommandType, Map<String, String>?) -> Unit,
    modifier: Modifier = Modifier
) {
    val nowPlaying = session.nowPlayingItem
    val isPaused = session.playState?.isPaused == true

    if (nowPlaying == null) {
        Box(modifier = modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
            Text("Nothing is currently playing on ${session.deviceName}", style = MaterialTheme.typography.bodyLarge)
        }
        return
    }

    val imageUrl = remember(nowPlaying, baseUrl, accessToken) {
        nowPlaying.remotePrimaryImageUrl(baseUrl, accessToken)
    }
    var imageFailed by remember(imageUrl) { mutableStateOf(false) }
    val audioStreams = remember(mediaStreams) {
        mediaStreams.filter { it.type == MediaStreamType.AUDIO && it.index != null }
    }
    val subtitleStreams = remember(mediaStreams) {
        mediaStreams.filter { it.type == MediaStreamType.SUBTITLE && it.index != null }
    }
    val currentAudioIndex = session.playState?.audioStreamIndex
    val currentSubtitleIndex = session.playState?.subtitleStreamIndex
    val currentAudio = audioStreams.firstOrNull { it.index == currentAudioIndex }
    val currentSubtitle = subtitleStreams.firstOrNull { it.index == currentSubtitleIndex }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (availableSessions.size > 1) {
            RemoteSessionSelector(
                sessions = availableSessions,
                selectedSessionId = session.id,
                onSelectSession = onSelectSession,
            )
        }

        Text(
            text = "Playing on ${session.deviceName}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Box(
            modifier = Modifier
                .size(240.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (!imageUrl.isNullOrBlank() && !imageFailed) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = "Cover Art",
                    contentScale = ContentScale.Crop,
                    onError = { imageFailed = true },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (imageUrl.isNullOrBlank() || imageFailed) {
                Icon(
                    Icons.Default.CastConnected,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(64.dp),
                )
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = nowPlaying.name ?: "Unknown Title",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!nowPlaying.seriesName.isNullOrEmpty()) {
                Text(
                    text = nowPlaying.seriesName!!,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Subtitles and Audio selectors
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            var showAudioMenu by remember { mutableStateOf(false) }
            var showSubtitleMenu by remember { mutableStateOf(false) }

            Box(modifier = Modifier.weight(1f)) {
                OutlinedButton(
                    onClick = { showAudioMenu = true },
                    enabled = audioStreams.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.Audiotrack, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = currentAudio?.remoteTrackLabel() ?: "Audio",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                DropdownMenu(
                    expanded = showAudioMenu,
                    onDismissRequest = { showAudioMenu = false }
                ) {
                    audioStreams.forEach { stream ->
                        DropdownMenuItem(
                            text = { Text(stream.remoteTrackLabel()) },
                            onClick = {
                                showAudioMenu = false
                                stream.index?.let { index ->
                                    onGeneralCommand(
                                        GeneralCommandType.SET_AUDIO_STREAM_INDEX,
                                        mapOf("AudioStreamIndex" to index.toString()),
                                    )
                                }
                            }
                        )
                    }
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                OutlinedButton(
                    onClick = { showSubtitleMenu = true },
                    enabled = subtitleStreams.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.Subtitles, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = currentSubtitle?.remoteTrackLabel()
                            ?: if (currentSubtitleIndex == null || currentSubtitleIndex < 0) {
                                "Subtitles off"
                            } else {
                                "Subtitles"
                            },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                DropdownMenu(
                    expanded = showSubtitleMenu,
                    onDismissRequest = { showSubtitleMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("None") },
                        onClick = {
                            showSubtitleMenu = false
                            onGeneralCommand(
                                GeneralCommandType.SET_SUBTITLE_STREAM_INDEX,
                                mapOf("SubtitleStreamIndex" to "-1"),
                            )
                        }
                    )
                    subtitleStreams.forEach { stream ->
                        DropdownMenuItem(
                            text = { Text(stream.remoteTrackLabel()) },
                            onClick = {
                                showSubtitleMenu = false
                                stream.index?.let { index ->
                                    onGeneralCommand(
                                        GeneralCommandType.SET_SUBTITLE_STREAM_INDEX,
                                        mapOf("SubtitleStreamIndex" to index.toString()),
                                    )
                                }
                            }
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { onGeneralCommand(GeneralCommandType.VOLUME_DOWN, null) },
                modifier = Modifier.size(48.dp)
            ) {
                Icon(Icons.AutoMirrored.Rounded.VolumeDown, contentDescription = "Volume Down")
            }

            IconButton(
                onClick = { onPlayStateCommand(PlaystateCommand.PREVIOUS_TRACK) },
                modifier = Modifier.size(48.dp)
            ) {
                Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous")
            }

            FilledIconButton(
                onClick = {
                    onPlayStateCommand(PlaystateCommand.PLAY_PAUSE)
                },
                modifier = Modifier.size(64.dp)
            ) {
                Icon(
                    if (isPaused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                    contentDescription = if (isPaused) "Play" else "Pause",
                    modifier = Modifier.size(32.dp)
                )
            }

            IconButton(
                onClick = { onPlayStateCommand(PlaystateCommand.NEXT_TRACK) },
                modifier = Modifier.size(48.dp)
            ) {
                Icon(Icons.Rounded.SkipNext, contentDescription = "Next")
            }

            IconButton(
                onClick = { onGeneralCommand(GeneralCommandType.VOLUME_UP, null) },
                modifier = Modifier.size(48.dp)
            ) {
                Icon(Icons.AutoMirrored.Rounded.VolumeUp, contentDescription = "Volume Up")
            }
        }
    }
}

@Composable
fun RemoteControlMiniPlayer(
    session: SessionInfoDto?,
    availableSessions: List<SessionInfoDto>,
    baseUrl: String,
    accessToken: String?,
    mediaStreams: List<SpatialFinMediaStream>,
    onSelectSession: (String) -> Unit,
    onPlayStateCommand: (PlaystateCommand) -> Unit,
    onGeneralCommand: (GeneralCommandType, Map<String, String>?) -> Unit,
    modifier: Modifier = Modifier
) {
    if (session == null || session.nowPlayingItem == null) return

    var showSheet by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable { showSheet = true },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        tonalElevation = 4.dp
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.CastConnected,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = session.nowPlayingItem?.name ?: "Unknown Title",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Playing on ${session.deviceName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            val isPaused = session.playState?.isPaused == true
            IconButton(
                onClick = { onPlayStateCommand(PlaystateCommand.PLAY_PAUSE) }
            ) {
                Icon(
                    if (isPaused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                    contentDescription = if (isPaused) "Play" else "Pause"
                )
            }
        }
    }

    if (showSheet) {
        Dialog(
            onDismissRequest = { showSheet = false },
            properties =
                DialogProperties(
                    usePlatformDefaultWidth = false,
                    decorFitsSystemWindows = false,
                )
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(WindowInsets.safeDrawing),
                ) {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = { showSheet = false }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Close remote")
                        }
                    }
                    RemoteControlView(
                        session = session,
                        availableSessions = availableSessions,
                        baseUrl = baseUrl,
                        accessToken = accessToken,
                        mediaStreams = mediaStreams,
                        onSelectSession = onSelectSession,
                        onPlayStateCommand = onPlayStateCommand,
                        onGeneralCommand = onGeneralCommand,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

@Composable
private fun RemoteSessionSelector(
    sessions: List<SessionInfoDto>,
    selectedSessionId: String?,
    onSelectSession: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = sessions.firstOrNull { it.id == selectedSessionId } ?: sessions.firstOrNull()

    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Icon(Icons.Default.CastConnected, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.Start,
            ) {
                Text(
                    text = selected?.remoteDeviceLabel() ?: "Choose device",
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = selected?.nowPlayingItem?.name ?: "Remote playback",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.fillMaxWidth(0.92f),
        ) {
            sessions.forEach { remoteSession ->
                val sessionId = remoteSession.id ?: return@forEach
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(
                                text = remoteSession.remoteDeviceLabel(),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = remoteSession.nowPlayingItem?.name ?: "Remote playback",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    leadingIcon = { Icon(Icons.Default.CastConnected, contentDescription = null) },
                    onClick = {
                        expanded = false
                        onSelectSession(sessionId)
                    },
                )
            }
        }
    }
}

private data class RemoteImageRef(
    val itemId: UUID,
    val tag: String?,
)

private fun BaseItemDto.remotePrimaryImageUrl(baseUrl: String, accessToken: String?): String? {
    if (baseUrl.isBlank()) return null
    val imageRef = primaryImageRef()
    val builder =
        Uri.parse("${baseUrl.trimEnd('/')}/Items/${imageRef.itemId}/Images/Primary")
            .buildUpon()
            .appendQueryParameter("maxWidth", "512")
            .appendQueryParameter("quality", "90")

    imageRef.tag?.takeIf { it.isNotBlank() }?.let { builder.appendQueryParameter("tag", it) }
    accessToken?.takeIf { it.isNotBlank() }?.let { builder.appendQueryParameter("api_key", it) }
    return builder.build().toString()
}

private fun BaseItemDto.primaryImageRef(): RemoteImageRef {
    val ownPrimaryTag = imageTags?.get(ImageType.PRIMARY)
    if (!ownPrimaryTag.isNullOrBlank()) {
        return RemoteImageRef(id, ownPrimaryTag)
    }

    parentPrimaryImageItemId?.takeIf { !parentPrimaryImageTag.isNullOrBlank() }?.let { itemId ->
        return RemoteImageRef(itemId, parentPrimaryImageTag)
    }
    seriesId?.takeIf { !seriesPrimaryImageTag.isNullOrBlank() }?.let { itemId ->
        return RemoteImageRef(itemId, seriesPrimaryImageTag)
    }
    albumId?.takeIf { !albumPrimaryImageTag.isNullOrBlank() }?.let { itemId ->
        return RemoteImageRef(itemId, albumPrimaryImageTag)
    }

    return RemoteImageRef(id, ownPrimaryTag)
}

private fun SpatialFinMediaStream.remoteTrackLabel(): String {
    return listOf(displayTitle, title, language.uppercase(), codec.uppercase())
        .firstOrNull { !it.isNullOrBlank() }
        ?: "Unknown"
}

private fun SessionInfoDto.remoteDeviceLabel(): String =
    deviceName
        ?.takeIf { it.isNotBlank() }
        ?: client
            ?.takeIf { it.isNotBlank() }
        ?: "SpatialFin"
