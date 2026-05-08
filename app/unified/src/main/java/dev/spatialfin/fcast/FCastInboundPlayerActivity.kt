package dev.spatialfin.fcast

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import dev.spatialfin.R
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
 */
class FCastInboundPlayerActivity : ComponentActivity() {

    private var player: ExoPlayer? = null

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
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
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
