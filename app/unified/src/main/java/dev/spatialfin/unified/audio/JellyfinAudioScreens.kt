package dev.spatialfin.unified.audio

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.models.AudioQueueItem
import dev.jdtech.jellyfin.models.CollectionType
import dev.jdtech.jellyfin.models.SpatialFinAudioBook
import dev.jdtech.jellyfin.models.SpatialFinAudioTrack
import dev.jdtech.jellyfin.models.SpatialFinItem
import dev.jdtech.jellyfin.models.SpatialFinMusicAlbum
import dev.jdtech.jellyfin.models.SpatialFinMusicArtist
import dev.jdtech.jellyfin.models.SpatialFinPlaylist
import dev.jdtech.jellyfin.models.toAudioQueueItems
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.spatialfin.unified.music.maFocusHighlight
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class JellyfinAudioDetailType {
    Album,
    Artist,
    Playlist,
    Book,
}

private enum class MusicTab(val label: String) {
    Albums("Albums"),
    Artists("Artists"),
    Songs("Songs"),
}

data class JellyfinAudioLibraryState(
    val albums: List<SpatialFinMusicAlbum> = emptyList(),
    val artists: List<SpatialFinMusicArtist> = emptyList(),
    val songs: List<SpatialFinAudioTrack> = emptyList(),
    val playlists: List<SpatialFinPlaylist> = emptyList(),
    val books: List<SpatialFinAudioBook> = emptyList(),
    val isLoading: Boolean = false,
    val error: Throwable? = null,
)

@HiltViewModel
class JellyfinAudioLibraryViewModel
@Inject
constructor(private val repository: JellyfinRepository) : ViewModel() {
    private val _state = MutableStateFlow(JellyfinAudioLibraryState())
    val state = _state.asStateFlow()

    fun load(parentId: UUID, type: CollectionType) {
        viewModelScope.launch {
            _state.value = JellyfinAudioLibraryState(isLoading = true)
            runCatching {
                    when (type) {
                        CollectionType.Music ->
                            JellyfinAudioLibraryState(
                                albums = repository.getAudioAlbums(parentId),
                                artists = repository.getAudioArtists(parentId),
                                songs = repository.getAudioSongs(parentId),
                            )
                        CollectionType.Playlists ->
                            JellyfinAudioLibraryState(
                                playlists = repository.getAudioPlaylists(parentId)
                            )
                        CollectionType.Books ->
                            JellyfinAudioLibraryState(books = repository.getAudioBooks(parentId))
                        else -> JellyfinAudioLibraryState()
                    }
                }
                .onSuccess { _state.value = it }
                .onFailure { _state.value = JellyfinAudioLibraryState(error = it) }
        }
    }
}

data class JellyfinAudioDetailState(
    val item: SpatialFinItem? = null,
    val tracks: List<SpatialFinAudioTrack> = emptyList(),
    val isLoading: Boolean = false,
    val error: Throwable? = null,
)

@HiltViewModel
class JellyfinAudioDetailViewModel
@Inject
constructor(private val repository: JellyfinRepository) : ViewModel() {
    private val _state = MutableStateFlow(JellyfinAudioDetailState())
    val state = _state.asStateFlow()

    fun load(itemId: UUID, detailType: JellyfinAudioDetailType, parentId: UUID?) {
        viewModelScope.launch {
            _state.value = JellyfinAudioDetailState(isLoading = true)
            runCatching {
                    val item = repository.getItem(itemId)
                    val tracks =
                        when (detailType) {
                            JellyfinAudioDetailType.Album -> repository.getAudioAlbumTracks(itemId)
                            JellyfinAudioDetailType.Artist ->
                                repository.getAudioArtistTracks(parentId, itemId)
                            JellyfinAudioDetailType.Playlist ->
                                repository.getAudioPlaylistTracks(itemId)
                            JellyfinAudioDetailType.Book -> repository.getAudioBookTracks(itemId)
                        }
                    item to tracks
                }
                .onSuccess { (item, tracks) ->
                    _state.value = JellyfinAudioDetailState(item = item, tracks = tracks)
                }
                .onFailure { _state.value = JellyfinAudioDetailState(error = it) }
        }
    }
}

@Composable
fun JellyfinAudioLibraryScreen(
    libraryId: UUID,
    libraryName: String,
    libraryType: CollectionType,
    onBack: () -> Unit,
    onDetailClick: (id: UUID, title: String, type: JellyfinAudioDetailType) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: JellyfinAudioLibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val dispatcher = LocalAudioPlaybackDispatcher.current
    var selectedTab by rememberSaveable { mutableStateOf(MusicTab.Albums) }

    LaunchedEffect(libraryId, libraryType) { viewModel.load(libraryId, libraryType) }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
            AudioTopBar(title = libraryName, onBack = onBack)
            Spacer(Modifier.height(12.dp))
            when {
                state.isLoading -> LoadingBlock()
                state.error != null ->
                    Text(
                        text = state.error?.message ?: "Could not load audio library",
                        color = MaterialTheme.colorScheme.error,
                    )
                libraryType == CollectionType.Music -> {
                    TabRow(selectedTabIndex = selectedTab.ordinal) {
                        MusicTab.entries.forEach { tab ->
                            Tab(
                                selected = selectedTab == tab,
                                onClick = { selectedTab = tab },
                                text = { Text(tab.label) },
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    when (selectedTab) {
                        MusicTab.Albums ->
                            AudioCardGrid(
                                items = state.albums,
                                onClick = {
                                    onDetailClick(it.id, it.name, JellyfinAudioDetailType.Album)
                                },
                            )
                        MusicTab.Artists ->
                            AudioCardGrid(
                                items = state.artists,
                                onClick = {
                                    onDetailClick(it.id, it.name, JellyfinAudioDetailType.Artist)
                                },
                            )
                        MusicTab.Songs ->
                            TrackList(
                                tracks = state.songs,
                                onTrackClick = { index ->
                                    dispatcher?.playQueue(state.songs.toAudioQueueItems(), index)
                                },
                            )
                    }
                }
                libraryType == CollectionType.Playlists ->
                    AudioCardGrid(
                        items = state.playlists,
                        onClick = { onDetailClick(it.id, it.name, JellyfinAudioDetailType.Playlist) },
                    )
                libraryType == CollectionType.Books ->
                    AudioCardGrid(
                        items = state.books,
                        onClick = { onDetailClick(it.id, it.name, JellyfinAudioDetailType.Book) },
                    )
            }
        }
    }
}

@Composable
fun JellyfinAudioDetailScreen(
    itemId: UUID,
    title: String,
    detailType: JellyfinAudioDetailType,
    parentId: UUID?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: JellyfinAudioDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val dispatcher = LocalAudioPlaybackDispatcher.current

    LaunchedEffect(itemId, detailType, parentId) { viewModel.load(itemId, detailType, parentId) }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
            AudioTopBar(title = title, onBack = onBack)
            Spacer(Modifier.height(12.dp))
            when {
                state.isLoading -> LoadingBlock()
                state.error != null ->
                    Text(
                        text = state.error?.message ?: "Could not load tracks",
                        color = MaterialTheme.colorScheme.error,
                    )
                else -> {
                    state.item?.let { item ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ArtworkBox(item = item, modifier = Modifier.size(120.dp))
                            Spacer(Modifier.width(16.dp))
                            Column {
                                Text(
                                    text = item.name,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                )
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    IconButton(
                                        onClick = {
                                            dispatcher?.playQueue(state.tracks.toAudioQueueItems())
                                        },
                                        modifier = Modifier.size(48.dp).maFocusHighlight(),
                                    ) {
                                        Icon(Icons.Filled.PlayArrow, contentDescription = "Play All")
                                    }
                                    IconButton(
                                        onClick = {
                                            dispatcher?.playQueue(
                                                state.tracks.shuffled().toAudioQueueItems()
                                            )
                                        },
                                        modifier = Modifier.size(48.dp).maFocusHighlight(),
                                    ) {
                                        Icon(Icons.Filled.Shuffle, contentDescription = "Shuffle")
                                    }
                                }
                            }
                        }
                    }

                    if (state.tracks.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("No tracks found", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        TrackList(
                            tracks = state.tracks,
                            onTrackClick = { index ->
                                dispatcher?.playQueue(state.tracks.toAudioQueueItems(), index)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun JellyfinAudioMiniPlayer(
    dispatcher: AudioPlaybackDispatcher,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
    suppressed: Boolean = false,
) {
    val state by dispatcher.state.collectAsStateWithLifecycle()
    val item = state.currentItem

    AnimatedVisibility(
        visible = item != null && !suppressed,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        if (item != null) {
            Surface(
                modifier = modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 8.dp,
                shape = RoundedCornerShape(16.dp),
            ) {
                Column {
                    if (state.phase == AudioPlaybackPhase.Buffering) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable(onClick = onExpand)
                                .maFocusHighlight(RoundedCornerShape(12.dp))
                                .padding(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                        AudioArtwork(item = item, size = 44)
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            item.subtitle?.takeIf { it.isNotBlank() }?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        }
                        IconButton(onClick = { dispatcher.playPause() }, modifier = Modifier.maFocusHighlight()) {
                            Icon(
                                imageVector =
                                    if (state.phase == AudioPlaybackPhase.Playing) Icons.Filled.Pause
                                    else Icons.Filled.PlayArrow,
                                contentDescription =
                                    if (state.phase == AudioPlaybackPhase.Playing) "Pause" else "Play",
                            )
                        }
                        IconButton(onClick = { dispatcher.next() }, modifier = Modifier.maFocusHighlight()) {
                            Icon(Icons.Filled.SkipNext, contentDescription = "Next")
                        }
                        IconButton(onClick = { dispatcher.stop() }, modifier = Modifier.maFocusHighlight()) {
                            Icon(Icons.Filled.Stop, contentDescription = "Stop")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun JellyfinAudioNowPlayingScreen(
    dispatcher: AudioPlaybackDispatcher,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by dispatcher.state.collectAsStateWithLifecycle()
    val item = state.currentItem
    var showQueue by remember { mutableStateOf(false) }
    var showLyrics by remember { mutableStateOf(false) }
    var showChapters by remember { mutableStateOf(false) }
    var lyrics by remember { mutableStateOf<String?>(null) }
    var sliderPosition by remember(state.currentItem?.id) { mutableFloatStateOf(0f) }

    LaunchedEffect(item?.id) {
        lyrics = null
        showLyrics = false
        showChapters = false
        item?.id?.let { lyrics = dispatcher.lyricsFor(it)?.plainText }
    }

    LaunchedEffect(state.positionMs, state.durationMs, item?.id) {
        sliderPosition =
            if (state.durationMs > 0L) state.positionMs.toFloat() / state.durationMs.toFloat()
            else 0f
    }

    when {
        showQueue -> {
            QueuePanel(
                state = state,
                onBack = { showQueue = false },
                onSelect = dispatcher::playIndex,
                modifier = modifier,
            )
            return
        }
        showLyrics -> {
            TextPanel(
                title = item?.title ?: "Lyrics",
                body = lyrics.orEmpty(),
                onBack = { showLyrics = false },
                modifier = modifier,
            )
            return
        }
        showChapters -> {
            ChapterPanel(
                item = item,
                onBack = { showChapters = false },
                onSelect = { chapterStartMs ->
                    dispatcher.seekTo(chapterStartMs)
                    showChapters = false
                },
                modifier = modifier,
            )
            return
        }
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                IconButton(onClick = onBack, modifier = Modifier.maFocusHighlight()) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                }
                Row {
                    if (item?.chapters?.isNotEmpty() == true) {
                        IconButton(onClick = { showChapters = true }, modifier = Modifier.maFocusHighlight()) {
                            Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = "Chapters")
                        }
                    }
                    if (!lyrics.isNullOrBlank()) {
                        IconButton(onClick = { showLyrics = true }, modifier = Modifier.maFocusHighlight()) {
                            Icon(Icons.Filled.Lyrics, contentDescription = "Lyrics")
                        }
                    }
                    IconButton(onClick = { showQueue = true }, modifier = Modifier.maFocusHighlight()) {
                        Icon(Icons.Filled.QueueMusic, contentDescription = "Queue")
                    }
                }
            }
            BoxWithConstraints(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                val wide = maxWidth >= 760.dp
                if (wide) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(32.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        NowPlayingArtwork(item = item, modifier = Modifier.weight(0.9f))
                        NowPlayingControls(
                            state = state,
                            sliderPosition = sliderPosition,
                            onSliderChange = { sliderPosition = it },
                            onSliderFinish = {
                                dispatcher.seekTo((sliderPosition * state.durationMs).toLong())
                            },
                            dispatcher = dispatcher,
                            modifier = Modifier.weight(1.1f),
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        NowPlayingArtwork(item = item, modifier = Modifier.fillMaxWidth(0.7f))
                        NowPlayingControls(
                            state = state,
                            sliderPosition = sliderPosition,
                            onSliderChange = { sliderPosition = it },
                            onSliderFinish = {
                                dispatcher.seekTo((sliderPosition * state.durationMs).toLong())
                            },
                            dispatcher = dispatcher,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AudioTopBar(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(onClick = onBack, modifier = Modifier.maFocusHighlight()) {
            Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
        }
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun LoadingBlock() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun AudioCardGrid(items: List<SpatialFinItem>, onClick: (SpatialFinItem) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(180.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 96.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(items, key = { it.id }) { item ->
            ElevatedCard(onClick = { onClick(item) }, shape = RoundedCornerShape(8.dp), modifier = Modifier.maFocusHighlight(RoundedCornerShape(8.dp))) {
                Column(modifier = Modifier.padding(10.dp)) {
                    ArtworkBox(item = item, modifier = Modifier.fillMaxWidth().aspectRatio(1f))
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    item.overview.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackList(tracks: List<SpatialFinAudioTrack>, onTrackClick: (Int) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(tracks, key = { _, track -> track.id }) { index, track ->
            TrackRow(track = track, onClick = { onTrackClick(index) })
        }
    }
}

@Composable
private fun TrackRow(track: SpatialFinAudioTrack, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick)
            .maFocusHighlight(RoundedCornerShape(8.dp)),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ArtworkBox(item = track, modifier = Modifier.size(48.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val subtitle =
                    track.artists.joinToString(", ").ifBlank { track.album ?: track.albumArtist.orEmpty() }
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                text = formatDuration(track.runtimeTicks / 10_000L),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NowPlayingArtwork(item: AudioQueueItem?, modifier: Modifier = Modifier) {
    Box(modifier = modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp))) {
        if (item?.artwork != null) {
            AsyncImage(
                model = item.artwork,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(
                modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.GraphicEq,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(80.dp),
                )
            }
        }
    }
}

@Composable
private fun NowPlayingControls(
    state: AudioSessionState,
    sliderPosition: Float,
    onSliderChange: (Float) -> Unit,
    onSliderFinish: () -> Unit,
    dispatcher: AudioPlaybackDispatcher,
    modifier: Modifier = Modifier,
) {
    val item = state.currentItem
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = item?.title ?: "Nothing playing",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        item?.subtitle?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(20.dp))
        Slider(
            value = sliderPosition.coerceIn(0f, 1f),
            onValueChange = onSliderChange,
            onValueChangeFinished = onSliderFinish,
            enabled = state.durationMs > 0L,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatDuration((sliderPosition * state.durationMs).toLong()))
            Text(formatDuration(state.durationMs))
        }
        Spacer(Modifier.height(18.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = dispatcher::previous, modifier = Modifier.maFocusHighlight()) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous")
            }
            FilledIconButton(onClick = dispatcher::playPause, modifier = Modifier.size(64.dp).maFocusHighlight()) {
                Icon(
                    imageVector =
                        if (state.phase == AudioPlaybackPhase.Playing) Icons.Filled.Pause
                        else Icons.Filled.PlayArrow,
                    contentDescription =
                        if (state.phase == AudioPlaybackPhase.Playing) "Pause" else "Play",
                )
            }
            IconButton(onClick = dispatcher::next, modifier = Modifier.maFocusHighlight()) {
                Icon(Icons.Filled.SkipNext, contentDescription = "Next")
            }
            IconButton(onClick = dispatcher::stop, modifier = Modifier.maFocusHighlight()) {
                Icon(Icons.Filled.Stop, contentDescription = "Stop")
            }
        }
        Spacer(Modifier.height(16.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                FilterChip(
                    selected = state.shuffleEnabled,
                    onClick = dispatcher::toggleShuffle,
                    modifier = Modifier.maFocusHighlight(RoundedCornerShape(8.dp)),
                    label = { Text("Shuffle") },
                    leadingIcon = { Icon(Icons.Filled.Shuffle, contentDescription = null) },
                )
            }
            item {
                FilterChip(
                    selected = state.repeatMode != AudioRepeatMode.Off,
                    onClick = dispatcher::cycleRepeatMode,
                    modifier = Modifier.maFocusHighlight(RoundedCornerShape(8.dp)),
                    label = {
                        Text(
                            when (state.repeatMode) {
                                AudioRepeatMode.One -> "Repeat one"
                                AudioRepeatMode.All -> "Repeat all"
                                AudioRepeatMode.Off -> "Repeat"
                            }
                        )
                    },
                    leadingIcon = {
                        Icon(
                            if (state.repeatMode == AudioRepeatMode.One) Icons.Filled.RepeatOne
                            else Icons.Filled.Repeat,
                            contentDescription = null,
                        )
                    },
                )
            }
            item {
                AssistChip(
                    onClick = { dispatcher.setPlaybackSpeed(nextSpeed(state.playbackSpeed)) },
                    modifier = Modifier.maFocusHighlight(RoundedCornerShape(8.dp)),
                    label = { Text("${formatSpeed(state.playbackSpeed)}x") },
                )
            }
        }
    }
}

@Composable
private fun QueuePanel(
    state: AudioSessionState,
    onBack: () -> Unit,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
            AudioTopBar(title = "Queue", onBack = onBack)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(state.queue, key = { _, item -> item.id }) { index, item ->
                    Surface(
                        modifier =
                            Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onSelect(index) }
                                .maFocusHighlight(RoundedCornerShape(8.dp)),
                        color =
                            if (index == state.currentIndex) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceContainer,
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AudioArtwork(item = item, size = 42)
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                item.subtitle?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
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
private fun TextPanel(title: String, body: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
            AudioTopBar(title = title, onBack = onBack)
            LazyColumn(contentPadding = PaddingValues(vertical = 12.dp)) {
                item {
                    Text(
                        text = body.ifBlank { "No lyrics available" },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
    }
}

@Composable
private fun ChapterPanel(
    item: AudioQueueItem?,
    onBack: () -> Unit,
    onSelect: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
            AudioTopBar(title = "Chapters", onBack = onBack)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(item?.chapters.orEmpty()) { chapter ->
                    Surface(
                        modifier =
                            Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onSelect(chapter.startPosition) }
                                .maFocusHighlight(RoundedCornerShape(8.dp)),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(chapter.name ?: "Chapter")
                                Text(
                                    formatDuration(chapter.startPosition),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ArtworkBox(item: SpatialFinItem, modifier: Modifier = Modifier) {
    Box(modifier = modifier.clip(RoundedCornerShape(8.dp))) {
        if (item.images.primary != null) {
            AsyncImage(
                model = item.images.primary,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(
                modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun AudioArtwork(item: AudioQueueItem, size: Int) {
    Box(modifier = Modifier.size(size.dp).clip(RoundedCornerShape(8.dp))) {
        if (item.artwork != null) {
            AsyncImage(
                model = item.artwork,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(
                modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

private fun nextSpeed(current: Float): Float =
    when {
        current < 1f -> 1f
        current < 1.25f -> 1.25f
        current < 1.5f -> 1.5f
        current < 2f -> 2f
        else -> 1f
    }

private fun formatSpeed(speed: Float): String {
    val rounded = (speed * 100).toInt() / 100f
    return rounded.toString().trimEnd('0').trimEnd('.')
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
