package dev.jdtech.jellyfin.models

enum class CollectionType(val type: String, private val legacySavedStateValue: String) {
    // Keep these values explicit: older Beam builds persisted enum names, which R8 may rewrite.
    Movies("movies", "Movies"),
    TvShows("tvshows", "TvShows"),
    HomeVideos("homevideos", "HomeVideos"),
    Music("music", "Music"),
    Playlists("playlists", "Playlists"),
    Books("books", "Books"),
    LiveTv("livetv", "LiveTv"),
    BoxSets("boxsets", "BoxSets"),
    Mixed("null", "Mixed"),
    Folders("folders", "Folders"),
    Unknown("unknown", "Unknown");

    companion object {
        val defaultValue = Unknown

        val supported = listOf(Movies, TvShows, BoxSets, Mixed, Folders, Music, Playlists, Books)

        fun fromString(string: String?): CollectionType {
            if (
                string == null
            ) { // TODO jellyfin returns null as the collectiontype for mixed libraries. This is
                //  obviously wrong, but probably an upstream issue. Should be fixed whenever
                //  upstream fixes this
                return Mixed
            }

            return entries.firstOrNull {
                it.type == string || it.legacySavedStateValue == string
            } ?: defaultValue
        }
    }
}
