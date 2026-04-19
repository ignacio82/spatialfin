package dev.jdtech.jellyfin.player.xr

import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Looper
import android.graphics.Color as AndroidColor
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.session.MediaSession
import androidx.xr.runtime.Session
import androidx.xr.runtime.SessionCreateSuccess
import androidx.xr.scenecore.scene
import dagger.hilt.android.AndroidEntryPoint
import dev.jdtech.jellyfin.core.diagnostics.PlayerLaunchBreadcrumbs
import dev.jdtech.jellyfin.models.SpatialFinEpisode
import dev.jdtech.jellyfin.models.SpatialFinItem
import dev.jdtech.jellyfin.models.SpatialFinMovie
import dev.jdtech.jellyfin.models.SpatialFinSeason
import dev.jdtech.jellyfin.models.SpatialFinShow
import dev.jdtech.jellyfin.player.local.presentation.PlayerViewModel
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.settings.voice.VoiceTelemetryStore
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import timber.log.Timber

@AndroidEntryPoint
class XrPlayerActivity : AppCompatActivity() {
    @Inject lateinit var repository: JellyfinRepository
    @Inject lateinit var serverDatabase: dev.jdtech.jellyfin.database.ServerDatabaseDao
    @Inject lateinit var contentKeyManager: dev.jdtech.jellyfin.security.ContentKeyManager

    @Inject lateinit var voiceTelemetryStore: VoiceTelemetryStore

    private val viewModel: PlayerViewModel by viewModels()

    private var xrSession: Session? = null
    private var mediaSession: MediaSession? = null
    private var currentStereoMode: String = "mono"
    private var libassRenderer: LibassRenderer? = null
    private var finishRequested = false
    private var homeSpaceRequestIssued = false

    companion object {
        fun createIntent(
            context: android.content.Context,
            itemId: UUID,
            itemKind: String,
            startFromBeginning: Boolean = false,
            stereoMode: String = "mono",
            mediaSourceIndex: Int? = null,
            maxBitrate: Long? = null,
            openSyncPlayDialogOnStart: Boolean = false,
        ): android.content.Intent {
            return android.content.Intent(context, XrPlayerActivity::class.java).apply {
                putExtra("itemId", itemId.toString())
                putExtra("itemKind", itemKind)
                putExtra("startFromBeginning", startFromBeginning)
                putExtra("stereoMode", stereoMode)
                putExtra("openSyncPlayDialogOnStart", openSyncPlayDialogOnStart)
                mediaSourceIndex?.let { putExtra("mediaSourceIndex", it) }
                maxBitrate?.let { putExtra("maxBitrate", it) }
            }
        }

        fun createIntentForLocalMedia(
            context: android.content.Context,
            mediaStoreId: Long,
            startFromBeginning: Boolean = false,
            stereoMode: String = "mono",
        ): android.content.Intent {
            return android.content.Intent(context, XrPlayerActivity::class.java).apply {
                putExtra("localMediaId", mediaStoreId)
                putExtra("startFromBeginning", startFromBeginning)
                putExtra("stereoMode", stereoMode)
            }
        }

        fun createIntentForNetworkMedia(
            context: android.content.Context,
            networkVideoId: String,
            startFromBeginning: Boolean = false,
            stereoMode: String = "mono",
        ): android.content.Intent {
            return android.content.Intent(context, XrPlayerActivity::class.java).apply {
                putExtra("networkVideoId", networkVideoId)
                putExtra("startFromBeginning", startFromBeginning)
                putExtra("stereoMode", stereoMode)
            }
        }

        fun createIntentForItem(
            context: android.content.Context,
            item: SpatialFinItem,
            startFromBeginning: Boolean = false,
        ): android.content.Intent? {
            val itemKind =
                when (item) {
                    is SpatialFinMovie -> "Movie"
                    is SpatialFinEpisode -> "Episode"
                    is SpatialFinSeason -> "Season"
                    is SpatialFinShow -> "Series"
                    else -> null
                } ?: return null

            return createIntent(
                context = context,
                itemId = item.id,
                itemKind = itemKind,
                startFromBeginning = startFromBeginning,
            )
        }
    }

    @OptIn(androidx.media3.common.util.UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        recordLaunchPhase("onCreate:start")

        // Enable wide color gamut for HDR support
        window.colorMode = android.content.pm.ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT

        // Ensure window is transparent so we can see the XR scene behind the UI
        window.setBackgroundDrawable(ColorDrawable(AndroidColor.TRANSPARENT))

        val extras = intent.extras
        val itemIdString = extras?.getString("itemId")
        val localMediaId = extras?.getLong("localMediaId")?.takeIf { it > 0L }
        val networkVideoId = extras?.getString("networkVideoId")
        if (itemIdString == null && localMediaId == null && networkVideoId == null) {
            Timber.w("Player launch rejected: missing itemId/localMediaId/networkVideoId extras")
            finish()
            return
        }
        val itemId =
            itemIdString?.let { rawId ->
                runCatching { UUID.fromString(rawId) }
                    .onFailure { Timber.e(it, "Player launch rejected: invalid itemId=%s", rawId) }
                    .getOrNull()
            }
        if (itemIdString != null && itemId == null) {
            finish()
            return
        }
        val itemKind = extras.getString("itemKind").orEmpty()
        val startFromBeginning = intent.getBooleanExtra("startFromBeginning", false)
        val mediaSourceIndex = if (intent.hasExtra("mediaSourceIndex")) intent.getIntExtra("mediaSourceIndex", -1).takeIf { it >= 0 } else null
        val maxBitrate = if (intent.hasExtra("maxBitrate")) intent.getLongExtra("maxBitrate", 0L).takeIf { it > 0L } else null
        val openSyncPlayDialogOnStart = intent.getBooleanExtra("openSyncPlayDialogOnStart", false)
        currentStereoMode = extras.getString("stereoMode") ?: "mono"
        val stereoPlayback = currentStereoMode == "sbs" || currentStereoMode == "top_bottom" || currentStereoMode == "multiview"
        recordLaunchPhase("onCreate:inputs-validated")

        recordLaunchPhase("onCreate:before-viewmodel-preferences")
        val libassUsagePref = viewModel.appPreferences.getValue(viewModel.appPreferences.libassSubtitleUsage)
        val xrSubtitleSize = viewModel.appPreferences.getValue(viewModel.appPreferences.xrSubtitleSize)
        // Preload embedded ASS fonts synchronously once at activity start. This blocks
        // the main thread briefly here (same as the other sync prefs/I/O already above),
        // but keeps the renderer thread free — previously the loader ran runBlocking()
        // inside LibassTextRenderer.ensureFontsLoaded, stalling ExoPlayer during track
        // initialization.
        val preloadedLibassFonts: List<Pair<String, ByteArray>> = itemId?.let {
            runCatching { runBlocking(Dispatchers.IO) { loadLibassFonts(it, maxBitrate) } }
                .onFailure { err -> Timber.w(err, "subtitle: preload embedded ASS fonts failed") }
                .getOrDefault(emptyList())
        }.orEmpty()
        val libassFontLoader: (() -> List<Pair<String, ByteArray>>)? =
            if (itemId != null) ({ preloadedLibassFonts }) else null
        Timber.i(
            "subtitle: libassUsagePref=%s libassAvailable=%b stereoMode=%s",
            libassUsagePref,
            LibassRenderer.isAvailable(),
            currentStereoMode,
        )
        recordLaunchPhase("onCreate:subtitle-prefs-ready")

        if (!stereoPlayback) {
            // Initial dimensions are a placeholder — we resize() below as soon as
            // ExoPlayer reports the actual Format size via onVideoSizeChanged so
            // libass renders at the native video resolution (pixel-perfect cues).
            libassRenderer =
                runCatching { LibassRenderer(1920, 1080).apply { init() } }
                    .onFailure { Timber.w(it, "subtitle: failed to initialize LibassRenderer") }
                    .getOrNull()
        }
        if (stereoPlayback) {
            Timber.i("subtitle: stereo playback detected — skipping libass renderer registration")
        }

        // Replace PlayerViewModel's ExoPlayer with one that uses LibassTextRenderer
        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildTextRenderers(
                context: android.content.Context,
                output: TextOutput,
                outputLooper: Looper,
                extensionRendererMode: Int,
                out: java.util.ArrayList<Renderer>
            ) {
                val renderer = libassRenderer
                if (renderer != null) {
                    out.add(
                        LibassTextRenderer(
                            renderer,
                            onTrackInitialized = {},
                            fontLoader = libassFontLoader,
                            usagePref = libassUsagePref,
                            srtFontSize = xrSubtitleSize,
                        ).apply {
                            onSubtitleText = viewModel::recordAssistantSubtitleLine
                        }
                    )
                    Timber.i("subtitle: LibassTextRenderer registered (pref=%s)", libassUsagePref)
                    // Do NOT add the default TextRenderer in this mode. With parsing disabled,
                    // LibassTextRenderer receives raw ASS/SRT/VTT bytes and handles them
                    // (including full-file sideloaded samples which it explodes into events).
                } else {
                    // Stereo mode: no libass, parsing is enabled → default TextRenderer handles cues.
                    super.buildTextRenderers(context, output, outputLooper, extensionRendererMode, out)
                }
            }
        }.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
         .setEnableDecoderFallback(true)

        // Subtitles are sideloaded via MediaItem.SubtitleConfiguration using Jellyfin's
        // per-stream delivery URL (see PlaylistManager). The custom ExtractorsFactory drops
        // embedded text tracks inside MKV containers so Media3's buggy zlib handling never
        // surfaces as garbage subtitles on screen.
        val extractorsFactory = dev.jdtech.jellyfin.player.core.extractor.mkv.ZlibSubtitleExtractorsFactory()
        val encryptedDataSourceFactory =
            dev.jdtech.jellyfin.player.core.security.EncryptedLocalDataSourceFactory(
                delegate = androidx.media3.datasource.DefaultDataSource.Factory(this),
                contentKeyManager = contentKeyManager,
                database = serverDatabase,
            )
        val mediaSourceFactory = DefaultMediaSourceFactory(encryptedDataSourceFactory, extractorsFactory)
            .experimentalParseSubtitlesDuringExtraction(stereoPlayback)
        if (stereoPlayback) {
            Timber.i("subtitle: stereo playback — using Media3 subtitle parsing/transcoding for fallback renderer")
        } else {
            Timber.i("subtitle: subtitle transcoding disabled — raw ASS bytes will flow to LibassTextRenderer")
        }

        val trackSelector = DefaultTrackSelector(this)
        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .setUsage(C.USAGE_MEDIA)
            .setSpatializationBehavior(C.SPATIALIZATION_BEHAVIOR_AUTO)
            .build()

        val player = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(audioAttributes, false)
            .setTrackSelector(trackSelector)
            .setSeekBackIncrementMs(viewModel.appPreferences.getValue(viewModel.appPreferences.playerSeekBackInc))
            .setSeekForwardIncrementMs(viewModel.appPreferences.getValue(viewModel.appPreferences.playerSeekForwardInc))
            .build()
        
        Timber.d("XrPlayer: step 1 — calling replacePlayer (libassRenderer=%b)", libassRenderer != null)
        viewModel.replacePlayer(player)

        // Match the libass render resolution to the actual video size. Without this
        // we render 1080p ASS cues and upscale them into the panel, which shows as
        // fuzzy edges on 4K content.
        if (libassRenderer != null) {
            player.addListener(
                object : androidx.media3.common.Player.Listener {
                    override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                        val w = videoSize.width
                        val h = videoSize.height
                        if (w > 0 && h > 0) {
                            Timber.i("subtitle: video size changed %dx%d — resizing libass renderer", w, h)
                            libassRenderer?.resize(w, h, w, h)
                        }
                    }
                }
            )
        }
        Timber.d("XrPlayer: step 2 — replacePlayer done")
        recordLaunchPhase("onCreate:player-replaced")

        // Initialize XR Session
        Timber.d("XrPlayer: step 3 — calling Session.create")
        try {
            val result = Session.create(this)
            Timber.d("XrPlayer: step 4 — Session.create returned %s", result?.javaClass?.simpleName)
            if (result is SessionCreateSuccess) {
                xrSession = result.session
                recordLaunchPhase("onCreate:session-created")

                // Request full space mode for 3D support and immersive playback
                try {
                    xrSession?.scene?.requestFullSpaceMode()
                    Timber.d("XrPlayer: step 5 — full space mode requested")
                } catch (e: Exception) {
                    Timber.w(e, "XrPlayer: step 5 FAILED — full space mode request failed")
                }
            } else {
                recordLaunchPhase("onCreate:session-unavailable")
                Timber.w("XrPlayer: step 4 — Session.create returned non-success: %s", result)
            }
        } catch (e: Exception) {
            Timber.e(e, "XrPlayer: step 3 FAILED — Session.create threw exception")
        }

        Timber.d("XrPlayer: step 6 — calling setContent")
        recordLaunchPhase("onCreate:setContent")
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val session = xrSession
                if (session != null) {
                    SpatialPlayerScreen(
                        viewModel = viewModel,
                        session = session,
                        initialStereoMode = currentStereoMode,
                        itemId = itemId,
                        localMediaId = localMediaId,
                        networkVideoId = networkVideoId,
                        itemKind = itemKind,
                        startFromBeginning = startFromBeginning,
                        mediaSourceIndex = mediaSourceIndex,
                        maxBitrate = maxBitrate,
                        openSyncPlayDialogOnStart = openSyncPlayDialogOnStart,
                        libassRenderer = libassRenderer,
                        onSearchQuery = { query -> repository.getSearchItems(query) },
                        onLaunchSearchResult = { item ->
                            createIntentForItem(this, item)?.let { launchIntent ->
                                startActivity(launchIntent)
                                requestFinish("launch-search-result")
                            }
                        },
                        telemetryStore = voiceTelemetryStore,
                        onBackClick = { requestFinish("xr-player-back") }
                    )
                } else {
                    // Fallback UI or close if session failed
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("XR Session not available", color = Color.White)
                        LaunchedEffect(Unit) {
                            delay(2000)
                            finish()
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        mediaSession?.release()
        mediaSession = MediaSession.Builder(this, viewModel.player).build()
    }

    override fun onResume() {
        super.onResume()
        viewModel.player.playWhenReady = viewModel.playWhenReady
    }

    override fun onPause() {
        if (finishRequested) {
            recordLaunchPhase("exit:onPause")
        }
        Timber.d(
            "XrPlayerActivity onPause mediaId=%s posMs=%d state=%d finishRequested=%b",
            viewModel.player.currentMediaItem?.mediaId,
            viewModel.player.currentPosition,
            viewModel.player.playbackState,
            finishRequested,
        )
        super.onPause()
        viewModel.updatePlaybackProgress()
    }

    override fun onStop() {
        if (finishRequested) {
            recordLaunchPhase("exit:onStop")
        }
        Timber.d(
            "XrPlayerActivity onStop mediaId=%s posMs=%d state=%d isFinishing=%b finishRequested=%b",
            viewModel.player.currentMediaItem?.mediaId,
            viewModel.player.currentPosition,
            viewModel.player.playbackState,
            isFinishing,
            finishRequested,
        )
        super.onStop()
        viewModel.playWhenReady = viewModel.player.playWhenReady
        viewModel.player.playWhenReady = false
        try {
            val returnOnFinish = viewModel.appPreferences.getValue(
                viewModel.appPreferences.xrReturnHomeSpaceAfterPlayback
            )
            // Only force a Home Space transition during an explicit exit flow or when a
            // finishing player should return home by preference. Let ordinary background
            // stops follow the XR runtime's own lifecycle to avoid teardown races.
            if (finishRequested || (isFinishing && returnOnFinish)) {
                requestHomeSpaceMode("onStop")
            } else {
                Timber.d(
                    "XrPlayerActivity skipping home space request onStop isFinishing=%b finishRequested=%b returnOnFinish=%b",
                    isFinishing,
                    finishRequested,
                    returnOnFinish,
                )
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to request home space mode")
        }
        mediaSession?.release()
        mediaSession = null
    }

    override fun onDestroy() {
        if (finishRequested) {
            recordLaunchPhase("exit:onDestroy")
        }
        Timber.d(
            "XrPlayerActivity onDestroy mediaId=%s posMs=%d state=%d error=%s finishRequested=%b",
            viewModel.player.currentMediaItem?.mediaId,
            viewModel.player.currentPosition,
            viewModel.player.playbackState,
            viewModel.player.playerError?.errorCodeName,
            finishRequested,
        )
        super.onDestroy()
        xrSession = null
        libassRenderer?.destroy()
        libassRenderer = null
        PlayerLaunchBreadcrumbs.clear(this)
    }

    private fun requestFinish(reason: String) {
        if (finishRequested || isFinishing) {
            Timber.d("XrPlayerActivity finish already requested reason=%s", reason)
            return
        }
        finishRequested = true
        recordLaunchPhase("exit:finish-requested:$reason")
        Timber.i(
            "XrPlayerActivity finish requested reason=%s mediaId=%s posMs=%d state=%d useLibass=%b",
            reason,
            viewModel.player.currentMediaItem?.mediaId,
            viewModel.player.currentPosition,
            viewModel.player.playbackState,
            libassRenderer != null,
        )
        runCatching { viewModel.updatePlaybackProgress() }
        // Do not force XR teardown before finish(). SpatialPlayerScreen.onDispose() already
        // detaches the SurfaceEntity, and preemptive home-space/surface work has proven
        // crash-prone on device during player shutdown.
        recordLaunchPhase("exit:finish-called:$reason")
        finish()
    }

    private fun recordLaunchPhase(phase: String) {
        PlayerLaunchBreadcrumbs.markPending(this, "XrPlayerActivity:$phase")
    }

    private fun requestHomeSpaceMode(stage: String) {
        if (homeSpaceRequestIssued) {
            Timber.d("XrPlayerActivity home space already requested; skipping stage=%s", stage)
            return
        }
        homeSpaceRequestIssued = true
        runCatching {
            xrSession?.scene?.requestHomeSpaceMode()
        }.onSuccess {
            recordLaunchPhase("exit:home-space-requested:$stage")
            Timber.d("XrPlayerActivity requested home space mode stage=%s", stage)
        }.onFailure { error ->
            homeSpaceRequestIssued = false
            Timber.w(error, "XrPlayerActivity failed to request home space mode stage=%s", stage)
        }
    }

    private suspend fun loadLibassFonts(
        itemId: UUID,
        maxBitrate: Long?,
    ): List<Pair<String, ByteArray>> {
        val sources = repository.getMediaSources(itemId, includePath = false, maxBitrate = maxBitrate)
        val fontAttachments = sources.flatMap { source ->
            source.mediaAttachments.map { attachment -> source to attachment }
        }.filter { (_, attachment) ->
            isFontAttachment(attachment.fileName, attachment.mimeType, attachment.codec)
        }
        Timber.i("subtitle: candidate embedded ASS fonts=%d itemId=%s", fontAttachments.size, itemId)
        val loadedFonts = fontAttachments.mapNotNull { (source, attachment) ->
            val fileName = attachment.fileName.ifBlank { "attachment-${source.id}-${attachment.index}" }
            repository.getMediaAttachment(itemId, source.id, attachment.index)?.let { bytes ->
                fileName to bytes
            }
        }
        Timber.i("subtitle: loaded embedded ASS fonts=%d itemId=%s", loadedFonts.size, itemId)
        return loadedFonts
    }

    private fun isFontAttachment(fileName: String, mimeType: String, codec: String): Boolean {
        val normalizedName = fileName.lowercase()
        val normalizedMime = mimeType.lowercase()
        val normalizedCodec = codec.lowercase()
        return normalizedMime.contains("font") ||
            normalizedMime.contains("truetype") ||
            normalizedMime.contains("opentype") ||
            normalizedName.endsWith(".ttf") ||
            normalizedName.endsWith(".otf") ||
            normalizedName.endsWith(".ttc") ||
            normalizedName.endsWith(".otc") ||
            normalizedCodec.contains("ttf") ||
            normalizedCodec.contains("otf")
    }
}
