package dev.jdtech.jellyfin.watchnext

import android.content.Context
import android.provider.BaseColumns
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import dev.jdtech.jellyfin.deeplink.PlayDeepLink
import dev.jdtech.jellyfin.models.SpatialFinEpisode
import dev.jdtech.jellyfin.models.SpatialFinItem
import dev.jdtech.jellyfin.models.SpatialFinMovie
import java.util.UUID
import kotlin.math.max
import timber.log.Timber

/**
 * Syncs Jellyfin "Continue Watching" + "Next Up" into the Google TV home screen's
 * Watch Next row via the system `TvContractCompat.WatchNextPrograms` provider.
 *
 * Design:
 *  - Each program's `internalProviderId` is the Jellyfin item UUID, which we use
 *    to diff desired state against the rows currently published.
 *  - Fully-played items (`played = true`) are dropped from the publish set and
 *    their existing rows are deleted, matching Google's guidance that finished
 *    content should leave Watch Next.
 *  - Resume items become `WATCH_NEXT_TYPE_CONTINUE`; Next Up episodes become
 *    `WATCH_NEXT_TYPE_NEXT`. An item present in both lists (rare: a show you're
 *    mid-episode in and whose "next up" is also the same episode) stays
 *    CONTINUE so the "picks up where you left off" slot wins.
 *  - Tap target is a `spatialfin://play?id=...&kind=...&startMs=...` deep link
 *    (see `PlayDeepLink`), resolved by `TvPlayerActivity`'s `ACTION_VIEW`
 *    intent-filter in `app/unified/src/tv/AndroidManifest.xml`.
 *
 * No-ops on devices without `FEATURE_LEANBACK`; `WatchNextScheduler` gates that
 * upstream, and `ContentResolver.insert` on a device that doesn't host the
 * provider will simply return null.
 */
object WatchNextSync {

    data class Stats(val inserted: Int, val updated: Int, val deleted: Int) {
        fun isNoop(): Boolean = inserted == 0 && updated == 0 && deleted == 0
    }

    fun sync(
        context: Context,
        resumeItems: List<SpatialFinItem>,
        nextUpItems: List<SpatialFinEpisode>,
    ): Stats {
        val desired = buildDesiredPrograms(resumeItems, nextUpItems)
        val existing = queryExistingRows(context)

        var inserted = 0
        var updated = 0
        var deleted = 0

        val resolver = context.contentResolver

        for ((id, rowId) in existing) {
            if (id !in desired) {
                resolver.delete(TvContractCompat.buildWatchNextProgramUri(rowId), null, null)
                deleted++
            }
        }

        for ((id, program) in desired) {
            val values = program.toContentValues()
            val existingRowId = existing[id]
            if (existingRowId != null) {
                resolver.update(
                    TvContractCompat.buildWatchNextProgramUri(existingRowId),
                    values,
                    null,
                    null,
                )
                updated++
            } else {
                val uri =
                    resolver.insert(TvContractCompat.WatchNextPrograms.CONTENT_URI, values)
                if (uri != null) inserted++
            }
        }

        val stats = Stats(inserted, updated, deleted)
        if (!stats.isNoop()) {
            Timber.i(
                "WatchNextSync: inserted=%d updated=%d deleted=%d (resume=%d nextUp=%d)",
                inserted,
                updated,
                deleted,
                resumeItems.size,
                nextUpItems.size,
            )
        }
        return stats
    }

    private fun buildDesiredPrograms(
        resumeItems: List<SpatialFinItem>,
        nextUpItems: List<SpatialFinEpisode>,
    ): Map<UUID, WatchNextProgram> {
        val programs = linkedMapOf<UUID, WatchNextProgram>()
        val now = System.currentTimeMillis()

        for (item in resumeItems) {
            if (item.played) continue
            val program = buildContinueProgram(item, now) ?: continue
            programs[item.id] = program
        }
        for (episode in nextUpItems) {
            if (episode.played) continue
            // If an item is somehow in both lists, CONTINUE wins — keep it.
            if (programs.containsKey(episode.id)) continue
            val program = buildNextUpProgram(episode, now) ?: continue
            programs[episode.id] = program
        }
        return programs
    }

    private fun buildContinueProgram(
        item: SpatialFinItem,
        now: Long,
    ): WatchNextProgram? {
        val durationMs = ticksToMillis(item.runtimeTicks)
        val positionMs = ticksToMillis(item.playbackPositionTicks).coerceAtLeast(0L)
        if (durationMs <= 0L) return null

        val kind = kindFor(item) ?: return null
        val builder =
            WatchNextProgram.Builder()
                .setInternalProviderId(item.id.toString())
                .setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
                .setLastEngagementTimeUtcMillis(now)
                .setDurationMillis(durationMs.toInt())
                .setLastPlaybackPositionMillis(positionMs.toInt())
                .setIntentUri(PlayDeepLink.build(item.id, kind, startPositionMs = positionMs))

        item.images.primary?.let { builder.setPosterArtUri(it) }
        item.images.backdrop?.let { builder.setThumbnailUri(it) }
        if (item.overview.isNotBlank()) builder.setDescription(item.overview)

        when (item) {
            is SpatialFinMovie -> {
                builder.setType(TvContractCompat.PreviewPrograms.TYPE_MOVIE)
                builder.setTitle(item.name.ifBlank { return null })
                item.productionYear?.let { builder.setReleaseDate(it.toString()) }
            }
            is SpatialFinEpisode -> configureEpisode(builder, item)
            else -> return null
        }
        return builder.build()
    }

    private fun buildNextUpProgram(
        episode: SpatialFinEpisode,
        now: Long,
    ): WatchNextProgram? {
        val durationMs = ticksToMillis(episode.runtimeTicks)
        if (durationMs <= 0L) return null

        val builder =
            WatchNextProgram.Builder()
                .setInternalProviderId(episode.id.toString())
                .setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_NEXT)
                .setLastEngagementTimeUtcMillis(now)
                .setDurationMillis(durationMs.toInt())
                .setIntentUri(PlayDeepLink.build(episode.id, PlayDeepLink.KIND_EPISODE))

        episode.images.primary?.let { builder.setPosterArtUri(it) }
        episode.images.backdrop?.let { builder.setThumbnailUri(it) }
        if (episode.overview.isNotBlank()) builder.setDescription(episode.overview)

        configureEpisode(builder, episode)
        return builder.build()
    }

    private fun kindFor(item: SpatialFinItem): String? =
        when (item) {
            is SpatialFinMovie -> PlayDeepLink.KIND_MOVIE
            is SpatialFinEpisode -> PlayDeepLink.KIND_EPISODE
            else -> null
        }

    private fun configureEpisode(builder: WatchNextProgram.Builder, episode: SpatialFinEpisode) {
        builder.setType(TvContractCompat.PreviewPrograms.TYPE_TV_EPISODE)
        // For TV_EPISODE, `title` is the series name and `episodeTitle` is the
        // episode-specific title. Google TV renders them as two stacked lines.
        val seriesTitle = episode.seriesName.ifBlank { episode.name }
        builder.setTitle(seriesTitle)
        if (episode.name.isNotBlank() && episode.name != seriesTitle) {
            builder.setEpisodeTitle(episode.name)
        }
        if (episode.indexNumber > 0) builder.setEpisodeNumber(episode.indexNumber)
        if (episode.parentIndexNumber > 0) builder.setSeasonNumber(episode.parentIndexNumber)
    }

    private fun queryExistingRows(context: Context): Map<UUID, Long> {
        val projection =
            arrayOf(
                BaseColumns._ID,
                TvContractCompat.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID,
            )
        val result = linkedMapOf<UUID, Long>()
        runCatching {
            context.contentResolver
                .query(TvContractCompat.WatchNextPrograms.CONTENT_URI, projection, null, null, null)
                ?.use { cursor ->
                    val idIndex = cursor.getColumnIndexOrThrow(BaseColumns._ID)
                    val providerIdIndex =
                        cursor.getColumnIndexOrThrow(
                            TvContractCompat.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID
                        )
                    while (cursor.moveToNext()) {
                        val providerId = cursor.getString(providerIdIndex) ?: continue
                        val uuid = runCatching { UUID.fromString(providerId) }.getOrNull() ?: continue
                        result[uuid] = cursor.getLong(idIndex)
                    }
                }
        }.onFailure { Timber.w(it, "WatchNextSync: failed to read existing rows") }
        return result
    }

    private fun ticksToMillis(ticks: Long): Long = max(0L, ticks / 10_000L)
}
