package dev.jdtech.jellyfin.session

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Process-wide signal that the active Jellyfin session changed —
 * server, user, address, or freshly-saved auth credentials.
 *
 * Why: [dev.jdtech.jellyfin.api.JellyfinApi] is a single mutable singleton.
 * When [dev.jdtech.jellyfin.setup.data.SetupRepositoryImpl.setCurrentUser]
 * (or sibling mutators) updates its `userId` / `accessToken` / `baseUrl`
 * in place, ViewModels that already cached state against the *previous*
 * credentials never learn to re-fetch. In-Activity navigation (Users → Home)
 * doesn't fire `Activity.onResume`, so the existing refresh path through
 * [dev.jdtech.jellyfin.viewmodels.MainViewModel.refresh] never runs.
 *
 * Subscribers (MainViewModel, HomeViewModel, MediaViewModel, …) collect
 * [events] in their `init` and re-load their own data when it fires.
 *
 * Implementation: replay = 0 so a late subscriber doesn't spuriously reload
 * on a stale historical emit; extraBufferCapacity = 1 with DROP_OLDEST so
 * [notifyChanged] never suspends and rapid back-to-back changes coalesce.
 */
@Singleton
class ActiveSessionBus @Inject constructor() {
    private val _events =
        MutableSharedFlow<Unit>(
            replay = 0,
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    val events: SharedFlow<Unit> = _events.asSharedFlow()

    fun notifyChanged() {
        _events.tryEmit(Unit)
    }
}
