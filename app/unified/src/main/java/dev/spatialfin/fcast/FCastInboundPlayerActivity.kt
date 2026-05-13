package dev.spatialfin.fcast

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import dev.jdtech.jellyfin.repository.JellyfinRepository
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.ui.PlayerView
import dev.jdtech.jellyfin.fcast.protocol.PlaybackState
import dev.jdtech.jellyfin.fcast.protocol.PlaybackUpdateMessage
import dev.jdtech.jellyfin.fcast.protocol.SplitAvMetadata
import dev.jdtech.jellyfin.fcast.protocol.SplitAvRole
import dev.jdtech.jellyfin.fcast.protocol.VolumeUpdateMessage
import dev.jdtech.jellyfin.fcast.receiver.ExternalStreamPlayer
import dev.jdtech.jellyfin.fcast.receiver.ExternalStreamRequest
import dev.jdtech.jellyfin.fcast.receiver.FCastInboundSession
import dev.jdtech.jellyfin.player.xr.LibassRenderer
import dev.jdtech.jellyfin.player.xr.LibassTextRenderer
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
@AndroidEntryPoint
class FCastInboundPlayerActivity : ComponentActivity() {

    /**
     * Hilt-injected so we can fetch embedded MKV font attachments when the cast URL points at
     * a Jellyfin item. Activity stays lightweight when the URL isn't Jellyfin (third-party
     * sender, raw HTTP stream, etc.) — the deferred just completes with an empty list.
     */
    @Inject lateinit var repository: JellyfinRepository

    private var player: ExoPlayer? = null
    private var playbackTickerJob: Job? = null
    private var subtitleRenderJob: Job? = null
    private var libassRenderer: LibassRenderer? = null
    private var libassFontsDeferred: CompletableDeferred<List<Pair<String, ByteArray>>>? = null
    private var splitAvRole: SplitAvRole? = null
    private var playbackTickerIntervalMs: Long = NORMAL_TICKER_INTERVAL_MS
    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = pushPlaybackSnapshot()
        override fun onPlaybackStateChanged(playbackState: Int) = pushPlaybackSnapshot()
        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            // Drop the libass per-event cache on every seek — otherwise the post-seek frame
            // is composited on top of stale glyph buffers from before the seek, producing a
            // "ghost subtitle" for one frame. Mirrors the same fix the main player uses
            // (LibassTextRenderer.onPositionReset → libassRenderer.clearCache).
            if (reason == Player.DISCONTINUITY_REASON_SEEK ||
                reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) {
                libassRenderer?.clearCache()
            }
            pushPlaybackSnapshot()
        }
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
            // Clear libass first so the post-seek frame renders against an empty event cache.
            // The Player.Listener's onPositionDiscontinuity also clears it, but doing this
            // here on the inbound-FCast path keeps the bitmap clean even if the player coalesces
            // the seek before the listener fires.
            libassRenderer?.clearCache()
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
        val exo = buildPlayerWithLibass()
        player = exo
        findViewById<PlayerView>(R.id.fcast_inbound_player_view).player = exo
        exo.addListener(playerListener)
        startSubtitleRenderLoop()

        if (!applyIntent(intent)) finish()
    }

    /**
     * Fire-and-forget Jellyfin font fetch for the cast URL. Completes the [libassFontsDeferred]
     * with the resolved (name, bytes) pairs, or an empty list when the URL isn't a Jellyfin
     * stream / the receiver can't resolve the item. Called from [applyIntent] every time a new
     * Play arrives so each cast in a single session gets its own font set.
     */
    private fun preloadLibassFontsAsync(url: String) {
        val deferred = libassFontsDeferred ?: CompletableDeferred<List<Pair<String, ByteArray>>>().also {
            libassFontsDeferred = it
        }
        val itemId = extractJellyfinItemId(url)
        if (itemId == null) {
            deferred.complete(emptyList())
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val fonts = runCatching {
                val sources = repository.getMediaSources(itemId = itemId, includePath = false)
                val candidates = sources.flatMap { source ->
                    source.mediaAttachments.map { source to it }
                }.filter { (_, att) -> isFontAttachment(att.fileName, att.mimeType, att.codec) }
                Timber.tag(TAG).i(
                    "preloadLibassFontsAsync: candidate fonts=%d for itemId=%s",
                    candidates.size, itemId,
                )
                candidates.mapNotNull { (source, attachment) ->
                    val fileName = attachment.fileName.ifBlank { "attachment-${source.id}-${attachment.index}" }
                    repository.getMediaAttachment(itemId, source.id, attachment.index)
                        ?.let { fileName to it }
                }
            }.onFailure {
                Timber.tag(TAG).w(it, "preloadLibassFontsAsync failed for %s", itemId)
            }.getOrDefault(emptyList())
            deferred.complete(fonts)
        }
    }

    /**
     * Extract the Jellyfin item UUID from a `/Videos/<uuid>/stream` style URL. Returns null
     * for non-Jellyfin or malformed URLs — we then skip the font fetch entirely.
     */
    private fun extractJellyfinItemId(url: String): UUID? {
        val match = JELLYFIN_VIDEO_PATH_REGEX.find(url) ?: return null
        return runCatching { UUID.fromString(match.groupValues[1]) }.getOrNull()
    }

    private fun isFontAttachment(fileName: String, mimeType: String, codec: String): Boolean {
        val nameL = fileName.lowercase()
        val mimeL = mimeType.lowercase()
        val codecL = codec.lowercase()
        return mimeL.contains("font") ||
            mimeL.contains("truetype") ||
            mimeL.contains("opentype") ||
            nameL.endsWith(".ttf") ||
            nameL.endsWith(".otf") ||
            nameL.endsWith(".ttc") ||
            nameL.endsWith(".otc") ||
            codecL.contains("ttf") ||
            codecL.contains("otf")
    }

    /**
     * Build an ExoPlayer that routes ASS/SSA/SRT/VTT text tracks through [LibassTextRenderer]
     * → [LibassRenderer]. Mirrors the wiring [dev.jdtech.jellyfin.player.beam.BeamPlayerActivity]
     * uses for its 2D player — the FCast inbound activity is the SpatialFin → SpatialFin cast
     * receiver's player, so when a SpatialFin sender pushes a Jellyfin item with embedded ASS
     * subs the receiver renders them with full libass fidelity instead of plain dialogue.
     *
     * Defensive fallback: if the native `libass_jni.so` couldn't be loaded for any reason, the
     * builder degrades silently to ExoPlayer's default text renderer — ASS will look plain but
     * the cast still plays.
     */
    @OptIn(UnstableApi::class)
    private fun buildPlayerWithLibass(): ExoPlayer {
        // 1920×1080 render canvas; resized to the on-screen panel below once we know the
        // actual layout size. The XR libass renderer caches one bitmap of this size; a too-low
        // initial value would force a re-alloc on first frame.
        val renderer = runCatching {
            if (LibassRenderer.isAvailable()) {
                LibassRenderer(1920, 1080).apply { init() }
            } else {
                Timber.tag(TAG).w("libass unavailable — text tracks will use the default renderer")
                null
            }
        }.onFailure { Timber.tag(TAG).e(it, "Failed to init libass for FCast inbound") }
            .getOrNull()
        libassRenderer = renderer

        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildTextRenderers(
                context: Context,
                output: TextOutput,
                outputLooper: Looper,
                extensionRendererMode: Int,
                out: ArrayList<Renderer>,
            ) {
                val active = renderer
                if (active != null) {
                    // libass claims text tracks exclusively. We deliberately skip
                    // super.buildTextRenderers so ExoPlayer's stock TextRenderer doesn't grab
                    // SRT/VTT ahead of libass — libass renders them through its synthetic ASS
                    // header so the user gets consistent styling across all text formats.
                    // PR 6: forward the deferred embedded-font fetch into the renderer. The
                    // libass thread will block on this at track init — typically already
                    // resolved by the time we get there because preloadLibassFontsAsync runs
                    // in onCreate/onNewIntent.
                    val fontLoader: () -> List<Pair<String, ByteArray>> = {
                        runCatching {
                            runBlocking {
                                libassFontsDeferred?.await().orEmpty()
                            }
                        }.getOrDefault(emptyList())
                    }
                    out.add(
                        LibassTextRenderer(
                            libassRenderer = active,
                            onTrackInitialized = {},
                            fontLoader = fontLoader,
                            usagePref = "auto",
                        )
                    )
                    Timber.tag(TAG).i("FCast inbound: LibassTextRenderer registered")
                } else {
                    super.buildTextRenderers(context, output, outputLooper, extensionRendererMode, out)
                }
            }
        }.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)

        // CRUCIAL: subtitle transcoding off so raw ASS bytes survive to LibassTextRenderer.
        // With Media3's default `experimentalParseSubtitlesDuringExtraction(true)`, the
        // extractor converts ASS into `application/x-media3-cues` and libass never sees the
        // original payload — anime subs render as plain white Arial.
        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .experimentalParseSubtitlesDuringExtraction(false)

        return ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
    }

    /**
     * Single ~60 Hz loop that pumps the libass renderer and updates the on-screen overlay
     * bitmap. Skipped when there is no libass renderer or when we're in split-A/V audio mode
     * (no video to overlay onto, and the audio receiver renders no subs by design).
     */
    private fun startSubtitleRenderLoop() {
        subtitleRenderJob?.cancel()
        subtitleRenderJob = lifecycleScope.launch {
            val overlay = findViewById<ImageView>(R.id.fcast_inbound_subtitle_overlay) ?: return@launch
            val r = libassRenderer ?: return@launch
            while (isActive) {
                val p = player
                if (p == null || splitAvRole == SplitAvRole.AUDIO) {
                    if (overlay.visibility != View.GONE) overlay.visibility = View.GONE
                    delay(120L)
                    continue
                }
                val result = r.renderFrame(p.currentPosition)
                if (result.hasContent && result.bitmap != null) {
                    val bitmap: Bitmap = result.bitmap!!
                    overlay.setImageBitmap(bitmap)
                    if (overlay.visibility != View.VISIBLE) overlay.visibility = View.VISIBLE
                } else {
                    if (overlay.visibility != View.GONE) overlay.visibility = View.GONE
                }
                delay(16L) // ~60 fps for karaoke / \move / \fad smoothness
            }
        }
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
        // Kick off the embedded-font fetch ASAP — the LibassTextRenderer's font loader closure
        // blocks on the resulting deferred at track init, which happens after ExoPlayer
        // resolves the manifest. That's typically 200ms+ from now, which is enough lead time
        // for a healthy Jellyfin server to round-trip `/MediaSources`. On slow networks the
        // closure still blocks but only the libass renderer thread sees that, never the UI.
        preloadLibassFontsAsync(url)
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
        subtitleRenderJob?.cancel()
        subtitleRenderJob = null
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
        libassRenderer?.destroy()
        libassRenderer = null
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
        private const val TAG: String = "FCastInbound"

        /**
         * Matches `/Videos/<uuid>/stream` and the alternates Jellyfin actually serves
         * (`/Videos/<uuid>/<sessionId>/stream`, `/Videos/<uuid>/main.m3u8`, etc.). The first
         * captured UUID is the item id.
         */
        private val JELLYFIN_VIDEO_PATH_REGEX = Regex(
            "/Videos/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})/",
        )

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
