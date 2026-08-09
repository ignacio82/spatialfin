package dev.jdtech.jellyfin.repository

import org.jellyfin.sdk.model.api.ItemFields
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verified against a live Jellyfin 10.11.11: `/Items` omits `ParentId`, `Width`, and
 * `Height` unless they are explicitly requested. Dropping `PARENT_ID` from the browse
 * field list is a *silent* regression — every grid still populates, but every
 * `SpatialFinPhoto.parentId` comes back null and the photo viewer degrades to showing
 * one image with no way to swipe to the next.
 */
class BrowseItemFieldsTest {

    @Test
    fun `browse queries request the field-gated properties the UI depends on`() {
        val fields = JellyfinRepositoryImpl.BROWSE_ITEM_FIELDS

        assertTrue(
            "PARENT_ID is required or photo-viewer folder paging silently breaks",
            ItemFields.PARENT_ID in fields,
        )
        // SeriesFilter.dropEmptyShows is conservative without these two.
        assertTrue(ItemFields.CHILD_COUNT in fields)
        assertTrue(ItemFields.RECURSIVE_ITEM_COUNT in fields)
    }
}
