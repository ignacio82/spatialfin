package dev.jdtech.jellyfin.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionTypeTest {
    @Test
    fun stableValuesRemainStableAndRoundTrip() {
        val expectedTypes =
            mapOf(
                CollectionType.Movies to "movies",
                CollectionType.TvShows to "tvshows",
                CollectionType.HomeVideos to "homevideos",
                CollectionType.MusicVideos to "musicvideos",
                CollectionType.Trailers to "trailers",
                CollectionType.Photos to "photos",
                CollectionType.Music to "music",
                CollectionType.Playlists to "playlists",
                CollectionType.Books to "books",
                CollectionType.LiveTv to "livetv",
                CollectionType.BoxSets to "boxsets",
                CollectionType.Mixed to "null",
                CollectionType.Folders to "folders",
                CollectionType.Unknown to "unknown",
            )

        assertEquals(CollectionType.entries.size, expectedTypes.size)
        expectedTypes.forEach { (type, persistedType) ->
            assertEquals(persistedType, type.type)
            assertEquals(type, CollectionType.fromString(persistedType))
        }
    }

    @Test
    fun legacySavedStateValuesAreMigrated() {
        val legacyValues =
            mapOf(
                "Movies" to CollectionType.Movies,
                "TvShows" to CollectionType.TvShows,
                "HomeVideos" to CollectionType.HomeVideos,
                "MusicVideos" to CollectionType.MusicVideos,
                "Trailers" to CollectionType.Trailers,
                "Photos" to CollectionType.Photos,
                "Music" to CollectionType.Music,
                "Playlists" to CollectionType.Playlists,
                "Books" to CollectionType.Books,
                "LiveTv" to CollectionType.LiveTv,
                "BoxSets" to CollectionType.BoxSets,
                "Mixed" to CollectionType.Mixed,
                "Folders" to CollectionType.Folders,
                "Unknown" to CollectionType.Unknown,
            )

        assertEquals(CollectionType.entries.size, legacyValues.size)
        legacyValues.forEach { (legacyValue, type) ->
            assertEquals(type, CollectionType.fromString(legacyValue))
        }
    }

    @Test
    fun nullAndUnrecognizedValuesUseExistingFallbacks() {
        assertEquals(CollectionType.Mixed, CollectionType.fromString(null))
        assertEquals(CollectionType.Unknown, CollectionType.fromString("not-a-collection-type"))
    }

    /**
     * Regression: a server whose media lived in a "Home videos and photos" library
     * rendered as a completely empty app, because the library was filtered out of both
     * the library list and the Home "Latest" rows.
     */
    @Test
    fun nonFilmLibraryKindsAreBrowsable() {
        listOf(
            CollectionType.HomeVideos,
            CollectionType.MusicVideos,
            CollectionType.Trailers,
            CollectionType.Photos,
        ).forEach { assertTrue("$it must be browsable", it in CollectionType.supported) }
    }

    @Test
    fun onlyLiveTvIsHidden() {
        assertFalse(CollectionType.LiveTv in CollectionType.supported)
        assertEquals(CollectionType.entries.size - 1, CollectionType.supported.size)
    }
}
