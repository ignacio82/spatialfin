package dev.jdtech.jellyfin.repository

import androidx.paging.PagingSource
import androidx.paging.PagingState
import dev.jdtech.jellyfin.models.SpatialFinItem
import dev.jdtech.jellyfin.models.SortBy
import dev.jdtech.jellyfin.models.SortOrder
import java.util.UUID
import org.jellyfin.sdk.model.api.BaseItemKind
import timber.log.Timber

class ItemsPagingSource(
    private val jellyfinRepository: JellyfinRepository,
    private val parentId: UUID?,
    private val includeTypes: List<BaseItemKind>?,
    private val recursive: Boolean,
    private val sortBy: SortBy,
    private val sortOrder: SortOrder,
) : PagingSource<Int, SpatialFinItem>() {
    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, SpatialFinItem> {
        val position = params.key ?: 0

        Timber.d("Retrieving position: $position")

        return try {
            val items =
                jellyfinRepository.getItems(
                    parentId = parentId,
                    includeTypes = includeTypes,
                    recursive = recursive,
                    sortBy = sortBy,
                    sortOrder = sortOrder,
                    startIndex = position,
                    limit = params.loadSize,
                )
            LoadResult.Page(
                data = items,
                prevKey = if (position == 0) null else position - params.loadSize,
                nextKey = if (items.isEmpty()) null else position + params.loadSize,
            )
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }

    // Keys here are absolute startIndex offsets into the Jellyfin result set
    // (see `load` — `position` is `startIndex`). The standard index-keyed
    // recipe: land the refresh on the anchor page's own first-index so scroll
    // position survives invalidation / rotation instead of snapping to 0.
    override fun getRefreshKey(state: PagingState<Int, SpatialFinItem>): Int? {
        val anchor = state.anchorPosition ?: return null
        val closest = state.closestPageToPosition(anchor) ?: return null
        return closest.prevKey?.plus(state.config.pageSize)
            ?: closest.nextKey?.minus(state.config.pageSize)
    }
}
