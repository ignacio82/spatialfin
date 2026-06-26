package dev.spatialfin.unified.music

import android.app.PendingIntent
import android.content.Intent
import android.os.Looper
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dagger.hilt.android.AndroidEntryPoint
import dev.jdtech.jellyfin.data.musicassistant.api.ServiceClient
import dev.jdtech.jellyfin.data.musicassistant.repository.MaSessionRepository
import dev.spatialfin.unified.MaPlayDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Hosts the system [MediaSession] for Music Assistant playback so MA shows up
 * in the notification shade / lock screen and responds to Bluetooth + wired
 * headset transport (and, later, Android Auto). The session wraps
 * [MaMediaPlayer], which mirrors [MaSessionRepository] and forwards transport to
 * [MaPlayDispatcher].
 *
 * Started by [MaMediaSessionBridge] once MA has a now-playing track; the service
 * stops itself when MA playback ends so it isn't a permanent foreground
 * service. The SendSpin receiver service is unrelated — it owns the local audio
 * output; this owns the *control surface*.
 */
@UnstableApi
@AndroidEntryPoint
class MaMediaSessionService : MediaSessionService() {

    @Inject
    lateinit var session: MaSessionRepository

    @Inject
    lateinit var serviceClient: ServiceClient

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val dispatcher = MaPlayDispatcher(applicationContext, session, serviceClient)
        val player = MaMediaPlayer(Looper.getMainLooper(), session, dispatcher)
        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(launchAppIntent())
            .build()

        // Tear down once MA playback is gone, so we don't linger as a foreground
        // service after the user stops the music.
        scope.launch {
            session.session
                .map { it.nowPlaying == null }
                .collect { idle -> if (idle) stopSelf() }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        scope.cancel()
        mediaSession?.run {
            player.release()
            release()
        }
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
}
