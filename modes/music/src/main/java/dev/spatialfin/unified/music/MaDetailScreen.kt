package dev.spatialfin.unified.music

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.jdtech.jellyfin.data.musicassistant.data.model.server.ServerMediaItem
import dev.jdtech.jellyfin.data.musicassistant.data.model.server.ServerMediaItemChapter

/**
 * Shared detail screen for albums, artists, and playlists. Renders a hero
 * header (artwork + title + subtitle + Play All button) followed by a track
 * list, and — for artists — a discography row below the tracks.
 *
 * One screen for three kinds because the structure is identical; only the
 * subtitle line and the secondary list (artist's albums) differ. Splitting
 * into three near-identical files would mean three places to keep in sync
 * for the Phase 3 add-to-queue context menu, the Phase 4 share button, the
 * Phase 5 MediaSession surface, etc.
 *
 * Track taps go through [LocalMaPlayDispatcher] — same path as
 * [MaSearchScreen], so the optimistic mini-player kicks in immediately and
 * the user gets identical playback latency from any entry point.
 */
@Composable
fun MaDetailScreen(
    seed: ServerMediaItem,
    kind: MaDetailViewModel.DetailKind,
    onBack: () -> Unit,
    onOpenItem: (MaBrowseTarget) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MaDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(seed.itemId, kind) {
        viewModel.load(seed, kind)
    }
    val dispatcher = dev.spatialfin.unified.LocalMaPlayDispatcher.current

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(modifier = Modifier.fillMaxSize()) {
            when (val s = state) {
                MaDetailState.Loading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                is MaDetailState.Error -> {
                    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(s.message, color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = { viewModel.load(seed, kind) }, modifier = Modifier.padding(top = 12.dp)) {
                                Text("Retry")
                            }
                        }
                    }
                }
                is MaDetailState.Loaded -> LoadedBody(
                    state = s,
                    dispatcher = dispatcher,
                    onPlayHeader = {
                        // Audiobooks resume from their stored position; everything
                        // else (re)starts from the top.
                        val resume = s.header.resumePositionMs ?: 0L
                        if (s.kind == MaDetailViewModel.DetailKind.Audiobook && resume > 0L) {
                            dispatcher?.playThenSeek(s.header, resume)
                        } else {
                            dispatcher?.play(s.header)
                        }
                    },
                    onPlayTrack = { dispatcher?.play(it) },
                    onPlayChapter = { ch ->
                        dispatcher?.playThenSeek(s.header, (ch.start * 1000.0).toLong())
                    },
                    onOpenItem = onOpenItem,
                    onToggleHeaderFavorite = { viewModel.toggleHeaderFavorite() },
                    onRemovePlaylistTrack = { position ->
                        viewModel.removePlaylistTrack(s.header.itemId, position)
                    },
                    onDeletePlaylist = {
                        viewModel.deletePlaylist(s.header.itemId, onDeleted = onBack)
                    },
                )
            }

            // Back affordance is always on top; even during Loading the user
            // shouldn't be trapped on the spinner.
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        }
    }
}

@Composable
private fun LoadedBody(
    state: MaDetailState.Loaded,
    dispatcher: dev.spatialfin.unified.MaPlayDispatcher?,
    onPlayHeader: () -> Unit,
    onPlayTrack: (ServerMediaItem) -> Unit,
    onPlayChapter: (ServerMediaItemChapter) -> Unit,
    onOpenItem: (MaBrowseTarget) -> Unit,
    onToggleHeaderFavorite: () -> Unit,
    onRemovePlaylistTrack: (Int) -> Unit,
    onDeletePlaylist: () -> Unit,
) {
    // Only editable playlists expose in-list editing (remove track, delete
    // playlist). MA marks user-owned playlists with is_editable.
    val playlistEditable = state.kind == MaDetailViewModel.DetailKind.Playlist &&
        state.header.isEditable == true
    val selection = rememberMaSelection()
    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(top = 56.dp, bottom = 16.dp),
        ) {
            item("header") {
                Header(
                    state = state,
                    onPlay = onPlayHeader,
                    onToggleFavorite = onToggleHeaderFavorite,
                    onDeletePlaylist = if (playlistEditable) onDeletePlaylist else null,
                )
            }
            // Audiobook chapters: seek targets within one stream, not children.
            val chapters = if (state.kind == MaDetailViewModel.DetailKind.Audiobook) {
                state.header.metadata?.chapters.orEmpty()
            } else {
                emptyList()
            }
            if (chapters.isNotEmpty()) {
                item("chapters-label") { SectionLabel(text = "Chapters") }
                items(chapters, key = { "ch|" + it.position }) { chapter ->
                    ChapterRow(chapter = chapter, onClick = { onPlayChapter(chapter) })
                }
            }
            if (state.tracks.isNotEmpty()) {
                item("tracks-label") {
                    SectionLabel(
                        text = when (state.kind) {
                            MaDetailViewModel.DetailKind.Album -> "Tracks"
                            MaDetailViewModel.DetailKind.Artist -> "Top tracks"
                            MaDetailViewModel.DetailKind.Playlist -> "Tracks"
                            MaDetailViewModel.DetailKind.Podcast -> "Episodes"
                            MaDetailViewModel.DetailKind.Audiobook -> "Chapters"
                        },
                    )
                }
                itemsIndexed(state.tracks, key = { _, it -> it.itemId + "|" + it.provider }) { index, track ->
                    val key = track.itemId + "|" + track.provider
                    MaSearchItemRow(
                        item = track,
                        selected = selection.isSelected(key),
                        onLongClick = { selection.toggle(key, track) },
                        onClick = {
                            if (selection.active) selection.toggle(key, track) else onPlayTrack(track)
                        },
                        trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (playlistEditable) {
                                    IconButton(onClick = { onRemovePlaylistTrack(index) }) {
                                        Icon(
                                            Icons.Filled.RemoveCircleOutline,
                                            contentDescription = "Remove from playlist",
                                        )
                                    }
                                }
                                MaTrackMenu(item = track, dispatcher = dispatcher)
                            }
                        },
                    )
                }
            }
            if (state.kind == MaDetailViewModel.DetailKind.Artist && state.albums.isNotEmpty()) {
                item("albums-label") { SectionLabel(text = "Albums") }
                items(state.albums, key = { "album|" + it.itemId + "|" + it.provider }) { album ->
                    MaSearchItemRow(
                        item = album,
                        onClick = { onOpenItem(MaBrowseTarget.Album(album)) },
                    )
                }
            }
        }
        MaSelectionBar(selection = selection, dispatcher = dispatcher)
    }
}

@Composable
private fun Header(
    state: MaDetailState.Loaded,
    onPlay: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDeletePlaylist: (() -> Unit)?,
) {
    val header = state.header
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .fillMaxWidth()
                .aspectRatio(1f),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 6.dp,
        ) {
            val art = header.preferredArtworkUrl()
            if (art.isNullOrBlank()) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.MusicNote,
                        contentDescription = null,
                        modifier = Modifier.size(72.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                AsyncImage(
                    model = art,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = header.name,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        header.subtitleLine()?.let { subtitle ->
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(16.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // A podcast isn't directly playable (no header play); everything
            // else has a primary action. Audiobooks say "Resume" when MA has a
            // stored position to pick up from.
            val resuming = state.kind == MaDetailViewModel.DetailKind.Audiobook &&
                (state.header.resumePositionMs ?: 0L) > 0L
            val playLabel = when (state.kind) {
                MaDetailViewModel.DetailKind.Album,
                MaDetailViewModel.DetailKind.Playlist -> "Play all"
                MaDetailViewModel.DetailKind.Artist -> "Play top tracks"
                MaDetailViewModel.DetailKind.Audiobook -> if (resuming) "Resume" else "Play"
                MaDetailViewModel.DetailKind.Podcast -> null
            }
            if (playLabel != null) {
                FilledTonalButton(onClick = onPlay) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(text = playLabel)
                }
            }
            val isFavorite = header.favorite == true
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = if (isFavorite) "Remove from favorites" else "Add to favorites",
                    tint = if (isFavorite) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (onDeletePlaylist != null) {
                IconButton(onClick = onDeletePlaylist) {
                    Icon(
                        Icons.Filled.DeleteOutline,
                        contentDescription = "Delete playlist",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun ChapterRow(chapter: ServerMediaItemChapter, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.PlayArrow,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = chapter.name,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = formatChapterTime(chapter.start),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Seconds → h:mm:ss (or m:ss under an hour). */
private fun formatChapterTime(seconds: Double): String {
    val total = seconds.toLong().coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 24.dp, top = 16.dp, end = 24.dp, bottom = 8.dp),
    )
}
