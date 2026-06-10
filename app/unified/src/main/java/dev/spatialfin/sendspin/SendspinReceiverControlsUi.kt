package dev.spatialfin.sendspin

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.sendspin.receiver.SendspinMusicAssistantAuthState
import dev.jdtech.jellyfin.sendspin.receiver.SendspinMusicAssistantPlayer
import dev.jdtech.jellyfin.sendspin.receiver.SendspinControllerCommands
import dev.jdtech.jellyfin.sendspin.receiver.SendspinReceiverService
import dev.jdtech.jellyfin.sendspin.receiver.SendspinReceiverSession
import dev.jdtech.jellyfin.sendspin.receiver.SendspinReceiverUiState
import dev.jdtech.jellyfin.sendspin.R as SendspinR

@Composable
fun SendspinFullscreenPlayer(
    modifier: Modifier = Modifier,
    suppressed: Boolean = false,
    queueViewModel: SendspinQueueViewModel = hiltViewModel(),
) {
    val state by SendspinReceiverSession.state.collectAsState()
    val queueItems by queueViewModel.queueItems.collectAsState()

    LaunchedEffect(state.musicAssistantCurrentPlayerId) {
        queueViewModel.setQueueId(state.musicAssistantCurrentPlayerId)
    }
    // [suppressed] when MA presents this playback through the unified MA player,
    // so the SendSpin fullscreen doesn't auto-pop over it. When this device is an
    // MA-driven endpoint (authenticated), the MA now-playing player owns the UI
    // outright — never auto-pop the SendSpin fullscreen (it used to flash back on
    // pause). The receiver keeps running headless.
    val maDriven = state.musicAssistantAuthState == SendspinMusicAssistantAuthState.AUTHENTICATED
    if (suppressed || maDriven || !state.showControls) return

    val context = LocalContext.current
    val bitmap =
        remember(state.albumArtwork) {
            state.albumArtwork
                ?.takeIf { it.isNotEmpty() }
                ?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
        }
    val playPauseCommand =
        if (state.isPlaying) SendspinControllerCommands.PAUSE else SendspinControllerCommands.PLAY
    var sliderVolume by remember(state.volume) {
        mutableStateOf(state.volume.coerceIn(0, 100) / 100f)
    }
    var showGroupPanel by remember { mutableStateOf(false) }
    var showQueuePanel by remember { mutableStateOf(false) }

    fun send(command: String, volume: Int? = null, muted: Boolean? = null) {
        SendspinReceiverService.sendControllerCommand(
            context = context.applicationContext,
            command = command,
            volume = volume,
            muted = muted,
        )
    }

    fun dismiss() {
        SendspinReceiverService.dismissControls(context.applicationContext)
    }

    fun openGroupPanel() {
        showGroupPanel = true
        SendspinReceiverService.refreshMusicAssistantGroups(context.applicationContext)
    }

    Dialog(
        onDismissRequest = ::dismiss,
        properties =
            DialogProperties(
                dismissOnBackPress = true,
                dismissOnClickOutside = false,
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
    ) {
        BackHandler(enabled = true) { dismiss() }
        Box(modifier = modifier.fillMaxSize().background(Color(0xFF060A0F))) {
            SendspinBackdrop(
                bitmap = bitmap,
                artworkUrl = state.artworkUrl,
                levels = state.visualizerLevels,
                isPlaying = state.isPlaying,
            )

            BoxWithConstraints(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .safeDrawingPadding()
                        .padding(horizontal = 24.dp, vertical = 20.dp),
            ) {
                val wide = maxWidth >= 720.dp
                val title = state.title?.takeIf(String::isNotBlank) ?: "SendSpin audio"
                val subtitle = sendspinSubtitle(state.artist, state.album, state.serverName)
                val status = if (state.isPlaying) "Receiving from SendSpin" else "Paused"
                val controls: @Composable () -> Unit = {
                    SendspinControls(
                        isPlaying = state.isPlaying,
                        supportsPrevious = state.supports(SendspinControllerCommands.PREVIOUS),
                        supportsPlayPause = state.supports(playPauseCommand),
                        supportsNext = state.supports(SendspinControllerCommands.NEXT),
                        supportsStop = state.supports(SendspinControllerCommands.STOP),
                        supportsMute = state.supports(SendspinControllerCommands.MUTE),
                        supportsVolume = state.supports(SendspinControllerCommands.VOLUME),
                        muted = state.muted,
                        sliderVolume = sliderVolume,
                        onSliderChange = { sliderVolume = it.coerceIn(0f, 1f) },
                        onSliderFinished = {
                            send(
                                SendspinControllerCommands.VOLUME,
                                volume = (sliderVolume * 100).toInt().coerceIn(0, 100),
                            )
                        },
                        onPrevious = { send(SendspinControllerCommands.PREVIOUS) },
                        onPlayPause = { send(playPauseCommand) },
                        onNext = { send(SendspinControllerCommands.NEXT) },
                        onStop = { send(SendspinControllerCommands.STOP) },
                        onMute = { send(SendspinControllerCommands.MUTE, muted = !state.muted) },
                    )
                }

                if (wide) {
                    SendspinWideLayout(
                        bitmap = bitmap,
                        artworkUrl = state.artworkUrl,
                        levels = state.visualizerLevels,
                        isPlaying = state.isPlaying,
                        title = title,
                        subtitle = subtitle,
                        status = status,
                        onDismiss = ::dismiss,
                        onGroupClick = ::openGroupPanel,
                        onQueueClick = { showQueuePanel = true },
                        controls = controls,
                    )
                } else {
                    SendspinPhoneLayout(
                        bitmap = bitmap,
                        artworkUrl = state.artworkUrl,
                        levels = state.visualizerLevels,
                        isPlaying = state.isPlaying,
                        title = title,
                        subtitle = subtitle,
                        status = status,
                        onDismiss = ::dismiss,
                        onGroupClick = ::openGroupPanel,
                        onQueueClick = { showQueuePanel = true },
                        controls = controls,
                    )
                }
            }
            if (showGroupPanel) {
                SendspinGroupOverlay(
                    state = state,
                    onDismiss = { showGroupPanel = false },
                    onRefresh = {
                        SendspinReceiverService.refreshMusicAssistantGroups(context.applicationContext)
                    },
                    onSetServerUrl = { serverUrl ->
                        SendspinReceiverService.setMusicAssistantServerUrl(
                            context.applicationContext,
                            serverUrl,
                        )
                    },
                    onLogin = { serverUrl, username, password ->
                        SendspinReceiverService.loginMusicAssistant(
                            context = context.applicationContext,
                            serverUrl = serverUrl,
                            username = username,
                            password = password,
                        )
                    },
                    onSaveToken = { serverUrl, token ->
                        SendspinReceiverService.saveMusicAssistantToken(
                            context = context.applicationContext,
                            serverUrl = serverUrl,
                            token = token,
                        )
                    },
                    onTogglePlayer = { player, add ->
                        SendspinReceiverService.setMusicAssistantGroupMember(
                            context = context.applicationContext,
                            playerId = player.id,
                            add = add,
                        )
                    },
                )
            }
            if (showQueuePanel) {
                SendspinQueueOverlay(
                    queueItems = queueItems,
                    onDismiss = { showQueuePanel = false },
                    onPlayItem = { queueViewModel.playItem(it) },
                    onClearQueue = { queueViewModel.clearQueue() }
                )
            }
        }
    }
}

@Composable
private fun SendspinWideLayout(
    bitmap: ImageBitmap?,
    artworkUrl: String?,
    levels: List<Float>,
    isPlaying: Boolean,
    title: String,
    subtitle: String,
    status: String,
    onDismiss: () -> Unit,
    onGroupClick: () -> Unit,
    onQueueClick: () -> Unit,
    controls: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(42.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(vertical = 28.dp),
            contentAlignment = Alignment.Center,
        ) {
            SendspinArtwork(
                bitmap = bitmap,
                artworkUrl = artworkUrl,
                levels = levels,
                isPlaying = isPlaying,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .widthIn(max = 560.dp)
                        .aspectRatio(1f),
            )
        }

        Column(
            modifier = Modifier.weight(1f).fillMaxHeight().padding(vertical = 36.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            SendspinTopBar(status = status, onDismiss = onDismiss, onGroupClick = onGroupClick, onQueueClick = onQueueClick)
            Spacer(Modifier.height(36.dp))
            SendspinMetadata(title = title, subtitle = subtitle, wide = true)
            Spacer(Modifier.height(32.dp))
            SendspinVisualizer(
                levels = levels,
                isPlaying = isPlaying,
                modifier = Modifier.fillMaxWidth().height(128.dp).clip(RoundedCornerShape(8.dp)),
            )
            Spacer(Modifier.height(36.dp))
            controls()
        }
    }
}

@Composable
private fun SendspinPhoneLayout(
    bitmap: ImageBitmap?,
    artworkUrl: String?,
    levels: List<Float>,
    isPlaying: Boolean,
    title: String,
    subtitle: String,
    status: String,
    onDismiss: () -> Unit,
    onGroupClick: () -> Unit,
    onQueueClick: () -> Unit,
    controls: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val compactHeight = maxHeight < 700.dp
        val artworkSize =
            minOf(
                    maxWidth * 0.78f,
                    maxHeight * if (compactHeight) 0.30f else 0.36f,
                    if (compactHeight) 280.dp else 340.dp,
                )
                .coerceAtLeast(168.dp)
        val topGap = if (compactHeight) 12.dp else 22.dp
        val artworkGap = if (compactHeight) 14.dp else 22.dp
        val visualizerHeight = if (compactHeight) 64.dp else 84.dp

        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SendspinTopBar(status = status, onDismiss = onDismiss, onGroupClick = onGroupClick, onQueueClick = onQueueClick)
            Spacer(Modifier.height(topGap))
            SendspinArtwork(
                bitmap = bitmap,
                artworkUrl = artworkUrl,
                levels = levels,
                isPlaying = isPlaying,
                modifier = Modifier.size(artworkSize),
            )
            Spacer(Modifier.height(artworkGap))
            SendspinMetadata(title = title, subtitle = subtitle, wide = false)
            Spacer(Modifier.height(if (compactHeight) 14.dp else 18.dp))
            SendspinVisualizer(
                levels = levels,
                isPlaying = isPlaying,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(visualizerHeight)
                        .clip(RoundedCornerShape(8.dp)),
            )
            Spacer(Modifier.weight(1f))
            controls()
        }
    }
}

@Composable
private fun SendspinTopBar(
    status: String,
    onDismiss: () -> Unit,
    onGroupClick: () -> Unit,
    onQueueClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = "SpatialFin",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
            Text(
                text = status,
                style = MaterialTheme.typography.labelLarge,
                color = Color.White.copy(alpha = 0.68f),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onGroupClick, modifier = Modifier.size(48.dp)) {
                Icon(
                    painter = painterResource(CoreR.drawable.ic_speaker),
                    contentDescription = "SendSpin speakers",
                    tint = Color.White,
                )
            }
            IconButton(onClick = onQueueClick, modifier = Modifier.size(48.dp)) {
                Icon(
                    painter = painterResource(CoreR.drawable.ic_library),
                    contentDescription = "Queue",
                    tint = Color.White,
                )
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                Icon(
                    painter = painterResource(CoreR.drawable.ic_x),
                    contentDescription = "Close SendSpin player",
                    tint = Color.White,
                )
            }
        }
    }
}

@Composable
private fun SendspinMetadata(
    title: String,
    subtitle: String,
    wide: Boolean,
) {
    Column(
        horizontalAlignment = if (wide) Alignment.Start else Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = title,
            style = if (wide) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            maxLines = if (wide) 3 else 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (wide) TextAlign.Start else TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = subtitle,
            style = if (wide) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
            color = Color.White.copy(alpha = 0.72f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (wide) TextAlign.Start else TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
        )
    }
}

@Composable
private fun SendspinControls(
    isPlaying: Boolean,
    supportsPrevious: Boolean,
    supportsPlayPause: Boolean,
    supportsNext: Boolean,
    supportsStop: Boolean,
    supportsMute: Boolean,
    supportsVolume: Boolean,
    muted: Boolean,
    sliderVolume: Float,
    onSliderChange: (Float) -> Unit,
    onSliderFinished: () -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onStop: () -> Unit,
    onMute: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val compactControls = maxWidth < 340.dp
            val secondarySize = if (compactControls) 52.dp else 60.dp
            val secondaryIconSize = if (compactControls) 24.dp else 28.dp
            val playSize = if (compactControls) 76.dp else 88.dp
            val playIconSize = if (compactControls) 34.dp else 38.dp
            val controlGap = if (compactControls) 12.dp else 18.dp

            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                PlayerControlButton(
                    icon = CoreR.drawable.ic_skip_back,
                    contentDescription = "Previous",
                    enabled = supportsPrevious,
                    size = secondarySize,
                    iconSize = secondaryIconSize,
                    onClick = onPrevious,
                )
                Spacer(Modifier.width(controlGap))
                PlayerControlButton(
                    icon = if (isPlaying) CoreR.drawable.ic_pause else CoreR.drawable.ic_play,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    enabled = supportsPlayPause,
                    size = playSize,
                    iconSize = playIconSize,
                    prominent = true,
                    onClick = onPlayPause,
                )
                Spacer(Modifier.width(controlGap))
                PlayerControlButton(
                    icon = CoreR.drawable.ic_skip_forward,
                    contentDescription = "Next",
                    enabled = supportsNext,
                    size = secondarySize,
                    iconSize = secondaryIconSize,
                    onClick = onNext,
                )
                if (supportsStop) {
                    Spacer(Modifier.width(controlGap))
                    PlayerControlButton(
                        icon = SendspinR.drawable.sendspin_ic_stop,
                        contentDescription = "Stop",
                        enabled = true,
                        size = secondarySize,
                        iconSize = if (compactControls) 22.dp else 26.dp,
                        onClick = onStop,
                    )
                }
            }
        }

        if (supportsVolume || supportsMute) {
            Row(
                modifier = Modifier.padding(top = 26.dp).fillMaxWidth().widthIn(max = 560.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onMute,
                    enabled = supportsMute,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        painter =
                            painterResource(
                                if (muted || sliderVolume <= 0f) {
                                    CoreR.drawable.ic_volume_0
                                } else {
                                    CoreR.drawable.ic_volume
                                }
                            ),
                        contentDescription = if (muted) "Unmute" else "Mute",
                        tint = Color.White.copy(alpha = if (supportsMute) 0.9f else 0.38f),
                    )
                }
                Slider(
                    value = sliderVolume,
                    onValueChange = onSliderChange,
                    onValueChangeFinished = onSliderFinished,
                    valueRange = 0f..1f,
                    enabled = supportsVolume,
                    colors =
                        SliderDefaults.colors(
                            activeTrackColor = Color.White,
                            inactiveTrackColor = Color.White.copy(alpha = 0.25f),
                            thumbColor = Color.White,
                        ),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${(sliderVolume * 100).toInt()}%",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White.copy(alpha = 0.74f),
                    textAlign = TextAlign.End,
                    modifier = Modifier.padding(start = 12.dp).widthIn(min = 44.dp),
                )
            }
        }
    }
}

@Composable
private fun SendspinGroupOverlay(
    state: SendspinReceiverUiState,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onSetServerUrl: (String) -> Unit,
    onLogin: (String, String, String) -> Unit,
    onSaveToken: (String, String) -> Unit,
    onTogglePlayer: (SendspinMusicAssistantPlayer, Boolean) -> Unit,
) {
    var serverUrl by remember(state.musicAssistantServerUrl) {
        mutableStateOf(state.musicAssistantServerUrl.orEmpty())
    }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    val needsAuth =
        state.musicAssistantAuthState == SendspinMusicAssistantAuthState.MISSING ||
            state.musicAssistantAuthState == SendspinMusicAssistantAuthState.INVALID ||
            state.musicAssistantAuthState == SendspinMusicAssistantAuthState.ERROR
    val visiblePlayers =
        state.musicAssistantPlayers
            .filter { it.isCurrent || it.inActiveGroup || it.canToggleGroup }
            .sortedWith(
                compareByDescending<SendspinMusicAssistantPlayer> { it.isCurrent }
                    .thenByDescending { it.inActiveGroup }
                    .thenBy { it.name.lowercase() }
            )

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.54f))
                .safeDrawingPadding()
                .padding(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = Color(0xF2141A22),
            contentColor = Color.White,
            modifier = Modifier.fillMaxWidth().widthIn(max = 560.dp),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Speakers",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text =
                                state.musicAssistantServerUrl
                                    ?.removePrefix("http://")
                                    ?.removePrefix("https://")
                                    ?: "Music Assistant",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.68f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (state.musicAssistantLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 8.dp).size(28.dp),
                            color = Color.White,
                            strokeWidth = 2.dp,
                        )
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(44.dp)) {
                        Icon(
                            painter = painterResource(CoreR.drawable.ic_x),
                            contentDescription = "Close speakers",
                            tint = Color.White,
                        )
                    }
                }

                state.musicAssistantError?.takeIf(String::isNotBlank)?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFFFB4A9),
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }

                if (needsAuth || state.musicAssistantServerUrl.isNullOrBlank()) {
                    SendspinMusicAssistantAuthPanel(
                        serverUrl = serverUrl,
                        username = username,
                        password = password,
                        token = token,
                        loading = state.musicAssistantLoading,
                        authState = state.musicAssistantAuthState,
                        onServerUrlChange = { serverUrl = it },
                        onUsernameChange = { username = it },
                        onPasswordChange = { password = it },
                        onTokenChange = { token = it },
                        onSetServerUrl = { onSetServerUrl(serverUrl) },
                        onLogin = { onLogin(serverUrl, username, password) },
                        onSaveToken = { onSaveToken(serverUrl, token) },
                    )
                } else {
                    Row(
                        modifier = Modifier.padding(top = 14.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = groupSummary(state),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.72f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onRefresh, enabled = !state.musicAssistantLoading) {
                            Text("Refresh")
                        }
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                        color = Color.White.copy(alpha = 0.14f),
                    )
                    if (visiblePlayers.isEmpty() && !state.musicAssistantLoading) {
                        Text(
                            text = "No compatible speakers found",
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.White.copy(alpha = 0.72f),
                            modifier = Modifier.padding(vertical = 24.dp),
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(visiblePlayers, key = { it.id }) { player ->
                                SendspinGroupPlayerRow(
                                    player = player,
                                    busy = player.id in state.musicAssistantBusyPlayerIds,
                                    onToggle = {
                                        onTogglePlayer(player, !player.inActiveGroup)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SendspinMusicAssistantAuthPanel(
    serverUrl: String,
    username: String,
    password: String,
    token: String,
    loading: Boolean,
    authState: SendspinMusicAssistantAuthState,
    onServerUrlChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onSetServerUrl: () -> Unit,
    onLogin: () -> Unit,
    onSaveToken: () -> Unit,
) {
    Column(modifier = Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text =
                when (authState) {
                    SendspinMusicAssistantAuthState.INVALID -> "Music Assistant rejected the saved token."
                    SendspinMusicAssistantAuthState.AUTHENTICATING -> "Signing in..."
                    else -> "Connect SpatialFin to Music Assistant once to manage speakers here."
                },
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.76f),
        )
        OutlinedTextField(
            value = serverUrl,
            onValueChange = onServerUrlChange,
            singleLine = true,
            label = { Text("Server URL") },
            placeholder = { Text("http://192.168.1.89:8095") },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            TextButton(
                onClick = onSetServerUrl,
                enabled = !loading && serverUrl.isNotBlank(),
            ) {
                Text("Use server")
            }
        }
        HorizontalDivider(color = Color.White.copy(alpha = 0.14f))
        OutlinedTextField(
            value = username,
            onValueChange = onUsernameChange,
            singleLine = true,
            label = { Text("Username") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChange,
            singleLine = true,
            label = { Text("Password") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = onLogin,
            enabled = !loading && serverUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Sign in")
        }
        HorizontalDivider(color = Color.White.copy(alpha = 0.14f))
        OutlinedTextField(
            value = token,
            onValueChange = onTokenChange,
            singleLine = true,
            label = { Text("Access token") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = onSaveToken,
            enabled = !loading && serverUrl.isNotBlank() && token.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Save token")
        }
    }
}

@Composable
private fun SendspinGroupPlayerRow(
    player: SendspinMusicAssistantPlayer,
    busy: Boolean,
    onToggle: () -> Unit,
) {
    val enabled = player.canToggleGroup && !busy
    val status = playerStatus(player)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(
                    if (player.inActiveGroup) {
                        Color.White.copy(alpha = 0.14f)
                    } else {
                        Color.White.copy(alpha = 0.07f)
                    }
                )
                .clickable(enabled = enabled, onClick = onToggle)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    color = Color.White,
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    painter = painterResource(CoreR.drawable.ic_speaker),
                    contentDescription = null,
                    tint = Color.White.copy(alpha = if (enabled || player.inActiveGroup) 0.92f else 0.42f),
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
            Text(
                text = player.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = Color.White.copy(alpha = if (player.available) 1f else 0.46f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.62f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            painter =
                painterResource(
                    if (player.inActiveGroup) {
                        CoreR.drawable.ic_check
                    } else {
                        CoreR.drawable.ic_plus
                    }
                ),
            contentDescription = if (player.inActiveGroup) "Remove speaker" else "Add speaker",
            tint =
                if (enabled) {
                    Color.White
                } else {
                    Color.White.copy(alpha = if (player.inActiveGroup) 0.78f else 0.30f)
                },
            modifier = Modifier.size(24.dp),
        )
    }
}

private fun groupSummary(state: SendspinReceiverUiState): String {
    val count = state.musicAssistantActiveMemberIds.size.coerceAtLeast(1)
    val current =
        state.musicAssistantPlayers.firstOrNull { it.id == state.musicAssistantCurrentPlayerId }
            ?.name
            ?: "SpatialFin"
    return if (count == 1) {
        "$current is playing alone"
    } else {
        "$current is grouped with ${count - 1} other speaker${if (count == 2) "" else "s"}"
    }
}

private fun playerStatus(player: SendspinMusicAssistantPlayer): String =
    when {
        player.isCurrent -> "This device"
        player.inActiveGroup -> "Playing in this group"
        !player.available -> "Unavailable"
        !player.enabled -> "Disabled"
        !player.canToggleGroup -> "Not compatible with this group"
        player.powered == false -> "Available, powered off"
        else -> player.playbackState?.takeIf(String::isNotBlank)?.replaceFirstChar(Char::titlecase)
            ?: "Available"
    }

@Composable
private fun PlayerControlButton(
    @DrawableRes icon: Int,
    contentDescription: String,
    enabled: Boolean,
    size: Dp,
    iconSize: Dp,
    modifier: Modifier = Modifier,
    prominent: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier.size(size),
        shape = CircleShape,
        color =
            if (prominent) {
                Color.White
            } else {
                Color.White.copy(alpha = 0.12f)
            },
        contentColor =
            if (prominent) {
                Color(0xFF101820)
            } else {
                Color.White
            },
        enabled = enabled,
        onClick = onClick,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(icon),
                contentDescription = contentDescription,
                tint =
                    if (enabled) {
                        if (prominent) Color(0xFF101820) else Color.White
                    } else {
                        Color.White.copy(alpha = 0.34f)
                    },
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

@Composable
private fun SendspinArtwork(
    bitmap: ImageBitmap?,
    artworkUrl: String?,
    levels: List<Float>,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White.copy(alpha = 0.08f)),
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (!artworkUrl.isNullOrBlank()) {
            AsyncImage(
                model = artworkUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            SendspinVisualizer(
                levels = levels,
                isPlaying = isPlaying,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            colors =
                                listOf(
                                    Color.White.copy(alpha = 0.10f),
                                    Color.Transparent,
                                    Color.Black.copy(alpha = 0.18f),
                                )
                        )
                    )
        )
    }
}

@Composable
private fun SendspinBackdrop(
    bitmap: ImageBitmap?,
    artworkUrl: String?,
    levels: List<Float>,
    isPlaying: Boolean,
) {
    Box(Modifier.fillMaxSize()) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(64.dp),
            )
        } else if (!artworkUrl.isNullOrBlank()) {
            AsyncImage(
                model = artworkUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(64.dp),
            )
        } else {
            SendspinVisualizer(
                levels = levels,
                isPlaying = isPlaying,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0xE8080B10),
                            Color(0xCC0C1018),
                            Color(0xF306090D),
                        )
                    )
                )
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color.White.copy(alpha = 0.16f), Color.Transparent),
                        radius = 1100f,
                    )
                )
        )
    }
}

@Composable
private fun SendspinVisualizer(
    levels: List<Float>,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "sendspin-visualizer")
    val fallbackA by
        transition.animateFloat(
            initialValue = 0.20f,
            targetValue = 0.76f,
            animationSpec =
                infiniteRepeatable(
                    animation = tween(durationMillis = 680, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
            label = "sendspin-visualizer-a",
        )
    val fallbackB by
        transition.animateFloat(
            initialValue = 0.64f,
            targetValue = 0.28f,
            animationSpec =
                infiniteRepeatable(
                    animation = tween(durationMillis = 760, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
            label = "sendspin-visualizer-b",
        )
    val fallbackC by
        transition.animateFloat(
            initialValue = 0.34f,
            targetValue = 0.94f,
            animationSpec =
                infiniteRepeatable(
                    animation = tween(durationMillis = 820, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
            label = "sendspin-visualizer-c",
        )

    val resolvedLevels =
        when {
            levels.isNotEmpty() -> levels.toStableVisualizerLevels()
            isPlaying ->
                listOf(
                    fallbackA,
                    fallbackB,
                    fallbackC,
                    fallbackB,
                    fallbackA,
                    0.48f,
                    fallbackC,
                    0.34f,
                    fallbackA,
                    0.56f,
                    fallbackC,
                    fallbackB,
                    0.42f,
                    fallbackA,
                    0.30f,
                    fallbackB,
                )
            else ->
                listOf(
                    0.20f,
                    0.38f,
                    0.28f,
                    0.48f,
                    0.24f,
                    0.36f,
                    0.18f,
                    0.30f,
                    0.22f,
                    0.42f,
                    0.32f,
                    0.50f,
                    0.26f,
                    0.34f,
                    0.20f,
                    0.30f,
                )
        }
    val animatedLevels =
        resolvedLevels.mapIndexed { index, level ->
            animateFloatAsState(
                    targetValue = level,
                    animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
                    label = "sendspin-visualizer-level-$index",
                )
                .value
        }

    Canvas(modifier = modifier.background(Color.White.copy(alpha = 0.06f))) {
        val barCount = animatedLevels.size.coerceAtLeast(1)
        val gap = size.width * 0.012f
        val barWidth = ((size.width - gap * (barCount + 1)) / barCount).coerceAtLeast(3f)
        val maxHeight = size.height * 0.78f
        val baseY = size.height * 0.86f
        animatedLevels.forEachIndexed { index, level ->
            val height = (maxHeight * level.coerceIn(0.08f, 1f)).coerceAtLeast(size.height * 0.08f)
            val x = gap + index * (barWidth + gap)
            val alpha = 0.54f + (index.toFloat() / barCount.toFloat()) * 0.32f
            drawRoundRect(
                color = Color.White.copy(alpha = alpha),
                topLeft = androidx.compose.ui.geometry.Offset(x, baseY - height),
                size = androidx.compose.ui.geometry.Size(barWidth, height),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
            )
        }
    }
}

private fun List<Float>.toStableVisualizerLevels(): List<Float> {
    val levels = takeIf { it.isNotEmpty() } ?: return emptyList()
    if (levels.size == VISUALIZER_BAR_COUNT) {
        return levels.map { it.coerceIn(0.08f, 1f) }
    }
    return List(VISUALIZER_BAR_COUNT) { index ->
        levels[index % levels.size].coerceIn(0.08f, 1f)
    }
}

private const val VISUALIZER_BAR_COUNT = 16

private fun sendspinSubtitle(
    artist: String?,
    album: String?,
    serverName: String?,
): String {
    val mediaParts = listOfNotNull(
        artist?.takeIf(String::isNotBlank),
        album?.takeIf(String::isNotBlank),
    )
    if (mediaParts.isNotEmpty()) return mediaParts.joinToString(" - ")
    return serverName?.takeIf(String::isNotBlank)?.let { "$it - SendSpin" } ?: "SendSpin"
}
