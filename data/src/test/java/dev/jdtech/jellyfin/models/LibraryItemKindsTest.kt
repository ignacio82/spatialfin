package dev.jdtech.jellyfin.models

import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-shell copies of this mapping are what let `homevideos` libraries render
 * empty on some surfaces and be missing entirely on others. These lock the shared
 * version's contract so a future library kind can't be half-added again.
 */
class LibraryItemKindsTest {

    @Test
    fun `home video libraries request the kinds those libraries actually contain`() {
        val kinds = CollectionType.HomeVideos.browsableItemKinds(foldersFirst = false)!!

        // Loose files come back as Video, stills as Photo — never as Movie.
        assertTrue(BaseItemKind.VIDEO in kinds)
        assertTrue(BaseItemKind.PHOTO in kinds)
        assertTrue(BaseItemKind.PHOTO_ALBUM in kinds)
    }

    @Test
    fun `folder-structured libraries keep folders even for the flat XR grid`() {
        listOf(
                CollectionType.HomeVideos,
                CollectionType.Photos,
                CollectionType.MusicVideos,
                CollectionType.Trailers,
                CollectionType.Mixed,
                CollectionType.Folders,
            )
            .forEach { type ->
                val kinds = type.browsableItemKinds(foldersFirst = false)!!
                assertTrue("$type must browse folder-first", BaseItemKind.FOLDER in kinds)
            }
    }

    @Test
    fun `film libraries flatten for XR and stay folder-first for Beam and TV`() {
        listOf(CollectionType.Movies, CollectionType.TvShows, CollectionType.BoxSets).forEach {
            type ->
            val flat = type.browsableItemKinds(foldersFirst = false)!!
            val foldered = type.browsableItemKinds(foldersFirst = true)!!

            assertTrue("$type should flatten on XR", BaseItemKind.FOLDER !in flat)
            assertEquals(listOf(BaseItemKind.FOLDER) + flat, foldered)
        }
    }

    @Test
    fun `a sub-folder of a photo library still requests stills`() {
        // Every sub-folder resolves to Folders regardless of the library it came from,
        // so drilling into a photo folder has to keep asking for photos.
        val kinds = CollectionType.Folders.browsableItemKinds(foldersFirst = true)!!

        assertTrue(BaseItemKind.PHOTO in kinds)
        assertTrue(BaseItemKind.VIDEO in kinds)
    }

    @Test
    fun `unsupported library kinds defer to the server`() {
        // null means "omit includeItemTypes" — never "request nothing".
        assertNull(CollectionType.LiveTv.browsableItemKinds(foldersFirst = true))
        assertNull(CollectionType.Unknown.browsableItemKinds(foldersFirst = true))
    }

    @Test
    fun `every browsable library kind either has a mapping or defers`() {
        // A kind that returns an empty list would query for nothing and render blank —
        // the exact failure mode this file exists to prevent.
        CollectionType.supported.forEach { type ->
            val kinds = type.browsableItemKinds(foldersFirst = true)
            assertTrue("$type maps to an empty request", kinds == null || kinds.isNotEmpty())
        }
    }
}
