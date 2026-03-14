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
import kotlinx.coroutines.delay
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
        ): android.content.Intent {
            return android.content.Intent(context, XrPlayerActivity::class.java).apply {
                putExtra("itemId", itemId.toString())
                putExtra("itemKind", itemKind)
                putExtra("startFromBeginning", startFromBeginning)
                putExtra("stereoMode", stereoMode)
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

        // Ensure window is transparent so we can see the XR scene behind the UI
        window.setBackgroundDrawable(ColorDrawable(AndroidColor.TRANSPARENT))

        val itemIdString = intent.extras?.getString("itemId")
        val localMediaId = intent.extras?.getLong("localMediaId")?.takeIf { it > 0L }
        if (itemIdString == null && localMediaId == null) {
            finish()
            return
        }
        val itemId = itemIdString?.let(UUID::fromString)
        val itemKind = intent.extras?.getString("itemKind") ?: ""
        val startFromBeginning = intent.extras!!.getBoolean("startFromBeginning")
        currentStereoMode = intent.extras?.getString("stereoMode") ?: "mono"
        val stereoPlayback = currentStereoMode == "sbs" || currentStereoMode == "top_bottom"

        val libassUsagePref = viewModel.appPreferences.getValue(viewModel.appPreferences.libassSubtitleUsage)
        Timber.i(
            "subtitle: libassUsagePref=%s libassAvailable=%b stereoMode=%s",
            libassUsagePref,
            LibassRenderer.isAvailable(),
            currentStereoMode,
        )

        if (!stereoPlayback) {
            libassRenderer = LibassRenderer(1920, 1080).apply { init() }
        } else {
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
                    out.add(LibassTextRenderer(renderer, onTrackInitialized = {}, usagePref = libassUsagePref))
                    Timber.i("subtitle: LibassTextRenderer registered (pref=%s)", libassUsagePref)
                }
                // Keep default TextRenderer as fallback for SRT/VTT/PGS tracks
                super.buildTextRenderers(context, output, outputLooper, extensionRendererMode, out)
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
            .setPauseAtEndOfMediaItems(true)
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
                        itemKind = itemKind,
                        startFromBeginning = startFromBeginning,
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
            if (!isFinishing) {
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
}
