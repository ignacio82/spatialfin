package dev.spatialfin.fcast

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import dev.jdtech.jellyfin.fcast.protocol.PlaybackState
import dev.jdtech.jellyfin.fcast.protocol.PlaybackUpdateMessage
import dev.jdtech.jellyfin.fcast.protocol.SplitAvMetadata
import dev.jdtech.jellyfin.fcast.protocol.SplitAvRole
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
    private var splitAvRole: SplitAvRole? = null
    private var playbackTickerIntervalMs: Long = NORMAL_TICKER_INTERVAL_MS
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

        setContentView(R.layout.activity_fcast_inbound_player)
        val exo = ExoPlayer.Builder(this).build()
        player = exo
        findViewById<PlayerView>(R.id.fcast_inbound_player_view).player = exo
        exo.addListener(playerListener)

        if (!applyIntent(intent)) finish()
    }

    /**
     * Re-route a subsequent inbound Play frame into the same Activity instance. Because the
     * Activity is `launchMode="singleTask"` + `FLAG_ACTIVITY_CLEAR_TOP`, the second Play (the
     * real media URL that follows the calibration WAV) gets delivered here rather than
     * re-running [onCreate]. Without this override the Activity would keep playing the old
     * (calibration / previous-cast) URL and the user would hear nothing for the real media.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyIntent(intent)
    }

    /**
     * Read playback parameters from [newIntent] and (re)configure the ExoPlayer + audio-only
     * overlay accordingly. Returns false if the intent is missing the required URL — caller is
     * responsible for finishing in that case (only relevant from [onCreate]; an in-session
     * malformed intent just leaves the previous media playing).
     */
    private fun applyIntent(newIntent: Intent): Boolean {
        val url = newIntent.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank()) {
            Timber.w("FCastInboundPlayerActivity intent without %s", EXTRA_URL)
            return false
        }
        val container = newIntent.getStringExtra(EXTRA_CONTAINER)
        val startMs = newIntent.getLongExtra(EXTRA_START_MS, 0L).coerceAtLeast(0L)
        val title = newIntent.getStringExtra(EXTRA_TITLE)
        val newSplitAvRole = newIntent.getStringExtra(EXTRA_SPLIT_AV_ROLE)
            ?.let { runCatching { SplitAvRole.valueOf(it) }.getOrNull() }
        val splitAvCadenceHz = newIntent.getIntExtra(EXTRA_SPLIT_AV_CADENCE_HZ, -1)
            .takeIf { it > 0 }
        splitAvRole = newSplitAvRole
        playbackTickerIntervalMs = if (newSplitAvRole != null) {
            val hz = splitAvCadenceHz ?: SplitAvMetadata.DEFAULT_SYNC_CADENCE_HZ
            (1_000L / hz.coerceAtLeast(1)).coerceAtLeast(MIN_TICKER_INTERVAL_MS)
        } else {
            NORMAL_TICKER_INTERVAL_MS
        }

        val playerView = findViewById<PlayerView>(R.id.fcast_inbound_player_view)
        val audioOnlyOverlay = findViewById<LinearLayout>(R.id.fcast_inbound_audio_only_overlay)
        val audioOnlyTitle = findViewById<TextView>(R.id.fcast_inbound_audio_only_title)
        val audioOnlyStop = findViewById<Button>(R.id.fcast_inbound_audio_only_stop)
        val exo = player ?: return false

        if (newSplitAvRole == SplitAvRole.AUDIO) {
            exo.trackSelectionParameters = TrackSelectionParameters.Builder()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                .build()
            audioOnlyOverlay.visibility = View.VISIBLE
            audioOnlyTitle.text = title.orEmpty()
            playerView.useController = false
            audioOnlyStop.setOnClickListener {
                player?.stop()
                finish()
            }
        } else {
            // Non-split path: clear any previous overlay state (e.g. when an earlier session
            // disabled video tracks for a calibration WAV).
            exo.trackSelectionParameters = TrackSelectionParameters.Builder().build()
            audioOnlyOverlay.visibility = View.GONE
            playerView.useController = true
        }
        Timber.i(
            "FCast inbound: url=%s role=%s ticker=%dms",
            url, newSplitAvRole?.name ?: "fullAv", playbackTickerIntervalMs,
        )

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

        // Re-tune ExoPlayer to the new media. setMediaItem replaces the queue so the old
        // (calibration) URL stops playing immediately.
        exo.setMediaItem(mediaItem, startMs)
        exo.prepare()
        // Split-A/V startup coordination: when we're the audio receiver, *load* the stream
        // but stay paused. The XR sender is still loading its own (video) master and is
        // multiple seconds behind us at this point. If we start playing now, the sender's
        // drift policy will see a huge negative drift the instant its master finishes
        // loading and either degrade or hard-seek the master forward into a buffer cycle.
        // The sender's pause-mirror sends Resume on the FCast wire as soon as its master
        // becomes playWhenReady=true — that's our cue to actually start audio. Until then
        // we stay at startMs with playWhenReady=false.
        if (newSplitAvRole == SplitAvRole.AUDIO) {
            exo.playWhenReady = false
        } else {
            exo.play()
        }

        FCastInboundSession.bindControl(control)
        startPlaybackTicker()
        return true
    }

    private fun startPlaybackTicker() {
        playbackTickerJob?.cancel()
        playbackTickerJob = lifecycleScope.launch {
            // 1s cadence is enough to keep the sender's seek bar coherent without spamming
            // the wire. Sender-side seekBy() reads `remoteState.time` so this also keeps
            // ±10s skips accurate without forcing the sender to round-trip a request first.
            // In split-A/V audio mode the cadence is ~10x higher because the sender's drift
            // correction loop drives video timing off these beacons.
            while (isActive) {
                pushPlaybackSnapshot()
                delay(playbackTickerIntervalMs)
            }
        }
    }

    private fun pushPlaybackSnapshot() {
        val p = player ?: return
        // Report user *intent* (playWhenReady), not transient runtime state (isPlaying). A
        // brief buffering moment (chunk fetch, decoder warm-up after a seek) flips isPlaying
        // false but does not change the user's intent to play. In a split-A/V session the
        // sender's drift controller cascades any "Paused" beacon back through
        // pauseFromMaster() to the XR master — which then pauses the master, which the
        // mirror cascades back to us as a real pause, deadlocking the session every time the
        // Pixel hits a buffer chunk. Pause only when the user (or a programmatic mirror
        // resulting from one) has actually toggled playWhenReady=false.
        val state = when {
            !p.playWhenReady -> PlaybackState.Paused
            p.playbackState == Player.STATE_ENDED || p.playbackState == Player.STATE_IDLE ->
                PlaybackState.Idle
            else -> PlaybackState.Playing
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
        const val EXTRA_SPLIT_AV_ROLE: String = "fcast.in.split_av_role"
        const val EXTRA_SPLIT_AV_CADENCE_HZ: String = "fcast.in.split_av_cadence_hz"

        private const val NORMAL_TICKER_INTERVAL_MS: Long = 1_000L
        private const val MIN_TICKER_INTERVAL_MS: Long = 50L

        fun createIntent(
            context: Context,
            url: String,
            container: String?,
            startMs: Long = 0L,
            title: String? = null,
            splitAv: SplitAvMetadata? = null,
        ): Intent = Intent(context, FCastInboundPlayerActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(EXTRA_URL, url)
            container?.let { putExtra(EXTRA_CONTAINER, it) }
            putExtra(EXTRA_START_MS, startMs)
            title?.let { putExtra(EXTRA_TITLE, it) }
            splitAv?.let {
                putExtra(EXTRA_SPLIT_AV_ROLE, it.role.name)
                it.syncCadenceHz?.let { hz -> putExtra(EXTRA_SPLIT_AV_CADENCE_HZ, hz) }
            }
        }
    }
}
