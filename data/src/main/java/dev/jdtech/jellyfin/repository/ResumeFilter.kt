package dev.jdtech.jellyfin.repository

import dev.jdtech.jellyfin.models.SpatialFinItem

/**
 * Filters that decide what shows up in the "Continue Watching" row.
 *
 * Centralized so the rule is applied consistently between
 * [JellyfinRepositoryImpl.getResumeItems] (live server) and
 * [JellyfinRepositoryOfflineImpl.getResumeItems] (downloaded items),
 * and so the rule can be unit-tested without spinning up a database
 * or a Jellyfin client.
 */
object ResumeFilter {
    /**
     * Drop items the user has already finished. Jellyfin's `/Items/Resume`
     * endpoint occasionally surfaces items that were marked watched after
     * pausing; the offline path can hit the same case if a sync flips
     * `played` on a partially-watched item.
     */
    fun keepResumable(items: List<SpatialFinItem>): List<SpatialFinItem> =
        items.filterNot { it.played }
}
