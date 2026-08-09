package dev.jdtech.jellyfin.models

enum class CollectionType(val type: String, private val legacySavedStateValue: String) {
    // Keep these values explicit: older Beam builds persisted enum names, which R8 may rewrite.
    // Keep this list in sync with org.jellyfin.sdk.model.api.CollectionType — a server-side
    // collection type that is missing here collapses to Unknown.
    Movies("movies", "Movies"),
    TvShows("tvshows", "TvShows"),
    HomeVideos("homevideos", "HomeVideos"),
    MusicVideos("musicvideos", "MusicVideos"),
    Trailers("trailers", "Trailers"),
    Photos("photos", "Photos"),
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

        /**
         * Library kinds we cannot browse at all. Everything else is [supported].
         *
         * This is deliberately an *exclusion* list. An inclusion list silently hides
         * any library whose collection type we forgot to enumerate, which made whole
         * servers look empty — e.g. media filed under "Home videos and photos"
         * (`homevideos`) never reached the library list or the Home "Latest" rows.
         * New Jellyfin collection types now show up by default; add to this set only
         * when a type genuinely needs APIs we do not implement.
         *
         * `LiveTv` needs the tuner/EPG endpoints, which SpatialFin does not speak.
         */
        private val unsupported = setOf(LiveTv)

        val supported: List<CollectionType> = entries.filterNot { it in unsupported }

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
