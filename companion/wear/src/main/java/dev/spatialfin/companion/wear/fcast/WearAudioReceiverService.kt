package dev.spatialfin.companion.wear.fcast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import dagger.hilt.android.AndroidEntryPoint
import dev.jdtech.jellyfin.fcast.discovery.FCastReceiverAdvertiser
import dev.jdtech.jellyfin.fcast.protocol.PlayMessage
import dev.jdtech.jellyfin.fcast.protocol.PlaybackUpdateMessage
import dev.jdtech.jellyfin.fcast.protocol.SplitAvRole
import dev.jdtech.jellyfin.fcast.protocol.VolumeUpdateMessage
import dev.jdtech.jellyfin.fcast.protocol.splitAv
import dev.jdtech.jellyfin.fcast.receiver.FCastIngressRouter
import dev.jdtech.jellyfin.fcast.receiver.FCastReceiverServer
import dev.spatialfin.companion.wear.R
import dev.spatialfin.companion.wear.presentation.WearMainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@AndroidEntryPoint
class WearAudioReceiverService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var server: FCastReceiverServer? = null
    private var advertiser: FCastReceiverAdvertiser? = null
    private var player: ExoPlayer? = null
    private var wifiLock: WifiManager.WifiLock? = null
    @Volatile private var connectedSenderName: String? = null
    private var startupJob: Job? = null
    private var scheduledResume: Job? = null
    private var feedbackIntervalMs = 1_000L
    private var streamTitle = "Private audio"

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildOngoingNotification(idleStatusText()))
        if (startupJob?.isActive != true && server == null) {
            _isSinkActive.value = true
            _sinkStatus.value = "Starting…"
            startupJob = scope.launch {
                try {
                    startSink()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    _sinkStatus.value = "Could not start audio receiver"
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun startSink() {
        Timber.i("WearAudioReceiverService: starting Split-A/V watch audio sink")

        // 1. Acquire LOW_LATENCY Wi-Fi lock
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock =
            wifiManager
                .createWifiLock(
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY,
                    "SpatialFin:WearReceiverLock",
                )
                .apply { acquire() }

        player =
            ExoPlayer.Builder(this)
                .setAudioAttributes(AudioAttributes.DEFAULT, true)
                .build()
                .apply {
                    trackSelectionParameters =
                        trackSelectionParameters
                            .buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                            .build()
                    addListener(
                        object : Player.Listener {
                            override fun onEvents(player: Player, events: Player.Events) {
                                updatePlaybackStatus()
                            }

                            override fun onPlayerError(error: PlaybackException) {
                                val message = "Audio playback failed (${error.errorCodeName})"
                                updateNotification(message)
                                scope.launch { server?.broadcastError(message) }
                            }
                        }
                    )
                }
        val router =
            object : FCastIngressRouter {
                override fun onSenderIdentified(displayName: String?) {
                    connectedSenderName = displayName?.takeIf { it.isNotBlank() }
                    scope.launch { updatePlaybackStatus() }
                }

                override fun onPlay(request: PlayMessage): FCastIngressRouter.IngressResult {
                    val url =
                        request.url
                            ?: return FCastIngressRouter.IngressResult.Rejected(
                                "Missing stream URL"
                            )
                    if (Uri.parse(url).scheme?.lowercase() !in setOf("http", "https")) {
                        return FCastIngressRouter.IngressResult.Rejected(
                            "Audio requires an HTTP or HTTPS stream"
                        )
                    }
                    if (request.splitAv()?.role == SplitAvRole.VIDEO) {
                        return FCastIngressRouter.IngressResult.Rejected(
                            "This watch is an audio receiver"
                        )
                    }
                    scope.launch {
                        scheduledResume?.cancel()
                        val split = request.splitAv()
                        feedbackIntervalMs =
                            if (split?.role == SplitAvRole.AUDIO)
                                1_000L / (split.syncCadenceHz ?: 10).coerceIn(1, 10)
                            else 1_000L
                        streamTitle = request.metadata?.title?.take(160) ?: "Private audio"
                        val dataSource =
                            DefaultDataSource.Factory(
                                this@WearAudioReceiverService,
                                DefaultHttpDataSource.Factory()
                                    .setDefaultRequestProperties(request.headers.orEmpty()),
                            )
                        val mediaItem =
                            MediaItem.Builder().setUri(url).setMimeType(request.container).build()
                        player?.apply {
                            setMediaSource(
                                DefaultMediaSourceFactory(dataSource).createMediaSource(mediaItem)
                            )
                            volume = (request.volume ?: 1.0).toFloat().coerceIn(0f, 1f)
                            setPlaybackSpeed((request.speed ?: 1.0).toFloat().coerceIn(0.25f, 4f))
                            request.time?.let { seekTo((it * 1_000).toLong().coerceAtLeast(0)) }
                            // The video master resumes both halves after receiving our ready
                            // beacon.
                            playWhenReady = split?.role != SplitAvRole.AUDIO
                            prepare()
                        }
                        updatePlaybackStatus()
                    }
                    return FCastIngressRouter.IngressResult.Accepted
                }

                override fun onPause() {
                    scope.launch {
                        scheduledResume?.cancel()
                        player?.pause()
                    }
                }

                override fun onResume() {
                    scope.launch {
                        scheduledResume?.cancel()
                        player?.play()
                    }
                }

                override fun onResumeAt(atReceiverMonotonicMs: Long) {
                    scope.launch {
                        scheduledResume?.cancel()
                        scheduledResume = scope.launch {
                            delay(
                                resumeDelayMs(SystemClock.elapsedRealtime(), atReceiverMonotonicMs)
                            )
                            player?.play()
                        }
                    }
                }

                override fun onStop() {
                    scope.launch {
                        scheduledResume?.cancel()
                        player?.stop()
                    }
                }

                override fun onSeek(seconds: Double) {
                    scope.launch {
                        scheduledResume?.cancel()
                        player?.seekTo((seconds * 1_000).toLong().coerceAtLeast(0))
                    }
                }

                override fun onSetVolume(volume: Double) {
                    scope.launch { player?.volume = volume.toFloat().coerceIn(0f, 1f) }
                }

                override fun onSetSpeed(speed: Double) {
                    scope.launch { player?.setPlaybackSpeed(speed.toFloat().coerceIn(0.25f, 4f)) }
                }

                override fun onSetTrack(type: Int, trackId: String) {
                    scope.launch {
                        server?.broadcastError(
                            "Track selection is controlled by the sending device"
                        )
                    }
                }
            }
        val newServer =
            FCastReceiverServer(
                config = FCastReceiverServer.Config(displayName = "SpatialFin Watch Audio"),
                routerFactory = { router },
                parentScope = scope,
            )
        server = newServer
        newServer.start()
        val newAdvertiser = FCastReceiverAdvertiser(applicationContext)
        advertiser = newAdvertiser
        newAdvertiser.register(
            instanceName = "SpatialFin Watch Audio",
            port = newServer.boundPort,
            properties = mapOf("appName" to "SpatialFin", "type" to "audio_sink"),
        )
        updatePlaybackStatus()
        scope.launch {
            while (isActive) {
                val currentPlayer = player ?: break
                if (newServer.sessionCount > 0) {
                    // Sample on the player's application thread, then write off the main thread.
                    val update =
                        PlaybackUpdateMessage(
                            generationTime = System.currentTimeMillis(),
                            state =
                                when {
                                    currentPlayer.playbackState == Player.STATE_IDLE ||
                                        currentPlayer.playbackState == Player.STATE_ENDED -> 0
                                    currentPlayer.playWhenReady -> 1
                                    else -> 2
                                },
                            time = currentPlayer.currentPosition / 1_000.0,
                            duration = currentPlayer.duration.takeIf { it >= 0 }?.div(1_000.0),
                            speed = currentPlayer.playbackParameters.speed.toDouble(),
                            monotonicSampleMs = SystemClock.elapsedRealtime(),
                            videoReadyToStart = currentPlayer.playbackState == Player.STATE_READY,
                            bufferedPositionMs = currentPlayer.bufferedPosition,
                        )
                    val volumeUpdate =
                        VolumeUpdateMessage(
                            System.currentTimeMillis(),
                            currentPlayer.volume.toDouble(),
                        )
                    withContext(Dispatchers.IO) {
                        newServer.broadcastPlaybackUpdate(update)
                        newServer.broadcastVolumeUpdate(volumeUpdate)
                    }
                }
                delay(feedbackIntervalMs)
            }
        }
        Timber.i("WearAudioReceiverService: advertised on port %d", newServer.boundPort)
    }

    private fun updatePlaybackStatus() {
        val current = player
        val status =
            when {
                current?.playerError != null ->
                    "Audio playback failed (${current.playerError?.errorCodeName})"
                current?.isPlaying == true -> "Playing $streamTitle"
                current?.playbackState == Player.STATE_BUFFERING -> "Buffering $streamTitle"
                current?.playbackState == Player.STATE_READY -> "Ready / paused: $streamTitle"
                else -> idleStatusText()
            }
        updateNotification(status)
    }

    private fun idleStatusText(): String =
        connectedSenderName?.let { "Connected to $it" } ?: "Listening for private audio…"

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel =
                NotificationChannel(
                        CHANNEL_ID,
                        "SpatialFin Split Audio Sink",
                        NotificationManager.IMPORTANCE_LOW,
                    )
                    .apply { description = "Shows private audio playback status on watch" }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * Carries the Ongoing Activity chip as well as the sink's own status.
     *
     * An Ongoing Activity has to hang off a foreground-service notification, and this is the only
     * foreground service the watch is entitled to run: a pure remote plays nothing locally, so it
     * cannot justify a `mediaPlayback` service to the platform, and the battery cost would buy only
     * a shortcut. Attaching the chip here keeps the "exactly one foreground service, and only while
     * it has work" rule intact.
     */
    private fun buildOngoingNotification(statusText: String): Notification {
        val launchIntent =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, WearMainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val builder =
            NotificationCompat.Builder(this, CHANNEL_ID)
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
        _sinkStatus.value = statusText
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildOngoingNotification(statusText))
    }

    override fun onDestroy() {
        _isSinkActive.value = false
        connectedSenderName = null
        // Unregistration must outlive the service's cancelled playback scope.
        val oldAdvertiser = advertiser
        val oldStartup = startupJob
        scope.cancel()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            oldStartup?.join()
            oldAdvertiser?.unregister()
        }
        server?.stop()
        player?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        if (!_sinkStatus.value.startsWith("Could not")) _sinkStatus.value = "Off"
        super.onDestroy()
        Timber.i("WearAudioReceiverService: destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "wear_split_audio_channel"
        private const val NOTIFICATION_ID = 2049

        private val _sinkStatus = MutableStateFlow("Off")
        val sinkStatus: StateFlow<String> = _sinkStatus.asStateFlow()

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
