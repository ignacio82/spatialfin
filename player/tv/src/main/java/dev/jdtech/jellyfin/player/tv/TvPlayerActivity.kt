package dev.jdtech.jellyfin.player.tv

import android.Manifest
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Looper
import android.util.Rational
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.lazy.items
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.skydoves.compose.stability.runtime.TraceRecomposition
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.player.core.audio.AudioPassthroughSinks
import dev.jdtech.jellyfin.player.session.voice.ActivePlayerSessionHolder
import dev.jdtech.jellyfin.player.local.R as LocalR
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.layout.ContentScale
import androidx.core.content.ContextCompat
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.session.MediaSession
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import dagger.hilt.android.AndroidEntryPoint
import dev.jdtech.jellyfin.deeplink.PlayDeepLink
import dev.jdtech.jellyfin.models.SpatialFinEpisode
import dev.jdtech.jellyfin.models.SpatialFinItem
import dev.jdtech.jellyfin.models.SpatialFinMovie
import dev.jdtech.jellyfin.models.SpatialFinSource
import dev.jdtech.jellyfin.player.beam.LibassRenderer
import dev.jdtech.jellyfin.player.beam.LibassSubtitleHelper
import dev.jdtech.jellyfin.player.beam.LibassTextRenderer
import dev.jdtech.jellyfin.player.xr.ProgressSection
import dev.jdtech.jellyfin.player.core.domain.models.PlayerChapter
import dev.jdtech.jellyfin.player.core.domain.models.PlayerItem
import dev.jdtech.jellyfin.player.local.domain.getTrackNames
import dev.jdtech.jellyfin.player.local.presentation.PlayerEvents
import dev.jdtech.jellyfin.player.local.presentation.PlayerViewModel
import dev.jdtech.jellyfin.settings.presentation.enums.QualityOption
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.ArrayList
import java.util.UUID

@AndroidEntryPoint
class TvPlayerActivity : AppCompatActivity() {
    @javax.inject.Inject lateinit var serverDatabase: dev.jdtech.jellyfin.database.ServerDatabaseDao
    @javax.inject.Inject lateinit var contentKeyManager: dev.jdtech.jellyfin.security.ContentKeyManager
    companion object {
        private const val EXTRA_ITEM_ID = "itemId"
        private const val EXTRA_ITEM_KIND = "itemKind"
        private const val EXTRA_START_FROM_BEGINNING = "startFromBeginning"
        private const val EXTRA_LOCAL_MEDIA_ID = "localMediaId"
        private const val EXTRA_NETWORK_VIDEO_ID = "networkVideoId"
        private const val EXTRA_UNIVERSAL_PLUGIN_ID = "universalPluginId"
        private const val EXTRA_UNIVERSAL_ITEM_ID = "universalItemId"
        private const val EXTRA_UNIVERSAL_VIDEO_URL = "universalVideoUrl"
        private const val EXTRA_UNIVERSAL_TITLE = "universalTitle"
        private const val EXTRA_MEDIA_SOURCE_INDEX = "mediaSourceIndex"
        private const val EXTRA_MAX_BITRATE = "maxBitrate"
        private const val EXTRA_TRAILER = "trailer"
        // Placeholder — TV player doesn't show the SyncPlay dialog yet. The
        // extra is threaded through so detail-screen SyncPlay buttons can flip
        // this on once the TV dialog lands.
        const val EXTRA_OPEN_SYNC_PLAY = "openSyncPlayDialog"
        const val EXTRA_START_POSITION_MS = "startPositionMs"

        fun createIntent(
            context: Context,
            itemId: UUID,
            itemKind: String,
            startFromBeginning: Boolean = false,
            mediaSourceIndex: Int? = null,
            maxBitrate: Long? = null,
            openSyncPlayDialogOnStart: Boolean = false,
            startPositionMs: Long? = null,
            trailer: Boolean = false,
        ): Intent =
            Intent(context, TvPlayerActivity::class.java).apply {
                putExtra(EXTRA_ITEM_ID, itemId.toString())
                putExtra(EXTRA_ITEM_KIND, itemKind)
                putExtra(EXTRA_START_FROM_BEGINNING, startFromBeginning)
                mediaSourceIndex?.let { putExtra(EXTRA_MEDIA_SOURCE_INDEX, it) }
                maxBitrate?.let { putExtra(EXTRA_MAX_BITRATE, it) }
                if (openSyncPlayDialogOnStart) putExtra(EXTRA_OPEN_SYNC_PLAY, true)
                startPositionMs?.let { putExtra(EXTRA_START_POSITION_MS, it) }
                if (trailer) putExtra(EXTRA_TRAILER, true)
            }

        fun createIntentForLocalMedia(
            context: Context,
            mediaStoreId: Long,
            startFromBeginning: Boolean = false,
        ): Intent =
            Intent(context, TvPlayerActivity::class.java).apply {
                putExtra(EXTRA_LOCAL_MEDIA_ID, mediaStoreId)
                putExtra(EXTRA_START_FROM_BEGINNING, startFromBeginning)
            }

        fun createIntentForNetworkMedia(
            context: Context,
            networkVideoId: String,
            startFromBeginning: Boolean = false,
        ): Intent =
            Intent(context, TvPlayerActivity::class.java).apply {
                putExtra(EXTRA_NETWORK_VIDEO_ID, networkVideoId)
                putExtra(EXTRA_START_FROM_BEGINNING, startFromBeginning)
            }

        fun createIntentForUniversalMedia(
            context: Context,
            pluginId: String,
            itemId: String,
            videoUrl: String,
            title: String,
        ): Intent =
            Intent(context, TvPlayerActivity::class.java).apply {
                putExtra(EXTRA_UNIVERSAL_PLUGIN_ID, pluginId)
                putExtra(EXTRA_UNIVERSAL_ITEM_ID, itemId)
                putExtra(EXTRA_UNIVERSAL_VIDEO_URL, videoUrl)
                putExtra(EXTRA_UNIVERSAL_TITLE, title)
            }

        fun createIntentForSpatialItem(
            context: Context,
            item: SpatialFinItem,
            startFromBeginning: Boolean = false,
            openSyncPlayDialogOnStart: Boolean = false,
            startPositionMs: Long? = null,
            trailer: Boolean = false,
            maxBitrate: Long? = null,
        ): Intent? =
            when (item) {
                is SpatialFinMovie ->
                    createIntent(
                        context = context,
                        itemId = item.id,
                        itemKind = "Movie",
                        startFromBeginning = startFromBeginning,
                        openSyncPlayDialogOnStart = openSyncPlayDialogOnStart,
                        startPositionMs = startPositionMs,
                        trailer = trailer,
                        maxBitrate = maxBitrate,
                    )
                is SpatialFinEpisode ->
                    createIntent(
                        context = context,
                        itemId = item.id,
                        itemKind = "Episode",
                        startFromBeginning = startFromBeginning,
                        openSyncPlayDialogOnStart = openSyncPlayDialogOnStart,
                        startPositionMs = startPositionMs,
                        trailer = trailer,
                        maxBitrate = maxBitrate,
                    )
                else -> null
            }
    }

    private val viewModel: PlayerViewModel by viewModels()
    private var mediaSession: MediaSession? = null
    private var libassRenderer: LibassRenderer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()

        // ACTION_VIEW with a `spatialfin://play?id=...` URI lands here from the
        // TV Launcher's Watch Next cards (and, eventually, Global Search /
        // AppFunctions). Fold parsed fields into the same locals the explicit
        // `createIntent` path populates, so the rest of onCreate doesn't branch.
        val deepLink =
            if (Intent.ACTION_VIEW == intent.action) PlayDeepLink.parse(intent.data) else null

        val startFromBeginning = intent.getBooleanExtra(EXTRA_START_FROM_BEGINNING, false)
        val itemIdString = deepLink?.itemId?.toString() ?: intent.getStringExtra(EXTRA_ITEM_ID)
        val itemKind = deepLink?.kind ?: intent.getStringExtra(EXTRA_ITEM_KIND)
        val localMediaId = intent.getLongExtra(EXTRA_LOCAL_MEDIA_ID, 0L).takeIf { it > 0L }
        val networkVideoId = intent.getStringExtra(EXTRA_NETWORK_VIDEO_ID)
        val universalPluginId = intent.getStringExtra(EXTRA_UNIVERSAL_PLUGIN_ID)
        val universalItemId = intent.getStringExtra(EXTRA_UNIVERSAL_ITEM_ID)
        val universalVideoUrl = intent.getStringExtra(EXTRA_UNIVERSAL_VIDEO_URL)
        val universalTitle = intent.getStringExtra(EXTRA_UNIVERSAL_TITLE)
        val mediaSourceIndex =
            if (intent.hasExtra(EXTRA_MEDIA_SOURCE_INDEX)) {
                intent.getIntExtra(EXTRA_MEDIA_SOURCE_INDEX, -1).takeIf { it >= 0 }
            } else {
                null
            }
        val maxBitrate =
            if (intent.hasExtra(EXTRA_MAX_BITRATE)) {
                intent.getLongExtra(EXTRA_MAX_BITRATE, 0L).takeIf { it > 0L }
            } else {
                null
            }
        val startPositionMs =
            deepLink?.startPositionMs
                ?: if (intent.hasExtra(EXTRA_START_POSITION_MS)) {
                    intent.getLongExtra(EXTRA_START_POSITION_MS, 0L).takeIf { it > 0L }
                } else {
                    null
                }

        Timber.i(
            "TvPlayerActivity launch itemId=%s itemKind=%s localMediaId=%s networkVideoId=%s universalPluginId=%s universalItemId=%s startFromBeginning=%b mediaSourceIndex=%s maxBitrate=%s",
            itemIdString,
            itemKind,
            localMediaId,
            networkVideoId,
            universalPluginId,
            universalItemId,
            startFromBeginning,
            mediaSourceIndex,
            maxBitrate,
        )

        replacePlayerForTvSubtitles()

        when {
            localMediaId != null -> {
                viewModel.initializeLocalPlayer(
                    localMediaId = localMediaId,
                    startFromBeginning = startFromBeginning,
                )
            }
            !networkVideoId.isNullOrBlank() -> {
                viewModel.initializeNetworkPlayer(
                    networkVideoId = networkVideoId,
                    startFromBeginning = startFromBeginning,
                )
            }
            !universalPluginId.isNullOrBlank() &&
                !universalItemId.isNullOrBlank() &&
                !universalVideoUrl.isNullOrBlank() -> {
                viewModel.initializePlayerForUniversal(
                    pluginId = universalPluginId,
                    itemId = universalItemId,
                    videoUrl = universalVideoUrl,
                    title = universalTitle ?: "Unknown",
                )
            }
            !itemIdString.isNullOrBlank() && !itemKind.isNullOrBlank() -> {
                val itemId = runCatching { UUID.fromString(itemIdString) }.getOrNull()
                if (itemId == null) {
                    finish()
                    return
                }
                viewModel.initializePlayer(
                    itemId = itemId,
                    itemKind = itemKind,
                    startFromBeginning = startFromBeginning,
                    mediaSourceIndex = mediaSourceIndex,
                    maxBitrate = maxBitrate,
                    startPositionMs = startPositionMs,
                )
            }
            else -> {
                finish()
                return
            }
        }

        updatePipParams()

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                var isPipMode by remember { mutableStateOf(false) }
                DisposableEffect(Unit) {
                    val listener = androidx.core.util.Consumer<androidx.core.app.PictureInPictureModeChangedInfo> { info ->
                        isPipMode = info.isInPictureInPictureMode
                    }
                    addOnPictureInPictureModeChangedListener(listener)
                    onDispose { removeOnPictureInPictureModeChangedListener(listener) }
                }
                TvPlayerScreen(
                    viewModel = viewModel,
                    libassRenderer = libassRenderer,
                    isPipMode = isPipMode,
                    onBackClick = { finish() },
                    onSelectQuality = { bitrate ->
                        val itemId = viewModel.uiState.value.currentItemId?.let(UUID::fromString)
                        val itemKindValue = viewModel.uiState.value.currentItemKind
                        if (itemId != null && !itemKindValue.isNullOrBlank()) {
                            viewModel.changeQuality(itemId, itemKindValue, bitrate)
                        }
                    },
                    onSelectSource = { sourceIndex ->
                        val itemId = viewModel.uiState.value.currentItemId?.let(UUID::fromString)
                        val itemKindValue = viewModel.uiState.value.currentItemKind
                        if (itemId != null && !itemKindValue.isNullOrBlank()) {
                            viewModel.changeSource(itemId, itemKindValue, sourceIndex)
                        }
                    },
                )
            }
        }
    }

    private fun updatePipParams() {
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .setAutoEnterEnabled(true)
            .build()
        setPictureInPictureParams(params)
    }

    override fun onStart() {
        super.onStart()
        mediaSession?.release()
        mediaSession = MediaSession.Builder(this, viewModel.player).build()
    }

    override fun onResume() {
        super.onResume()
        // If the first frame hasn't rendered, we may be held back by the audio gate.
        // We restore playWhenReady from the ViewModel; if the gate is active, the
        // DisposableEffect inside TvPlayerScreen will re-intercept this and
        // re-arm gateWantsPlay.
        viewModel.player.playWhenReady = viewModel.playWhenReady
    }

    override fun onPause() {
        super.onPause()
        if (isInPictureInPictureMode) return
        viewModel.updatePlaybackProgress()
    }

    override fun onStop() {
        super.onStop()
        if (isInPictureInPictureMode) return
        viewModel.playWhenReady = viewModel.player.playWhenReady
        viewModel.player.playWhenReady = false
        mediaSession?.release()
        mediaSession = null
    }

    override fun onDestroy() {
        super.onDestroy()
        libassRenderer?.destroy()
        libassRenderer = null
    }

    @OptIn(UnstableApi::class)
    private fun replacePlayerForTvSubtitles() {
        val libassUsagePref = viewModel.appPreferences.getValue(viewModel.appPreferences.libassSubtitleUsage)
        // xrSubtitleSize defaults to 72 because XR renders subtitles onto a
        // virtual panel where that size reads well. On a real 1080p TV that's
        // oversized — scale down to roughly 55% so the on-screen text is a
        // comfortable reading size without changing the user's chosen pref.
        val subtitleTextSize = (viewModel.appPreferences.getValue(viewModel.appPreferences.xrSubtitleSize) * 55 / 100)
            .coerceAtLeast(18)
        val subtitleTextColor = viewModel.appPreferences.getValue(viewModel.appPreferences.subtitleTextColor)
        val subtitleBackgroundColor = viewModel.appPreferences.getValue(viewModel.appPreferences.subtitleBackgroundColor)
        libassRenderer =
            runCatching {
                if (LibassRenderer.isAvailable()) {
                    LibassRenderer(1920, 1080).apply { init() }
                } else {
                    null
                }
            }.getOrNull()

        val renderersFactory =
            object : DefaultRenderersFactory(this) {
                // Honour the audio-passthrough preference for every source, not just
                // Jellyfin streams: local, SMB/NFS and downloaded files run through the
                // same sink. AUTO returns null and keeps Media3's own route-derived sink.
                override fun buildAudioSink(
                    context: Context,
                    enableFloatOutput: Boolean,
                    enableAudioTrackPlaybackParams: Boolean,
                ): AudioSink =
                    AudioPassthroughSinks.buildOverride(
                        context = context,
                        appPreferences = viewModel.appPreferences,
                        enableFloatOutput = enableFloatOutput,
                        enableAudioTrackPlaybackParams = enableAudioTrackPlaybackParams,
                    ) ?: checkNotNull(
                        super.buildAudioSink(
                            context,
                            enableFloatOutput,
                            enableAudioTrackPlaybackParams,
                        ),
                    )

                override fun buildTextRenderers(
                    context: Context,
                    output: TextOutput,
                    outputLooper: Looper,
                    extensionRendererMode: Int,
                    out: ArrayList<Renderer>,
                ) {
                    libassRenderer?.let { renderer ->
                        out.add(
                            LibassTextRenderer(
                                libassRenderer = renderer,
                                onTrackInitialized = {},
                                usagePref = libassUsagePref,
                                srtFontSize = subtitleTextSize.coerceIn(18, 72),
                                subtitleTextColor = subtitleTextColor,
                                subtitleBackgroundColor = subtitleBackgroundColor,
                            )
                        )
                    }
                    super.buildTextRenderers(context, output, outputLooper, extensionRendererMode, out)
                    out.filterIsInstance<androidx.media3.exoplayer.text.TextRenderer>().forEach {
                        it.experimentalSetLegacyDecodingEnabled(true)
                        val index = out.indexOf(it)
                        if (index != -1) {
                            out[index] = dev.jdtech.jellyfin.player.core.FallbackTextRenderer(it)
                        }
                    }
                }
            }.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
                .setEnableDecoderFallback(true)

        val encryptedDataSourceFactory =
            dev.jdtech.jellyfin.player.core.security.EncryptedLocalDataSourceFactory(
                delegate = androidx.media3.datasource.DefaultDataSource.Factory(this, dev.jdtech.jellyfin.player.core.external.PluginHttpDataSourceFactory.create()),
                contentKeyManager = contentKeyManager,
                database = serverDatabase,
            )
        val mediaSourceFactory =
            DefaultMediaSourceFactory(encryptedDataSourceFactory)
                .experimentalParseSubtitlesDuringExtraction(true)

        val trackSelector = DefaultTrackSelector(this)
        trackSelector.setParameters(trackSelector.buildUponParameters().setPreferredTextLanguage(null))
        val audioAttributes =
            AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .setUsage(C.USAGE_MEDIA)
                .setSpatializationBehavior(C.SPATIALIZATION_BEHAVIOR_AUTO)
                .build()

        val player =
            ExoPlayer.Builder(this, renderersFactory)
                .setMediaSourceFactory(mediaSourceFactory)
                .setTrackSelector(trackSelector)
                .setAudioAttributes(audioAttributes, false)
                .setSeekBackIncrementMs(viewModel.appPreferences.getValue(viewModel.appPreferences.playerSeekBackInc))
                .setSeekForwardIncrementMs(viewModel.appPreferences.getValue(viewModel.appPreferences.playerSeekForwardInc))
                .build()
        viewModel.replacePlayer(player)
    }
}

@Composable
@TraceRecomposition(tag = "tv-player", threshold = 3)
private fun TvPlayerScreen(
    viewModel: PlayerViewModel,
    libassRenderer: LibassRenderer?,
    isPipMode: Boolean,
    onBackClick: () -> Unit,
    onSelectQuality: (Long) -> Unit,
    onSelectSource: (Int) -> Unit,
) {
    val player = viewModel.player
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val subtitleTextColor = remember(viewModel.appPreferences) { viewModel.appPreferences.getValue(viewModel.appPreferences.subtitleTextColor) }
    val subtitleBackgroundColor = remember(viewModel.appPreferences) { viewModel.appPreferences.getValue(viewModel.appPreferences.subtitleBackgroundColor) }
    // Scale XR's default 72sp down to ~55% for 1080p TVs (see replacePlayerForTvSubtitles).
    val subtitleSizeSp = remember(viewModel.appPreferences) {
        ((viewModel.appPreferences.getValue(viewModel.appPreferences.xrSubtitleSize) * 55) / 100)
            .coerceIn(18, 72).toFloat()
    }
    val context = LocalContext.current
    val openSyncPlayOnStart = remember {
        (context as? TvPlayerActivity)?.intent
            ?.getBooleanExtra(TvPlayerActivity.EXTRA_OPEN_SYNC_PLAY, false) ?: false
    }
    var activeDialog by remember {
        mutableStateOf<TvPlayerDialog?>(
            if (openSyncPlayOnStart) TvPlayerDialog.SyncPlay else null,
        )
    }
    val syncPlayState by viewModel.syncPlayState.collectAsStateWithLifecycle()
    LaunchedEffect(openSyncPlayOnStart) {
        if (openSyncPlayOnStart) viewModel.refreshSyncPlayGroups()
    }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(player.isPlaying) }
    // Until the first video frame is actually on-screen we keep audio held back
    // (see the first-frame gate below) and show a loading state instead of the
    // misleading Play overlay.
    var firstFrameRendered by remember { mutableStateOf(player.isPlaying) }
    var availableSources by remember { mutableStateOf<List<SpatialFinSource>>(emptyList()) }
    var sourcesLoading by remember { mutableStateOf(false) }
    var useLibass by remember { mutableStateOf(false) }
    var libassBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var controlsVisible by remember { mutableStateOf(true) }
    val density = LocalDensity.current
    val latestOnBack by rememberUpdatedState(onBackClick)
    val playPauseFocusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    var controlsHideJob by remember { mutableStateOf<Job?>(null) }

    fun revealControls() {
        controlsVisible = true
        controlsHideJob?.cancel()
        if (isPlaying && activeDialog == null) {
            controlsHideJob =
                scope.launch {
                    delay(4500L)
                    controlsVisible = false
                }
        }
    }

    fun togglePlaybackFromRemote() {
        if (player.playWhenReady || player.isPlaying) {
            player.pause()
        } else {
            player.play()
        }
    }

    LaunchedEffect(player.currentTracks) {
        val libassPref = viewModel.appPreferences.getValue(viewModel.appPreferences.libassSubtitleUsage)
        useLibass = LibassSubtitleHelper.shouldUseLibass(player, libassPref)
    }

    LaunchedEffect(uiState.currentItemId, uiState.currentItemKind) {
        val itemId = uiState.currentItemId?.let(UUID::fromString)
        val itemKind = uiState.currentItemKind
        if (itemId == null || itemKind.isNullOrBlank()) {
            availableSources = emptyList()
            sourcesLoading = false
            return@LaunchedEffect
        }

        sourcesLoading = true
        availableSources =
            runCatching {
                viewModel.repository.getMediaSources(itemId = itemId, includePath = true)
            }.getOrDefault(emptyList())
        sourcesLoading = false
    }

    LaunchedEffect(Unit) {
        viewModel.eventsChannelFlow.collect { event ->
            if (event is PlayerEvents.NavigateBack) {
                latestOnBack()
            }
        }
    }

    LaunchedEffect(isPlaying, activeDialog, controlsVisible) {
        controlsHideJob?.cancel()
        if (controlsVisible && isPlaying && activeDialog == null) {
            controlsHideJob =
                scope.launch {
                    delay(4500L)
                    controlsVisible = false
                }
        }
    }

    LaunchedEffect(firstFrameRendered, controlsVisible, activeDialog) {
        // The controls overlay (which owns playPauseFocusRequester) is not
        // composed until the first frame is rendered — only request focus once
        // it actually exists.
        if (firstFrameRendered && controlsVisible && activeDialog == null) {
            delay(50L)
            runCatching { playPauseFocusRequester.requestFocus() }
        }
    }

    // First-frame audio gate. ExoPlayer's video renderer reports itself "ready"
    // even before its surface is attached, so without this the player reaches
    // STATE_READY and starts audio while the screen is still black and the
    // overlay shows a stale Play icon. We hold playWhenReady=false until the
    // first video frame is rendered (ExoPlayer still decodes/presents that frame
    // while paused), then resume so audio and video start together. The gate is
    // one-shot — normal pause/resume after startup is untouched.
    val gateWantsPlay = remember { mutableStateOf(false) }
    DisposableEffect(player, firstFrameRendered) {
        val listener =
            object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) {
                    isPlaying = playing
                }

                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    if (!firstFrameRendered && playWhenReady) {
                        gateWantsPlay.value = true
                        player.playWhenReady = false
                    }
                }

                override fun onTracksChanged(tracks: Tracks) {
                    // Audio-only stream: no frame will ever render, so release
                    // the gate as soon as we know there is no video track.
                    // We require non-empty groups and at least one supported audio
                    // group to avoid releasing on the empty Tracks emitted during
                    // early preparation.
                    val hasVideo = tracks.groups.any { it.type == C.TRACK_TYPE_VIDEO }
                    val hasAudio = tracks.groups.any { it.type == C.TRACK_TYPE_AUDIO && it.isSupported }
                    if (!firstFrameRendered && !hasVideo && hasAudio) {
                        firstFrameRendered = true
                        if (gateWantsPlay.value) {
                            gateWantsPlay.value = false
                            player.play()
                        }
                    }
                }

                override fun onRenderedFirstFrame() {
                    if (!firstFrameRendered) {
                        firstFrameRendered = true
                        if (gateWantsPlay.value) {
                            gateWantsPlay.value = false
                            player.play()
                        }
                    }
                }
            }
        // Reconcile playback state on entry and after resume (keyed on firstFrameRendered).
        // If the activity was backgrounded and resumed, ExoPlayer may have
        // playWhenReady=true but audio is still held back; we re-intercept it here.
        if (!firstFrameRendered && player.playWhenReady) {
            gateWantsPlay.value = true
            player.playWhenReady = false
        }
        if (player.isPlaying) {
            firstFrameRendered = true
        }
        isPlaying = player.isPlaying
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // Last-resort anti-hang backstop: if no first frame arrives in a long
    // window (e.g. an unexpected surface problem) release the gate so playback
    // can never be silently stuck behind it.
    LaunchedEffect(player) {
        delay(15_000L)
        if (!firstFrameRendered) {
            firstFrameRendered = true
            if (gateWantsPlay.value) {
                gateWantsPlay.value = false
                player.play()
            }
        }
    }

    LaunchedEffect(player) {
        while (true) {
            currentPosition = player.currentPosition
            duration = player.duration.coerceAtLeast(0L)
            viewModel.updateCurrentSegment()
            delay(500L)
        }
    }

    // --- Voice assistant (design ui_kits/tv/TvPlayerScreen.jsx) ---
    // Brings the same assistant the phone + XR players have to the 10-foot
    // surface: the mic control captures speech, commands act on the player, and
    // questions are answered by the on-device/cloud chat engine + spoken back.
    var voiceSearchResults by remember { mutableStateOf<Pair<String, List<SpatialFinItem>>?>(null) }
    val voice = remember(viewModel, player) {
        TvPlayerVoiceController(
            appContext = context.applicationContext,
            viewModel = viewModel,
            player = player,
            scope = scope,
            onNavigateBack = { latestOnBack() },
            onControlsVisibility = { vis -> controlsVisible = vis },
            onLaunchItem = { item ->
                TvPlayerActivity.createIntentForSpatialItem(context, item)?.let(context::startActivity)
            },
            onShowSearchResults = { query, results -> voiceSearchResults = query to results },
            onOpenSyncPlay = { activeDialog = TvPlayerDialog.SyncPlay; viewModel.refreshSyncPlayGroups() },
        )
    }
    DisposableEffect(voice) {
        val activeSession = ActivePlayerSessionHolder.ActiveSession(
            controller = voice.controller,
            snapshotProvider = { buildTvPlayerSnapshot(uiState, player, currentPosition, duration, controlsVisible) },
            chaptersProvider = {
                viewModel.uiState.value.currentChapters.mapIndexed { index, chapter ->
                    (chapter.name ?: "Chapter ${index + 1}") to chapter.startPosition
                }
            },
            volumeProvider = { player.volume },
            speedProvider = { player.playbackParameters.speed },
            streamUrlProvider = { player.currentMediaItem?.localConfiguration?.uri?.toString() },
            currentItemIdProvider = { viewModel.uiState.value.currentItemId },
            // Resume position comes from Jellyfin's own playback state, which is what
            // "Continue Watching" means; startPositionMs is advisory only.
            onPlayMediaItem = { itemId, _, _ -> voice.playItemById(itemId) },
            onVoiceCommand = { transcript ->
                voice.handleVoiceTranscript(
                    transcript,
                    buildTvPlayerSnapshot(uiState, player, currentPosition, duration, controlsVisible),
                )
            },
        )
        ActivePlayerSessionHolder.bind(activeSession)
        onDispose {
            ActivePlayerSessionHolder.unbind(activeSession)
            voice.destroy()
        }
    }
    val voiceUi by voice.ui.collectAsStateWithLifecycle()
    val voicePartial by voice.partialTranscript.collectAsStateWithLifecycle()
    val latestStartVoice by rememberUpdatedState({
        voice.toggle(buildTvPlayerSnapshot(uiState, player, currentPosition, duration, controlsVisible))
    })
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) latestStartVoice() }
    val onVoice = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            latestStartVoice()
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // --- Up Next autoplay card (design components/tv/UpNextCard.jsx) ---
    // Mirrors the XR next-episode panel: offered during the last 2 minutes of an
    // episode when the playlist has a queued next item. "Play Now" skips ahead
    // immediately; "Cancel" dismisses (ExoPlayer still auto-advances at the
    // natural end — the existing behavior is untouched). Re-arms on item change.
    var nextUpDismissed by remember { mutableStateOf(false) }
    LaunchedEffect(uiState.currentItemId ?: uiState.currentItemTitle) { nextUpDismissed = false }
    val nextEpisode = uiState.nextEpisode
    val upNextRemainingMs = (duration - currentPosition).coerceAtLeast(0L)
    val showUpNext =
        !nextUpDismissed &&
            nextEpisode != null &&
            duration > TV_NEXT_EPISODE_THRESHOLD_MS &&
            upNextRemainingMs in 0L..TV_NEXT_EPISODE_THRESHOLD_MS
    val upNextFocusRequester = remember { FocusRequester() }
    LaunchedEffect(showUpNext, controlsVisible) {
        if (showUpNext && !controlsVisible) {
            delay(50L)
            runCatching { upNextFocusRequester.requestFocus() }
        }
    }
    BackHandler(enabled = showUpNext && activeDialog == null && !controlsVisible) {
        nextUpDismissed = true
    }

    BackHandler(enabled = activeDialog != null || controlsVisible) {
        when {
            activeDialog != null -> activeDialog = null
            controlsVisible -> controlsVisible = false
        }
    }

    val visibleSkipPromptSegment =
        uiState.currentSegment.takeIf {
            !controlsVisible &&
                activeDialog == null &&
                !isPipMode &&
                !showUpNext
        }

    BoxWithConstraints(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .onPreviewKeyEvent { keyEvent ->
                    val native = keyEvent.nativeKeyEvent
                    if (native.action != AndroidKeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                    when (native.keyCode) {
                        AndroidKeyEvent.KEYCODE_DPAD_LEFT,
                        AndroidKeyEvent.KEYCODE_SYSTEM_NAVIGATION_LEFT -> {
                            if (!showUpNext && !controlsVisible && activeDialog == null) {
                                player.seekBack()
                                true
                            } else {
                                false
                            }
                        }
                        AndroidKeyEvent.KEYCODE_DPAD_RIGHT,
                        AndroidKeyEvent.KEYCODE_SYSTEM_NAVIGATION_RIGHT -> {
                            if (!showUpNext && !controlsVisible && activeDialog == null) {
                                player.seekForward()
                                true
                            } else {
                                false
                            }
                        }
                        AndroidKeyEvent.KEYCODE_MEDIA_REWIND -> {
                            player.seekBack()
                            true
                        }
                        AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                            player.seekForward()
                            true
                        }
                        AndroidKeyEvent.KEYCODE_DPAD_UP,
                        AndroidKeyEvent.KEYCODE_SYSTEM_NAVIGATION_UP,
                        AndroidKeyEvent.KEYCODE_DPAD_DOWN,
                        AndroidKeyEvent.KEYCODE_SYSTEM_NAVIGATION_DOWN -> {
                            if (!showUpNext && !controlsVisible && activeDialog == null) {
                                revealControls()
                                true
                            } else {
                                false
                            }
                        }
                        AndroidKeyEvent.KEYCODE_DPAD_CENTER,
                        AndroidKeyEvent.KEYCODE_ENTER,
                        AndroidKeyEvent.KEYCODE_NUMPAD_ENTER,
                        AndroidKeyEvent.KEYCODE_SPACE -> {
                            if (!showUpNext && !controlsVisible && activeDialog == null) {
                                if (native.repeatCount == 0) {
                                    val segment = visibleSkipPromptSegment
                                    if (segment != null) {
                                        viewModel.skipSegment(segment)
                                    } else {
                                        togglePlaybackFromRemote()
                                    }
                                }
                                true
                            } else {
                                false
                            }
                        }
                        // Don't intercept Back/Escape — let BackHandler decide:
                        // first press hides any open dialog or the controls, second
                        // press (nothing to close) falls through to the system which
                        // finishes the activity. Intercepting here to "reveal
                        // controls" trapped the user with no way out of playback.
                        AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                        AndroidKeyEvent.KEYCODE_HEADSETHOOK -> {
                            if (native.repeatCount == 0) togglePlaybackFromRemote()
                            true
                        }
                        AndroidKeyEvent.KEYCODE_MEDIA_PLAY -> {
                            player.play()
                            true
                        }
                        AndroidKeyEvent.KEYCODE_MEDIA_PAUSE -> {
                            player.pause()
                            true
                        }
                        else -> false
                    }
                },
    ) {
        val renderWidth = with(density) { maxWidth.roundToPx() }.coerceAtLeast(1280)
        val renderHeight = with(density) { maxHeight.roundToPx() }.coerceAtLeast(720)
        LaunchedEffect(renderWidth, renderHeight, player.videoSize.width, player.videoSize.height) {
            val renderer = libassRenderer ?: return@LaunchedEffect
            renderer.resize(
                renderWidth,
                renderHeight,
                player.videoSize.width,
                player.videoSize.height,
            )
        }

        // Subtitles are visible only when libass is the active renderer AND the user hasn't
        // turned captions off ("None" in the CC dialog → visualSubtitlesEnabled=false). libass
        // retains the parsed track in native memory, so deselecting the ExoPlayer text track does
        // NOT stop it drawing — we must gate the overlay (and render loop) on this flag. Without
        // it, captions can never be turned off. (Same fix as Beam / XR.)
        val subtitlesVisible = useLibass && uiState.visualSubtitlesEnabled
        LaunchedEffect(subtitlesVisible) {
            val renderer = libassRenderer ?: return@LaunchedEffect
            if (!subtitlesVisible) {
                // Captions off: drop any lingering frame so the retained track stops showing.
                libassBitmap = null
                return@LaunchedEffect
            }
            while (true) {
                val result = renderer.renderFrame(player.currentPosition)
                libassBitmap =
                    if (result.hasContent) {
                        result.bitmap
                    } else {
                        null
                    }
                delay(33L)
            }
        }

        val context = LocalContext.current
        val playerView =
            remember {
                PlayerView(context).apply {
                    this.player = player
                    useController = false
                    setBackgroundColor(android.graphics.Color.BLACK)
                    // Playback start is owned by the ViewModel and the
                    // first-frame audio gate above — do NOT call player.play()
                    // here, it would let audio race ahead of the surface.
                }
            }

        DisposableEffect(player) {
            playerView.player = player
            playerView.subtitleView?.setStyle(
                CaptionStyleCompat(
                    subtitleTextColor,
                    subtitleBackgroundColor,
                    android.graphics.Color.TRANSPARENT,
                    CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                    android.graphics.Color.BLACK,
                    null,
                )
            )
            playerView.subtitleView?.setFixedTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, subtitleSizeSp)
            onDispose {
                playerView.player = null
            }
        }

        AndroidView(
            factory = { playerView },
            modifier =
                Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {
                            revealControls()
                            if (isPlaying) player.pause() else player.play()
                        },
                    ),
        )

        if (subtitlesVisible) {
            libassBitmap?.let { bitmap ->
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Subtitles",
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // While the first video frame is still pending (initial buffering /
        // surface attach) show a spinner instead of the misleading Play
        // overlay; the controls and pause overlay below are also held back.
        if (!firstFrameRendered && !isPipMode && uiState.playbackError == null) {
            androidx.compose.material3.CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color.White,
            )
        }

        // Playback failure surface (e.g. an 8K stream a 4K decoder can't handle):
        // without it the screen would just stay black with the spinner. Blocks the
        // center, offers a single action that leaves the player.
        uiState.playbackError?.let { error ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.85f))
                    .padding(48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                androidx.compose.material3.Text(
                    text = stringResource(LocalR.string.player_error_title),
                    color = Color.White,
                    style = androidx.compose.material3.MaterialTheme.typography.headlineSmall,
                )
                androidx.compose.foundation.layout.Spacer(Modifier.height(12.dp))
                androidx.compose.material3.Text(
                    text = error.message,
                    color = Color.White.copy(alpha = 0.85f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
                )
                androidx.compose.foundation.layout.Spacer(Modifier.height(24.dp))
                androidx.compose.material3.Button(
                    onClick = { viewModel.dismissPlaybackError(); onBackClick() },
                ) {
                    androidx.compose.material3.Text(stringResource(CoreR.string.close))
                }
            }
        }

        AnimatedVisibility(
            visible = firstFrameRendered && controlsVisible && !isPipMode,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            TvControllerOverlay(
                player = player,
                viewModel = viewModel,
                uiState = uiState,
                isPlaying = isPlaying,
                currentPosition = currentPosition,
                duration = duration,
                availableSources = availableSources,
                sourcesLoading = sourcesLoading,
                onBackClick = onBackClick,
                playPauseFocusRequester = playPauseFocusRequester,
                onOpenDialog = { activeDialog = it },
                onSelectQuality = onSelectQuality,
                onSelectSource = onSelectSource,
                onInteraction = ::revealControls,
                voiceAvailable = voice.isAvailable(),
                onVoice = { revealControls(); onVoice() },
            )
        }

        // Fladder-style pause overlay: logo / title top-left, wall-clock + ETA top-right.
        // Stays visible whenever the video is paused so the user can glance at the wall-clock;
        // when the full controller overlay is on-screen we drop the gradient and the top-left
        // title (both are already rendered by the controls) and keep only the clock.
        dev.jdtech.jellyfin.core.presentation.components.PlayerPauseOverlay(
            visible = firstFrameRendered && !isPlaying && !isPipMode,
            minimal = controlsVisible,
            title = uiState.currentSeriesName?.takeIf { it.isNotBlank() } ?: uiState.currentItemTitle,
            subtitle = if (!uiState.currentSeriesName.isNullOrBlank()) uiState.currentItemTitle else null,
            positionMs = currentPosition,
            durationMs = duration,
            titleFontSize = androidx.compose.ui.unit.TextUnit(42f, androidx.compose.ui.unit.TextUnitType.Sp),
            subtitleFontSize = androidx.compose.ui.unit.TextUnit(20f, androidx.compose.ui.unit.TextUnitType.Sp),
            clockFontSize = androidx.compose.ui.unit.TextUnit(28f, androidx.compose.ui.unit.TextUnitType.Sp),
            etaFontSize = androidx.compose.ui.unit.TextUnit(18f, androidx.compose.ui.unit.TextUnitType.Sp),
            logoSlot = uiState.currentItemLogoUri?.let { uri ->
                {
                    coil3.compose.AsyncImage(
                        model = uri,
                        contentDescription = null,
                        modifier = Modifier.heightIn(max = 120.dp).widthIn(max = 460.dp),
                        contentScale = ContentScale.Fit,
                    )
                }
            },
        )

        AnimatedVisibility(
            visible = !controlsVisible && !isPipMode && uiState.currentSegment != null && !showUpNext,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 64.dp, end = 32.dp),
        ) {
            uiState.currentSegment?.let { segment ->
                Button(
                    onClick = { viewModel.skipSegment(segment) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White.copy(alpha = 0.15f),
                        contentColor = Color.White,
                    ),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(
                        text = stringResource(uiState.currentSkipButtonStringRes) + " \u23CE",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = showUpNext && !isPipMode,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 48.dp, end = 48.dp),
        ) {
            nextEpisode?.let { ep ->
                TvUpNextCard(
                    nextEpisode = ep,
                    secondsRemaining = kotlin.math.ceil(upNextRemainingMs / 1000.0).toInt(),
                    fraction = (upNextRemainingMs.toFloat() / TV_NEXT_EPISODE_THRESHOLD_MS).coerceIn(0f, 1f),
                    onPlayNow = {
                        viewModel.skipToNextItem()
                        nextUpDismissed = true
                    },
                    onCancel = { nextUpDismissed = true },
                    playNowFocusRequester = upNextFocusRequester,
                )
            }
        }

        // Voice feedback overlay — top-center, parity with phone + XR.
        if (!isPipMode) {
            TvVoiceFeedbackOverlay(
                ui = voiceUi,
                partial = voicePartial,
                onDismiss = { voice.dismiss() },
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 120.dp),
            )
        }

        // Voice "search for…" results — a focusable list that plays on select.
        voiceSearchResults?.takeIf { !isPipMode }?.let { (query, results) ->
            TvVoiceSearchOverlay(
                query = query,
                results = results,
                onPlay = { item ->
                    voiceSearchResults = null
                    TvPlayerActivity.createIntentForSpatialItem(context, item)?.let(context::startActivity)
                },
                onDismiss = { voiceSearchResults = null },
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }

    if (isPipMode) return

    when (activeDialog) {
        TvPlayerDialog.Audio ->
            TvTrackSelectionDialog(
                title = "Audio Track",
                player = player,
                trackType = C.TRACK_TYPE_AUDIO,
                onTrackSelected = { index ->
                    viewModel.switchToTrack(C.TRACK_TYPE_AUDIO, index)
                    activeDialog = null
                },
                onDismiss = { activeDialog = null },
            )
        TvPlayerDialog.Subtitle ->
            TvTrackSelectionDialog(
                title = "Subtitle Track",
                player = player,
                trackType = C.TRACK_TYPE_TEXT,
                onTrackSelected = { index ->
                    viewModel.switchToTrack(C.TRACK_TYPE_TEXT, index)
                    activeDialog = null
                },
                onDismiss = { activeDialog = null },
                onSearchSubtitles = { activeDialog = TvPlayerDialog.SearchSubtitles },
            )
        TvPlayerDialog.SearchSubtitles ->
            Dialog(onDismissRequest = { 
                activeDialog = null
                viewModel.clearSubtitleSearchState() 
            }) {
                TvSubtitleSearchDialogContent(
                    viewModel = viewModel,
                    onDismiss = { 
                        activeDialog = null
                        viewModel.clearSubtitleSearchState()
                    }
                )
            }
        TvPlayerDialog.Chapters ->
            TvChapterDialog(
                chapters = uiState.currentChapters,
                onSelectChapter = { index ->
                    viewModel.seekToChapterIndex(index)
                    activeDialog = null
                },
                onDismiss = { activeDialog = null },
            )
        TvPlayerDialog.Quality ->
            TvQualityDialog(
                currentMaxBitrate = viewModel.appPreferences.getValue(viewModel.appPreferences.playerMaxBitrate),
                transcodeReason = uiState.currentTranscodeReason,
                onSelectQuality = {
                    onSelectQuality(it)
                    activeDialog = null
                },
                onDismiss = { activeDialog = null },
            )
        TvPlayerDialog.Source ->
            TvSourceDialog(
                sources = availableSources,
                selectedSourceId = uiState.currentMediaSourceId,
                isLoading = sourcesLoading,
                onSelectSource = { sourceIndex ->
                    onSelectSource(sourceIndex)
                    activeDialog = null
                },
                onDismiss = { activeDialog = null },
            )
        TvPlayerDialog.SyncPlay ->
            TvSyncPlayDialog(
                state = syncPlayState,
                onRefresh = { viewModel.refreshSyncPlayGroups() },
                onCreateGroup = { viewModel.createSyncPlayGroup() },
                onJoinGroup = { viewModel.joinSyncPlayGroup(it) },
                onLeaveGroup = { viewModel.leaveSyncPlayGroup() },
                onDismiss = { activeDialog = null },
            )
        null -> Unit
    }
}

private enum class TvPlayerDialog {
    Audio,
    Subtitle,
    SearchSubtitles,
    Chapters,
    Quality,
    Source,
    SyncPlay,
}

// Offer the Up Next card in the last 2 minutes of an episode — same window the
// XR next-episode panel uses (SpatialPlayerScreen.NEXT_EPISODE_THRESHOLD_MS), so
// the autoplay affordance is consistent across surfaces.
private const val TV_NEXT_EPISODE_THRESHOLD_MS = 2 * 60 * 1_000L

/**
 * UpNextCard (design components/tv/UpNextCard.jsx): a glass card that slides in
 * over the player bottom-right as an episode ends. Shows the next item's still,
 * an "UP NEXT" eyebrow, the title + series/episode line, and a Play Now button
 * wrapping a countdown ring (fills as the remaining time drops), plus Cancel.
 * The host owns the timer (`secondsRemaining` / `fraction`) and the actions.
 */
@Composable
private fun TvUpNextCard(
    nextEpisode: PlayerItem,
    secondsRemaining: Int,
    fraction: Float,
    onPlayNow: () -> Unit,
    onCancel: () -> Unit,
    playNowFocusRequester: FocusRequester,
) {
    val episodeLabel =
        nextEpisode.parentIndexNumber?.let { s ->
            nextEpisode.indexNumber?.let { ep -> "S${s}E$ep" }
        }
    val supporting =
        listOfNotNull(nextEpisode.seriesName, episodeLabel)
            .joinToString("  ·  ")
            .takeIf { it.isNotBlank() }
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = Color(0xF20D1824),
        modifier = Modifier.width(500.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Box(
                modifier = Modifier
                    .width(150.dp)
                    .height(84.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF0F1720)),
            ) {
                nextEpisode.backdropImageUri?.let { uri ->
                    coil3.compose.AsyncImage(
                        model = uri,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "UP NEXT",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                )
                Text(
                    text = nextEpisode.name,
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                supporting?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(2.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TvUpNextPlayButton(
                        secondsRemaining = secondsRemaining,
                        fraction = fraction,
                        onClick = onPlayNow,
                        modifier = Modifier.focusRequester(playNowFocusRequester),
                    )
                    TvOverlayTextButton(label = "Cancel", onClick = onCancel)
                }
            }
        }
    }
}

@Composable
private fun TvUpNextPlayButton(
    secondsRemaining: Int,
    fraction: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val container = if (isFocused) Color.White else MaterialTheme.colorScheme.primary
    val content = if (isFocused) Color.Black else MaterialTheme.colorScheme.onPrimary
    Surface(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(999.dp),
        color = container,
        contentColor = content,
        interactionSource = interactionSource,
    ) {
        Row(
            modifier = Modifier.padding(start = 6.dp, end = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(modifier = Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                Canvas(modifier = Modifier.size(36.dp)) {
                    val stroke = 3.dp.toPx()
                    drawArc(
                        color = content.copy(alpha = 0.25f),
                        startAngle = -90f,
                        sweepAngle = 360f,
                        useCenter = false,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(
                            width = stroke,
                            cap = androidx.compose.ui.graphics.StrokeCap.Round,
                        ),
                    )
                    drawArc(
                        color = content,
                        startAngle = -90f,
                        sweepAngle = 360f * fraction.coerceIn(0f, 1f),
                        useCenter = false,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(
                            width = stroke,
                            cap = androidx.compose.ui.graphics.StrokeCap.Round,
                        ),
                    )
                }
                Icon(
                    painter = painterResource(CoreR.drawable.ic_play),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
            Text(
                text = "Play Now · ${secondsRemaining}s",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun TvControllerOverlay(
    player: Player,
    viewModel: PlayerViewModel,
    uiState: PlayerViewModel.UiState,
    isPlaying: Boolean,
    currentPosition: Long,
    duration: Long,
    availableSources: List<SpatialFinSource>,
    sourcesLoading: Boolean,
    onBackClick: () -> Unit,
    playPauseFocusRequester: FocusRequester,
    onOpenDialog: (TvPlayerDialog) -> Unit,
    onSelectQuality: (Long) -> Unit,
    onSelectSource: (Int) -> Unit,
    onInteraction: () -> Unit,
    voiceAvailable: Boolean,
    onVoice: () -> Unit,
) {
    val overlayAlpha by animateFloatAsState(targetValue = 1f, label = "tvControlsAlpha")
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.28f * overlayAlpha)),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TvOverlayTextButton(
                label = "Back",
                onClick = {
                    onInteraction()
                    onBackClick()
                },
            )
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                val pauseLogoUri = uiState.currentItemLogoUri?.takeIf { !isPlaying }
                if (pauseLogoUri != null) {
                    coil3.compose.AsyncImage(
                        model = pauseLogoUri,
                        contentDescription = uiState.currentItemTitle,
                        modifier = Modifier.heightIn(max = 72.dp).widthIn(max = 360.dp),
                        contentScale = ContentScale.Fit,
                        alignment = Alignment.CenterStart,
                    )
                } else {
                    Text(
                        text = uiState.currentItemTitle,
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                buildPlayerSubtitle(uiState)?.let { subtitle ->
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.82f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                uiState.currentPlaybackInfoLabel?.let { label ->
                    Spacer(Modifier.height(4.dp))
                    dev.jdtech.jellyfin.core.presentation.components.MetadataPill {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.9f),
                            maxLines = 1,
                        )
                    }
                }
            }
        }

        // Bottom transport (design ui_kits/tv/TvPlayerScreen.jsx): the scrubber
        // with timecodes, then one control row — left transport cluster, right
        // options cluster — all circular glass icon buttons.
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 56.dp, vertical = 40.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            uiState.currentSegment?.let { segment ->
                TvOverlayTextButton(
                    label = stringResource(uiState.currentSkipButtonStringRes),
                    onClick = {
                        onInteraction()
                        viewModel.skipSegment(segment)
                    },
                    modifier = Modifier.align(Alignment.End),
                )
            }

            ProgressSection(
                uiState = uiState,
                player = player,
                currentPosition = currentPosition,
                duration = duration,
                resetAutoHide = onInteraction,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Left: transport cluster
                Row(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (uiState.currentChapters.isNotEmpty()) {
                        TvTransportIconButton(
                            iconRes = CoreR.drawable.ic_skip_back,
                            contentDescription = "Previous chapter",
                            onClick = {
                                onInteraction()
                                viewModel.seekToPreviousChapter()
                            },
                        )
                    }
                    TvTransportIconButton(
                        iconRes = CoreR.drawable.ic_rewind,
                        contentDescription = "Rewind",
                        onClick = {
                            onInteraction()
                            player.seekBack()
                        },
                    )
                    TvTransportIconButton(
                        iconRes = if (isPlaying) CoreR.drawable.ic_pause else CoreR.drawable.ic_play,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        modifier = Modifier.focusRequester(playPauseFocusRequester),
                        large = true,
                        onClick = {
                            onInteraction()
                            if (isPlaying) player.pause() else player.play()
                        },
                    )
                    TvTransportIconButton(
                        iconRes = CoreR.drawable.ic_fast_forward,
                        contentDescription = "Forward",
                        onClick = {
                            onInteraction()
                            player.seekForward()
                        },
                    )
                    if (uiState.currentChapters.isNotEmpty()) {
                        TvTransportIconButton(
                            iconRes = CoreR.drawable.ic_skip_forward,
                            contentDescription = "Next chapter",
                            onClick = {
                                onInteraction()
                                viewModel.seekToNextChapter()
                            },
                        )
                    }
                }

                // Right: options cluster — same dialogs as before, now icons
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (voiceAvailable) {
                        TvTransportIconButton(
                            iconRes = CoreR.drawable.ic_microphone,
                            contentDescription = "Voice",
                            onClick = onVoice,
                        )
                    }
                    TvTransportIconButton(
                        iconRes = CoreR.drawable.ic_closed_caption,
                        contentDescription = "Subtitles",
                        onClick = {
                            onInteraction()
                            onOpenDialog(TvPlayerDialog.Subtitle)
                        },
                    )
                    TvTransportIconButton(
                        iconRes = CoreR.drawable.ic_volume,
                        contentDescription = "Audio track",
                        onClick = {
                            onInteraction()
                            onOpenDialog(TvPlayerDialog.Audio)
                        },
                    )
                    if (uiState.currentChapters.isNotEmpty()) {
                        TvTransportIconButton(
                            iconRes = CoreR.drawable.ic_logs,
                            contentDescription = "Chapters",
                            onClick = {
                                onInteraction()
                                onOpenDialog(TvPlayerDialog.Chapters)
                            },
                        )
                    }
                    if (!uiState.currentItemId.isNullOrBlank() && !uiState.currentItemKind.isNullOrBlank()) {
                        TvTransportIconButton(
                            iconRes = CoreR.drawable.ic_gauge,
                            contentDescription = "Quality",
                            onClick = {
                                onInteraction()
                                onOpenDialog(TvPlayerDialog.Quality)
                            },
                        )
                    }
                    if (sourcesLoading || availableSources.size > 1) {
                        TvTransportIconButton(
                            iconRes = CoreR.drawable.ic_film,
                            contentDescription = "Media source",
                            onClick = {
                                onInteraction()
                                onOpenDialog(TvPlayerDialog.Source)
                            },
                        )
                    }
                    TvTransportIconButton(
                        iconRes = CoreR.drawable.ic_sparkles,
                        contentDescription = "Auto quality",
                        onClick = {
                            onInteraction()
                            onSelectQuality(0L)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun TvOverlayTextButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    TextButton(
        onClick = onClick,
        modifier = modifier,
        interactionSource = interactionSource,
        colors =
            ButtonDefaults.textButtonColors(
                containerColor =
                    if (isFocused) {
                        Color.White.copy(alpha = 0.18f)
                    } else {
                        Color.Transparent
                    },
                contentColor = Color.White,
            ),
    ) {
        Text(
            text = label, 
            color = Color.White,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun TvTransportIconButton(
    iconRes: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    large: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val size = if (large) 72.dp else 56.dp
    val iconSize = if (large) 36.dp else 26.dp
    val background =
        if (isFocused) MaterialTheme.colorScheme.primary
        else Color.White.copy(alpha = 0.12f)
    val contentColor = if (isFocused) Color.White else Color.White.copy(alpha = 0.92f)
    Box(
        modifier = modifier
            .size(size)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(background)
            .onPreviewKeyEvent { keyEvent ->
                val native = keyEvent.nativeKeyEvent
                if (
                    native.action == AndroidKeyEvent.ACTION_DOWN &&
                    native.repeatCount == 0 &&
                    (
                        native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
                            native.keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
                            native.keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
                    )
                ) {
                    onClick()
                    true
                } else {
                    false
                }
            }
            .focusable(interactionSource = interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            modifier = Modifier.size(iconSize),
            tint = contentColor,
        )
    }
}

@Composable
private fun TvVoiceFeedbackOverlay(
    ui: TvVoiceUi,
    partial: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(ui) {
        if (ui is TvVoiceUi.Answered || ui is TvVoiceUi.Error) {
            delay(6000L)
            onDismiss()
        }
    }
    AnimatedVisibility(
        visible = ui !is TvVoiceUi.Idle,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        val accent = if (ui is TvVoiceUi.Error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
        val label = when (ui) {
            is TvVoiceUi.Listening -> "Listening…"
            is TvVoiceUi.Processing -> "Thinking…"
            else -> null
        }
        val body = when (ui) {
            is TvVoiceUi.Listening -> partial.takeIf { it.isNotBlank() }
            is TvVoiceUi.Answered -> ui.text
            is TvVoiceUi.Error -> ui.text
            else -> null
        }
        Surface(shape = RoundedCornerShape(28.dp), color = Color(0xF20D1824), modifier = Modifier.widthIn(max = 760.dp)) {
            Row(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (ui is TvVoiceUi.Processing) {
                    androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.size(30.dp), color = accent, strokeWidth = 3.dp)
                } else {
                    Box(
                        modifier = Modifier.size(42.dp).clip(androidx.compose.foundation.shape.CircleShape).background(accent.copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(painterResource(CoreR.drawable.ic_microphone), contentDescription = null, modifier = Modifier.size(22.dp), tint = accent)
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    label?.let { Text(it, color = accent, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold) }
                    body?.let { Text(it, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 4, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
    }
}

@Composable
private fun TvVoiceSearchOverlay(
    query: String,
    results: List<SpatialFinItem>,
    onPlay: (SpatialFinItem) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onDismiss)
    val firstRowFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(80L)
        runCatching { firstRowFocus.requestFocus() }
    }
    Surface(
        modifier = modifier.widthIn(max = 720.dp).heightIn(max = 560.dp),
        shape = RoundedCornerShape(28.dp),
        color = Color(0xF20D1824),
    ) {
        Column(modifier = Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Results for “$query”", style = MaterialTheme.typography.titleLarge, color = Color.White, fontWeight = FontWeight.Bold)
            if (results.isEmpty()) {
                Text("No matches.", color = Color.White.copy(alpha = 0.7f))
            } else {
                androidx.compose.foundation.lazy.LazyColumn(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(results.take(20)) { item ->
                        val isFirst = item.id == results.first().id
                        TvVoiceSearchRow(
                            item = item,
                            modifier = if (isFirst) Modifier.focusRequester(firstRowFocus) else Modifier,
                            onClick = { onPlay(item) },
                        )
                    }
                }
            }
            TvOverlayTextButton(label = "Close", onClick = onDismiss, modifier = Modifier.align(Alignment.End))
        }
    }
}

@Composable
private fun TvVoiceSearchRow(item: SpatialFinItem, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .focusable(interactionSource = interactionSource)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .background(
                if (isFocused) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.05f),
                RoundedCornerShape(14.dp),
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(painterResource(CoreR.drawable.ic_play), contentDescription = null, modifier = Modifier.size(20.dp), tint = Color.White)
        Text(item.name, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun TvTrackSelectionDialog(
    title: String,
    player: Player,
    trackType: Int,
    onTrackSelected: (Int) -> Unit,
    onDismiss: () -> Unit,
    onSearchSubtitles: (() -> Unit)? = null,
) {
    val trackGroups = player.currentTracks.groups.filter { it.type == trackType }
    val trackNames = trackGroups.getTrackNames()
    val selectedIndex = trackGroups.indexOfFirst { it.isSelected }
    val initialFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        initialFocusRequester.requestFocus()
    }

    Dialog(onDismissRequest = onDismiss) {
        TvDialogSurface(
            title = title,
            onDismiss = onDismiss,
            width = 460.dp,
            onSearchSubtitles = onSearchSubtitles,
            trackType = trackType,
        ) {
            TvDialogOptionRow(
                label = "None",
                selected = selectedIndex == -1,
                onClick = { onTrackSelected(-1) },
                modifier = Modifier.focusRequester(initialFocusRequester),
            )
            trackNames.forEachIndexed { index, name ->
                TvDialogOptionRow(
                    label = name,
                    selected = index == selectedIndex,
                    onClick = { onTrackSelected(index) },
                )
            }
        }
    }
}

@Composable
private fun TvChapterDialog(
    chapters: List<PlayerChapter>,
    onSelectChapter: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val initialFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        initialFocusRequester.requestFocus()
    }

    Dialog(onDismissRequest = onDismiss) {
        TvDialogSurface(
            title = "Chapters",
            onDismiss = onDismiss,
            width = 460.dp,
        ) {
            chapters.forEachIndexed { index, chapter ->
                TvDialogOptionRow(
                    label = chapter.name?.ifBlank { "Chapter ${index + 1}" } ?: "Chapter ${index + 1}",
                    selected = false,
                    supportingText = formatTime(chapter.startPosition),
                    onClick = { onSelectChapter(index) },
                    modifier = if (index == 0) Modifier.focusRequester(initialFocusRequester) else Modifier,
                )
            }
        }
    }
}

@Composable
private fun TvSourceDialog(
    sources: List<SpatialFinSource>,
    selectedSourceId: String?,
    isLoading: Boolean,
    onSelectSource: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val initialFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        initialFocusRequester.requestFocus()
    }

    Dialog(onDismissRequest = onDismiss) {
        TvDialogSurface(
            title = "Media Source",
            onDismiss = onDismiss,
            width = 520.dp,
        ) {
            when {
                isLoading -> Text("Loading sources...", color = Color.White.copy(alpha = 0.8f))
                sources.isEmpty() -> Text("No alternate sources available.", color = Color.White.copy(alpha = 0.8f))
                else -> {
                    sources.forEachIndexed { index, source ->
                        TvDialogOptionRow(
                            label = source.name.ifBlank { "Source ${index + 1}" },
                            selected = source.id == selectedSourceId,
                            supportingText = buildSourceDescription(source),
                            onClick = { onSelectSource(index) },
                            modifier = if (index == 0) Modifier.focusRequester(initialFocusRequester) else Modifier,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TvQualityDialog(
    currentMaxBitrate: Long,
    transcodeReason: String? = null,
    onSelectQuality: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val currentOption = QualityOption.fromBps(currentMaxBitrate)
    val initialFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        initialFocusRequester.requestFocus()
    }

    Dialog(onDismissRequest = onDismiss) {
        TvDialogSurface(
            title = "Playback Quality",
            onDismiss = onDismiss,
            width = 460.dp,
        ) {
            if (transcodeReason != null) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = painterResource(CoreR.drawable.ic_info),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = transcodeReason,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.9f),
                        )
                    }
                }
            }
            QualityOption.entries.forEachIndexed { index, option ->
                TvDialogOptionRow(
                    label = stringResource(option.labelRes),
                    selected = currentOption == option,
                    onClick = { onSelectQuality(option.bps) },
                    modifier = if (index == 0) Modifier.focusRequester(initialFocusRequester) else Modifier,
                )
            }
        }
    }
}

@Composable
private fun TvDialogSurface(
    title: String,
    onDismiss: () -> Unit,
    width: androidx.compose.ui.unit.Dp,
    onSearchSubtitles: (() -> Unit)? = null,
    trackType: Int? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = Modifier.width(width).heightIn(max = 560.dp),
        shape = RoundedCornerShape(24.dp),
        color = Color(0xFF1C1C1C),
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(16.dp))
            Column(
                modifier =
                    Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = content,
            )
            Spacer(Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                if (trackType == C.TRACK_TYPE_TEXT && onSearchSubtitles != null) {
                    TvOverlayTextButton(
                        label = "SEARCH SUBTITLES",
                        onClick = {
                            onDismiss()
                            onSearchSubtitles()
                        }
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                TvOverlayTextButton(
                    label = "Close",
                    onClick = onDismiss,
                )
            }
        }
    }
}

@Composable
private fun TvDialogOptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .focusable(interactionSource = interactionSource)
                .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
                .background(
                    color =
                        when {
                            isFocused -> MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)
                            selected -> Color.White.copy(alpha = 0.10f)
                            else -> Color.Transparent
                        },
                    shape = RoundedCornerShape(16.dp),
                )
                .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!supportingText.isNullOrBlank()) {
                Text(
                    text = supportingText,
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun buildPlayerSubtitle(uiState: PlayerViewModel.UiState): String? {
    val parts = mutableListOf<String>()
    uiState.currentSeriesName?.takeIf { it.isNotBlank() }?.let(parts::add)
    if (uiState.currentSeasonNumber != null && uiState.currentEpisodeNumber != null) {
        parts += "S${uiState.currentSeasonNumber}E${uiState.currentEpisodeNumber}"
    }
    uiState.currentProductionYear?.let { parts += it.toString() }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" • ")
}

private fun currentChapterLabel(chapters: List<PlayerChapter>, currentPosition: Long): String? {
    if (chapters.isEmpty()) return null
    val currentIndex = chapters.indexOfLast { it.startPosition <= currentPosition }.takeIf { it >= 0 } ?: return null
    val chapter = chapters[currentIndex]
    return chapter.name?.ifBlank { null } ?: "Chapter ${currentIndex + 1}"
}

private fun buildSourceDescription(source: SpatialFinSource): String? {
    val parts = mutableListOf<String>()
    if (source.size > 0L) {
        parts += formatBytes(source.size)
    }
    source.path.takeIf { it.isNotBlank() }?.let(parts::add)
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" • ")
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var index = 0
    while (value >= 1024.0 && index < units.lastIndex) {
        value /= 1024.0
        index++
    }
    return if (index == 0) {
        "${value.toInt()} ${units[index]}"
    } else {
        String.format("%.1f %s", value, units[index])
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%d:%02d", minutes, seconds)
    }
}

@Composable
private fun TvSubtitleSearchDialogContent(
    viewModel: dev.jdtech.jellyfin.player.local.presentation.PlayerViewModel,
    onDismiss: () -> Unit,
) {
    val searchState by viewModel.subtitleSearchState.collectAsStateWithLifecycle()
    var language by remember { mutableStateOf(java.util.Locale.getDefault().language) }

    TvDialogSurface(
        title = "Search Subtitles",
        onDismiss = onDismiss,
        width = 460.dp,
    ) {
        androidx.compose.material3.OutlinedTextField(
            value = language,
            onValueChange = { language = it },
            label = { Text("Language Code (e.g. eng, spa, fre)", color = Color.White) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                onSearch = { viewModel.searchForSubtitles(language) }
            ),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                cursorColor = Color.White
            )
        )
        
        Spacer(Modifier.height(8.dp))
        
        TvOverlayTextButton(
            label = "Search",
            onClick = { viewModel.searchForSubtitles(language) },
            modifier = Modifier.align(Alignment.End)
        )
        
        Spacer(Modifier.height(16.dp))
        
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (val state = searchState) {
                is dev.jdtech.jellyfin.player.local.presentation.PlayerViewModel.SubtitleSearchState.Idle -> {
                    Text("Enter a language and click search.", color = Color.White, modifier = Modifier.align(Alignment.Center))
                }
                is dev.jdtech.jellyfin.player.local.presentation.PlayerViewModel.SubtitleSearchState.Searching -> {
                    androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                is dev.jdtech.jellyfin.player.local.presentation.PlayerViewModel.SubtitleSearchState.Downloading -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.align(Alignment.Center)) {
                        androidx.compose.material3.CircularProgressIndicator()
                        Spacer(Modifier.height(8.dp))
                        Text("Downloading...", color = Color.White)
                    }
                }
                is dev.jdtech.jellyfin.player.local.presentation.PlayerViewModel.SubtitleSearchState.Error -> {
                    Text("Error: ${state.message}", color = MaterialTheme.colorScheme.error)
                }
                is dev.jdtech.jellyfin.player.local.presentation.PlayerViewModel.SubtitleSearchState.Success -> {
                    if (state.options.isEmpty()) {
                        Text("No subtitles found.", color = Color.White, modifier = Modifier.align(Alignment.Center))
                    } else {
                        androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(state.options) { option: org.jellyfin.sdk.model.api.RemoteSubtitleInfo ->
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { viewModel.downloadAndSwitchSubtitles(option) }
                                        .padding(vertical = 12.dp, horizontal = 8.dp)
                                ) {
                                    Text(option.name ?: "Unknown", style = MaterialTheme.typography.titleMedium, color = Color.White)
                                    Text("Format: ${option.format} • Rating: ${option.communityRating ?: 0}", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
                                }
                                androidx.compose.material3.HorizontalDivider(color = Color.DarkGray)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TvSyncPlayDialog(
    state: PlayerViewModel.SyncPlayUiState,
    onRefresh: () -> Unit,
    onCreateGroup: () -> Unit,
    onJoinGroup: (UUID) -> Unit,
    onLeaveGroup: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = 720.dp)
                .fillMaxWidth(0.65f)
                .heightIn(max = 560.dp),
            shape = RoundedCornerShape(28.dp),
            color = Color(0xFF0F141C),
        ) {
            Column(
                modifier = Modifier.padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = "SyncPlay",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                )
                val active = state.activeGroup
                if (active != null) {
                    Text(
                        text = "In group: ${active.name}",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                    )
                    Text(
                        text = "Participants: ${active.participants.joinToString()}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.75f),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = onLeaveGroup, enabled = !state.isLoading) {
                            Text("Leave Group")
                        }
                        OutlinedButton(onClick = onRefresh, enabled = !state.isLoading) {
                            Text("Refresh")
                        }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = onCreateGroup, enabled = !state.isLoading) {
                            Text("Create Group")
                        }
                        OutlinedButton(onClick = onRefresh, enabled = !state.isLoading) {
                            Text("Refresh")
                        }
                    }
                }
                state.statusMessage?.let { msg ->
                    Text(
                        text = msg,
                        color = Color(0xFFB6D3F4),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (state.isLoading) {
                    Text("Loading SyncPlay groups…", color = Color.White.copy(alpha = 0.75f))
                }
                if (state.availableGroups.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        state.availableGroups.forEach { group ->
                            androidx.compose.material3.Card(
                                onClick = { onJoinGroup(group.id) },
                                colors = androidx.compose.material3.CardDefaults.cardColors(
                                    containerColor = Color(0xFF1A2433),
                                ),
                                shape = RoundedCornerShape(18.dp),
                            ) {
                                Column(
                                    modifier = Modifier.padding(18.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        text = group.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = Color.White,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    if (group.participants.isNotEmpty()) {
                                        Text(
                                            text = group.participants.joinToString(),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color.White.copy(alpha = 0.7f),
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else if (!state.isLoading && active == null) {
                    Text(
                        text = "No active SyncPlay groups on this server.",
                        color = Color.White.copy(alpha = 0.65f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text("Close")
                }
            }
        }
    }
}
