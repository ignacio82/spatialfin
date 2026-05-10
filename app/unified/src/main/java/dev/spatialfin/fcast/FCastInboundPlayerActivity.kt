package dev.spatialfin.fcast

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import dev.jdtech.jellyfin.fcast.protocol.PlaybackState
import dev.jdtech.jellyfin.fcast.protocol.PlaybackUpdateMessage
import dev.jdtech.jellyfin.fcast.protocol.VolumeUpdateMessage
import dev.jdtech.jellyfin.fcast.receiver.ExternalStreamPlayer
import dev.jdtech.jellyfin.fcast.receiver.ExternalStreamRequest
import dev.jdtech.jellyfin.fcast.receiver.FCastInboundSession
import dev.spatialfin.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Minimal 2D ExoPlayer-only Activity that plays an arbitrary HTTP/HLS/DASH URL pushed in via
 * FCast. We deliberately avoid the immersive XR / Beam / TV player Activities here — those
 * assume a Jellyfin item ID and pull subtitles, recommendations, voice services etc. through
 * `SpatialPlayerScreen`. For an external URL all that machinery is meaningless and the surgery
 * to teach those screens about itemId-less playback is its own change.
 *
 * On Galaxy XR this Activity will appear as a 2D spatial panel by default (the XR shell
 * places non-spatial activities as flat panels). If the user wants Full Space cinema scale,
 * they can flip into Full Space via the existing space-mode toggle.
 *
 * Lifecycle bridge: registers an [ExternalStreamPlayer] with [FCastInboundSession] so that
 * inbound FCast Pause/Resume/Seek/SetVolume/SetSpeed frames re-enter the running ExoPlayer,
 * and pushes [PlaybackUpdateMessage] back through the same bridge so the sender's
 * mini-controller reflects real state.
 */
class FCastInboundPlayerActivity : ComponentActivity() {

    private var player: ExoPlayer? = null
    private var playbackTickerJob: Job? = null
    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = pushPlaybackSnapshot()
        override fun onPlaybackStateChanged(playbackState: Int) = pushPlaybackSnapshot()
        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) = pushPlaybackSnapshot()
        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) =
            pushPlaybackSnapshot()
        override fun onVolumeChanged(volume: Float) {
            FCastInboundSession.pushVolumeUpdate(
                VolumeUpdateMessage(
                    generationTime = System.currentTimeMillis(),
                    volume = volume.toDouble(),
                )
            )
        }
    }

    private val control = object : ExternalStreamPlayer {
        // Inbound senders never call this — Play frames go through the Intent-based path —
        // but the interface requires it.
        override fun play(request: ExternalStreamRequest): ExternalStreamPlayer.PlayResult =
            ExternalStreamPlayer.PlayResult.Rejected("Use the Intent-based play path")

        override fun pause() = runOnUiThread { player?.pause() }.let { Unit }
        override fun resume() = runOnUiThread { player?.play() }.let { Unit }
        override fun stop() = runOnUiThread {
            player?.stop()
            finish()
        }.let { Unit }
        override fun seek(seconds: Double) = runOnUiThread {
            player?.seekTo((seconds * 1000.0).toLong().coerceAtLeast(0L))
        }.let { Unit }
        override fun setVolume(volume: Double) = runOnUiThread {
            player?.volume = volume.toFloat().coerceIn(0f, 1f)
        }.let { Unit }
        override fun setSpeed(speed: Double) = runOnUiThread {
            val s = speed.toFloat().coerceIn(0.25f, 4f)
            player?.setPlaybackSpeed(s)
        }.let { Unit }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val url = intent.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank()) {
            Timber.w("FCastInboundPlayerActivity launched without %s", EXTRA_URL)
            finish()
            return
        }
        val container = intent.getStringExtra(EXTRA_CONTAINER)
        val startMs = intent.getLongExtra(EXTRA_START_MS, 0L).coerceAtLeast(0L)
        val title = intent.getStringExtra(EXTRA_TITLE)

        setContentView(R.layout.activity_fcast_inbound_player)
        val playerView = findViewById<PlayerView>(R.id.fcast_inbound_player_view)

        val exo = ExoPlayer.Builder(this).build()
        player = exo
        playerView.player = exo
        exo.addListener(playerListener)

        val mediaItem = MediaItem.Builder()
            .setUri(url)
            .setMediaMetadata(
                MediaMetadata.Builder().setTitle(title ?: "FCast media").build(),
            )
            .apply {
                when {
                    !container.isNullOrBlank() -> setMimeType(container)
                    url.contains(".m3u8", ignoreCase = true) -> setMimeType(MimeTypes.APPLICATION_M3U8)
                    url.contains(".mpd", ignoreCase = true) -> setMimeType(MimeTypes.APPLICATION_MPD)
                }
            }
            .build()

        exo.setMediaItem(mediaItem, startMs)
        exo.prepare()
        exo.play()

        FCastInboundSession.bindControl(control)
        startPlaybackTicker()
    }

    private fun startPlaybackTicker() {
        playbackTickerJob?.cancel()
        playbackTickerJob = lifecycleScope.launch {
            // 1s cadence is enough to keep the sender's seek bar coherent without spamming
            // the wire. Sender-side seekBy() reads `remoteState.time` so this also keeps
            // ±10s skips accurate without forcing the sender to round-trip a request first.
            while (isActive) {
                pushPlaybackSnapshot()
                delay(1_000)
            }
        }
    }

    private fun pushPlaybackSnapshot() {
        val p = player ?: return
        val state = when {
            !p.playWhenReady -> PlaybackState.Paused
            p.isPlaying -> PlaybackState.Playing
            p.playbackState == Player.STATE_ENDED || p.playbackState == Player.STATE_IDLE ->
                PlaybackState.Idle
            else -> PlaybackState.Paused
        }
        val duration = p.duration.takeIf { it > 0L }?.let { it / 1000.0 }
        FCastInboundSession.pushPlaybackUpdate(
            PlaybackUpdateMessage(
                generationTime = System.currentTimeMillis(),
                state = state.code,
                time = p.currentPosition / 1000.0,
                duration = duration,
                speed = p.playbackParameters.speed.toDouble(),
            )
        )
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        playbackTickerJob?.cancel()
        playbackTickerJob = null
        FCastInboundSession.unbindControl(control)
        // Tell the sender the stream is over so its mini-controller drops back to Idle.
        FCastInboundSession.pushPlaybackUpdate(
            PlaybackUpdateMessage(
                generationTime = System.currentTimeMillis(),
                state = PlaybackState.Idle.code,
                time = (player?.currentPosition ?: 0L) / 1000.0,
            )
        )
        player?.removeListener(playerListener)
        player?.release()
        player = null
    }

    companion object {
        const val EXTRA_URL: String = "fcast.in.url"
        const val EXTRA_CONTAINER: String = "fcast.in.container"
        const val EXTRA_START_MS: String = "fcast.in.start_ms"
        const val EXTRA_TITLE: String = "fcast.in.title"

        fun createIntent(
            context: Context,
            url: String,
            container: String?,
            startMs: Long = 0L,
            title: String? = null,
        ): Intent = Intent(context, FCastInboundPlayerActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(EXTRA_URL, url)
            container?.let { putExtra(EXTRA_CONTAINER, it) }
            putExtra(EXTRA_START_MS, startMs)
            title?.let { putExtra(EXTRA_TITLE, it) }
        }
    }
}
