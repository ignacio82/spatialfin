package dev.jdtech.jellyfin.search

import android.app.SearchManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.BaseColumns
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.deeplink.PlayDeepLink
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import timber.log.Timber

/**
 * Surfaces SpatialFin's Jellyfin library into Google TV global search.
 *
 * Scope today: **local Room cache only** — movies/shows/episodes the user has
 * downloaded or otherwise materialised on-device. A real-time network search
 * against the Jellyfin server is intentionally skipped; the system launcher
 * hits this provider on every keystroke and a LAN round-trip would ANR.
 * Full library coverage is a future feature (see "Known Gaps" in GEMINI.md).
 *
 * Tap target: `spatialfin://play?id=...` — `TvPlayerActivity`'s `ACTION_VIEW`
 * intent-filter (declared in the TV flavor manifest) handles the hop.
 */
class SpatialFinSearchProvider : ContentProvider() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun database(): ServerDatabaseDao
        fun appPreferences(): AppPreferences
    }

    private lateinit var uriMatcher: UriMatcher
    private val deps: Deps by lazy {
        EntryPointAccessors.fromApplication(requireAttachedContext(), Deps::class.java)
    }

    override fun onCreate(): Boolean {
        val authority =
            context?.packageName?.let { "$it.search" } ?: return false
        uriMatcher =
            UriMatcher(UriMatcher.NO_MATCH).apply {
                addURI(authority, SearchManager.SUGGEST_URI_PATH_QUERY, SUGGEST_ROOT)
                addURI(authority, "${SearchManager.SUGGEST_URI_PATH_QUERY}/*", SUGGEST_WITH_QUERY)
            }
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val match = uriMatcher.match(uri)
        if (match != SUGGEST_ROOT && match != SUGGEST_WITH_QUERY) return null

        // Android passes the query either as the last path segment (voice /
        // Assistant) or in `selectionArgs` (typed in the search bar).
        val rawQuery =
            when {
                match == SUGGEST_WITH_QUERY -> uri.lastPathSegment
                !selectionArgs.isNullOrEmpty() -> selectionArgs[0]
                else -> null
            }
        val query = rawQuery?.trim().orEmpty()
        val cursor = MatrixCursor(COLUMNS)
        if (query.length < MIN_QUERY_LENGTH) return cursor

        val serverId = deps.appPreferences().getValue(deps.appPreferences().currentServer) ?: return cursor

        return try {
            val database = deps.database()
            val movies = database.searchMovies(serverId, query)
            val shows = database.searchShows(serverId, query)
            val episodes = database.searchEpisodes(serverId, query)

            var idCounter = 0L
            for (movie in movies.take(MAX_PER_KIND)) {
                cursor.addRow(
                    arrayOf<Any?>(
                        idCounter++,
                        movie.name,
                        secondaryLine(KIND_MOVIE, movie.productionYear?.toString()),
                        Intent.ACTION_VIEW,
                        PlayDeepLink.build(movie.id, PlayDeepLink.KIND_MOVIE).toString(),
                        movie.id.toString(),
                    )
                )
            }
            for (show in shows.take(MAX_PER_KIND)) {
                // Shows have no direct play target — deep-link to the show's
                // first pending episode isn't cheap from Room alone, so surface
                // the show name as a hint that points at the latest season's
                // first unplayed episode. For now, omit shows without a clear
                // playback destination.
                if (show.name.isBlank()) continue
                cursor.addRow(
                    arrayOf<Any?>(
                        idCounter++,
                        show.name,
                        secondaryLine(KIND_SHOW, show.productionYear?.toString()),
                        Intent.ACTION_VIEW,
                        PlayDeepLink.build(show.id, PlayDeepLink.KIND_MOVIE).toString(),
                        show.id.toString(),
                    )
                )
            }
            for (episode in episodes.take(MAX_PER_KIND)) {
                cursor.addRow(
                    arrayOf<Any?>(
                        idCounter++,
                        episode.name.ifBlank { episode.seriesName },
                        episodeSecondary(episode.seriesName, episode.parentIndexNumber, episode.indexNumber),
                        Intent.ACTION_VIEW,
                        PlayDeepLink.build(episode.id, PlayDeepLink.KIND_EPISODE).toString(),
                        episode.id.toString(),
                    )
                )
            }
            cursor
        } catch (e: Exception) {
            Timber.w(e, "SpatialFinSearchProvider: query failed for '%s'", query)
            cursor
        }
    }

    override fun getType(uri: Uri): String? =
        when (uriMatcher.match(uri)) {
            SUGGEST_ROOT,
            SUGGEST_WITH_QUERY -> SearchManager.SUGGEST_MIME_TYPE
            else -> null
        }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private fun requireAttachedContext(): Context = context ?: error("SpatialFinSearchProvider not attached")

    private fun secondaryLine(kind: String, year: String?): String =
        if (year.isNullOrBlank()) kind else "$kind • $year"

    private fun episodeSecondary(seriesName: String, season: Int, episode: Int): String {
        val code =
            if (season > 0 && episode > 0) "S${season}E${episode}"
            else if (episode > 0) "E$episode"
            else null
        return listOfNotNull(code, seriesName.takeIf { it.isNotBlank() })
            .joinToString(" • ")
    }

    companion object {
        private const val SUGGEST_ROOT = 1
        private const val SUGGEST_WITH_QUERY = 2

        private const val MIN_QUERY_LENGTH = 2
        private const val MAX_PER_KIND = 10

        private const val KIND_MOVIE = "Movie"
        private const val KIND_SHOW = "Show"

        private val COLUMNS =
            arrayOf(
                BaseColumns._ID,
                SearchManager.SUGGEST_COLUMN_TEXT_1,
                SearchManager.SUGGEST_COLUMN_TEXT_2,
                SearchManager.SUGGEST_COLUMN_INTENT_ACTION,
                SearchManager.SUGGEST_COLUMN_INTENT_DATA,
                SearchManager.SUGGEST_COLUMN_INTENT_EXTRA_DATA,
            )
    }
}
