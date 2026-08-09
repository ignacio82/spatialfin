package dev.jdtech.jellyfin.models

import org.jellyfin.sdk.model.api.BaseItemKind

/**
 * The Jellyfin item kinds a library of this [CollectionType] can show.
 *
 * This is the single source of truth for every library grid — XR, Beam, and TV. It
 * used to be copy-pasted per shell, which is how `homevideos` libraries ended up
 * invisible on some surfaces and empty on others: only the kinds someone remembered
 * to list were ever requested, and non-film libraries hand back `Video` / `Photo`
 * rather than `Movie`.
 *
 * @param foldersFirst `true` for shells that browse a library as its on-disk tree
 *   (Beam, TV) — the caller should then query non-recursively. `false` for the flat,
 *   paged XR grid, which flattens Movies/TV/BoxSets. Library kinds that are inherently
 *   folder-structured (home videos, photos, mixed) always include folders regardless,
 *   because flattening them buries the organisation the user filed the media under.
 *
 * A `null` result means "no opinion" — the caller should omit `includeItemTypes`
 * entirely and let the server decide.
 */
fun CollectionType.browsableItemKinds(foldersFirst: Boolean): List<BaseItemKind>? {
    val leafKinds =
        when (this) {
            CollectionType.Movies -> listOf(BaseItemKind.MOVIE)
            CollectionType.TvShows -> listOf(BaseItemKind.SERIES)
            CollectionType.BoxSets -> listOf(BaseItemKind.BOX_SET)
            CollectionType.HomeVideos,
            CollectionType.Photos ->
                listOf(
                    BaseItemKind.PHOTO_ALBUM,
                    BaseItemKind.VIDEO,
                    BaseItemKind.MOVIE,
                    BaseItemKind.MUSIC_VIDEO,
                    BaseItemKind.PHOTO,
                )
            CollectionType.MusicVideos -> listOf(BaseItemKind.MUSIC_VIDEO)
            CollectionType.Trailers -> listOf(BaseItemKind.TRAILER)
            // Any sub-folder of any library also resolves to Folders — including the
            // folders inside a home-video library — so this has to admit stills and
            // loose videos too, or drilling into a photo folder shows nothing.
            CollectionType.Mixed,
            CollectionType.Folders ->
                listOf(
                    BaseItemKind.PHOTO_ALBUM,
                    BaseItemKind.MOVIE,
                    BaseItemKind.SERIES,
                    BaseItemKind.EPISODE,
                    BaseItemKind.SEASON,
                    BaseItemKind.BOX_SET,
                    BaseItemKind.VIDEO,
                    BaseItemKind.MUSIC_VIDEO,
                    BaseItemKind.PHOTO,
                )
            else -> return null
        }

    return if (foldersFirst || this in FOLDER_STRUCTURED) {
        listOf(BaseItemKind.FOLDER) + leafKinds
    } else {
        leafKinds
    }
}

private val FOLDER_STRUCTURED =
    setOf(
        CollectionType.HomeVideos,
        CollectionType.Photos,
        CollectionType.MusicVideos,
        CollectionType.Trailers,
        CollectionType.Mixed,
        CollectionType.Folders,
    )
