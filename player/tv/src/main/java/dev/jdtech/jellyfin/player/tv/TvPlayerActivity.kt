package dev.jdtech.jellyfin.player.tv

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Looper
import android.util.Rational
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.lazy.items
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import dev.jdtech.jellyfin.core.R as CoreR
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
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
        private const val EXTRA_MEDIA_SOURCE_INDEX = "mediaSourceIndex"
        private const val EXTRA_MAX_BITRATE = "maxBitrate"
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
        ): Intent =
            Intent(context, TvPlayerActivity::class.java).apply {
                putExtra(EXTRA_ITEM_ID, itemId.toString())
                putExtra(EXTRA_ITEM_KIND, itemKind)
                putExtra(EXTRA_START_FROM_BEGINNING, startFromBeginning)
                mediaSourceIndex?.let { putExtra(EXTRA_MEDIA_SOURCE_INDEX, it) }
                maxBitrate?.let { putExtra(EXTRA_MAX_BITRATE, it) }
                if (openSyncPlayDialogOnStart) putExtra(EXTRA_OPEN_SYNC_PLAY, true)
                startPositionMs?.let { putExtra(EXTRA_START_POSITION_MS, it) }
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

        fun createIntentForSpatialItem(
            context: Context,
            item: SpatialFinItem,
            startFromBeginning: Boolean = false,
            openSyncPlayDialogOnStart: Boolean = false,
            startPositionMs: Long? = null,
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
                    )
                is SpatialFinEpisode ->
                    createIntent(
                        context = context,
                        itemId = item.id,
                        itemKind = "Episode",
                        startFromBeginning = startFromBeginning,
                        openSyncPlayDialogOnStart = openSyncPlayDialogOnStart,
                        startPositionMs = startPositionMs,
                    )
                else -> null
            }
    }

    private val viewModel: PlayerViewModel by viewModels()
    private var mediaSession: MediaSession? = null
    private var libassRenderer: LibassRenderer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
            "TvPlayerActivity launch itemId=%s itemKind=%s localMediaId=%s networkVideoId=%s startFromBeginning=%b mediaSourceIndex=%s maxBitrate=%s",
            itemIdString,
            itemKind,
            localMediaId,
            networkVideoId,
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
                override fun buildTextRenderers(
                    context: Context,
                    output: TextOutput,
                    outputLooper: Looper,
                    extensionRendererMode: Int,
                    out: ArrayList<Renderer>,
                ) {
                    super.buildTextRenderers(context, output, outputLooper, extensionRendererMode, out)
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
                }
            }.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
                .setEnableDecoderFallback(true)

        val encryptedDataSourceFactory =
            dev.jdtech.jellyfin.player.core.security.EncryptedLocalDataSourceFactory(
                delegate = androidx.media3.datasource.DefaultDataSource.Factory(this),
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

    LaunchedEffect(controlsVisible, activeDialog) {
        if (controlsVisible && activeDialog == null) {
            delay(50L)
            playPauseFocusRequester.requestFocus()
        }
    }

    LaunchedEffect(player) {
        while (true) {
            currentPosition = player.currentPosition
            duration = player.duration.coerceAtLeast(0L)
            isPlaying = player.isPlaying
            delay(500L)
        }
    }

    BackHandler(enabled = activeDialog != null || controlsVisible) {
        when {
            activeDialog != null -> activeDialog = null
            controlsVisible -> controlsVisible = false
        }
    }

    BoxWithConstraints(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .onPreviewKeyEvent { keyEvent ->
                    if (keyEvent.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (keyEvent.key) {
                        Key.DirectionLeft -> {
                            if (!controlsVisible) {
                                revealControls()
                                player.seekBack()
                                true
                            } else {
                                false
                            }
                        }
                        Key.DirectionRight -> {
                            if (!controlsVisible) {
                                revealControls()
                                player.seekForward()
                                true
                            } else {
                                false
                            }
                        }
                        Key.DirectionUp,
                        Key.DirectionDown,
                        Key.DirectionCenter,
                        Key.Enter,
                        Key.NumPadEnter -> {
                            if (!controlsVisible) {
                                revealControls()
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
                        Key.MediaPlayPause -> {
                            revealControls()
                            if (isPlaying) player.pause() else player.play()
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

        LaunchedEffect(useLibass) {
            val renderer = libassRenderer ?: return@LaunchedEffect
            while (useLibass) {
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

        if (useLibass) {
            libassBitmap?.let { bitmap ->
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Subtitles",
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        AnimatedVisibility(
            visible = controlsVisible && !isPipMode,
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
            )
        }

        // Fladder-style pause overlay: logo or title top-left, wall-clock + ETA top-right.
        // Only shown when paused AND the full controller overlay is hidden, so the
        // two surfaces don't stack awkwardly.
        dev.jdtech.jellyfin.core.presentation.components.PlayerPauseOverlay(
            visible = !isPlaying && !controlsVisible && !isPipMode,
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
                Text(
                    text = uiState.currentItemTitle,
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TvOverlayTextButton(
                    label = "Audio",
                    onClick = {
                        onInteraction()
                        onOpenDialog(TvPlayerDialog.Audio)
                    },
                )
                TvOverlayTextButton(
                    label = "Subtitles",
                    onClick = {
                        onInteraction()
                        onOpenDialog(TvPlayerDialog.Subtitle)
                    },
                )
                if (uiState.currentChapters.isNotEmpty()) {
                    TvOverlayTextButton(
                        label = "Chapters",
                        onClick = {
                            onInteraction()
                            onOpenDialog(TvPlayerDialog.Chapters)
                        },
                    )
                }
                if (sourcesLoading || availableSources.size > 1) {
                    TvOverlayTextButton(
                        label = "Source",
                        onClick = {
                            onInteraction()
                            onOpenDialog(TvPlayerDialog.Source)
                        },
                    )
                }
                if (!uiState.currentItemId.isNullOrBlank() && !uiState.currentItemKind.isNullOrBlank()) {
                    TvOverlayTextButton(
                        label = "Quality",
                        onClick = {
                            onInteraction()
                            onOpenDialog(TvPlayerDialog.Quality)
                        },
                    )
                }
            }
        }

        Column(
            modifier = Modifier.align(Alignment.Center),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (uiState.currentChapters.isNotEmpty()) {
                    TvOverlayTextButton(
                        label = "Prev Chapter",
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
                    TvOverlayTextButton(
                        label = "Next Chapter",
                        onClick = {
                            onInteraction()
                            viewModel.seekToNextChapter()
                        },
                    )
                }
            }
        }

        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
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
                horizontalArrangement = Arrangement.End,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (availableSources.size > 1) {
                        TvOverlayTextButton(
                            label = "Switch Source",
                            onClick = {
                                onInteraction()
                                onOpenDialog(TvPlayerDialog.Source)
                            },
                        )
                    }
                    TvOverlayTextButton(
                        label = "Auto Quality",
                        onClick = {
                            onInteraction()
                            onSelectQuality(0L)
                        },
                    )
                    if (availableSources.size > 1) {
                        TvOverlayTextButton(
                            label = "Default Source",
                            onClick = {
                                onInteraction()
                                onSelectSource(0)
                            },
                        )
                    }
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
        modifier = modifier.focusable(interactionSource = interactionSource),
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
        Text(label, color = Color.White)
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
    Surface(
        onClick = onClick,
        modifier = modifier
            .size(size)
            .focusable(interactionSource = interactionSource),
        shape = androidx.compose.foundation.shape.CircleShape,
        color = background,
        contentColor = contentColor,
        interactionSource = interactionSource,
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = contentDescription,
                modifier = Modifier.size(iconSize),
            )
        }
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
            width = 420.dp,
        ) {
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
