package dev.spatialfin.unified.music

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dagger.hilt.android.AndroidEntryPoint
import dev.jdtech.jellyfin.data.musicassistant.api.ServiceClient
import dev.jdtech.jellyfin.data.musicassistant.repository.MaSessionRepository
import dev.spatialfin.unified.MaPlayDispatcher
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
class MaMediaSessionService : MediaSessionService() {

    @Inject
    lateinit var session: MaSessionRepository

    @Inject
    lateinit var serviceClient: ServiceClient

    @Inject
    lateinit var appLockObserver: AppLockObserver

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        ensureLockedTeardownChannel()

        scope.launch {
            appLockObserver.lockState.collect { lock ->
                if (lock == AppLockManager.LockState.LOCKED) {
                    mediaSession?.run {
                        player.release()
                        release()
                    }
                    mediaSession = null
                    stopSelf()
                }
            }
        }

        if (appLockObserver.lockState.value == AppLockManager.LockState.LOCKED) {
            return
        }

        val dispatcher = MaPlayDispatcher(applicationContext, session, serviceClient)
        val player = MaMediaPlayer(Looper.getMainLooper(), session, dispatcher)
        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(launchAppIntent())
            .build()

        scope.launch {
            session.session
                .map { it.nowPlaying == null }
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
                mediaSession?.run {
                    player.release()
                    release()
                }
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
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    private fun ensureLockedTeardownChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (nm.getNotificationChannel(LOCKED_CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                LOCKED_CHANNEL_ID,
                "Music Assistant Playback",
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
        private const val LOCKED_CHANNEL_ID = "spatialfin_ma_locked_teardown"
        private const val LOCKED_NOTIFICATION_ID = 41202
    }
}
