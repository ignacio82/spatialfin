package dev.spatialfin.unified.audio

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import dagger.hilt.android.AndroidEntryPoint
import dev.spatialfin.unified.applock.AppLockManager
import dev.spatialfin.unified.applock.AppLockObserver
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@UnstableApi
@AndroidEntryPoint
class JellyfinAudioMediaSessionService : MediaSessionService() {

    @Inject
    lateinit var engine: Media3JellyfinAudioOutputEngine

    @Inject
    lateinit var appLockObserver: AppLockObserver

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        ensureLockedTeardownChannel()

        // Always register the lockState collector so any transition to LOCKED releases
        // the MediaSession (preserving @Singleton engine.player) and stops the service.
        scope.launch {
            appLockObserver.lockState.collect { lock ->
                if (lock == AppLockManager.LockState.LOCKED) {
                    mediaSession?.release()
                    mediaSession = null
                    stopSelf()
                }
            }
        }

        if (appLockObserver.lockState.value == AppLockManager.LockState.LOCKED) {
            return
        }

        mediaSession =
            MediaSession.Builder(this, engine.player)
                .setId(SESSION_ID)
                .setSessionActivity(launchAppIntent())
                .build()

        scope.launch {
            engine.state
                .map { it.queue.isEmpty() }
                .collect { idle -> if (idle) stopSelf() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (appLockObserver.lockState.value == AppLockManager.LockState.LOCKED) {
            try {
                runCatching {
                    val notification = NotificationCompat.Builder(this, LOCKED_CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.ic_media_pause)
                        .setSilent(true)
                        .setOngoing(false)
                        .build()
                    ServiceCompat.startForeground(
                        this,
                        LOCKED_NOTIFICATION_ID,
                        notification,
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                        } else {
                            0
                        },
                    )
                    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                }
            } finally {
                mediaSession?.release()
                mediaSession = null
                stopSelf()
            }
            return START_NOT_STICKY
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        if (appLockObserver.lockState.value == AppLockManager.LockState.LOCKED) return null
        return mediaSession
    }

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

    private fun ensureLockedTeardownChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (nm.getNotificationChannel(LOCKED_CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                LOCKED_CHANNEL_ID,
                "Audio Playback",
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                setSound(null, null)
                enableVibration(false)
            }
            nm.createNotificationChannel(channel)
        }
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
        private const val LOCKED_CHANNEL_ID = "spatialfin_audio_locked_teardown"
        private const val LOCKED_NOTIFICATION_ID = 41201
    }
}

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
