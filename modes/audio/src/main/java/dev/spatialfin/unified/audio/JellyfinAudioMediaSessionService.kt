package dev.spatialfin.unified.audio

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Hosts the system [MediaSession] for native Jellyfin audio playback so it shows
 * up in the notification shade / lock screen and responds to Bluetooth + wired
 * headset transport, mirroring [dev.spatialfin.unified.music.MaMediaSessionService]
 * for Music Assistant. The session wraps the [Media3JellyfinAudioOutputEngine]'s
 * ExoPlayer directly — the engine stays the owner of the player; this service only
 * owns the *control surface* while a queue is loaded.
 *
 * The session ID must be explicit: MaMediaSessionService uses the default
 * (empty) ID, and Media3 requires process-unique session IDs.
 */
@UnstableApi
@AndroidEntryPoint
class JellyfinAudioMediaSessionService : MediaSessionService() {

    @Inject
    lateinit var engine: Media3JellyfinAudioOutputEngine

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        mediaSession =
            MediaSession.Builder(this, engine.player)
                .setId(SESSION_ID)
                .setSessionActivity(launchAppIntent())
                .build()

        // Tear down once the queue is cleared so we don't linger as a foreground
        // service after the user stops the music.
        scope.launch {
            engine.state
                .map { it.queue.isEmpty() }
                .collect { idle -> if (idle) stopSelf() }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        scope.cancel()
        // Release only the session: the singleton engine owns the ExoPlayer and
        // survives service restarts.
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }

    private fun launchAppIntent(): PendingIntent {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(Intent.ACTION_MAIN)
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        const val SESSION_ID = "spatialfin_jellyfin_audio"
    }
}

/**
 * Connects a [MediaController] to [JellyfinAudioMediaSessionService] while a
 * Jellyfin audio queue is loaded. As with
 * [dev.spatialfin.unified.music.MaMediaSessionBridge], the controller connection
 * is what starts the service and makes Media3 drive the foreground media
 * notification — just running the service isn't enough. The controller is
 * released (and the service self-stops) when the queue is cleared.
 *
 * Install once at the activity root (covers every form factor).
 */
@Composable
fun JellyfinAudioMediaSessionBridge(dispatcher: AudioPlaybackDispatcher) {
    val context = LocalContext.current
    val state by dispatcher.state.collectAsStateWithLifecycle()
    val hasQueue = state.queue.isNotEmpty()

    DisposableEffect(hasQueue) {
        var controller: MediaController? = null
        var future: ListenableFuture<MediaController>? = null
        if (hasQueue) {
            val token =
                SessionToken(
                    context,
                    ComponentName(context, JellyfinAudioMediaSessionService::class.java),
                )
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
