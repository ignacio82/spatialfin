package dev.spatialfin.unified.audio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dev.jdtech.jellyfin.data.musicassistant.api.ServiceClient
import dev.jdtech.jellyfin.data.musicassistant.repository.MaSessionRepository
import dev.spatialfin.unified.MaPlayDispatcher
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map

/**
 * Keeps Music Assistant and native Jellyfin audio mutually exclusive so there is
 * exactly ONE active playback (and therefore one mini player / one full-screen
 * player) at a time — the same "one now-playing surface" rule
 * [dev.spatialfin.unified.music.MaLocalPlaybackBridge] establishes between the
 * SendSpin receiver and MA.
 *
 *  - Starting a native Jellyfin queue stops MA playback and dismisses its
 *    now-playing surfaces.
 *  - Dispatching an MA play (detected via the optimistic [MaSessionRepository]
 *    pending hint, so it fires immediately on tap rather than after the MA echo)
 *    stops the native Jellyfin queue.
 *
 * Both watchers act only on *rising edges* (`drop(1)` + `distinctUntilChanged`),
 * so a session that was already active when the app launched is left alone until
 * the user starts something on the other side.
 *
 * Install once at the activity root (covers every form factor).
 */
@Composable
fun AudioPlaybackArbitrationBridge(
    maSession: MaSessionRepository,
    maServiceClient: ServiceClient,
    jellyfinDispatcher: AudioPlaybackDispatcher,
) {
    val context = LocalContext.current
    val maPlayDispatcher = remember(context, maSession, maServiceClient) {
        MaPlayDispatcher(context.applicationContext, maSession, maServiceClient)
    }

    // Jellyfin queue starts -> stop MA.
    LaunchedEffect(jellyfinDispatcher, maSession) {
        jellyfinDispatcher.state
            .map { it.queue.isNotEmpty() }
            .distinctUntilChanged()
            .drop(1)
            .collect { jellyfinActive ->
                if (!jellyfinActive) return@collect
                val ma = maSession.session.value
                if (ma.nowPlaying != null || ma.pendingPlayUri != null) {
                    maPlayDispatcher.stop()
                }
            }
    }

    // MA play dispatched (or a track lands) -> stop Jellyfin.
    LaunchedEffect(jellyfinDispatcher, maSession) {
        maSession.session
            .map { it.pendingPlayUri != null || it.nowPlaying != null }
            .distinctUntilChanged()
            .drop(1)
            .collect { maActive ->
                if (maActive && jellyfinDispatcher.state.value.queue.isNotEmpty()) {
                    jellyfinDispatcher.stop()
                }
            }
    }
}
