package dev.jdtech.jellyfin.player.xr

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.ui.PlayerView
import androidx.xr.compose.platform.LocalSession
import androidx.xr.scenecore.scene
import dagger.hilt.android.AndroidEntryPoint
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.player.local.domain.getTrackNames
import dev.jdtech.jellyfin.player.local.presentation.PlayerViewModel
import dev.jdtech.jellyfin.player.local.R as LocalR
import java.util.UUID
import dev.jdtech.jellyfin.player.core.preload.PlaybackPreloadCoordinator
import javax.inject.Inject
import kotlinx.coroutines.delay

@AndroidEntryPoint
class MultitaskPlayerActivity : ComponentActivity() {

    @Inject lateinit var preloadCoordinator: PlaybackPreloadCoordinator

    private val viewModel: PlayerViewModel by viewModels()
    private var mediaSession: MediaSession? = null

    companion object {
        fun createIntent(
            context: Context,
            itemId: UUID,
            itemKind: String,
            startFromBeginning: Boolean = false,
            mediaSourceIndex: Int? = null,
            maxBitrate: Long? = null,
        ): Intent {
            return Intent(context, MultitaskPlayerActivity::class.java).apply {
                putExtra("itemId", itemId.toString())
                putExtra("itemKind", itemKind)
                putExtra("startFromBeginning", startFromBeginning)
                mediaSourceIndex?.let { putExtra("mediaSourceIndex", it) }
                maxBitrate?.let { putExtra("maxBitrate", it) }
            }
        }
        
        fun createIntentForLocalMedia(
            context: Context,
            mediaStoreId: Long,
            startFromBeginning: Boolean = false,
        ): Intent {
            return Intent(context, MultitaskPlayerActivity::class.java).apply {
                putExtra("localMediaId", mediaStoreId)
                putExtra("startFromBeginning", startFromBeginning)
            }
        }

        fun createIntentForNetworkMedia(
            context: Context,
            networkVideoId: String,
            startFromBeginning: Boolean = false,
        ): Intent {
            return Intent(context, MultitaskPlayerActivity::class.java).apply {
                putExtra("networkVideoId", networkVideoId)
                putExtra("startFromBeginning", startFromBeginning)
            }
        }
    }

    @OptIn(androidx.media3.common.util.UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable wide color gamut for HDR support
        window.colorMode = android.content.pm.ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT

        val itemIdString = intent.extras?.getString("itemId")
        val localMediaId = intent.extras?.getLong("localMediaId")?.takeIf { it > 0L }
        val networkVideoId = intent.extras?.getString("networkVideoId")

        if (itemIdString == null && localMediaId == null && networkVideoId == null) {
            finish()
            return
        }
        
        val itemId = itemIdString?.let(UUID::fromString)
        val itemKind = intent.extras?.getString("itemKind") ?: ""
        val startFromBeginning = intent.extras!!.getBoolean("startFromBeginning")
        val mediaSourceIndex = if (intent.hasExtra("mediaSourceIndex")) intent.getIntExtra("mediaSourceIndex", -1).takeIf { it >= 0 } else null
        val maxBitrate = if (intent.hasExtra("maxBitrate")) intent.getLongExtra("maxBitrate", 0L).takeIf { it > 0L } else null

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .setUsage(C.USAGE_MEDIA)
            .build()

        val player = preloadCoordinator.buildExoPlayer(ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .setSeekBackIncrementMs(viewModel.appPreferences.getValue(viewModel.appPreferences.playerSeekBackInc))
            .setSeekForwardIncrementMs(viewModel.appPreferences.getValue(viewModel.appPreferences.playerSeekForwardInc)))

        viewModel.replacePlayer(player)
        
        if (localMediaId != null) {
            viewModel.initializeLocalPlayer(localMediaId, startFromBeginning)
        } else if (networkVideoId != null) {
            viewModel.initializeNetworkPlayer(networkVideoId, startFromBeginning)
        } else if (itemId != null) {
            viewModel.initializePlayer(
                itemId = itemId,
                itemKind = itemKind,
                startFromBeginning = startFromBeginning,
                mediaSourceIndex = mediaSourceIndex,
                maxBitrate = maxBitrate,
            )
        }

        updatePipParams()

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val session = LocalSession.current
                LaunchedEffect(session) {
                    try {
                        session?.scene?.requestHomeSpaceMode()
                    } catch (e: Exception) {
                        // Ignore
                    }
                }

                var isPipMode by remember { mutableStateOf(false) }
                
                DisposableEffect(Unit) {
                    val listener = object : androidx.core.util.Consumer<androidx.core.app.PictureInPictureModeChangedInfo> {
                        override fun accept(t: androidx.core.app.PictureInPictureModeChangedInfo) {
                            isPipMode = t.isInPictureInPictureMode
                        }
                    }
                    addOnPictureInPictureModeChangedListener(listener)
                    onDispose {
                        removeOnPictureInPictureModeChangedListener(listener)
                    }
                }

                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        val context = LocalContext.current
                        val playerView = remember {
                            PlayerView(context).apply {
                                this.player = player
                                layoutParams = FrameLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                useController = false // Use custom controller
                                setBackgroundColor(android.graphics.Color.BLACK)
                            }
                        }

                        DisposableEffect(Unit) {
                            onDispose {
                                playerView.player = null
                            }
                        }

                        AndroidView(
                            factory = { playerView },
                            modifier = Modifier.fillMaxSize()
                        )

                        if (!isPipMode) {
                            MultitaskControllerOverlay(
                                player = player,
                                viewModel = viewModel,
                                onBackClick = { finish() }
                            )
                        }
                    }
                }
            }
        }
    }

    private fun updatePipParams() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val paramsBuilder = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                paramsBuilder.setAutoEnterEnabled(true)
            }
            setPictureInPictureParams(paramsBuilder.build())
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (isInPictureInPictureMode) {
                return
            }
        }
        viewModel.updatePlaybackProgress()
    }

    override fun onStop() {
        super.onStop()
        viewModel.playWhenReady = viewModel.player.playWhenReady
        viewModel.player.playWhenReady = false
        mediaSession?.release()
        mediaSession = null
    }
}

@Composable
private fun MultitaskControllerOverlay(
    player: Player,
    viewModel: PlayerViewModel,
    onBackClick: () -> Unit,
) {
    var visible by remember { mutableStateOf(true) }
    var activeDialog by remember { mutableStateOf<String?>(null) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    
    var currentPosition by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(player.isPlaying) }

    LaunchedEffect(player) {
        while (true) {
            currentPosition = player.currentPosition
            duration = player.duration.coerceAtLeast(0L)
            isPlaying = player.isPlaying
            delay(500L)
        }
    }

    LaunchedEffect(visible) {
        if (visible && isPlaying) {
            delay(5000L)
            visible = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { visible = !visible }
            )
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f))
            ) {
                // Top: Title
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBackClick) {
                        Icon(painterResource(CoreR.drawable.ic_arrow_left), "Back", tint = Color.White)
                    }
                    Text(
                        text = uiState.currentItemTitle,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        modifier = Modifier.padding(start = 8.dp).weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    
                    IconButton(onClick = { activeDialog = "audio" }) {
                        Icon(painterResource(CoreR.drawable.ic_speaker), "Audio", tint = Color.White)
                    }
                    IconButton(onClick = { activeDialog = "subtitle" }) {
                        Icon(painterResource(CoreR.drawable.ic_closed_caption), "Subtitles", tint = Color.White)
                    }
                }

                // Middle: Play/Pause
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { player.seekBack() }, modifier = Modifier.size(48.dp)) {
                        Icon(painterResource(CoreR.drawable.ic_rewind), null, tint = Color.White, modifier = Modifier.size(32.dp))
                    }
                    
                    IconButton(
                        onClick = { if (isPlaying) player.pause() else player.play() },
                        modifier = Modifier.size(64.dp)
                    ) {
                        Icon(
                            painterResource(if (isPlaying) CoreR.drawable.ic_pause else CoreR.drawable.ic_play),
                            null,
                            tint = Color.White,
                            modifier = Modifier.size(48.dp)
                        )
                    }

                    IconButton(onClick = { player.seekForward() }, modifier = Modifier.size(48.dp)) {
                        Icon(painterResource(CoreR.drawable.ic_fast_forward), null, tint = Color.White, modifier = Modifier.size(32.dp))
                    }
                }

                // Bottom: Progress
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(formatTime(currentPosition), color = Color.White, style = MaterialTheme.typography.labelMedium)
                        Text(formatTime(duration), color = Color.White, style = MaterialTheme.typography.labelMedium)
                    }
                    Slider(
                        value = if (duration > 0) currentPosition.toFloat() / duration.toFloat() else 0f,
                        onValueChange = { player.seekTo((it * duration).toLong()) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }

    if (activeDialog == "audio") {
        Dialog(onDismissRequest = { activeDialog = null }) {
            TrackSelectionDialogContent(
                title = stringResource(LocalR.string.select_audio_track),
                player = player,
                trackType = C.TRACK_TYPE_AUDIO,
                onTrackSelected = { index -> viewModel.switchToTrack(C.TRACK_TYPE_AUDIO, index) },
                onDismiss = { activeDialog = null }
            )
        }
    }
    if (activeDialog == "subtitle") {
        Dialog(onDismissRequest = { activeDialog = null }) {
            TrackSelectionDialogContent(
                title = stringResource(LocalR.string.select_subtitle_track),
                player = player,
                trackType = C.TRACK_TYPE_TEXT,
                onTrackSelected = { index -> viewModel.switchToTrack(C.TRACK_TYPE_TEXT, index) },
                onDismiss = { activeDialog = null }
            )
        }
    }
}

@Composable
private fun TrackSelectionDialogContent(
    title: String,
    player: Player,
    trackType: @C.TrackType Int,
    onTrackSelected: (Int) -> Unit,
    onDismiss: () -> Unit,
    onSearchSubtitles: (() -> Unit)? = null,
) {
    val trackGroups = player.currentTracks.groups.filter { it.type == trackType && it.isSupported }
    val trackNames = trackGroups.getTrackNames()
    val selectedIndex = trackGroups.indexOfFirst { it.isSelected }

    Surface(
        modifier = Modifier
            .width(400.dp)
            .heightIn(max = 450.dp),
        shape = RoundedCornerShape(24.dp),
        color = Color(0xFF1C1C1C),
        tonalElevation = 8.dp,
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = Color.White, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onTrackSelected(-1); onDismiss() }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = selectedIndex == -1,
                        onClick = { onTrackSelected(-1); onDismiss() },
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(LocalR.string.none), color = Color.White, style = MaterialTheme.typography.bodyLarge)
                }
                trackNames.forEachIndexed { index, name ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onTrackSelected(index); onDismiss() }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = index == selectedIndex,
                            onClick = { onTrackSelected(index); onDismiss() },
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            name,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                if (trackType == C.TRACK_TYPE_TEXT && onSearchSubtitles != null) {
                    TextButton(onClick = {
                        onDismiss()
                        onSearchSubtitles()
                    }) {
                        Text("SEARCH SUBTITLES", color = MaterialTheme.colorScheme.primary)
                    }
                } else {
                    Spacer(Modifier.weight(1f))
                }
                TextButton(onClick = onDismiss) {
                    Text("CLOSE", color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) String.format("%d:%02d:%02d", hours, minutes, seconds)
    else String.format("%d:%02d", minutes, seconds)
}
