package dev.spatialfin.unified.music

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.data.musicassistant.data.model.server.MediaType
import dev.jdtech.jellyfin.data.musicassistant.data.model.server.ServerMediaItem
import dev.jdtech.jellyfin.data.musicassistant.repository.MusicAssistantRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * One ViewModel covers album, artist, and playlist detail because the data
 * shape is identical: load a header [ServerMediaItem] and a list of child
 * [ServerMediaItem]s. The host calls [load] with a seed item (the search
 * result the user tapped) plus the [DetailKind] enum, and the VM dispatches
 * to the right MA repository methods.
 *
 * The header refetch (instead of trusting the seed item) matters because
 * MA's search result is sparser than the dedicated `/get` payload — search
 * gives us title + image but no artist links, no year, no track count.
 * Pulling the full header on entry means the screen renders with everything
 * the dedicated detail endpoint exposes, not just what fit in the search
 * row.
 *
 * For artists we keep two child lists (top tracks + albums) and fetch them
 * concurrently. For albums and playlists the header + single track list is
 * enough.
 */
@HiltViewModel
class MaDetailViewModel @Inject constructor(
    private val repository: MusicAssistantRepository,
) : ViewModel() {
    private val _state: MutableStateFlow<MaDetailState> = MutableStateFlow(MaDetailState.Loading)
    val state: StateFlow<MaDetailState> = _state.asStateFlow()

    // Remembered so playlist edits can re-fetch the list after a mutation.
    private var lastSeed: ServerMediaItem? = null
    private var lastKind: DetailKind? = null

    /** Fire once when the screen mounts. Idempotent if called multiple times. */
    fun load(seed: ServerMediaItem, kind: DetailKind) {
        lastSeed = seed
        lastKind = kind
        _state.value = MaDetailState.Loading
        viewModelScope.launch {
            try {
                when (kind) {
                    DetailKind.Album -> loadAlbum(seed)
                    DetailKind.Artist -> loadArtist(seed)
                    DetailKind.Playlist -> loadPlaylist(seed)
                    DetailKind.Podcast -> loadPodcast(seed)
                    DetailKind.Audiobook -> loadAudiobook(seed)
                }
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "%s detail load failed for %s", kind, seed.itemId)
                _state.value = MaDetailState.Error(e.message ?: "Failed to load")
            }
        }
    }

    private suspend fun loadAlbum(seed: ServerMediaItem) {
        val header = repository.getAlbum(seed) ?: seed
        val tracks = repository.getAlbumTracks(seed)
        _state.value = MaDetailState.Loaded(
            kind = DetailKind.Album,
            header = header,
            tracks = tracks,
            albums = emptyList(),
        )
    }

    private suspend fun loadArtist(seed: ServerMediaItem) {
        val header = repository.getArtist(seed) ?: seed
        val tracks = repository.getArtistTracks(seed)
        val albums = repository.getArtistAlbums(seed)
        _state.value = MaDetailState.Loaded(
            kind = DetailKind.Artist,
            header = header,
            tracks = tracks,
            albums = albums,
        )
    }

    private suspend fun loadPlaylist(seed: ServerMediaItem) {
        val header = repository.getPlaylist(seed) ?: seed
        val tracks = repository.getPlaylistTracks(seed)
        _state.value = MaDetailState.Loaded(
            kind = DetailKind.Playlist,
            header = header,
            tracks = tracks,
            albums = emptyList(),
        )
    }

    private suspend fun loadPodcast(seed: ServerMediaItem) {
        // A podcast isn't directly playable — you play its episodes. The header
        // carries description/publisher; `tracks` is the episode list.
        val header = repository.getPodcast(seed) ?: seed
        val episodes = repository.getPodcastEpisodes(seed)
        _state.value = MaDetailState.Loaded(
            kind = DetailKind.Podcast,
            header = header,
            tracks = episodes,
            albums = emptyList(),
        )
    }

    private suspend fun loadAudiobook(seed: ServerMediaItem) {
        // An audiobook is a single playable item; its chapters live in
        // header.metadata.chapters as seek targets, not as separate children.
        val header = repository.getAudiobook(seed) ?: seed
        _state.value = MaDetailState.Loaded(
            kind = DetailKind.Audiobook,
            header = header,
            tracks = emptyList(),
            albums = emptyList(),
        )
    }

    /**
     * Remove the track at [position] (0-based, matching the loaded list order)
     * from the editable playlist [playlistId]. Optimistically drops it from the
     * visible list, then reconciles with a refetch.
     */
    fun removePlaylistTrack(playlistId: String, position: Int) {
        val loaded = _state.value as? MaDetailState.Loaded ?: return
        if (position !in loaded.tracks.indices) return
        _state.value = loaded.copy(
            tracks = loaded.tracks.toMutableList().apply { removeAt(position) },
        )
        viewModelScope.launch {
            val ok = repository.removeTracksFromPlaylist(playlistId, listOf(position))
            if (!ok) {
                Timber.tag(TAG).w("remove_playlist_tracks failed (playlist=%s pos=%d)", playlistId, position)
            }
            // Refetch either way: success confirms, failure restores truth.
            lastSeed?.let { seed -> lastKind?.let { kind -> reloadInBackground(seed, kind) } }
        }
    }

    /** Delete the editable playlist [playlistId], then invoke [onDeleted]. */
    fun deletePlaylist(playlistId: String, onDeleted: () -> Unit) {
        viewModelScope.launch {
            val ok = repository.removeFromLibrary(playlistId, MediaType.PLAYLIST)
            if (!ok) Timber.tag(TAG).w("delete playlist %s failed", playlistId)
            onDeleted()
        }
    }

    /** Toggle the favourite flag on the header item (album / artist / playlist). */
    fun toggleHeaderFavorite() {
        val loaded = _state.value as? MaDetailState.Loaded ?: return
        val target = !(loaded.header.favorite == true)
        _state.value = loaded.copy(header = loaded.header.copy(favorite = target))
        viewModelScope.launch {
            val result = repository.setFavorite(loaded.header, target)
            if (result == null) {
                // Revert on failure.
                (_state.value as? MaDetailState.Loaded)?.let {
                    _state.value = it.copy(header = it.header.copy(favorite = !target))
                }
            }
        }
    }

    private suspend fun reloadInBackground(seed: ServerMediaItem, kind: DetailKind) {
        try {
            when (kind) {
                DetailKind.Album -> loadAlbum(seed)
                DetailKind.Artist -> loadArtist(seed)
                DetailKind.Playlist -> loadPlaylist(seed)
                DetailKind.Podcast -> loadPodcast(seed)
                DetailKind.Audiobook -> loadAudiobook(seed)
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "background reload failed for %s", seed.itemId)
        }
    }

    enum class DetailKind { Album, Artist, Playlist, Podcast, Audiobook }

    private companion object {
        const val TAG = "MaDetail"
    }
}

sealed interface MaDetailState {
    data object Loading : MaDetailState
    data class Error(val message: String) : MaDetailState
    data class Loaded(
        val kind: MaDetailViewModel.DetailKind,
        val header: ServerMediaItem,
        val tracks: List<ServerMediaItem>,
        val albums: List<ServerMediaItem>,
    ) : MaDetailState
}
