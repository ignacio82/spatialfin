package dev.jdtech.jellyfin.film.presentation.photo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.models.SpatialFinPhoto
import dev.jdtech.jellyfin.repository.JellyfinRepository
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemKind

data class PhotoViewerState(
    val photos: List<SpatialFinPhoto> = emptyList(),
    val startIndex: Int = 0,
    val isLoading: Boolean = false,
    val error: Throwable? = null,
)

/**
 * Backs the full-screen photo viewer. The caller only knows which photo was tapped,
 * so this resolves the rest of the containing folder to make swiping between shots
 * work without every browse surface having to hand over a list.
 */
@HiltViewModel
class PhotoViewerViewModel
@Inject
constructor(private val repository: JellyfinRepository) : ViewModel() {
    private val _state = MutableStateFlow(PhotoViewerState())
    val state = _state.asStateFlow()

    fun load(photoId: UUID, parentId: UUID?) {
        viewModelScope.launch {
            _state.emit(PhotoViewerState(isLoading = true))
            runCatching {
                    val siblings =
                        parentId?.let {
                            repository
                                .getItems(
                                    parentId = it,
                                    includeTypes = listOf(BaseItemKind.PHOTO),
                                    recursive = false,
                                    limit = MAX_PHOTOS_PER_FOLDER,
                                )
                                .filterIsInstance<SpatialFinPhoto>()
                        } ?: emptyList()

                    // A folder that paged past the cap, or a photo reached from search
                    // (no parent), still has to open — fall back to the single item.
                    val photos =
                        siblings.takeIf { list -> list.any { it.id == photoId } }
                            ?: listOfNotNull(repository.getItem(photoId) as? SpatialFinPhoto)

                    photos to photos.indexOfFirst { it.id == photoId }.coerceAtLeast(0)
                }
                .onSuccess { (photos, index) ->
                    _state.emit(PhotoViewerState(photos = photos, startIndex = index))
                }
                .onFailure { _state.emit(PhotoViewerState(error = it)) }
        }
    }

    private companion object {
        /** Guards against pulling a 20k-image folder into memory to open one shot. */
        const val MAX_PHOTOS_PER_FOLDER = 500
    }
}
