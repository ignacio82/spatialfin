package dev.spatialfin.companion.wear.fcast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.app.PendingIntent
import androidx.core.app.NotificationCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import dagger.hilt.android.AndroidEntryPoint
import dev.jdtech.jellyfin.fcast.discovery.FCastReceiverAdvertiser
import dev.jdtech.jellyfin.fcast.receiver.FCastIngressRouter
import dev.jdtech.jellyfin.fcast.protocol.PlayMessage
import dev.jdtech.jellyfin.fcast.receiver.FCastReceiverServer
import dev.spatialfin.companion.wear.R
import dev.spatialfin.companion.wear.presentation.WearMainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

@AndroidEntryPoint
class WearAudioReceiverService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var server: FCastReceiverServer? = null
    private var advertiser: FCastReceiverAdvertiser? = null
    private var player: ExoPlayer? = null
    private var wifiLock: WifiManager.WifiLock? = null
    @Volatile
    private var connectedSenderName: String? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        _isSinkActive.value = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildOngoingNotification(idleStatusText()))
        scope.launch { startSink() }
        return START_NOT_STICKY
    }

    private suspend fun startSink() {
        Timber.i("WearAudioReceiverService: starting Split-A/V watch audio sink")

        // 1. Acquire LOW_LATENCY Wi-Fi lock
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifiManager
            .createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "SpatialFin:WearReceiverLock")
            .apply { acquire() }

        // 2. Setup low-latency ExoPlayer for Bluetooth earbuds
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .build()

        // 3. Router
        val router = object : FCastIngressRouter {
            // An mDNS-advertised sink accepts a URL from anything on the subnet, so who
            // is pushing has to be visible in the ongoing notification rather than silent.
            override fun onSenderIdentified(displayName: String?) {
                connectedSenderName = displayName?.takeIf { it.isNotBlank() }
                scope.launch { updateNotification(idleStatusText()) }
            }

            override fun onPlay(request: PlayMessage): FCastIngressRouter.IngressResult {
                val url = request.url ?: return FCastIngressRouter.IngressResult.Rejected("Missing stream URL")
                val streamTitle = request.metadata?.title ?: "Private Stream"
                scope.launch {
                    player?.apply {
                        setMediaItem(MediaItem.fromUri(url))
                        request.time?.let { seekTo((it * 1000).toLong()) }
                        prepare()
                        play()
                    }
                    updateNotification("$streamTitle — from ${connectedSenderName ?: "an unknown sender"}")
                }
                return FCastIngressRouter.IngressResult.Accepted
            }

            override fun onPause() {
                scope.launch { player?.pause() }
            }

            override fun onResume() {
                scope.launch { player?.play() }
            }

            override fun onStop() {
                scope.launch { player?.stop() }
            }

            override fun onSeek(seconds: Double) {
                scope.launch { player?.seekTo((seconds * 1000).toLong()) }
            }

            override fun onSetVolume(volume: Double) {
                scope.launch { player?.volume = volume.toFloat() }
            }

            override fun onSetSpeed(speed: Double) {
                scope.launch { player?.setPlaybackSpeed(speed.toFloat()) }
            }

            override fun onSetTrack(type: Int, trackId: String) = Unit
        }

        // 4. Start receiver socket server and advertise actual bound port
        val newServer = FCastReceiverServer(
            config = FCastReceiverServer.Config(displayName = "SpatialFin Watch Audio"),
            routerFactory = { router },
            parentScope = scope,
        )
        newServer.start()
        server = newServer

        advertiser = FCastReceiverAdvertiser(applicationContext).apply {
            register(
                instanceName = "SpatialFin Watch Audio",
                port = newServer.boundPort,
                properties = mapOf("appName" to "SpatialFin", "type" to "audio_sink"),
            )
        }
        Timber.i("WearAudioReceiverService: advertised on port %d", newServer.boundPort)
    }

    private fun idleStatusText(): String =
        connectedSenderName?.let { "Connected to $it" } ?: "Listening for private audio…"

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SpatialFin Split Audio Sink",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shows private audio playback status on watch"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * Carries the Ongoing Activity chip as well as the sink's own status.
     *
     * An Ongoing Activity has to hang off a foreground-service notification, and this is
     * the only foreground service the watch is entitled to run: a pure remote plays
     * nothing locally, so it cannot justify a `mediaPlayback` service to the platform,
     * and the battery cost would buy only a shortcut. Attaching the chip here keeps the
     * "exactly one foreground service, and only while it has work" rule intact.
     */
    private fun buildOngoingNotification(statusText: String): Notification {
        val launchIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, WearMainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SpatialFin Audio Sink")
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_launcher_wear)
            .setContentIntent(launchIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)

        OngoingActivity.Builder(this, NOTIFICATION_ID, builder)
            .setAnimatedIcon(R.drawable.ic_launcher_wear)
            .setStaticIcon(R.drawable.ic_launcher_wear)
            .setTouchIntent(launchIntent)
            .setStatus(Status.Builder().addTemplate(statusText).build())
            .build()
            .apply(this)

        return builder.build()
    }

    private fun updateNotification(statusText: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildOngoingNotification(statusText))
    }

    override fun onDestroy() {
        _isSinkActive.value = false
        connectedSenderName = null
        scope.launch { advertiser?.unregister() }
        server?.stop()
        player?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        scope.cancel()
        super.onDestroy()
        Timber.i("WearAudioReceiverService: destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "wear_split_audio_channel"
        private const val NOTIFICATION_ID = 2049

        private val _isSinkActive = MutableStateFlow(false)
        val isSinkActive: StateFlow<Boolean> = _isSinkActive.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, WearAudioReceiverService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, WearAudioReceiverService::class.java)
            context.stopService(intent)
        }
    }
}
