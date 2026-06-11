package dev.spatialfin.unified.music

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AllInclusive
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.jdtech.jellyfin.data.musicassistant.data.model.server.RepeatMode
import dev.jdtech.jellyfin.data.musicassistant.repository.MaSession
import dev.jdtech.jellyfin.data.musicassistant.repository.MaSessionRepository
import dev.jdtech.jellyfin.data.musicassistant.repository.PlaybackPhase
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import kotlinx.coroutines.delay

/**
 * Full-screen MA Now Playing.
 *
 * Two state sources:
 *  - **[MaSessionRepository.session]** for everything that changes via MA
 *    server events: track metadata, server-side elapsed time, player picker,
 *    playback phase.
 *  - **A local 1 Hz tick** that extrapolates the scrubber between
 *    `QUEUE_TIME_UPDATED` events (those fire ~once per second per active
 *    queue; without the tick the scrubber jumps in 1-second steps and looks
 *    laggy on a 60 Hz display).
 *
 * Full transport: play/pause/skip, stop (dismisses the player), repeat-cycle,
 * shuffle, a draggable seek scrubber, and a per-player volume slider (shown
 * only for players that expose a volume control).
 *
 * Designed for parity across Beam (regular screen), TV (full-screen route with
 * d-pad focus), and XR Home Space (regular screen) / Full Space (hosted in a
 * SpatialDialog). Form-factor hosts wrap this in their own back/navigation
 * chrome.
 */
@Composable
fun MaNowPlayingScreen(
    session: MaSessionRepository,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onPickPlayer: (() -> Unit)? = null,
    onOpenSearch: (() -> Unit)? = null,
    onOpenParty: (() -> Unit)? = null,
) {
    val state by session.session.collectAsStateWithLifecycle()
    val dispatcher = dev.spatialfin.unified.LocalMaPlayDispatcher.current
    var showQueue by remember { mutableStateOf(false) }
    var showLyrics by remember { mutableStateOf(false) }
    var showChapters by remember { mutableStateOf(false) }

    // Lyrics + audiobook chapters are fetched lazily per track (the now-playing
    // payload doesn't carry them). Each button only shows once we actually have
    // content for the current item.
    var lyrics by remember { mutableStateOf<String?>(null) }
    var chapters by remember {
        mutableStateOf<List<dev.jdtech.jellyfin.data.musicassistant.data.model.server.ServerMediaItemChapter>>(emptyList())
    }
    val currentUri = state.nowPlaying?.uri
    LaunchedEffect(currentUri, dispatcher) {
        lyrics = null
        chapters = emptyList()
        showLyrics = false
        showChapters = false
        val uri = currentUri
        if (!uri.isNullOrBlank() && dispatcher != null) {
            lyrics = runCatching { dispatcher.lyricsFor(uri) }.getOrNull()
            if (uri.contains("://audiobook/")) {
                chapters = runCatching { dispatcher.chaptersFor(uri) }.getOrDefault(emptyList())
            }
        }
    }

    if (showQueue) {
        MaQueuePanel(onBack = { showQueue = false }, modifier = modifier)
        return
    }
    if (showLyrics) {
        MaLyricsPanel(
            lyrics = lyrics.orEmpty(),
            title = state.nowPlaying?.title,
            onBack = { showLyrics = false },
            modifier = modifier,
        )
        return
    }
    if (showChapters) {
        MaChaptersPanel(
            chapters = chapters,
            title = state.nowPlaying?.title,
            onSelect = { chapter ->
                dispatcher?.seekTo((chapter.start * 1000.0).toLong())
                showChapters = false
            },
            onBack = { showChapters = false },
            modifier = modifier,
        )
        return
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 12.dp)) {
            // Top bar gets its own row so the centered artwork below can never
            // overlap the back / action icons (it did on shorter phone screens).
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Always reachable: the Party plugin is configured server-side
                    // (no player "source"), so we can't gate on partySource — the
                    // party screen itself loads the guest QR via `party/url` and
                    // simply omits it when the plugin isn't present.
                    if (onOpenParty != null) {
                        IconButton(onClick = onOpenParty) {
                            Icon(Icons.Filled.Celebration, contentDescription = "Party screen")
                        }
                    }
                    if (chapters.isNotEmpty()) {
                        IconButton(onClick = { showChapters = true }) {
                            Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = "Chapters")
                        }
                    }
                    if (!lyrics.isNullOrBlank()) {
                        IconButton(onClick = { showLyrics = true }) {
                            Icon(Icons.Filled.Lyrics, contentDescription = "Lyrics")
                        }
                    }
                    IconButton(onClick = { showQueue = true }) {
                        Icon(Icons.Filled.QueueMusic, contentDescription = "Queue")
                    }
                    if (onOpenSearch != null) {
                        IconButton(onClick = onOpenSearch) {
                            Icon(Icons.Filled.Search, contentDescription = "Search Music Assistant")
                        }
                    }
                    // Current-track actions (add to queue / playlist) — makes this
                    // the single player with the MA overflow.
                    state.nowPlaying?.uri?.takeIf { it.isNotBlank() }?.let { uri ->
                        NowPlayingTrackMenu(uri = uri, title = state.nowPlaying?.title, dispatcher = dispatcher)
                    }
                }
            }
            // Body fills the rest, centered, and scrolls if it's taller than the
            // remaining space (tall content on small phones).
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                contentAlignment = Alignment.Center,
            ) {
                NowPlayingBody(
                    state = state,
                    onPickPlayer = onPickPlayer,
                    onToggleParty = dispatcher?.let { { it.togglePartyMode() } },
                    onPrevious = dispatcher?.let { { it.previous() } },
                    onPlayPause = dispatcher?.let { { it.playPause() } },
                    onNext = dispatcher?.let { { it.next() } },
                    onStop = dispatcher?.let { { it.stop() } },
                    onCycleRepeat = dispatcher?.let { { it.cycleRepeatMode() } },
                    onToggleShuffle = dispatcher?.let { { it.toggleShuffle() } },
                    onSeek = dispatcher?.let { d -> { ms: Long -> d.seekTo(ms) } },
                    onSetVolume = dispatcher?.let { d -> { v: Int -> d.setVolume(v) } },
                    onToggleMute = dispatcher?.let { { it.toggleMute() } },
                    onToggleDontStop = dispatcher?.let { { it.toggleDontStopTheMusic() } },
                )
            }
        }
    }
}

@Composable
private fun NowPlayingTrackMenu(
    uri: String,
    title: String?,
    dispatcher: dev.spatialfin.unified.MaPlayDispatcher?,
) {
    if (dispatcher == null) return
    var expanded by remember { mutableStateOf(false) }
    var showPlaylist by remember { mutableStateOf(false) }
    val item = remember(uri) {
        dev.jdtech.jellyfin.data.musicassistant.data.model.server.ServerMediaItem(
            itemId = "",
            provider = "",
            name = title ?: "Track",
            uri = uri,
            mediaType = "track",
        )
    }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "Track actions")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Add to queue") },
                leadingIcon = { Icon(Icons.Filled.QueueMusic, contentDescription = null) },
                onClick = { expanded = false; dispatcher.addToQueue(item) },
            )
            DropdownMenuItem(
                text = { Text("Add to playlist") },
                leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                onClick = { expanded = false; showPlaylist = true },
            )
        }
    }
    if (showPlaylist) {
        MaAddToPlaylistDialog(
            items = listOf(item),
            dispatcher = dispatcher,
            onDismiss = { showPlaylist = false },
        )
    }
}

@Composable
private fun NowPlayingBody(
    state: MaSession,
    modifier: Modifier = Modifier,
    onPickPlayer: (() -> Unit)? = null,
    onToggleParty: (() -> Unit)? = null,
    onPrevious: (() -> Unit)? = null,
    onPlayPause: (() -> Unit)? = null,
    onNext: (() -> Unit)? = null,
    onStop: (() -> Unit)? = null,
    onCycleRepeat: (() -> Unit)? = null,
    onToggleShuffle: (() -> Unit)? = null,
    onSeek: ((Long) -> Unit)? = null,
    onSetVolume: ((Int) -> Unit)? = null,
    onToggleMute: (() -> Unit)? = null,
    onToggleDontStop: (() -> Unit)? = null,
) {
    val track = state.nowPlaying
    if (track == null) {
        Column(
            modifier = modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.MusicNote,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Nothing is playing",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Tap a track in your library to start playback.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        return
    }

    Column(
        modifier = modifier
            .widthIn(max = 520.dp)
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 8.dp,
        ) {
            if (track.artworkUrl.isNullOrBlank()) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.MusicNote,
                        contentDescription = null,
                        modifier = Modifier.size(96.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                AsyncImage(
                    model = track.artworkUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(24.dp)),
                )
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = track.title ?: "Unknown track",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            val artistLine = track.artist
            if (!artistLine.isNullOrBlank()) {
                Text(
                    text = artistLine,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Scrubber(state = state, onSeek = onSeek)

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Shuffle — tinted when on.
            IconButton(
                onClick = { onToggleShuffle?.invoke() },
                modifier = Modifier.maFocusHighlight(),
                enabled = onToggleShuffle != null,
            ) {
                Icon(
                    Icons.Filled.Shuffle,
                    contentDescription = if (state.shuffleEnabled) "Shuffle on" else "Shuffle off",
                    modifier = Modifier.size(24.dp),
                    tint = if (state.shuffleEnabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            IconButton(
                onClick = { onPrevious?.invoke() },
                modifier = Modifier.maFocusHighlight(),
                enabled = onPrevious != null && state.playbackPhase != PlaybackPhase.Preparing,
            ) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous", modifier = Modifier.size(36.dp))
            }
            FilledIconButton(
                onClick = { onPlayPause?.invoke() },
                modifier = Modifier.size(64.dp).maFocusHighlight(),
                enabled = onPlayPause != null && state.playbackPhase != PlaybackPhase.Preparing,
            ) {
                Icon(
                    imageVector = if (state.playbackPhase == PlaybackPhase.Playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (state.playbackPhase == PlaybackPhase.Playing) "Pause" else "Play",
                    modifier = Modifier.size(36.dp),
                )
            }
            IconButton(
                onClick = { onNext?.invoke() },
                modifier = Modifier.maFocusHighlight(),
                enabled = onNext != null && state.playbackPhase != PlaybackPhase.Preparing,
            ) {
                Icon(Icons.Filled.SkipNext, contentDescription = "Next", modifier = Modifier.size(36.dp))
            }
            // Repeat — Off (dim) / All (tinted) / One (tinted, RepeatOne glyph).
            IconButton(
                onClick = { onCycleRepeat?.invoke() },
                modifier = Modifier.maFocusHighlight(),
                enabled = onCycleRepeat != null,
            ) {
                Icon(
                    imageVector = if (state.repeatMode == RepeatMode.ONE) {
                        Icons.Filled.RepeatOne
                    } else {
                        Icons.Filled.Repeat
                    },
                    contentDescription = when (state.repeatMode) {
                        RepeatMode.OFF -> "Repeat off"
                        RepeatMode.ALL -> "Repeat all"
                        RepeatMode.ONE -> "Repeat one"
                    },
                    modifier = Modifier.size(24.dp),
                    tint = if (state.repeatMode == RepeatMode.OFF) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
        }

        // Stop — ends playback and dismisses the player (unlike Pause).
        IconButton(
            onClick = { onStop?.invoke() },
            modifier = Modifier.maFocusHighlight(),
            enabled = onStop != null && state.playbackPhase != PlaybackPhase.Stopped,
        ) {
            Icon(
                Icons.Filled.Stop,
                contentDescription = "Stop",
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Volume — only for players that expose a volume knob (a Chromecast
        // group does; a raw line-out source may not).
        state.selectedPlayer?.takeIf { it.supportsVolume }?.let { player ->
            VolumeRow(
                level = player.volumeLevel ?: 0,
                muted = player.volumeMuted,
                onSetVolume = onSetVolume,
                onToggleMute = onToggleMute,
            )
        }

        // Player picker chip. Always render it (even with no selected player)
        // so the user can choose where to play before tapping a song.
        AssistChip(
            onClick = { onPickPlayer?.invoke() },
            modifier = Modifier.maFocusHighlight(RoundedCornerShape(8.dp)),
            enabled = onPickPlayer != null,
            label = {
                Text(
                    text = state.selectedPlayer
                        ?.let { "Playing on ${it.name}" }
                        ?: "Choose a player",
                )
            },
            colors = AssistChipDefaults.assistChipColors(),
        )

        // "Don't Stop The Music" — MA keeps the queue alive with similar tracks
        // when it runs dry. Only meaningful once a queue exists.
        if (onToggleDontStop != null && state.activeQueueId != null) {
            FilterChip(
                selected = state.dontStopTheMusic,
                onClick = onToggleDontStop,
                modifier = Modifier.maFocusHighlight(RoundedCornerShape(8.dp)),
                label = { Text(if (state.dontStopTheMusic) "Endless play on" else "Endless play") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.AllInclusive,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
            )
        }

        // Party mode toggle — only when the selected player exposes the MA
        // Party plugin's source.
        state.partySource?.let { party ->
            FilterChip(
                selected = party.active,
                onClick = { onToggleParty?.invoke() },
                modifier = Modifier.maFocusHighlight(RoundedCornerShape(8.dp)),
                enabled = onToggleParty != null,
                label = { Text(if (party.active) "Party mode on" else "Party mode") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Celebration,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
            )
        }
    }
}

/**
 * Read-only scrubber that extrapolates between `QUEUE_TIME_UPDATED` events.
 *
 * MA emits `QUEUE_TIME_UPDATED` at roughly 1 Hz for the active queue. If we
 * bound the bar directly to [MaSession.elapsedMs] it would visibly jump in
 * 1 s steps. Instead we anchor on `elapsedAsOfEpochMs` and add the local
 * wall-clock delta. The 250 ms tick is the cheapest update interval that
 * looks smooth without burning CPU.
 *
 * When the duration is unknown we fall back to an indeterminate strip — happy
 * for streams (`PlayerState.PLAYING` with no duration: radio, podcast).
 */
@Composable
private fun Scrubber(state: MaSession, onSeek: ((Long) -> Unit)? = null) {
    val duration = state.nowPlaying?.durationMs
    val anchorElapsed = state.elapsedMs
    val anchorAt = state.elapsedAsOfEpochMs
    var now by remember(anchorAt) { mutableLongStateOf(System.currentTimeMillis()) }

    // While the user is dragging the thumb, this holds the dragged fraction so
    // the bar + elapsed label track the finger instead of the 1 Hz server tick.
    // Not keyed on the server anchor: a tick mid-drag must not reset the thumb.
    // Cleared on release.
    var scrubFraction by remember { mutableStateOf<Float?>(null) }

    // Tick only while we have something to extrapolate. Stopped / Preparing /
    // missing-duration content skips the tick to keep idle CPU near zero.
    val shouldTick = duration != null && anchorElapsed != null && anchorAt != null &&
        state.playbackPhase == PlaybackPhase.Playing
    LaunchedEffect(shouldTick, anchorAt) {
        if (!shouldTick) return@LaunchedEffect
        while (true) {
            delay(250)
            now = System.currentTimeMillis()
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (duration == null || anchorElapsed == null) {
            // Indeterminate: live stream / unknown length / preparing.
            if (state.playbackPhase == PlaybackPhase.Preparing) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                )
            } else {
                Spacer(Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(2.dp),
                        ),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(text = "—", style = MaterialTheme.typography.labelSmall)
                Text(text = "—", style = MaterialTheme.typography.labelSmall)
            }
            return@Column
        }

        val effective = if (shouldTick && anchorAt != null) {
            (anchorElapsed + (now - anchorAt)).coerceAtMost(duration)
        } else {
            anchorElapsed.coerceAtMost(duration)
        }
        val liveProgress = if (duration > 0) (effective.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f
        val shownProgress = scrubFraction ?: liveProgress
        val shownElapsed = (shownProgress * duration).toLong()

        if (onSeek != null) {
            Slider(
                value = shownProgress,
                onValueChange = { scrubFraction = it },
                onValueChangeFinished = {
                    scrubFraction?.let { f -> onSeek((f * duration).toLong()) }
                    scrubFraction = null
                },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LinearProgressIndicator(
                progress = { shownProgress },
                modifier = Modifier.fillMaxWidth().height(4.dp),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = formatDuration(shownElapsed), style = MaterialTheme.typography.labelSmall)
            Text(text = formatDuration(duration), style = MaterialTheme.typography.labelSmall)
        }
    }
}

/**
 * Per-player volume: mute toggle + slider. Only rendered for players that
 * report a volume control. The slider tracks the finger locally while dragging
 * and commits on release, so we don't fire a `volume_set` RPC per pixel.
 */
@Composable
private fun VolumeRow(
    level: Int,
    muted: Boolean,
    onSetVolume: ((Int) -> Unit)?,
    onToggleMute: (() -> Unit)?,
) {
    // Not keyed on `level`: a server volume echo mid-drag must not snap the
    // thumb. While dragging, show the dragged value; otherwise the live level.
    var dragLevel by remember { mutableStateOf<Float?>(null) }
    val shown = dragLevel ?: level.toFloat()
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconButton(
            onClick = { onToggleMute?.invoke() }, 
            modifier = Modifier.maFocusHighlight(),
            enabled = onToggleMute != null
        ) {
            Icon(
                imageVector = if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = if (muted) "Unmute" else "Mute",
                modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = if (muted) 0f else shown,
            onValueChange = { dragLevel = it },
            onValueChangeFinished = {
                dragLevel?.let { onSetVolume?.invoke(it.toInt()) }
                dragLevel = null
            },
            valueRange = 0f..100f,
            enabled = onSetVolume != null && !muted,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Full-screen lyrics view, mirroring the queue panel's host pattern (the
 * now-playing screen swaps to it and back). Scrollable plain text — MA returns
 * lyrics as a single newline-delimited blob, synced timestamps not exposed.
 */
@Composable
private fun MaLyricsPanel(
    lyrics: String,
    title: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            IconButton(onClick = onBack, modifier = Modifier.align(Alignment.TopStart)) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 56.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = title ?: "Lyrics",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = lyrics,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/**
 * Full-screen audiobook chapter picker (same host pattern as the lyrics/queue
 * panels). Tapping a chapter seeks the active stream to its start — the
 * audiobook is a single item, so chapters are seek offsets, not tracks.
 */
@Composable
private fun MaChaptersPanel(
    chapters: List<dev.jdtech.jellyfin.data.musicassistant.data.model.server.ServerMediaItemChapter>,
    title: String?,
    onSelect: (dev.jdtech.jellyfin.data.musicassistant.data.model.server.ServerMediaItemChapter) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            IconButton(onClick = onBack, modifier = Modifier.align(Alignment.TopStart)) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(top = 56.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                item("title") {
                    Text(
                        text = title ?: "Chapters",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }
                itemsIndexed(chapters, key = { _, c -> "ch|" + c.position }) { index, chapter ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(chapter) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${index + 1}.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.widthIn(min = 28.dp),
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(
                            text = chapter.name,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(
                            text = formatDuration((chapter.start * 1000.0).toLong()),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

@Suppress("UnusedPrivateMember")
@Composable
private fun PreparingSpinner() {
    CircularProgressIndicator()
    Spacer(Modifier.size(8.dp))
    Text("Preparing audio…", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
}

@Composable
internal fun Modifier.maFocusHighlight(shape: androidx.compose.ui.graphics.Shape = androidx.compose.foundation.shape.CircleShape): Modifier {
    var isFocused by remember { mutableStateOf(false) }
    val scale by androidx.compose.animation.core.animateFloatAsState(if (isFocused) 1.15f else 1f, label = "focusScale")
    val outlineColor = MaterialTheme.colorScheme.primary
    return this
        .onFocusChanged { isFocused = it.isFocused }
        .scale(scale)
        .background(if (isFocused) outlineColor.copy(alpha = 0.15f) else Color.Transparent, shape)
        .border(if (isFocused) 2.dp else 0.dp, if (isFocused) outlineColor else Color.Transparent, shape)
}
