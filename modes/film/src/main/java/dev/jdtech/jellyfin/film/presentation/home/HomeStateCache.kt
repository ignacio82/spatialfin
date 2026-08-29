package dev.jdtech.jellyfin.film.presentation.home

import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The last loaded home screen, kept so returning to Home paints instantly
 * instead of showing a spinner while the network round-trips.
 *
 * This used to be a `@Volatile var` in `HomeViewModel`'s companion object —
 * process-wide mutable state with no owner, outliving every ViewModel, cleared
 * by hand at the call sites that happened to remember. That is how a row
 * disabled in the Sources tab kept coming back: a `HomeState` captured while the
 * row was still enabled survived in the companion field and was re-emitted by
 * the next cache hit. (`HomeViewModel.state` now also filters at read time, so
 * that specific bug cannot recur — but the shape that produced it is worth not
 * keeping.)
 *
 * As a `@Singleton` its lifetime is explicit, Hilt owns it, and the keying and
 * expiry are testable without standing up a ViewModel. [now] is injectable for
 * exactly that reason.
 */
@Singleton
class HomeStateCache internal constructor(private val now: () -> Long) {
    // Hilt builds it through the no-arg constructor; tests use the internal one
    // above to drive the clock. A default argument on the @Inject constructor
    // would leave Hilt looking for a Function0<Long> binding.
    @Inject constructor() : this(System::currentTimeMillis)

    private data class Entry(
        val serverId: String?,
        val userId: UUID?,
        val state: HomeState,
        val timestampMs: Long,
    )

    @Volatile private var entry: Entry? = null

    /**
     * The cached state for this server + user, or null when nothing is cached or
     * the cache belongs to a different session.
     *
     * Deliberately returns a stale entry too: the caller paints it immediately
     * and then refreshes, which is what makes the tab switch feel instant. Ask
     * [isFresh] to decide whether the refresh can be skipped.
     */
    fun get(serverId: String?, userId: UUID?): HomeState? =
        entry?.takeIf { it.serverId == serverId && it.userId == userId }?.state

    /** True when the entry for this session is young enough to skip a reload. */
    fun isFresh(serverId: String?, userId: UUID?): Boolean {
        val current = entry?.takeIf { it.serverId == serverId && it.userId == userId } ?: return false
        return now() - current.timestampMs < FRESHNESS_MS
    }

    fun put(serverId: String?, userId: UUID?, state: HomeState) {
        entry = Entry(serverId = serverId, userId = userId, state = state, timestampMs = now())
    }

    /** Drops the cache — a row was toggled, plugin settings changed, and so on. */
    fun invalidate() {
        entry = null
    }

    private companion object {
        const val FRESHNESS_MS = 2 * 60 * 1000L
    }
}
