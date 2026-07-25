package dev.spatialfin.unified

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.jdtech.jellyfin.models.SpatialFinChapter
import dev.jdtech.jellyfin.models.SpatialFinMediaStream
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.GeneralCommandType
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.PlaystateCommand
import org.jellyfin.sdk.model.api.SessionInfoDto
import java.util.Locale
import java.util.UUID

@Composable
fun RemoteControlView(
    session: SessionInfoDto,
    availableSessions: List<SessionInfoDto>,
    baseUrl: String,
    accessToken: String?,
    mediaStreams: List<SpatialFinMediaStream>,
    chapters: List<SpatialFinChapter> = emptyList(),
    onSelectSession: (String) -> Unit,
    onPlayStateCommand: (PlaystateCommand) -> Unit,
    onGeneralCommand: (GeneralCommandType, Map<String, String>?) -> Unit,
    onSeekTo: (Long) -> Unit = {},
    onSeekBy: (Long) -> Unit = {},
    onSetVolume: (Int) -> Unit = {},
    onToggleMute: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val nowPlaying = session.nowPlayingItem
    val playState = session.playState

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
    val currentAudioIndex = playState?.audioStreamIndex
    val currentSubtitleIndex = playState?.subtitleStreamIndex
    val currentAudio = audioStreams.firstOrNull { it.index == currentAudioIndex }
    val currentSubtitle = subtitleStreams.firstOrNull { it.index == currentSubtitleIndex }

    val positionTicks = playState?.positionTicks ?: 0L
    val durationTicks = nowPlaying.runTimeTicks ?: 0L
    val isPaused = playState?.isPaused == true
    val isMuted = playState?.isMuted == true
    val initialVolume = playState?.volumeLevel ?: 100

    var scrubbingPositionTicks by remember(positionTicks) { mutableStateOf<Long?>(null) }
    var scrubbingVolumePercent by remember(initialVolume) { mutableStateOf<Float?>(null) }
    var showChapterSheet by remember { mutableStateOf(false) }

    val activePositionTicks = scrubbingPositionTicks ?: positionTicks
    val activeVolumePercent = scrubbingVolumePercent?.toInt() ?: initialVolume

    val activeChapter = remember(chapters, activePositionTicks) {
        if (chapters.isEmpty()) null
        else {
            val activeMs = activePositionTicks / 10_000L
            chapters.lastOrNull { it.startPosition <= activeMs }
                ?: chapters.firstOrNull()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Ambient Blurred Backdrop Artwork
        if (!imageUrl.isNullOrBlank() && !imageFailed) {
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(70.dp)
                    .alpha(0.22f)
            )
        }

        // Gradient Overlay for readability and cinematic depth
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0x33111318),
                            Color(0xDD111318),
                            Color(0xF00C0E13),
                        )
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
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

            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.CastConnected,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Playing on ${session.deviceName}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Cover Art Poster Card
            Box(
                modifier = Modifier
                    .size(210.dp)
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

            // Title and Subtitle Info
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = nowPlaying.name ?: "Unknown Title",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                val subtitleText = remember(nowPlaying) {
                    buildList {
                        if (!nowPlaying.seriesName.isNullOrEmpty()) add(nowPlaying.seriesName)
                        val seasonNum = nowPlaying.parentIndexNumber
                        val episodeNum = nowPlaying.indexNumber
                        if (seasonNum != null && episodeNum != null) {
                            add("S${seasonNum}:E${episodeNum}")
                        } else if (!nowPlaying.seasonName.isNullOrEmpty()) {
                            add(nowPlaying.seasonName)
                        }
                    }.joinToString(" • ")
                }

                if (subtitleText.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = subtitleText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Metadata Badges Row (Year, Rating, Audio/Sub Stream Languages)
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    nowPlaying.productionYear?.let { year ->
                        BadgeChip(text = year.toString())
                    }
                    nowPlaying.officialRating?.takeIf { it.isNotBlank() }?.let { rating ->
                        BadgeChip(text = rating)
                    }
                    currentAudio?.let { audio ->
                        BadgeChip(text = audio.language.takeIf { it.isNotBlank() }?.uppercase() ?: "AUDIO")
                    }
                    currentSubtitle?.let { sub ->
                        BadgeChip(text = sub.language.takeIf { it.isNotBlank() }?.uppercase() ?: "SUB")
                    }
                }
            }

            // Playback Progress Scrub Bar (Timeline)
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                val maxRange = durationTicks.coerceAtLeast(1L).toFloat()
                val sliderValue = activePositionTicks.coerceIn(0L, durationTicks.coerceAtLeast(1L)).toFloat()

                Slider(
                    value = sliderValue,
                    onValueChange = { newValue ->
                        scrubbingPositionTicks = newValue.toLong()
                    },
                    onValueChangeFinished = {
                        scrubbingPositionTicks?.let { target ->
                            onSeekTo(target)
                        }
                        scrubbingPositionTicks = null
                    },
                    valueRange = 0f..maxRange,
                    enabled = durationTicks > 0L,
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = formatTicks(activePositionTicks),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (durationTicks > 0L) {
                        val remainingTicks = (durationTicks - activePositionTicks).coerceAtLeast(0L)
                        Text(
                            text = "-${formatTicks(remainingTicks)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }

                    Text(
                        text = formatTicks(durationTicks),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Transport Controls (-10s, Prev, Play/Pause, Next, +10s)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { onSeekBy(-10) },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        Icons.Rounded.Replay10,
                        contentDescription = "Rewind 10s",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }

                IconButton(
                    onClick = { onPlayStateCommand(PlaystateCommand.PREVIOUS_TRACK) },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous Track")
                }

                FilledIconButton(
                    onClick = { onPlayStateCommand(PlaystateCommand.PLAY_PAUSE) },
                    modifier = Modifier.size(68.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                ) {
                    Icon(
                        if (isPaused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                        contentDescription = if (isPaused) "Play" else "Pause",
                        modifier = Modifier.size(36.dp)
                    )
                }

                IconButton(
                    onClick = { onPlayStateCommand(PlaystateCommand.NEXT_TRACK) },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(Icons.Rounded.SkipNext, contentDescription = "Next Track")
                }

                IconButton(
                    onClick = { onSeekBy(10) },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        Icons.Rounded.Forward10,
                        contentDescription = "Forward 10s",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            // Audio, Subtitle, and Chapter Selection Buttons Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                var showAudioMenu by remember { mutableStateOf(false) }
                var showSubtitleMenu by remember { mutableStateOf(false) }

                if (chapters.isNotEmpty()) {
                    Box(modifier = Modifier.weight(1f)) {
                        OutlinedButton(
                            onClick = { showChapterSheet = true },
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                        ) {
                            Icon(Icons.Rounded.Bookmark, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = activeChapter?.name ?: "Chapters (${chapters.size})",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }

                Box(modifier = Modifier.weight(1f)) {
                    OutlinedButton(
                        onClick = { showAudioMenu = true },
                        enabled = audioStreams.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                    ) {
                        Icon(Icons.Rounded.Audiotrack, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = currentAudio?.remoteTrackLabel() ?: "Audio",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelMedium
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
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                    ) {
                        Icon(Icons.Rounded.Subtitles, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = currentSubtitle?.remoteTrackLabel()
                                ?: if (currentSubtitleIndex == null || currentSubtitleIndex < 0) {
                                    "Subtitles off"
                                } else {
                                    "Subtitles"
                                },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelMedium
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

            // Volume Control Slider Row
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onToggleMute) {
                    Icon(
                        if (isMuted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
                        contentDescription = if (isMuted) "Unmute" else "Mute",
                        tint = if (isMuted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Slider(
                    value = activeVolumePercent.toFloat(),
                    onValueChange = { scrubbingVolumePercent = it },
                    onValueChangeFinished = {
                        scrubbingVolumePercent?.let { vol -> onSetVolume(vol.toInt()) }
                        scrubbingVolumePercent = null
                    },
                    valueRange = 0f..100f,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.onSurface,
                        activeTrackColor = MaterialTheme.colorScheme.onSurface,
                        inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                )

                Spacer(Modifier.width(8.dp))
                Text(
                    text = "$activeVolumePercent%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(36.dp),
                    textAlign = TextAlign.End
                )
            }
        }
    }

    // Chapters Picker Dialog
    if (showChapterSheet && chapters.isNotEmpty()) {
        ChapterSelectionDialog(
            chapters = chapters,
            activeChapter = activeChapter,
            baseUrl = baseUrl,
            itemId = nowPlaying.id,
            accessToken = accessToken,
            onSelectChapter = { chapter ->
                showChapterSheet = false
                onSeekTo(chapter.startPosition * 10_000L)
            },
            onDismiss = { showChapterSheet = false }
        )
    }
}

/**
 * Self-contained network-remote entry point: pulls the shared [RemoteControlViewModel],
 * observes the controllable SpatialFin sessions on the network, and renders the
 * [RemoteControlMiniPlayer] (which stays hidden until something is controllable). Drop this
 * into any form factor's chrome — phone (Beam), XR Home Space, and TV all use the same host so
 * the remote behaves identically everywhere. Renders nothing when no remote session exists.
 */
@Composable
fun RemoteControlMiniPlayerHost(modifier: Modifier = Modifier) {
    val viewModel: RemoteControlViewModel = hiltViewModel()
    val activeSession by viewModel.activeRemoteSession.collectAsStateWithLifecycle()
    val availableSessions by viewModel.activeRemoteSessions.collectAsStateWithLifecycle()
    val mediaStreams by viewModel.activeMediaStreams.collectAsStateWithLifecycle()
    val chapters by viewModel.activeChapters.collectAsStateWithLifecycle()
    // Reset dismissed state when the playing item changes so a new item always shows.
    var dismissed by remember(activeSession?.id, activeSession?.nowPlayingItem?.id) { mutableStateOf(false) }

    if (!dismissed) {
        RemoteControlMiniPlayer(
            session = activeSession,
            availableSessions = availableSessions,
            baseUrl = viewModel.baseUrl,
            accessToken = viewModel.accessToken,
            mediaStreams = mediaStreams,
            chapters = chapters,
            onSelectSession = viewModel::selectRemoteSession,
            onPlayStateCommand = { cmd ->
                activeSession?.id?.let { viewModel.sendCommand(it, cmd) }
            },
            onGeneralCommand = { cmd, args ->
                activeSession?.id?.let { viewModel.sendGeneralCommand(it, cmd, args) }
            },
            onSeekTo = { seekTicks ->
                activeSession?.id?.let { viewModel.seekTo(it, seekTicks) }
            },
            onSeekBy = { deltaSec ->
                activeSession?.id?.let { id ->
                    val pos = activeSession?.playState?.positionTicks ?: 0L
                    viewModel.seekBy(id, pos, deltaSec)
                }
            },
            onSetVolume = { volume ->
                activeSession?.id?.let { viewModel.setVolume(it, volume) }
            },
            onToggleMute = {
                activeSession?.id?.let { viewModel.toggleMute(it) }
            },
            onDismiss = { dismissed = true },
            modifier = modifier,
        )
    }
}

@Composable
fun RemoteControlMiniPlayer(
    session: SessionInfoDto?,
    availableSessions: List<SessionInfoDto>,
    baseUrl: String,
    accessToken: String?,
    mediaStreams: List<SpatialFinMediaStream>,
    chapters: List<SpatialFinChapter> = emptyList(),
    onSelectSession: (String) -> Unit,
    onPlayStateCommand: (PlaystateCommand) -> Unit,
    onGeneralCommand: (GeneralCommandType, Map<String, String>?) -> Unit,
    onSeekTo: (Long) -> Unit = {},
    onSeekBy: (Long) -> Unit = {},
    onSetVolume: (Int) -> Unit = {},
    onToggleMute: () -> Unit = {},
    onDismiss: () -> Unit = {},
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
            IconButton(onClick = onDismiss) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = "Dismiss",
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
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
                        chapters = chapters,
                        onSelectSession = onSelectSession,
                        onPlayStateCommand = onPlayStateCommand,
                        onGeneralCommand = onGeneralCommand,
                        onSeekTo = onSeekTo,
                        onSeekBy = onSeekBy,
                        onSetVolume = onSetVolume,
                        onToggleMute = onToggleMute,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

@Composable
private fun ChapterSelectionDialog(
    chapters: List<SpatialFinChapter>,
    activeChapter: SpatialFinChapter?,
    baseUrl: String,
    itemId: UUID,
    accessToken: String?,
    onSelectChapter: (SpatialFinChapter) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .fillMaxHeight(0.75f),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Select Chapter (${chapters.size})",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.Close, contentDescription = "Close")
                    }
                }

                Spacer(Modifier.height(12.dp))

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(chapters.size) { index ->
                        val chapter = chapters[index]
                        val isSelected = chapter == activeChapter
                        val chapterImageUrl = remember(chapter.imageUri, baseUrl, accessToken, itemId, index) {
                            chapter.imageUri?.toString()
                                ?: remoteChapterImageUrl(baseUrl, itemId, index, null, accessToken)
                        }

                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectChapter(chapter) },
                            shape = RoundedCornerShape(16.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            border = if (isSelected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(64.dp, 40.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (!chapterImageUrl.isNullOrBlank()) {
                                        AsyncImage(
                                            model = chapterImageUrl,
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    } else {
                                        Text(
                                            text = "${index + 1}",
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                Spacer(Modifier.width(12.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = chapter.name ?: "Chapter ${index + 1}",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = formatTicks(chapter.startPosition * 10_000L),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                if (isSelected) {
                                    Icon(
                                        Icons.Rounded.Check,
                                        contentDescription = "Active Chapter",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BadgeChip(text: String) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            fontSize = 10.sp
        )
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

private fun formatTicks(ticks: Long?): String {
    if (ticks == null || ticks <= 0L) return "00:00"
    val totalSeconds = ticks / 10_000_000L
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}

private fun remoteChapterImageUrl(
    baseUrl: String,
    itemId: UUID,
    chapterIndex: Int,
    tag: String?,
    accessToken: String?
): String? {
    if (baseUrl.isBlank() || tag.isNullOrBlank()) return null
    val builder = Uri.parse("${baseUrl.trimEnd('/')}/Items/$itemId/Images/Chapter/$chapterIndex")
        .buildUpon()
        .appendQueryParameter("maxWidth", "384")
        .appendQueryParameter("tag", tag)
    accessToken?.takeIf { it.isNotBlank() }?.let { builder.appendQueryParameter("api_key", it) }
    return builder.build().toString()
}
