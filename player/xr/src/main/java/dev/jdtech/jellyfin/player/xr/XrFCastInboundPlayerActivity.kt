package dev.jdtech.jellyfin.player.xr

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.text.CueGroup
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.session.MediaSession
import androidx.xr.runtime.Session
import androidx.xr.runtime.SessionCreateSuccess
import androidx.xr.scenecore.scene
import dagger.hilt.android.AndroidEntryPoint
import dev.jdtech.jellyfin.fcast.protocol.PlaybackState
import dev.jdtech.jellyfin.fcast.protocol.PlaybackUpdateMessage
import dev.jdtech.jellyfin.fcast.protocol.SpatialFinTrack
import dev.jdtech.jellyfin.fcast.protocol.SpatialFinTracksUpdateMessage
import dev.jdtech.jellyfin.fcast.protocol.VolumeUpdateMessage
import dev.jdtech.jellyfin.fcast.receiver.ExternalStreamIntentCodec
import dev.jdtech.jellyfin.fcast.receiver.ExternalStreamPlayer
import dev.jdtech.jellyfin.fcast.receiver.ExternalStreamRequest
import dev.jdtech.jellyfin.fcast.receiver.ExternalStreamSource
import dev.jdtech.jellyfin.fcast.receiver.FCastInboundBridgeIpcClient
import dev.jdtech.jellyfin.player.core.external.ExternalStreamMediaPreparer
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import timber.log.Timber

/**
 * Focused Full Space FCast receiver. It deliberately owns no library view model or Jellyfin
 * session state: external media is transient and exposes playback controls only.
 */
@AndroidEntryPoint
class XrFCastInboundPlayerActivity : AppCompatActivity() {
    @Inject lateinit var appPreferences: AppPreferences
    @Inject lateinit var repository: JellyfinRepository

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private var xrSession: Session? = null
    private var bridge: FCastInboundBridgeIpcClient? = null
    private var ticker: Job? = null
    private var subtitleTicker: Job? = null
    private var libassRenderer: LibassRenderer? = null
    private var libassFontsDeferred: CompletableDeferred<List<Pair<String, ByteArray>>>? = null
    private var finishRequested = false
    private var usedSurfaceEntity = false
    private val requestState = MutableStateFlow<ExternalStreamRequest?>(null)
    private val cuesState = MutableStateFlow<List<androidx.media3.common.text.Cue>>(emptyList())
    private val libassBitmapState = MutableStateFlow<Bitmap?>(null)
    private val libassFrameVersionState = MutableStateFlow(0)
    private val mediaPreparer by lazy {
        ExternalStreamMediaPreparer(this, parseSubtitlesDuringExtraction = false)
    }

    private val resumeHandler = Handler(Looper.getMainLooper())
    private var scheduledResume: Runnable? = null

    private fun cancelScheduledResume() {
        scheduledResume?.let(resumeHandler::removeCallbacks)
        scheduledResume = null
    }

    private val control = object : ExternalStreamPlayer {
        override fun play(request: ExternalStreamRequest): ExternalStreamPlayer.PlayResult =
            ExternalStreamPlayer.PlayResult.Rejected("Play requests launch or replace the immersive Activity")

        override fun pause() = runOnUiThread {
            cancelScheduledResume()
            player?.pause()
            pushPlaybackSnapshot()
        }

        override fun resume() = runOnUiThread {
            cancelScheduledResume()
            player?.play()
            pushPlaybackSnapshot()
        }

        override fun resumeAt(atReceiverMonotonicMs: Long) = runOnUiThread {
            cancelScheduledResume()
            val delayMs = atReceiverMonotonicMs - SystemClock.elapsedRealtime()
            if (delayMs <= 0L || delayMs > MAX_SCHEDULED_START_WAIT_MS) {
                player?.play()
            } else {
                Runnable {
                    player?.play()
                    pushPlaybackSnapshot()
                    scheduledResume = null
                }.also {
                    scheduledResume = it
                    resumeHandler.postDelayed(it, delayMs)
                }
            }
        }

        override fun stop() = runOnUiThread { requestFinish("sender-stop") }

        override fun seek(seconds: Double) = runOnUiThread {
            libassRenderer?.clearCache()
            player?.seekTo((seconds * 1000.0).toLong().coerceAtLeast(0L))
        }

        override fun setVolume(volume: Double) = runOnUiThread {
            player?.volume = volume.toFloat().coerceIn(0f, 1f)
        }

        override fun setSpeed(speed: Double) = runOnUiThread {
            player?.setPlaybackSpeed(speed.toFloat().coerceIn(0.25f, 4f))
        }

        override fun setTrack(type: Int, trackId: String) = runOnUiThread {
            val exo = player ?: return@runOnUiThread
            val groupIndex = trackId.toIntOrNull() ?: return@runOnUiThread
            val group = exo.currentTracks.groups.getOrNull(groupIndex) ?: return@runOnUiThread
            if (group.type != type) return@runOnUiThread
            exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                .setOverrideForType(androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, 0))
                .setTrackTypeDisabled(type, false)
                .build()
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = pushPlaybackSnapshot()
        override fun onPlaybackStateChanged(playbackState: Int) = pushPlaybackSnapshot()
        override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) =
            pushPlaybackSnapshot()
        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK ||
                reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) {
                libassRenderer?.clearCache()
            }
        }
        override fun onVolumeChanged(volume: Float) {
            bridge?.publish(VolumeUpdateMessage(System.currentTimeMillis(), volume.toDouble()))
        }
        override fun onTracksChanged(tracks: Tracks) = pushTracksSnapshot(tracks)
        override fun onCues(cueGroup: CueGroup) {
            cuesState.value = cueGroup.cues
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.colorMode = android.content.pm.ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val request = ExternalStreamIntentCodec.getRequest(intent)
        if (request == null) {
            finish()
            return
        }

        val exo = buildPlayerWithLibass().also {
            player = it
            it.addListener(playerListener)
        }

        val result = runCatching { Session.create(this) }.getOrNull()
        if (result !is SessionCreateSuccess) {
            Timber.tag(TAG).w("XR session unavailable; forwarding incoming cast to flat player")
            forwardToFlatPlayer(request)
            exo.release()
            player = null
            finish()
            return
        }
        xrSession = result.session
        runCatching { result.session.scene.requestFullSpaceMode() }

        bridge = FCastInboundBridgeIpcClient(this, control).also { it.connect() }
        mediaSession = MediaSession.Builder(this, exo).build()
        setContent {
            XrFCastInboundPlayerScreen(
                session = result.session,
                player = exo,
                requestState = requestState,
                cuesState = cuesState,
                libassBitmapState = libassBitmapState,
                libassFrameVersionState = libassFrameVersionState,
                libassEnabled = libassRenderer != null,
                preferences = appPreferences,
                onSurfaceAttached = { usedSurfaceEntity = true },
                onExit = { requestFinish("user-exit") },
                onSubtitleResize = { rw, rh, sw, sh ->
                    libassRenderer?.resize(rw, rh, sw, sh)
                },
            )
        }
        applyRequest(request)
        ticker = lifecycleScope.launch {
            while (isActive) {
                pushPlaybackSnapshot()
                delay(1_000L)
            }
        }
        subtitleTicker = lifecycleScope.launch {
            while (isActive) {
                val rendered = libassRenderer?.renderFrame(exo.currentPosition)
                val newBitmap = rendered?.bitmap?.takeIf { rendered.hasContent }
                if (newBitmap != null) {
                    libassBitmapState.value = newBitmap
                    libassFrameVersionState.value += 1
                } else if (libassBitmapState.value != null) {
                    libassBitmapState.value = null
                }
                delay(16L)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        ExternalStreamIntentCodec.getRequest(intent)?.let(::applyRequest)
    }

    private fun applyRequest(request: ExternalStreamRequest) {
        val exo = player ?: return
        requestState.value = request
        preloadLibassFontsAsync((request.source as? ExternalStreamSource.Url)?.url)
        runCatching { mediaPreparer.replace(exo, request) }
            .onFailure {
                Timber.tag(TAG).w(it, "Could not prepare immersive inbound source")
                requestFinish("prepare-failed")
            }
            .onSuccess {
                exo.play()
                pushPlaybackSnapshot()
            }
    }

    private fun pushPlaybackSnapshot() {
        val exo = player ?: return
        val state = when {
            !exo.playWhenReady -> PlaybackState.Paused
            exo.playbackState == Player.STATE_ENDED || exo.playbackState == Player.STATE_IDLE ->
                PlaybackState.Idle
            else -> PlaybackState.Playing
        }
        bridge?.publish(
            PlaybackUpdateMessage(
                generationTime = System.currentTimeMillis(),
                state = state.code,
                time = exo.currentPosition / 1000.0,
                duration = exo.duration.takeIf { it > 0 }?.div(1000.0),
                speed = exo.playbackParameters.speed.toDouble(),
                monotonicSampleMs = SystemClock.elapsedRealtime(),
            ),
        )
    }

    private fun pushTracksSnapshot(tracks: Tracks) {
        val audio = mutableListOf<SpatialFinTrack>()
        val subtitles = mutableListOf<SpatialFinTrack>()
        tracks.groups.forEachIndexed { index, group ->
            if (!group.isSupported || (group.type != C.TRACK_TYPE_AUDIO && group.type != C.TRACK_TYPE_TEXT)) {
                return@forEachIndexed
            }
            val format = group.getTrackFormat(0)
            val track = SpatialFinTrack(
                id = index.toString(),
                name = format.label ?: format.language ?: "Track $index",
                language = format.language,
                isSelected = group.isSelected,
            )
            if (group.type == C.TRACK_TYPE_AUDIO) audio += track else subtitles += track
        }
        bridge?.publish(SpatialFinTracksUpdateMessage(audio, subtitles))
    }

    private fun forwardToFlatPlayer(request: ExternalStreamRequest) {
        val fallback = Intent().setClassName(this, "dev.spatialfin.fcast.FCastInboundPlayerActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        ExternalStreamIntentCodec.putRequest(fallback, request)
        runCatching { startActivity(fallback) }
            .onFailure { Timber.tag(TAG).e(it, "Flat inbound fallback launch failed") }
    }

    private fun buildPlayerWithLibass(): ExoPlayer {
        val usagePref = appPreferences.getValue(appPreferences.libassSubtitleUsage)
        val renderer = runCatching {
            if (usagePref != "never" && LibassRenderer.isAvailable()) {
                LibassRenderer(1920, 1080).apply { init() }
            } else {
                null
            }
        }.onFailure { Timber.tag(TAG).w(it, "Failed to initialize inbound libass renderer") }
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
                if (active == null) {
                    super.buildTextRenderers(context, output, outputLooper, extensionRendererMode, out)
                } else {
                    out.add(
                        LibassTextRenderer(
                            libassRenderer = active,
                            onTrackInitialized = {},
                            fontLoader = {
                                runCatching { runBlocking { libassFontsDeferred?.await().orEmpty() } }
                                    .getOrDefault(emptyList())
                            },
                            usagePref = usagePref,
                            srtFontSize = appPreferences.getValue(appPreferences.xrSubtitleSize),
                        ),
                    )
                }
            }
        }.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)
            
        val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(this)
            .experimentalParseSubtitlesDuringExtraction(false)

        return ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
    }

    private fun preloadLibassFontsAsync(url: String?) {
        val deferred = CompletableDeferred<List<Pair<String, ByteArray>>>().also {
            libassFontsDeferred = it
        }
        val itemId = url?.let(::extractJellyfinItemId)
        if (itemId == null) {
            deferred.complete(emptyList())
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val fonts = runCatching {
                repository.getMediaSources(itemId, includePath = false)
                    .flatMap { source -> source.mediaAttachments.map { source to it } }
                    .filter { (_, attachment) ->
                        isFontAttachment(attachment.fileName, attachment.mimeType, attachment.codec)
                    }
                    .mapNotNull { (source, attachment) ->
                        val name = attachment.fileName.ifBlank { "attachment-${source.id}-${attachment.index}" }
                        repository.getMediaAttachment(itemId, source.id, attachment.index)?.let { name to it }
                    }
            }.onFailure { Timber.tag(TAG).w(it, "Embedded font load failed for immersive inbound playback") }
                .getOrDefault(emptyList())
            deferred.complete(fonts)
        }
    }

    private fun extractJellyfinItemId(url: String): UUID? =
        JELLYFIN_VIDEO_PATH_REGEX.find(url)?.groupValues?.get(1)
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    private fun isFontAttachment(fileName: String, mimeType: String, codec: String): Boolean {
        val name = fileName.lowercase()
        val mime = mimeType.lowercase()
        val value = codec.lowercase()
        return mime.contains("font") || mime.contains("truetype") || mime.contains("opentype") ||
            name.endsWith(".ttf") || name.endsWith(".otf") || name.endsWith(".ttc") ||
            value.contains("ttf") || value.contains("otf")
    }

    private fun requestFinish(reason: String) {
        if (finishRequested || isFinishing) return
        finishRequested = true
        Timber.tag(TAG).i("Immersive inbound player finishing: %s", reason)
        finish()
    }

    override fun onPause() {
        super.onPause()
        killProcessIfFinishing("onPause")
    }

    override fun onStop() {
        super.onStop()
        if (!isFinishing) player?.pause()
        killProcessIfFinishing("onStop")
    }

    override fun onDestroy() {
        ticker?.cancel()
        subtitleTicker?.cancel()
        cancelScheduledResume()
        bridge?.disconnect()
        bridge = null
        mediaSession?.release()
        mediaSession = null
        player?.removeListener(playerListener)
        player?.release()
        player = null
        libassRenderer?.destroy()
        libassRenderer = null
        xrSession = null
        super.onDestroy()
    }

    private fun killProcessIfFinishing(stage: String) {
        if (!isFinishing || !usedSurfaceEntity) return
        Timber.tag(TAG).w("Killing :xrplayer at %s after immersive inbound close", stage)
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    companion object {
        private const val TAG = "XrFCastInbound"
        private const val MAX_SCHEDULED_START_WAIT_MS = 4_000L
        private val JELLYFIN_VIDEO_PATH_REGEX = Regex(
            "/Videos/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})/",
        )

        fun createIntent(context: Context, request: ExternalStreamRequest): Intent =
            Intent(context, XrFCastInboundPlayerActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                ExternalStreamIntentCodec.putRequest(this, request)
            }
    }
}
