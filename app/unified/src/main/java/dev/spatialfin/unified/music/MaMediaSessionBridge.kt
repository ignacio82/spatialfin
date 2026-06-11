package dev.spatialfin.unified.music

import android.content.ComponentName
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import dev.jdtech.jellyfin.data.musicassistant.repository.MaSessionRepository

/**
 * Connects a [MediaController] to [MaMediaSessionService] while Music Assistant
 * has a now-playing track. The controller connection is what makes the
 * MediaSessionService start its foreground media notification (shade /
 * lock-screen) and bind the framework session to the system media controls —
 * just running the service isn't enough; Media3 only drives the notification
 * once a controller is connected. Connecting also starts the service, so no
 * separate startService is needed. The controller is released (and the service
 * self-stops) when MA playback ends.
 *
 * Install once at the activity root (covers every form factor), alongside
 * [MaConnectionBridge].
 */
@Composable
fun MaMediaSessionBridge(session: MaSessionRepository) {
    val context = LocalContext.current
    val state by session.session.collectAsStateWithLifecycle()
    val hasTrack = state.nowPlaying != null

    DisposableEffect(hasTrack) {
        var controller: MediaController? = null
        var future: ListenableFuture<MediaController>? = null
        if (hasTrack) {
            val token = SessionToken(context, ComponentName(context, MaMediaSessionService::class.java))
            val f = MediaController.Builder(context, token).buildAsync()
            future = f
            f.addListener({ runCatching { controller = f.get() } }, MoreExecutors.directExecutor())
        }
        onDispose {
            future?.let { runCatching { MediaController.releaseFuture(it) } }
            controller?.let { runCatching { it.release() } }
        }
    }
}
