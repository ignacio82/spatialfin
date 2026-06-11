package dev.spatialfin.unified.music

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jdtech.jellyfin.data.musicassistant.repository.MaSessionRepository

/**
 * Starts [MaMediaSessionService] the moment Music Assistant has a now-playing
 * track, so the system media notification / lock-screen controls appear. The
 * service stops itself when MA playback ends. We start it only on the
 * no-track → track transition (while the app is foreground, which is always
 * true when the user initiates playback) to stay within Android's
 * background-start rules.
 *
 * Install once at the activity root (covers every form factor), alongside
 * [MaConnectionBridge].
 */
@Composable
fun MaMediaSessionBridge(session: MaSessionRepository) {
    val context = LocalContext.current
    val state by session.session.collectAsStateWithLifecycle()
    val hasTrack = state.nowPlaying != null
    LaunchedEffect(hasTrack) {
        if (hasTrack) {
            runCatching {
                context.startService(Intent(context, MaMediaSessionService::class.java))
            }
        }
    }
}
