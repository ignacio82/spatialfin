package dev.jdtech.jellyfin.player.beam

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Looper
import android.speech.tts.TextToSpeech
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.lazy.items
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.compose.setContent
import org.jellyfin.sdk.model.api.RemoteSubtitleInfo
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.AudioAttributes
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import dagger.hilt.android.AndroidEntryPoint
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.models.SpatialFinEpisode
import dev.jdtech.jellyfin.models.SpatialFinItem
import dev.jdtech.jellyfin.models.SpatialFinMovie
import dev.jdtech.jellyfin.models.SpatialFinSource
import dev.jdtech.jellyfin.models.SyncPlayGroup
import dev.jdtech.jellyfin.player.core.domain.models.PlayerChapter
import dev.jdtech.jellyfin.player.core.domain.models.PlayerContentSource
import dev.jdtech.jellyfin.player.core.domain.models.PlayerItem
import dev.jdtech.jellyfin.player.local.presentation.PlayerEvents
import dev.jdtech.jellyfin.player.local.domain.getTrackNames
import dev.jdtech.jellyfin.player.local.presentation.PlayerViewModel
import dev.jdtech.jellyfin.player.local.R as LocalR
import dev.jdtech.jellyfin.player.session.voice.PlayerSessionController
import dev.jdtech.jellyfin.player.session.voice.PlayerStateSnapshot
import dev.jdtech.jellyfin.player.session.voice.XrPlayerAction
import dev.jdtech.jellyfin.player.beam.voice.BeamChatEngine
import dev.jdtech.jellyfin.player.beam.voice.BeamCommandCoordinator
import dev.jdtech.jellyfin.player.beam.voice.BeamGeminiCloudService
import dev.jdtech.jellyfin.player.beam.voice.BeamGeminiNanoService
import dev.jdtech.jellyfin.player.beam.voice.BeamVoiceService
import dev.jdtech.jellyfin.player.beam.voice.BeamVoiceState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.ArrayList
import java.util.Locale
import java.util.UUID

@AndroidEntryPoint
class BeamPlayerActivity : AppCompatActivity() {
    companion object {
        private const val EXTRA_ITEM_ID = "itemId"
        private const val EXTRA_ITEM_KIND = "itemKind"
        private const val EXTRA_START_FROM_BEGINNING = "startFromBeginning"
        private const val EXTRA_LOCAL_MEDIA_ID = "localMediaId"
        private const val EXTRA_NETWORK_VIDEO_ID = "networkVideoId"
        private const val EXTRA_MEDIA_SOURCE_INDEX = "mediaSourceIndex"
        private const val EXTRA_MAX_BITRATE = "maxBitrate"

        fun createIntent(
            context: Context,
            itemId: UUID,
            itemKind: String,
            startFromBeginning: Boolean = false,
            mediaSourceIndex: Int? = null,
            maxBitrate: Long? = null,
        ): Intent =
            Intent(context, BeamPlayerActivity::class.java).apply {
                putExtra(EXTRA_ITEM_ID, itemId.toString())
                putExtra(EXTRA_ITEM_KIND, itemKind)
                putExtra(EXTRA_START_FROM_BEGINNING, startFromBeginning)
                mediaSourceIndex?.let { putExtra(EXTRA_MEDIA_SOURCE_INDEX, it) }
                maxBitrate?.let { putExtra(EXTRA_MAX_BITRATE, it) }
            }

        fun createIntentForLocalMedia(
            context: Context,
            mediaStoreId: Long,
            startFromBeginning: Boolean = false,
        ): Intent =
            Intent(context, BeamPlayerActivity::class.java).apply {
                putExtra(EXTRA_LOCAL_MEDIA_ID, mediaStoreId)
                putExtra(EXTRA_START_FROM_BEGINNING, startFromBeginning)
            }

        fun createIntentForNetworkMedia(
            context: Context,
            networkVideoId: String,
            startFromBeginning: Boolean = false,
        ): Intent =
            Intent(context, BeamPlayerActivity::class.java).apply {
                putExtra(EXTRA_NETWORK_VIDEO_ID, networkVideoId)
                putExtra(EXTRA_START_FROM_BEGINNING, startFromBeginning)
            }

        fun createIntentForSpatialItem(
            context: Context,
            item: SpatialFinItem,
            startFromBeginning: Boolean = false,
        ): Intent? =
            when (item) {
                is SpatialFinMovie -> createIntent(context, item.id, "Movie", startFromBeginning)
                is SpatialFinEpisode -> createIntent(context, item.id, "Episode", startFromBeginning)
                else -> null
            }
    }

    private val viewModel: PlayerViewModel by viewModels()
    private var mediaSession: MediaSession? = null
    private var libassRenderer: LibassRenderer? = null
    private var pendingAudioPermissionResult: ((Boolean) -> Unit)? = null
    private val recordAudioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            pendingAudioPermissionResult?.invoke(granted)
            pendingAudioPermissionResult = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val startFromBeginning = intent.getBooleanExtra(EXTRA_START_FROM_BEGINNING, false)
        val itemIdString = intent.getStringExtra(EXTRA_ITEM_ID)
        val itemKind = intent.getStringExtra(EXTRA_ITEM_KIND)
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

        Timber.i(
            "BeamPlayerActivity launch itemId=%s itemKind=%s localMediaId=%s networkVideoId=%s startFromBeginning=%b mediaSourceIndex=%s maxBitrate=%s intent=%s",
            itemIdString,
            itemKind,
            localMediaId,
            networkVideoId,
            startFromBeginning,
            mediaSourceIndex,
            maxBitrate,
            intent.extras,
        )

        replacePlayerForBeamSubtitles()

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
                )
            }
            else -> {
                finish()
                return
            }
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                BeamPlayerScreen(
                    viewModel = viewModel,
                    libassRenderer = libassRenderer,
                    onBackClick = { finish() },
                    onSelectQuality = { bitrate ->
                        val itemId = viewModel.uiState.value.currentItemId?.let(UUID::fromString)
                        val itemKind = viewModel.uiState.value.currentItemKind
                        if (itemId != null && !itemKind.isNullOrBlank()) {
                            viewModel.changeQuality(itemId, itemKind, bitrate)
                        }
                    },
                    onSelectSource = { sourceIndex ->
                        val itemId = viewModel.uiState.value.currentItemId?.let(UUID::fromString)
                        val itemKind = viewModel.uiState.value.currentItemKind
                        if (itemId != null && !itemKind.isNullOrBlank()) {
                            viewModel.changeSource(itemId, itemKind, sourceIndex)
                        }
                    },
                    ensureRecordAudioPermission = { onResult ->
                        ensureRecordAudioPermission(onResult)
                    },
                )
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
        Timber.d(
            "BeamPlayerActivity onPause mediaId=%s posMs=%d state=%d",
            viewModel.player.currentMediaItem?.mediaId,
            viewModel.player.currentPosition,
            viewModel.player.playbackState,
        )
        viewModel.updatePlaybackProgress()
    }

    override fun onStop() {
        super.onStop()
        viewModel.playWhenReady = viewModel.player.playWhenReady
        viewModel.player.playWhenReady = false
        mediaSession?.release()
        mediaSession = null
    }

    override fun onDestroy() {
        super.onDestroy()
        Timber.d(
            "BeamPlayerActivity onDestroy mediaId=%s posMs=%d error=%s",
            viewModel.player.currentMediaItem?.mediaId,
            viewModel.player.currentPosition,
            viewModel.player.playerError?.errorCodeName,
        )
        libassRenderer?.destroy()
        libassRenderer = null
    }

    private fun ensureRecordAudioPermission(onResult: (Boolean) -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            onResult(true)
            return
        }
        pendingAudioPermissionResult = onResult
        recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    @OptIn(UnstableApi::class)
    private fun replacePlayerForBeamSubtitles() {
        val libassUsagePref = viewModel.appPreferences.getValue(viewModel.appPreferences.libassSubtitleUsage)
        val subtitleTextSize = viewModel.appPreferences.getValue(viewModel.appPreferences.xrSubtitleSize)
        val subtitleTextColor = viewModel.appPreferences.getValue(viewModel.appPreferences.subtitleTextColor)
        val subtitleBackgroundColor = viewModel.appPreferences.getValue(viewModel.appPreferences.subtitleBackgroundColor)
        Timber.i("Replacing Beam player for subtitles libassPref=%s", libassUsagePref)
        libassRenderer =
            runCatching {
                if (LibassRenderer.isAvailable()) {
                    LibassRenderer(1920, 1080).apply { init() }
                } else {
                    null
                }
            }.onFailure {
                Timber.e(it, "Failed to initialize Beam libass renderer")
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
                    val renderer = libassRenderer
                    // Always add default text renderers first (handles PGS, DVB-SUB, etc.)
                    super.buildTextRenderers(context, output, outputLooper, extensionRendererMode, out)
                    if (renderer != null) {
                        // Add LibassTextRenderer for styled ASS/SSA/SRT/VTT rendering
                        out.add(
                            LibassTextRenderer(
                                libassRenderer = renderer,
                                onTrackInitialized = {},
                                usagePref = libassUsagePref,
                                srtFontSize = subtitleTextSize.coerceIn(28, 96),
                                subtitleTextColor = subtitleTextColor,
                                subtitleBackgroundColor = subtitleBackgroundColor,
                            )
                        )
                    }
                }
            }.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
                .setEnableDecoderFallback(true)

        val mediaSourceFactory =
            DefaultMediaSourceFactory(this)
                .experimentalParseSubtitlesDuringExtraction(true)

        val trackSelector = DefaultTrackSelector(this)
        trackSelector.setParameters(
            trackSelector.buildUponParameters().setPreferredTextLanguage(null)
        )
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
                .setPauseAtEndOfMediaItems(true)
                .build()
        Timber.i(
            "Beam player replacement ready player=%s libassEnabled=%b seekBackMs=%d seekForwardMs=%d",
            player::class.java.simpleName,
            libassRenderer != null,
            viewModel.appPreferences.getValue(viewModel.appPreferences.playerSeekBackInc),
            viewModel.appPreferences.getValue(viewModel.appPreferences.playerSeekForwardInc),
        )
        viewModel.replacePlayer(player)
    }

    private fun createIntentForPlayerItem(context: Context, item: PlayerItem): Intent =
        when (item.contentSource) {
            PlayerContentSource.JELLYFIN ->
                createIntent(
                    context = context,
                    itemId = item.itemId,
                    itemKind =
                        if (item.seriesId != null || item.parentIndexNumber != null || item.indexNumber != null) {
                            "Episode"
                        } else {
                            viewModel.uiState.value.currentItemKind ?: "Movie"
                        },
                    startFromBeginning = false,
                )
            PlayerContentSource.LOCAL ->
                createIntentForLocalMedia(
                    context = context,
                    mediaStoreId = item.localMediaId ?: error("Missing local media id"),
                    startFromBeginning = false,
                )
            PlayerContentSource.NETWORK ->
                createIntentForNetworkMedia(
                    context = context,
                    networkVideoId = item.networkVideoId ?: error("Missing network video id"),
                    startFromBeginning = false,
                )
        }
}

@Composable
private fun BeamPlayerScreen(
    viewModel: PlayerViewModel,
    libassRenderer: LibassRenderer?,
    onBackClick: () -> Unit,
    onSelectQuality: (Long) -> Unit,
    onSelectSource: (Int) -> Unit,
    ensureRecordAudioPermission: ((Boolean) -> Unit) -> Unit,
) {
    val player = viewModel.player
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val syncPlayState by viewModel.syncPlayState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val subtitleTextColor = remember(viewModel.appPreferences) { viewModel.appPreferences.getValue(viewModel.appPreferences.subtitleTextColor) }
    val subtitleBackgroundColor = remember(viewModel.appPreferences) { viewModel.appPreferences.getValue(viewModel.appPreferences.subtitleBackgroundColor) }
    val subtitleSizeSp = remember(viewModel.appPreferences) { viewModel.appPreferences.getValue(viewModel.appPreferences.xrSubtitleSize).coerceIn(28, 96).toFloat() }
    var controlsVisible by remember { mutableStateOf(true) }
    var activeDialog by remember { mutableStateOf<BeamPlayerDialog?>(null) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(player.isPlaying) }
    var availableSources by remember { mutableStateOf<List<SpatialFinSource>>(emptyList()) }
    var sourcesLoading by remember { mutableStateOf(false) }
    var useLibass by remember { mutableStateOf(false) }
    var libassBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var voiceFeedback by remember { mutableStateOf<String?>(null) }
    var voiceResults by remember { mutableStateOf<List<SpatialFinItem>>(emptyList()) }
    var voiceSearchQuery by remember { mutableStateOf<String?>(null) }
    var voiceSearchError by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val voiceService = remember(context) { BeamVoiceService(context) }
    val nanoService = remember(context) { BeamGeminiNanoService(context.applicationContext) }
    val cloudService = remember(context, viewModel.repository, viewModel.appPreferences) {
        BeamGeminiCloudService(context.applicationContext, viewModel.appPreferences, viewModel.repository)
    }
    val commandCoordinator = remember(context) {
        BeamCommandCoordinator(context.applicationContext, nanoService, cloudService)
    }
    val chatEngine = remember(context) {
        BeamChatEngine(context.applicationContext, nanoService, cloudService, viewModel.appPreferences)
    }
    val voiceState by voiceService.state.collectAsStateWithLifecycle()
    val partialTranscript by voiceService.partialTranscript.collectAsStateWithLifecycle()
    val conversationHistory = remember { androidx.compose.runtime.mutableStateListOf<Pair<String, String>>() }
    LaunchedEffect(uiState.currentItemTitle) { conversationHistory.clear() }
    val latestContext by rememberUpdatedState(context)
    val latestSyncPlayState by rememberUpdatedState(syncPlayState)
    val latestControlsVisible by rememberUpdatedState(controlsVisible)
    val speechSynthesizer = remember(context) { BeamSpeechSynthesizer(context.applicationContext) }

    val sessionController =
        remember(viewModel, player) {
            PlayerSessionController(
                viewModel = viewModel,
                player = player,
                onControlsVisibilityChange = { controlsVisible = it },
                onNavigateBack = onBackClick,
                onShowVoiceSearch = { query, results, error ->
                    voiceSearchQuery = query
                    voiceResults = results
                    voiceSearchError = error
                    voiceFeedback = error ?: "Found ${results.size} result(s)"
                },
                onShowSyncPlayDialog = { activeDialog = BeamPlayerDialog.SyncPlay },
                onGoHome = {
                    onBackClick()
                    true
                },
                onCloseApp = onBackClick,
                onLaunchSearchResult = { item ->
                    BeamPlayerActivity.createIntentForSpatialItem(latestContext, item)?.let { intent ->
                        latestContext.startActivity(intent)
                    }
                },
                onSearchQuery = { query -> viewModel.repository.getSearchItems(query) },
                getAvailableSyncPlayGroups = { latestSyncPlayState.availableGroups },
                setPassthroughEnabled = {},
                getPassthroughEnabled = { false },
            )
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
                if (player.hasNextMediaItem()) {
                    player.seekToNextMediaItem()
                    player.play()
                } else {
                    onBackClick()
                }
            }
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

    LaunchedEffect(controlsVisible, isPlaying) {
        if (controlsVisible && isPlaying) {
            delay(5000L)
            controlsVisible = false
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            voiceService.destroy()
            speechSynthesizer.destroy()
        }
    }

    BoxWithConstraints(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black)
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
                if (result.hasContent) {
                    result.bitmap?.let {
                        libassBitmap = it
                    }
                } else {
                    libassBitmap = null
                }
                delay(33L)
            }
        }

        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { controlsVisible = !controlsVisible },
                    )
        ) {
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
                modifier = Modifier.fillMaxSize(),
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
                visible = controlsVisible,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                BeamControllerOverlay(
                    player = player,
                    viewModel = viewModel,
                    uiState = uiState,
                    isPlaying = isPlaying,
                    currentPosition = currentPosition,
                    duration = duration,
                    availableSources = availableSources,
                sourcesLoading = sourcesLoading,
                syncPlayState = syncPlayState,
                voiceState = voiceState,
                onBackClick = onBackClick,
                onOpenDialog = { activeDialog = it },
                onSelectQuality = onSelectQuality,
                    onSelectSource = onSelectSource,
                    onVoiceClick = {
                        if (!viewModel.appPreferences.getValue(viewModel.appPreferences.voiceControlEnabled)) {
                            voiceFeedback = "Voice commands are disabled in settings."
                            return@BeamControllerOverlay
                        }
                        ensureRecordAudioPermission { granted ->
                            if (!granted) {
                                voiceFeedback = "Microphone permission was denied."
                                return@ensureRecordAudioPermission
                            }
                            if (voiceState == BeamVoiceState.LISTENING) {
                                voiceService.stopListening()
                            } else {
                                voiceFeedback = null
                                voiceService.startListening { transcript ->
                                    coroutineScope.launch {
                                        val snapshot = buildVoiceSnapshot(
                                            uiState = uiState,
                                            player = player,
                                            controlsVisible = latestControlsVisible,
                                            syncPlayState = latestSyncPlayState,
                                        )
                                        val result = commandCoordinator.parse(transcript, snapshot)
                                        val feedback =
                                            when (val action = result.action) {
                                                is XrPlayerAction.ChatQuery -> {
                                                    val reply = chatEngine.query(
                                                        question = action.query,
                                                        playerState = snapshot,
                                                        verbosity = viewModel.appPreferences.getValue(viewModel.appPreferences.voiceAssistantVerbosity),
                                                        spoilerPolicy = viewModel.appPreferences.getValue(viewModel.appPreferences.voiceAssistantSpoilerPolicy),
                                                        conversationHistory = conversationHistory.toList(),
                                                        onGetSuggestions = { viewModel.repository.getSuggestions() },
                                                    )
                                                    if (reply != null) {
                                                        conversationHistory.add(action.query to reply)
                                                        if (conversationHistory.size > 6) conversationHistory.removeAt(0)
                                                    }
                                                    reply
                                                }
                                                else -> sessionController.dispatch(action)
                                            }
                                        voiceFeedback = feedback
                                        if (viewModel.appPreferences.getValue(viewModel.appPreferences.voiceAssistantSpokenReplies)) {
                                            speechSynthesizer.speak(feedback)
                                        }
                                        voiceService.resetState()
                                    }
                                }
                            }
                        }
                    },
                    onPlayNext = { nextEpisode ->
                        if (player.hasNextMediaItem()) {
                            player.seekToNextMediaItem()
                            player.play()
                        } else {
                            coroutineScope.launch {
                                playStandaloneNextItem(
                                    viewModel = viewModel,
                                    nextEpisode = nextEpisode,
                                )
                            }
                        }
                    },
                )
            }

            BeamVoiceOverlay(
                state = voiceState,
                partialTranscript = partialTranscript,
                feedback = voiceFeedback,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 72.dp),
            )
        }
    }

    when (activeDialog) {
        BeamPlayerDialog.Audio ->
            BeamTrackSelectionDialog(
                title = stringResource(LocalR.string.select_audio_track),
                player = player,
                trackType = C.TRACK_TYPE_AUDIO,
                onTrackSelected = { index ->
                    viewModel.switchToTrack(C.TRACK_TYPE_AUDIO, index)
                    activeDialog = null
                },
                onDismiss = { activeDialog = null },
            )
        BeamPlayerDialog.Subtitle ->
            BeamTrackSelectionDialog(
                title = stringResource(LocalR.string.select_subtitle_track),
                player = player,
                trackType = C.TRACK_TYPE_TEXT,
                onTrackSelected = { index ->
                    viewModel.switchToTrack(C.TRACK_TYPE_TEXT, index)
                    activeDialog = null
                },
                onDismiss = { activeDialog = null },
                onSearchSubtitles = { activeDialog = BeamPlayerDialog.SearchSubtitles }
            )
        BeamPlayerDialog.SearchSubtitles ->
            Dialog(onDismissRequest = { 
                activeDialog = null
                viewModel.clearSubtitleSearchState() 
            }) {
                BeamSubtitleSearchDialogContent(
                    viewModel = viewModel,
                    onDismiss = { 
                        activeDialog = null
                        viewModel.clearSubtitleSearchState()
                    }
                )
            }
        BeamPlayerDialog.Chapters ->
            BeamChapterDialog(
                chapters = uiState.currentChapters,
                onSelectChapter = { index ->
                    viewModel.seekToChapterIndex(index)
                    activeDialog = null
                },
                onDismiss = { activeDialog = null },
            )
        BeamPlayerDialog.Quality ->
            BeamQualityDialog(
                onSelectQuality = {
                    onSelectQuality(it)
                    activeDialog = null
                },
                onDismiss = { activeDialog = null },
            )
        BeamPlayerDialog.Source ->
            BeamSourceDialog(
                sources = availableSources,
                selectedSourceId = uiState.currentMediaSourceId,
                isLoading = sourcesLoading,
                onSelectSource = { sourceIndex ->
                    onSelectSource(sourceIndex)
                    activeDialog = null
                },
                onDismiss = { activeDialog = null },
            )
        BeamPlayerDialog.SyncPlay ->
            BeamSyncPlayDialog(
                state = syncPlayState,
                onRefresh = { viewModel.refreshSyncPlayGroups() },
                onCreateGroup = { viewModel.createSyncPlayGroup() },
                onJoinGroup = { viewModel.joinSyncPlayGroup(it) },
                onLeaveGroup = { viewModel.leaveSyncPlayGroup() },
                onDismiss = { activeDialog = null },
            )
        null -> Unit
    }

    if (voiceSearchQuery != null || voiceSearchError != null || voiceResults.isNotEmpty()) {
        BeamVoiceSearchDialog(
            query = voiceSearchQuery.orEmpty(),
            results = voiceResults,
            error = voiceSearchError,
            onDismiss = {
                sessionController.clearPendingSelection()
                voiceSearchQuery = null
                voiceResults = emptyList()
                voiceSearchError = null
            },
            onSelect = { item ->
                BeamPlayerActivity.createIntentForSpatialItem(context, item)?.let { intent ->
                    context.startActivity(intent)
                }
            },
        )
    }
}

private enum class BeamPlayerDialog {
    Audio,
    Subtitle,
    SearchSubtitles,
    Chapters,
    Quality,
    Source,
    SyncPlay,
}

@Composable
private fun BeamControllerOverlay(
    player: Player,
    viewModel: PlayerViewModel,
    uiState: PlayerViewModel.UiState,
    isPlaying: Boolean,
    currentPosition: Long,
    duration: Long,
    availableSources: List<SpatialFinSource>,
    sourcesLoading: Boolean,
    syncPlayState: PlayerViewModel.SyncPlayUiState,
    voiceState: BeamVoiceState,
    onBackClick: () -> Unit,
    onOpenDialog: (BeamPlayerDialog) -> Unit,
    onSelectQuality: (Long) -> Unit,
    onSelectSource: (Int) -> Unit,
    onVoiceClick: () -> Unit,
    onPlayNext: (PlayerItem) -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.42f)),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBackClick) {
                Icon(painterResource(CoreR.drawable.ic_arrow_left), "Back", tint = Color.White)
            }
            Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                Text(
                    text = uiState.currentItemTitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                buildBeamPlayerSubtitle(uiState)?.let { subtitle ->
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = { onOpenDialog(BeamPlayerDialog.Audio) }) {
                Icon(painterResource(CoreR.drawable.ic_speaker), "Audio", tint = Color.White)
            }
            IconButton(onClick = { onOpenDialog(BeamPlayerDialog.Subtitle) }) {
                Icon(painterResource(CoreR.drawable.ic_closed_caption), "Subtitles", tint = Color.White)
            }
            if (uiState.currentChapters.isNotEmpty()) {
                TextButton(onClick = { onOpenDialog(BeamPlayerDialog.Chapters) }) {
                    Text("Chapters", color = Color.White)
                }
            }
            if (sourcesLoading || availableSources.size > 1) {
                TextButton(onClick = { onOpenDialog(BeamPlayerDialog.Source) }) {
                    Text("Source", color = Color.White)
                }
            }
            if (!uiState.currentItemId.isNullOrBlank() && !uiState.currentItemKind.isNullOrBlank()) {
                IconButton(onClick = onVoiceClick) {
                    Icon(
                        painterResource(CoreR.drawable.ic_microphone),
                        "Voice",
                        tint = if (voiceState == BeamVoiceState.LISTENING) Color(0xFF4FC3F7) else Color.White,
                    )
                }
                TextButton(onClick = { onOpenDialog(BeamPlayerDialog.SyncPlay) }) {
                    Text(
                        if (syncPlayState.activeGroup != null) "SyncPlay On" else "SyncPlay",
                        color = Color.White,
                    )
                }
                TextButton(onClick = { onOpenDialog(BeamPlayerDialog.Quality) }) {
                    Text("Quality", color = Color.White)
                }
            }
        }

        Row(
            modifier = Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (uiState.currentChapters.isNotEmpty()) {
                TextButton(onClick = { viewModel.seekToPreviousChapter() }) {
                    Text("Prev Chapter", color = Color.White)
                }
            }
            IconButton(onClick = { player.seekBack() }, modifier = Modifier.size(48.dp)) {
                Icon(painterResource(CoreR.drawable.ic_rewind), null, tint = Color.White, modifier = Modifier.size(30.dp))
            }
            IconButton(
                onClick = { if (isPlaying) player.pause() else player.play() },
                modifier = Modifier.size(64.dp),
            ) {
                Icon(
                    painterResource(if (isPlaying) CoreR.drawable.ic_pause else CoreR.drawable.ic_play),
                    null,
                    tint = Color.White,
                    modifier = Modifier.size(44.dp),
                )
            }
            IconButton(onClick = { player.seekForward() }, modifier = Modifier.size(48.dp)) {
                Icon(painterResource(CoreR.drawable.ic_fast_forward), null, tint = Color.White, modifier = Modifier.size(30.dp))
            }
            if (uiState.currentChapters.isNotEmpty()) {
                TextButton(onClick = { viewModel.seekToNextChapter() }) {
                    Text("Next Chapter", color = Color.White)
                }
            }
        }

        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            uiState.currentSegment?.let { segment ->
                Button(
                    onClick = { viewModel.skipSegment(segment) },
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text(stringResource(uiState.currentSkipButtonStringRes))
                }
            }
            uiState.nextEpisode?.let { nextEpisode ->
                Button(
                    onClick = { onPlayNext(nextEpisode) },
                    modifier = Modifier.align(Alignment.End),
                ) {
                    val nextLabel =
                        buildNextEpisodeLabel(nextEpisode)?.let { "$it - ${nextEpisode.name}" }
                            ?: nextEpisode.name
                    Text("Play Next: $nextLabel")
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(formatTime(currentPosition), color = Color.White, style = MaterialTheme.typography.labelMedium)
                Text(formatTime(duration), color = Color.White, style = MaterialTheme.typography.labelMedium)
            }
            Slider(
                value = if (duration > 0L) currentPosition.toFloat() / duration.toFloat() else 0f,
                onValueChange = { fraction ->
                    player.seekTo((fraction * duration).toLong())
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                val chapterLabel = currentChapterLabel(uiState.currentChapters, currentPosition)
                Text(chapterLabel ?: "", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall)
                if (!uiState.currentItemId.isNullOrBlank() && !uiState.currentItemKind.isNullOrBlank()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (availableSources.size > 1) {
                            TextButton(onClick = { onOpenDialog(BeamPlayerDialog.Source) }) {
                                Text("Switch Source", color = Color.White)
                            }
                        }
                        TextButton(onClick = { onSelectQuality(0L) }) {
                            Text("Auto Quality", color = Color.White)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BeamVoiceOverlay(
    state: BeamVoiceState,
    partialTranscript: String,
    feedback: String?,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = state != BeamVoiceState.IDLE || !feedback.isNullOrBlank(),
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color.Black.copy(alpha = 0.82f),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    when (state) {
                        BeamVoiceState.LISTENING -> "Listening"
                        BeamVoiceState.PROCESSING -> "Processing"
                        BeamVoiceState.ERROR -> "Voice Error"
                        BeamVoiceState.IDLE -> "Voice"
                    },
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                )
                if (partialTranscript.isNotBlank()) {
                    Text(partialTranscript, color = Color.White)
                } else if (!feedback.isNullOrBlank()) {
                    Text(feedback, color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun BeamVoiceSearchDialog(
    query: String,
    results: List<SpatialFinItem>,
    error: String?,
    onDismiss: () -> Unit,
    onSelect: (SpatialFinItem) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
        title = { Text(if (query.isBlank()) "Voice Search" else "Voice Search: $query") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (results.isEmpty()) {
                    Text("No playable results.")
                } else {
                    results.take(5).forEach { item ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(item.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            TextButton(onClick = { onSelect(item) }) {
                                Text("Play")
                            }
                        }
                    }
                }
            }
        },
    )
}

private fun buildVoiceSnapshot(
    uiState: PlayerViewModel.UiState,
    player: Player,
    controlsVisible: Boolean,
    syncPlayState: PlayerViewModel.SyncPlayUiState,
): PlayerStateSnapshot {
    val audioGroups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
    val subtitleGroups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
    val audioNames = audioGroups.getTrackNames().toList()
    val subtitleNames = subtitleGroups.getTrackNames().toList()
    return PlayerStateSnapshot(
        isPlaying = player.isPlaying,
        positionSeconds = player.currentPosition / 1000L,
        durationSeconds = player.duration.coerceAtLeast(0L) / 1000L,
        controlsVisible = controlsVisible,
        currentItemTitle = uiState.currentItemTitle,
        currentOverview = uiState.currentOverview,
        currentSeriesName = uiState.currentSeriesName,
        currentSeasonNumber = uiState.currentSeasonNumber,
        currentEpisodeNumber = uiState.currentEpisodeNumber,
        currentChapterName = currentChapterLabel(uiState.currentChapters, player.currentPosition),
        nextEpisodeTitle = uiState.nextEpisode?.name,
        currentGenres = uiState.currentGenres,
        castNames = uiState.currentPeople.filter { it.type.equals("Actor", ignoreCase = true) }.map { it.name },
        directors = uiState.currentPeople.filter { it.type.equals("Director", ignoreCase = true) }.map { it.name },
        writers = uiState.currentPeople.filter { it.type.equals("Writer", ignoreCase = true) }.map { it.name },
        productionYear = uiState.currentProductionYear,
        officialRating = uiState.currentOfficialRating,
        audioTrackNames = audioNames,
        subtitleTrackNames = subtitleNames,
        chapterNames = uiState.currentChapters.mapNotNull { it.name },
        currentAudioTrack = audioGroups.getOrNull(audioGroups.indexOfFirst { it.isSelected }.coerceAtLeast(0))?.let {
            audioNames.getOrNull(audioGroups.indexOf(it))
        },
        currentSubtitleTrack = subtitleGroups.getOrNull(subtitleGroups.indexOfFirst { it.isSelected }.coerceAtLeast(0))?.let {
            subtitleNames.getOrNull(subtitleGroups.indexOf(it))
        },
        syncPlayActive = syncPlayState.activeGroup != null,
        syncPlayGroupName = syncPlayState.activeGroup?.name,
        syncPlayParticipantNames = syncPlayState.activeGroup?.participants.orEmpty(),
        currentRatings = uiState.currentRatings.map { it.value },
    )
}

private class BeamSpeechSynthesizer(context: Context) : TextToSpeech.OnInitListener {
    private var textToSpeech: TextToSpeech? = TextToSpeech(context, this)
    private var ready = false

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            textToSpeech?.language = Locale.getDefault()
        }
    }

    fun speak(text: String?) {
        if (!ready || text.isNullOrBlank()) return
        textToSpeech?.speak(text.take(320), TextToSpeech.QUEUE_FLUSH, null, "beam-voice-feedback")
    }

    fun destroy() {
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        ready = false
    }
}

@Composable
private fun BeamSyncPlayDialog(
    state: PlayerViewModel.SyncPlayUiState,
    onRefresh: () -> Unit,
    onCreateGroup: () -> Unit,
    onJoinGroup: (UUID) -> Unit,
    onLeaveGroup: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier =
                Modifier
                    .width(520.dp)
                    .heightIn(max = 680.dp),
            shape = RoundedCornerShape(24.dp),
            color = Color(0xFF1C1C1C),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("SyncPlay", style = MaterialTheme.typography.titleLarge, color = Color.White, fontWeight = FontWeight.Bold)
                state.activeGroup?.let { group ->
                    BeamSyncPlayGroupCard(
                        group = group,
                        isActive = true,
                        onJoin = {},
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = onLeaveGroup, enabled = !state.isLoading) {
                            Text("Leave Group")
                        }
                        TextButton(onClick = onRefresh, enabled = !state.isLoading) {
                            Text("Refresh")
                        }
                    }
                } ?: Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onCreateGroup, enabled = !state.isLoading) {
                        Text("Create Group")
                    }
                    TextButton(onClick = onRefresh, enabled = !state.isLoading) {
                        Text("Refresh")
                    }
                }
                state.statusMessage?.let { message ->
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.secondary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (state.isLoading) {
                    Text("Loading SyncPlay groups...", color = Color.White.copy(alpha = 0.8f))
                }
                Column(
                    modifier =
                        Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    state.availableGroups.forEach { group ->
                        BeamSyncPlayGroupCard(
                            group = group,
                            isActive = state.activeGroup?.id == group.id,
                            onJoin = { onJoinGroup(group.id) },
                        )
                    }
                    if (state.availableGroups.isEmpty() && !state.isLoading) {
                        Text(
                            "No active SyncPlay groups on this server.",
                            color = Color.White.copy(alpha = 0.7f),
                        )
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text("Close")
                }
            }
        }
    }
}

@Composable
private fun BeamSyncPlayGroupCard(
    group: SyncPlayGroup,
    isActive: Boolean,
    onJoin: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color =
            if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.22f)
            else Color.White.copy(alpha = 0.06f),
        onClick = { if (!isActive) onJoin() },
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(group.name, color = Color.White, fontWeight = FontWeight.SemiBold)
            Text(
                text = "${group.participants.size} participant(s) • ${group.state}",
                color = Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "Participants: ${group.participants.joinToString().ifBlank { "Just you" }}",
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun BeamTrackSelectionDialog(
    title: String,
    player: Player,
    trackType: @C.TrackType Int,
    onTrackSelected: (Int) -> Unit,
    onDismiss: () -> Unit,
    onSearchSubtitles: (() -> Unit)? = null,
) {
    val trackGroups = player.currentTracks.groups.filter { it.type == trackType }
    val trackNames = trackGroups.getTrackNames()
    val selectedIndex = trackGroups.indexOfFirst { it.isSelected }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier =
                Modifier
                    .width(420.dp)
                    .heightIn(max = 480.dp),
            shape = RoundedCornerShape(24.dp),
            color = Color(0xFF1C1C1C),
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, color = Color.White, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))
                Column(
                    modifier =
                        Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState()),
                ) {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onTrackSelected(-1)
                                }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selectedIndex == -1, onClick = { onTrackSelected(-1) })
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(LocalR.string.none), color = Color.White)
                    }
                    trackNames.forEachIndexed { index, name ->
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onTrackSelected(index) }
                                    .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = index == selectedIndex, onClick = { onTrackSelected(index) })
                            Spacer(Modifier.width(12.dp))
                            Text(name, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    if (trackType == C.TRACK_TYPE_TEXT && onSearchSubtitles != null) {
                        TextButton(onClick = {
                            onDismiss()
                            onSearchSubtitles()
                        }) {
                            Text("SEARCH SUBTITLES")
                        }
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    TextButton(onClick = onDismiss) {
                        Text("Close")
                    }
                }
            }
        }
    }
}

@Composable
private fun BeamChapterDialog(
    chapters: List<PlayerChapter>,
    onSelectChapter: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier =
                Modifier
                    .width(420.dp)
                    .heightIn(max = 480.dp),
            shape = RoundedCornerShape(24.dp),
            color = Color(0xFF1C1C1C),
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text("Chapters", style = MaterialTheme.typography.titleLarge, color = Color.White, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))
                Column(
                    modifier =
                        Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState()),
                ) {
                    chapters.forEachIndexed { index, chapter ->
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelectChapter(index) }
                                    .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = chapter.name?.ifBlank { "Chapter ${index + 1}" } ?: "Chapter ${index + 1}",
                                color = Color.White,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Spacer(Modifier.weight(1f))
                            Text(formatTime(chapter.startPosition), color = Color.White.copy(alpha = 0.7f))
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text("Close")
                }
            }
        }
    }
}

@Composable
private fun BeamSourceDialog(
    sources: List<SpatialFinSource>,
    selectedSourceId: String?,
    isLoading: Boolean,
    onSelectSource: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier =
                Modifier
                    .width(480.dp)
                    .heightIn(max = 520.dp),
            shape = RoundedCornerShape(24.dp),
            color = Color(0xFF1C1C1C),
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text("Media Source", style = MaterialTheme.typography.titleLarge, color = Color.White, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))
                when {
                    isLoading -> {
                        Text("Loading sources...", color = Color.White.copy(alpha = 0.8f))
                    }
                    sources.isEmpty() -> {
                        Text("No alternate sources available.", color = Color.White.copy(alpha = 0.8f))
                    }
                    else -> {
                        Column(
                            modifier =
                                Modifier
                                    .weight(1f, fill = false)
                                    .verticalScroll(rememberScrollState()),
                        ) {
                            sources.forEachIndexed { index, source ->
                                val selected = source.id == selectedSourceId
                                Row(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .clickable { onSelectSource(index) }
                                            .padding(vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(selected = selected, onClick = { onSelectSource(index) })
                                    Spacer(Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            text = source.name.ifBlank { "Source ${index + 1}" },
                                            color = Color.White,
                                            style = MaterialTheme.typography.bodyLarge,
                                        )
                                        buildSourceDescription(source)?.let { description ->
                                            Text(
                                                text = description,
                                                color = Color.White.copy(alpha = 0.7f),
                                                style = MaterialTheme.typography.bodySmall,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text("Close")
                }
            }
        }
    }
}

@Composable
private fun BeamQualityDialog(
    onSelectQuality: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val options =
        listOf(
            0L to "Auto",
            3_000_000L to "3 Mbps",
            5_000_000L to "5 Mbps",
            10_000_000L to "10 Mbps",
            20_000_000L to "20 Mbps",
            40_000_000L to "40 Mbps",
        )

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier =
                Modifier
                    .width(360.dp)
                    .heightIn(max = 420.dp),
            shape = RoundedCornerShape(24.dp),
            color = Color(0xFF1C1C1C),
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text("Playback Quality", style = MaterialTheme.typography.titleLarge, color = Color.White, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))
                options.forEach { (bitrate, label) ->
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable { onSelectQuality(bitrate) }
                                .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(label, color = Color.White, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text("Close")
                }
            }
        }
    }
}

private fun buildBeamPlayerSubtitle(uiState: PlayerViewModel.UiState): String? {
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

private suspend fun playStandaloneNextItem(
    viewModel: PlayerViewModel,
    nextEpisode: PlayerItem,
) {
    viewModel.initializePlayer(
        itemId = nextEpisode.itemId,
        itemKind = "Episode",
        startFromBeginning = false,
    )
}

private fun buildNextEpisodeLabel(item: PlayerItem): String? {
    val season = item.parentIndexNumber ?: return null
    val episode = item.indexNumber ?: return null
    return "S${season}E${episode}"
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
    return if (hours > 0) String.format("%d:%02d:%02d", hours, minutes, seconds) else String.format("%d:%02d", minutes, seconds)
}

@Composable
private fun BeamSubtitleSearchDialogContent(
    viewModel: dev.jdtech.jellyfin.player.local.presentation.PlayerViewModel,
    onDismiss: () -> Unit,
) {
    val searchState by viewModel.subtitleSearchState.collectAsStateWithLifecycle()
    var language by remember { mutableStateOf(java.util.Locale.getDefault().language) }

    Surface(
        modifier = Modifier
            .width(420.dp)
            .heightIn(max = 480.dp),
        shape = RoundedCornerShape(24.dp),
        color = Color(0xFF1C1C1C),
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text("Search Subtitles", style = MaterialTheme.typography.titleLarge, color = Color.White, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            
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
            
            Spacer(Modifier.height(16.dp))
            
            Button(
                onClick = { viewModel.searchForSubtitles(language) },
                modifier = Modifier.align(Alignment.End)
            ) {
                Text("Search")
            }
            
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
                                items(state.options) { option: RemoteSubtitleInfo ->
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
            
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text("Close")
            }
        }
    }
}
