package dev.spatialfin.unified.music

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
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

    /** Fire once when the screen mounts. Idempotent if called multiple times. */
    fun load(seed: ServerMediaItem, kind: DetailKind) {
        _state.value = MaDetailState.Loading
        viewModelScope.launch {
            try {
                when (kind) {
                    DetailKind.Album -> loadAlbum(seed)
                    DetailKind.Artist -> loadArtist(seed)
                    DetailKind.Playlist -> loadPlaylist(seed)
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

    enum class DetailKind { Album, Artist, Playlist }

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
