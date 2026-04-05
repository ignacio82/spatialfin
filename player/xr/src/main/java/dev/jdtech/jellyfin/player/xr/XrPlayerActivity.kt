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

    @Inject lateinit var voiceTelemetryStore: VoiceTelemetryStore

    private val viewModel: PlayerViewModel by viewModels()

    private var xrSession: Session? = null
    private var mediaSession: MediaSession? = null
    private var currentStereoMode: String = "mono"
    private var libassRenderer: LibassRenderer? = null

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

        val libassUsagePref = viewModel.appPreferences.getValue(viewModel.appPreferences.libassSubtitleUsage)
        val xrSubtitleSize = viewModel.appPreferences.getValue(viewModel.appPreferences.xrSubtitleSize)
        val libassFontLoader = itemId?.let { buildLibassFontLoader(it, maxBitrate) }
        Timber.i(
            "subtitle: libassUsagePref=%s libassAvailable=%b stereoMode=%s",
            libassUsagePref,
            LibassRenderer.isAvailable(),
            currentStereoMode,
        )

        if (!stereoPlayback) {
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
                        )
                    )
                    Timber.i("subtitle: LibassTextRenderer registered (pref=%s)", libassUsagePref)
                    // Do NOT add the default TextRenderer here. With parsing disabled,
                    // raw subtitle bytes (e.g. application/pgs) arrive in their original
                    // format which the modern TextRenderer cannot decode (it expects
                    // application/x-media3-cues). LibassTextRenderer handles all text-based
                    // formats (ASS/SSA/SRT/VTT). PGS (bitmap) is unsupported by libass and
                    // intentionally left unclaimed so ExoPlayer skips those tracks silently.
                } else {
                    // Stereo mode: no libass, parsing is enabled → subtitles arrive as
                    // application/x-media3-cues which the default TextRenderer can handle.
                    super.buildTextRenderers(context, output, outputLooper, extensionRendererMode, out)
                }
            }
        }.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
         .setEnableDecoderFallback(true)

        // Libass needs raw ASS/SSA packets, but ExoPlayer's fallback subtitle pipeline
        // needs extraction/parsing enabled so ASS can be converted into displayable cues.
        val mediaSourceFactory = DefaultMediaSourceFactory(this)
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
        
        viewModel.replacePlayer(player)

        // Initialize XR Session
        try {
            val result = Session.create(this)
            if (result is SessionCreateSuccess) {
                xrSession = result.session
                
                // Request full space mode for 3D support and immersive playback
                try {
                    val capabilities = xrSession?.scene?.spatialCapabilities
                    if (capabilities?.contains(androidx.xr.scenecore.SpatialCapability.SPATIAL_3D_CONTENT) == true) {
                        xrSession?.scene?.requestFullSpaceMode()
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Failed to request full space mode")
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "XR session not available")
        }

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
                                finish()
                            }
                        },
                        telemetryStore = voiceTelemetryStore,
                        onBackClick = { finish() }
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
        super.onPause()
        viewModel.updatePlaybackProgress()
    }

    override fun onStop() {
        super.onStop()
        viewModel.playWhenReady = viewModel.player.playWhenReady
        viewModel.player.playWhenReady = false
        try {
            val returnOnFinish = viewModel.appPreferences.getValue(
                viewModel.appPreferences.xrReturnHomeSpaceAfterPlayback
            )
            // Always return home when backgrounded (not finishing).
            // Also return home when finishing if the preference requests it.
            if (!isFinishing || returnOnFinish) {
                xrSession?.scene?.requestHomeSpaceMode()
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to request home space mode")
        }
        mediaSession?.release()
        mediaSession = null
    }

    override fun onDestroy() {
        super.onDestroy()
        xrSession = null
        libassRenderer?.destroy()
    }

    private fun buildLibassFontLoader(
        itemId: UUID,
        maxBitrate: Long?,
    ): () -> List<Pair<String, ByteArray>> = {
        runBlocking(Dispatchers.IO) {
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
            loadedFonts
        }
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
