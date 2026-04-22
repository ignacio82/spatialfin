package dev.jdtech.jellyfin.repository

/**
 * A read result tagged with where it came from.
 *
 * `SmartJellyfinRepository` exposes `fetch*` sibling methods that return this
 * wrapper so UI layers can show "showing cached data" banners or refuse to
 * commit destructive edits based on stale offline data. The plain
 * `JellyfinRepository` methods still return the unwrapped value — callers opt
 * in by calling the `fetch*` variants.
 */
data class Fetched<out T>(
    val value: T,
    val isOffline: Boolean,
)
