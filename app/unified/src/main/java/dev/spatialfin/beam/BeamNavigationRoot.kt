package dev.spatialfin.beam

import dev.spatialfin.R
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import dev.jdtech.jellyfin.presentation.settings.MusicAssistantAuthDialog
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import coil3.compose.AsyncImage
import androidx.compose.runtime.CompositionLocalProvider
import com.skydoves.compose.stability.runtime.TraceRecomposition
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import dev.jdtech.jellyfin.core.llm.LlmModelManager
import dev.jdtech.jellyfin.core.llm.VoiceCapability
import dev.jdtech.jellyfin.models.SpatialFinEpisode
import dev.jdtech.jellyfin.models.SpatialFinItem
import dev.jdtech.jellyfin.models.SpatialFinMovie
import dev.jdtech.jellyfin.models.SpatialFinSeason
import dev.jdtech.jellyfin.models.SpatialFinShow
import dev.jdtech.jellyfin.player.beam.LocalBeamWidth
import dev.jdtech.jellyfin.player.beam.isCompact
import dev.jdtech.jellyfin.player.beam.rememberBeamWidth
import dev.jdtech.jellyfin.player.xr.voice.VoiceState
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.settings.voice.VoiceTelemetryStore
import dev.spatialfin.unified.HomeVoiceController
import dev.spatialfin.unified.HomeVoiceNavigation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.core.presentation.components.CinematicBackdrop
import dev.jdtech.jellyfin.models.CollectionType
import dev.jdtech.jellyfin.models.SpatialFinAudioBook
import dev.jdtech.jellyfin.models.SpatialFinAudioTrack
import dev.jdtech.jellyfin.player.beam.BeamPlayerActivity
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.viewmodels.MainState
import dev.jdtech.jellyfin.models.SpatialFinMusicAlbum
import dev.jdtech.jellyfin.models.SpatialFinMusicArtist
import dev.jdtech.jellyfin.models.SpatialFinPlaylist
import dev.jdtech.jellyfin.models.toAudioQueueItem
import dev.spatialfin.unified.audio.JellyfinAudioDetailScreen
import dev.spatialfin.unified.audio.JellyfinAudioDetailType
import dev.spatialfin.unified.audio.JellyfinAudioLibraryScreen
import dev.spatialfin.unified.audio.JellyfinAudioMiniPlayer
import dev.spatialfin.unified.audio.JellyfinAudioNowPlayingScreen
import dev.spatialfin.unified.audio.LocalAudioPlaybackDispatcher
import java.util.UUID

private enum class BeamRoute {
    Welcome,
    Companion,
    Servers,
    AddServer,
    Users,
    Login,
    Home,
    Search,
    Library,
    Show,
    Season,
    Detail,
    Person,
    Local,
    Network,
    NetworkShare,
    NetworkAddShare,
    Downloads,
    Plugins,
    PluginBrowse,
    Settings,
    /** Dedicated Music Assistant search across tracks/albums/artists/etc. */
    MaSearch,
    /** Music Assistant album/artist/playlist detail. The selected
     *  [ServerMediaItem] is held in [BeamRoot]'s `maDetailSeed` state. */
    MaDetail,
    /** Native Jellyfin albums/artists/playlists/books. */
    AudioDetail,
}

private data class BeamTab(
    val route: BeamRoute,
    val label: String,
    val icon: ImageVector,
)

private val primaryTabs =
    listOf(
        BeamTab(BeamRoute.Home, "Home", Icons.Rounded.Home),
        BeamTab(BeamRoute.Search, "Search", Icons.Rounded.Search),
        BeamTab(BeamRoute.Downloads, "Downloads", Icons.Rounded.CloudDownload),
        BeamTab(BeamRoute.Plugins, "Sources", Icons.Rounded.Apps),
        BeamTab(BeamRoute.Settings, "Settings", Icons.Rounded.Settings),
    )

val LocalBeamBackground = androidx.compose.runtime.compositionLocalOf<(Any?) -> Unit> { {} }

private val AUDIO_COLLECTION_TYPES =
    setOf(CollectionType.Music, CollectionType.Playlists, CollectionType.Books)

@Composable
@TraceRecomposition(tag = "beam-shell", threshold = 3)
fun BeamNavigationRoot(
    state: MainState,
    appPreferences: AppPreferences,
    repository: JellyfinRepository,
    llmModelManager: LlmModelManager,
    voiceTelemetryStore: VoiceTelemetryStore,
    fcastSession: dev.spatialfin.fcast.session.CastSessionManager,
    musicAssistantRepository: dev.jdtech.jellyfin.data.musicassistant.repository.MusicAssistantRepository,
    maSession: dev.jdtech.jellyfin.data.musicassistant.repository.MaSessionRepository,
    maServiceClient: dev.jdtech.jellyfin.data.musicassistant.api.ServiceClient,
    onReconnect: () -> Unit = {},
    onFinishApp: () -> Unit = {},
    maDeepLink: kotlinx.coroutines.flow.StateFlow<dev.jdtech.jellyfin.deeplink.MaDeepLink.Parsed?>? = null,
    onMaDeepLinkHandled: () -> Unit = {},
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var currentRoute by rememberSaveable { mutableStateOf(BeamRoute.Welcome) }
    var voiceQuery by rememberSaveable { mutableStateOf<String?>(null) }
    var showMusicAssistantSettings by remember { mutableStateOf(false) }
    val maPlayDispatcher = remember(context, maSession, maServiceClient) {
        dev.spatialfin.unified.MaPlayDispatcher(context, maSession, maServiceClient)
    }
    var showNowPlaying by rememberSaveable { mutableStateOf(false) }
    var showJellyfinAudioNowPlaying by rememberSaveable { mutableStateOf(false) }
    val jellyfinAudioDispatcher = LocalAudioPlaybackDispatcher.current

    val sendspinState by dev.jdtech.jellyfin.sendspin.receiver.SendspinReceiverSession.state.collectAsStateWithLifecycle()
    // Make the MA session track local SendSpin playback → one unified player.
    dev.spatialfin.unified.music.MaLocalPlaybackBridge(maSession)
    val musicAssistantSubtitle = when (sendspinState.musicAssistantAuthState) {
        dev.jdtech.jellyfin.sendspin.receiver.SendspinMusicAssistantAuthState.AUTHENTICATED -> sendspinState.musicAssistantServerUrl ?: "Configured server"
        dev.jdtech.jellyfin.sendspin.receiver.SendspinMusicAssistantAuthState.AUTHENTICATING -> "Authenticating..."
        dev.jdtech.jellyfin.sendspin.receiver.SendspinMusicAssistantAuthState.INVALID -> "Invalid credentials"
        dev.jdtech.jellyfin.sendspin.receiver.SendspinMusicAssistantAuthState.ERROR -> "Connection error"
        else -> "Not configured"
    }

    val voiceController = remember(context) {
        HomeVoiceController(
            applicationContext = context.applicationContext,
            appPreferences = appPreferences,
            repository = repository,
            llmModelManagerProvider = { llmModelManager },
            voiceTelemetryStore = voiceTelemetryStore,
        )
    }
    val voiceState by voiceController.voiceService.state.collectAsStateWithLifecycle()
    val voicePartial by voiceController.voiceService.partialTranscript.collectAsStateWithLifecycle()
    val isTtsSpeaking by voiceController.tts.isSpeaking.collectAsStateWithLifecycle()
    val voiceCapability by llmModelManager.voiceCapability.collectAsStateWithLifecycle()

    val remoteControlViewModel: dev.spatialfin.unified.RemoteControlViewModel =
        androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel()
    val activeRemoteSessions by remoteControlViewModel.activeRemoteSessions.collectAsStateWithLifecycle()
    val activeRemoteSession by remoteControlViewModel.activeRemoteSession.collectAsStateWithLifecycle()
    val activeMediaStreams by remoteControlViewModel.activeMediaStreams.collectAsStateWithLifecycle()

    // Show voice UI whenever the device can run a usable backend (AICore / NPU / GPU)
    // or the user has a cloud API key. Hide it on CPU-only LiteRT — the experience
    // is measured in tens of seconds per reply and users tap the mic expecting
    // something closer to conversational. UNKNOWN keeps the UI visible during the
    // initial probe so we don't flash-hide during boot.
    val showVoiceUi = voiceCapability != VoiceCapability.CPU_ONLY
    val assistantSpokenReplies = appPreferences.getValue(appPreferences.voiceAssistantSpokenReplies)
    val assistantVoiceName = appPreferences.getValue(appPreferences.voiceAssistantVoice)

    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    var prefilledUsername by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedNetworkShareId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedLibraryId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedLibraryName by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedLibraryType by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedShowId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedSeasonId by rememberSaveable { mutableStateOf<String?>(null) }
    var showBackRoute by rememberSaveable { mutableStateOf(BeamRoute.Home) }
    var seasonBackRoute by rememberSaveable { mutableStateOf(BeamRoute.Home) }
    var selectedDetailItemId by rememberSaveable { mutableStateOf<String?>(null) }
    var detailBackRoute by rememberSaveable { mutableStateOf(BeamRoute.Home) }
    var selectedPersonId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedAudioItemId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedAudioTitle by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedAudioDetailType by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedAudioParentId by rememberSaveable { mutableStateOf<String?>(null) }
    var audioBackRoute by rememberSaveable { mutableStateOf(BeamRoute.Home) }
    // Phase 2 — MA detail navigation. Holds the seed ServerMediaItem that
    // the user tapped (search/recommendation) so the detail screen can call
    // its `/get` and child-list endpoints. Not @Saveable because the rich
    // type isn't, and a process-death restore would reasonably bounce the
    // user to the search route anyway.
    var maDetailSeed by remember {
        mutableStateOf<dev.jdtech.jellyfin.data.musicassistant.data.model.server.ServerMediaItem?>(null)
    }
    var maDetailKind by remember {
        mutableStateOf<dev.spatialfin.unified.music.MaDetailViewModel.DetailKind?>(null)
    }
    var maDetailBackRoute by rememberSaveable { mutableStateOf(BeamRoute.MaSearch) }

    fun openMaBrowse(target: dev.spatialfin.unified.music.MaBrowseTarget) {
        maDetailSeed = target.item
        maDetailKind = target.detailKind
        maDetailBackRoute = currentRoute
        currentRoute = BeamRoute.MaDetail
    }

    fun openJellyfinAudioDetail(
        itemId: UUID,
        title: String,
        detailType: JellyfinAudioDetailType,
        parentId: UUID? = null,
        backRoute: BeamRoute = currentRoute,
    ) {
        selectedAudioItemId = itemId.toString()
        selectedAudioTitle = title
        selectedAudioDetailType = detailType.name
        selectedAudioParentId = parentId?.toString()
        audioBackRoute = backRoute
        currentRoute = BeamRoute.AudioDetail
    }
    var selectedPluginId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedPluginRowId by rememberSaveable { mutableStateOf<String?>(null) }
    var personBackRoute by rememberSaveable { mutableStateOf(BeamRoute.Home) }
    var beamBackgroundUrl by remember { mutableStateOf<Any?>(null) }

    // When the AI layer produces a search action, it sets voiceSearchQuery; mirror
    // that into the Beam Search tab so existing search UI and Seerr fallback still work.
    LaunchedEffect(voiceController.voiceSearchQuery) {
        val query = voiceController.voiceSearchQuery
        if (!query.isNullOrBlank()) {
            voiceQuery = query
            currentRoute = BeamRoute.Search
            voiceController.voiceSearchQuery = null
        }
    }

    val navigation = remember(context, fcastSession, jellyfinAudioDispatcher) {
        object : HomeVoiceNavigation {
            override fun launchItem(item: SpatialFinItem): Boolean {
                val routed = dev.spatialfin.fcast.session.launchPlayback(
                    context = context,
                    sessionManager = fcastSession,
                    scope = coroutineScope,
                    item = item,
                    splitAvIntentBuilder = {
                        BeamPlayerActivity.createIntentForSpatialItem(
                            context, item, splitAvVideoRole = true,
                        )
                    },
                ) { BeamPlayerActivity.createIntentForSpatialItem(context, item) }
                if (routed) return true
                // Non-playable (Show/Season/BoxSet) or playback couldn't start — jump to detail.
                return when (item) {
                    is SpatialFinMovie -> {
                        selectedDetailItemId = item.id.toString()
                        detailBackRoute = currentRoute
                        currentRoute = BeamRoute.Detail
                        true
                    }
                    is SpatialFinEpisode -> {
                        selectedDetailItemId = item.id.toString()
                        detailBackRoute = currentRoute
                        currentRoute = BeamRoute.Detail
                        true
                    }
                    is SpatialFinShow -> {
                        selectedShowId = item.id.toString()
                        showBackRoute = currentRoute
                        currentRoute = BeamRoute.Show
                        true
                    }
                    is SpatialFinSeason -> {
                        selectedSeasonId = item.id.toString()
                        seasonBackRoute = currentRoute
                        currentRoute = BeamRoute.Season
                        true
                    }
                    is SpatialFinMusicAlbum -> {
                        openJellyfinAudioDetail(item.id, item.name, JellyfinAudioDetailType.Album)
                        true
                    }
                    is SpatialFinMusicArtist -> {
                        openJellyfinAudioDetail(item.id, item.name, JellyfinAudioDetailType.Artist)
                        true
                    }
                    is SpatialFinPlaylist -> {
                        openJellyfinAudioDetail(item.id, item.name, JellyfinAudioDetailType.Playlist)
                        true
                    }
                    is SpatialFinAudioBook -> {
                        openJellyfinAudioDetail(item.id, item.name, JellyfinAudioDetailType.Book)
                        true
                    }
                    is SpatialFinAudioTrack -> {
                        jellyfinAudioDispatcher?.playQueue(listOf(item.toAudioQueueItem()))
                        true
                    }
                    else -> {
                        voiceController.voiceSearchQuery = item.name
                        true
                    }
                }
            }

            override fun launchMusic(query: String): Boolean {
                coroutineScope.launch {
                    dev.spatialfin.unified.launchMusicAssistantQuery(
                        context = context,
                        repository = musicAssistantRepository,
                        session = maSession,
                        serviceClient = maServiceClient,
                        query = query,
                    )
                }
                return true
            }

            override fun goHome() { currentRoute = BeamRoute.Home }
            override fun goBack() { currentRoute = BeamRoute.Home }
            override fun closeApp() { onFinishApp() }
        }
    }

    val latestHasMicPermission = rememberUpdatedState(hasMicPermission)
    val latestAssistantSpoken = rememberUpdatedState(assistantSpokenReplies)
    val latestAssistantVoice = rememberUpdatedState(assistantVoiceName)

    val micPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            hasMicPermission = granted
            if (granted) {
                voiceController.requestVoiceCommand(
                    scope = coroutineScope,
                    source = "beam-permission-grant",
                    currentVoiceState = voiceState,
                    currentTtsSpeaking = isTtsSpeaking,
                    hasAudioPermission = true,
                    assistantSpokenReplies = latestAssistantSpoken.value,
                    assistantVoiceName = latestAssistantVoice.value,
                    navigation = navigation,
                    onAudioPermissionMissing = {},
                )
            }
        }

    fun startVoiceCommand(source: String) {
        voiceController.requestVoiceCommand(
            scope = coroutineScope,
            source = source,
            currentVoiceState = voiceState,
            currentTtsSpeaking = isTtsSpeaking,
            hasAudioPermission = latestHasMicPermission.value,
            assistantSpokenReplies = latestAssistantSpoken.value,
            assistantVoiceName = latestAssistantVoice.value,
            navigation = navigation,
            onAudioPermissionMissing = {
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            },
        )
    }

    val onVoiceMicClick: () -> Unit = {
        if (voiceController.isVoiceTurnBusy(voiceState, isTtsSpeaking)) {
            // On Beam, a mic tap during TTS or listening means "stop". Don't
            // re-arm the follow-up window — the recognizer re-opening 200ms
            // after a stop and transcribing whatever the user says next is a
            // UX bug, not a feature. (XR retains the auto-resume because the
            // pinch-to-interrupt gesture there genuinely means "let me answer".)
            voiceController.interruptVoiceCommand(
                scope = coroutineScope,
                reason = "beam-mic-tap",
                currentVoiceState = voiceState,
                currentTtsSpeaking = isTtsSpeaking,
                requestFollowUp = { startVoiceCommand("beam-follow-up-interrupt") },
                resumeAfterInterrupt = false,
            )
        } else {
            startVoiceCommand("beam-mic-tap")
        }
    }

    voiceController.RegisterEffects(
        requestFollowUp = { startVoiceCommand("beam-follow-up-auto") },
    )

    DisposableEffect(voiceController) { onDispose { voiceController.destroy() } }

    LaunchedEffect(
        state.isLoading,
        state.hasServers,
        state.hasCurrentServer,
        state.hasCurrentUser,
        appPreferences.getValue(appPreferences.onboardingCompleted),
    ) {
        if (state.isLoading) {
            timber.log.Timber.d("[UserSwitch] LaunchedEffect: isLoading=true, skipping. currentRoute=$currentRoute")
            return@LaunchedEffect
        }

        val onboardingCompleted = appPreferences.getValue(appPreferences.onboardingCompleted)
        val newRoute =
            when {
                !onboardingCompleted && currentRoute == BeamRoute.Companion -> BeamRoute.Companion
                !onboardingCompleted -> BeamRoute.Welcome
                state.hasServers && state.hasCurrentServer && state.hasCurrentUser ->
                    if (state.isOfflineMode) BeamRoute.Downloads else BeamRoute.Home
                state.hasServers && state.hasCurrentServer -> BeamRoute.Users
                state.hasServers -> BeamRoute.Servers
                else -> BeamRoute.Local
            }
        timber.log.Timber.d("[UserSwitch] LaunchedEffect: resolved newRoute=$newRoute, currentRoute=$currentRoute, hasCurrentUser=${state.hasCurrentUser}")
        currentRoute = newRoute
    }

    // When going offline, redirect away from Home/Search (unusable without server).
    // When coming back online, return to Home.
    LaunchedEffect(state.isOfflineMode) {
        if (!state.isLoading && appPreferences.getValue(appPreferences.onboardingCompleted)) {
            if (state.isOfflineMode && (currentRoute == BeamRoute.Home || currentRoute == BeamRoute.Search)) {
                currentRoute = BeamRoute.Downloads
            } else if (!state.isOfflineMode && currentRoute == BeamRoute.Downloads) {
                currentRoute = BeamRoute.Home
            }
        }
    }

    val showPrimaryNavigation =
        !state.isLoading && appPreferences.getValue(appPreferences.onboardingCompleted)

    val beamWidth = rememberBeamWidth()
    val useBottomNav = beamWidth.isCompact && showPrimaryNavigation

    val sidebarContent: @Composable () -> Unit = {
        BeamSidebar(
            currentRoute = currentRoute,
            isOfflineMode = state.isOfflineMode,
            onNavigate = { route -> currentRoute = route },
            onReconnect = onReconnect,
            voiceState = voiceState,
            voicePartial = voicePartial,
            onVoiceClick = onVoiceMicClick,
            showVoice = showVoiceUi,
        )
    }

    CompositionLocalProvider(
        LocalBeamBackground provides { beamBackgroundUrl = it },
        LocalBeamWidth provides beamWidth,
        dev.spatialfin.fcast.session.LocalFCastSession provides fcastSession,
        dev.spatialfin.unified.LocalMaPlayDispatcher provides maPlayDispatcher,
    ) {
        Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0F141C))) {
            CinematicBackdrop(
                modifier = Modifier.fillMaxSize(),
                blurRadius = 80.dp,
                scrimTopAlpha = 0.55f,
                scrimBottomAlpha = 0.75f,
            ) {
                AsyncImage(
                    model = beamBackgroundUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
            Scaffold(
                containerColor = Color.Transparent,
                floatingActionButton = {
                    if (showPrimaryNavigation && showVoiceUi) {
                        BeamVoiceFab(
                            voiceState = voiceState,
                            isTtsSpeaking = isTtsSpeaking,
                            partialTranscript = voicePartial,
                            onClick = onVoiceMicClick,
                        )
                    }
                },
                bottomBar = {
                    Column {
                        dev.spatialfin.fcast.session.FCastMiniController(
                            sessionManager = fcastSession,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                        // When SendSpin is actively receiving (we're the
                        // audio output for someone else's cast), its mini-player
                        // owns the slot — it has the live PCM-side metadata
                        // and the right transport command surface. Otherwise
                        // fall through to the MA controller mini-player.
                        // Unified player: when MA is presenting this playback,
                        // the MA mini-player (with queue / playlist / party on
                        // expand) owns the slot and the SendSpin one is hidden.
                        val maNowPlaying by maSession.session.collectAsStateWithLifecycle()
                        dev.spatialfin.sendspin.SendspinMiniPlayer(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            suppressed = maNowPlaying.nowPlaying != null ||
                                maNowPlaying.pendingPlayUri != null,
                        )
                        dev.spatialfin.unified.music.MaMiniPlayer(
                            session = maSession,
                            onExpand = { showNowPlaying = true },
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                        jellyfinAudioDispatcher?.let { dispatcher ->
                            JellyfinAudioMiniPlayer(
                                dispatcher = dispatcher,
                                onExpand = { showJellyfinAudioNowPlaying = true },
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                // pendingPlayUri covers the optimistic window after an
                                // MA play tap, before MA echoes nowPlaying — the
                                // arbitration bridge is about to clear this queue.
                                suppressed = maNowPlaying.nowPlaying != null ||
                                    maNowPlaying.pendingPlayUri != null,
                            )
                        }
                        dev.spatialfin.unified.RemoteControlMiniPlayer(
                            session = activeRemoteSession,
                            availableSessions = activeRemoteSessions,
                            baseUrl = repository.getBaseUrl(),
                            accessToken = repository.getAccessToken(),
                            mediaStreams = activeMediaStreams,
                            onSelectSession = { sessionId ->
                                remoteControlViewModel.selectRemoteSession(sessionId)
                            },
                            onPlayStateCommand = { cmd ->
                                activeRemoteSession?.id?.let {
                                    remoteControlViewModel.sendCommand(it, cmd)
                                }
                            },
                            onGeneralCommand = { cmd, args ->
                                activeRemoteSession?.id?.let {
                                    remoteControlViewModel.sendGeneralCommand(it, cmd, args)
                                }
                            },
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                        if (useBottomNav) {
                            BeamBottomNavigationRow(
                                currentRoute = currentRoute,
                                isOfflineMode = state.isOfflineMode,
                                onNavigate = { currentRoute = it },
                            )
                        }
                    }
                },
            ) { innerPadding ->
                Row(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .padding(
                                start = if (useBottomNav) 12.dp else 18.dp,
                                end = if (useBottomNav) 12.dp else 18.dp,
                                top = if (useBottomNav) 12.dp else 18.dp,
                                bottom = 0.dp,
                            ),
                ) {
            if (showPrimaryNavigation && !useBottomNav) {
                sidebarContent()
                Spacer(Modifier.width(18.dp))
            }
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                when {
                    state.isLoading -> {
                        BeamStatusScreen(
                            title = "SpatialFin",
                            body = "Loading your media...",
                            contentPadding = PaddingValues(0.dp),
                        )
                    }
                    currentRoute == BeamRoute.Welcome -> {
                        BeamWelcomeScreen(
                            contentPadding = PaddingValues(0.dp),
                            onImportFromCompanion = {
                                currentRoute = BeamRoute.Companion
                            },
                            onConnectToServer = {
                                appPreferences.setValue(appPreferences.onboardingCompleted, true)
                                currentRoute = BeamRoute.Servers
                            },
                            onUseLocalMedia = {
                                appPreferences.setValue(appPreferences.onboardingCompleted, true)
                                currentRoute = BeamRoute.Local
                            },
                        )
                    }
                    currentRoute == BeamRoute.Companion -> {
                        BeamCompanionScreen(
                            contentPadding = PaddingValues(0.dp),
                            onBack = { currentRoute = BeamRoute.Welcome },
                            onFinishApp = onFinishApp,
                        )
                    }
                    currentRoute == BeamRoute.AddServer -> {
                        BeamAddServerScreen(
                            contentPadding = PaddingValues(0.dp),
                            onSuccess = { currentRoute = BeamRoute.Users },
                            onBackClick = { currentRoute = BeamRoute.Servers },
                        )
                    }
                    currentRoute == BeamRoute.Login -> {
                        BeamLoginScreen(
                            contentPadding = PaddingValues(0.dp),
                            prefilledUsername = prefilledUsername,
                            onSuccess = { currentRoute = BeamRoute.Home },
                            onChangeServerClick = { currentRoute = BeamRoute.Servers },
                            onBackClick = { currentRoute = BeamRoute.Users },
                        )
                    }
                    currentRoute == BeamRoute.Home -> {
                        BeamHomeScreen(
                            contentPadding = PaddingValues(0.dp),
                            onOpenLibrary = { libraryId, libraryName, libraryType ->
                                selectedLibraryId = libraryId.toString()
                                selectedLibraryName = libraryName
                                selectedLibraryType = libraryType.name
                                currentRoute = BeamRoute.Library
                            },
                            onOpenShow = { showId ->
                                selectedShowId = showId.toString()
                                showBackRoute = BeamRoute.Home
                                currentRoute = BeamRoute.Show
                            },
                            onOpenSeason = { seasonId ->
                                selectedSeasonId = seasonId.toString()
                                seasonBackRoute = BeamRoute.Home
                                currentRoute = BeamRoute.Season
                            },
                            onOpenItem = { itemId ->
                                selectedDetailItemId = itemId.toString()
                                detailBackRoute = BeamRoute.Home
                                currentRoute = BeamRoute.Detail
                            },
                            onOpenJellyfinAudioDetail = { itemId, title, detailType ->
                                openJellyfinAudioDetail(
                                    itemId = itemId,
                                    title = title,
                                    detailType = detailType,
                                    backRoute = BeamRoute.Home,
                                )
                            },
                            onOpenPluginBrowse = { pluginId, rowId ->
                                selectedPluginId = pluginId
                                selectedPluginRowId = rowId
                                currentRoute = BeamRoute.PluginBrowse
                            },
                            onOpenMaBrowse = { uri, name ->
                                dev.spatialfin.unified.music.maDetailTargetForUri(uri, name)
                                    ?.let(::openMaBrowse)
                            },
                            userName = state.currentUser?.name,
                            onOpenServer = { currentRoute = BeamRoute.Servers },
                            onOpenUser = { currentRoute = BeamRoute.Users },
                            onOpenCast = { /* Handle cast later */ },
                        )
                    }
                    currentRoute == BeamRoute.PluginBrowse -> {
                        val pluginId = selectedPluginId
                        if (pluginId == null) {
                            currentRoute = BeamRoute.Home
                        } else {
                            dev.jdtech.jellyfin.plugins.ui.PluginBrowseScreen(
                                pluginId = pluginId,
                                rowId = selectedPluginRowId,
                                onBack = { currentRoute = BeamRoute.Home },
                                onItemClick = { item ->
                                    navigation.launchItem(item)
                                }
                            )
                        }
                    }
                    currentRoute == BeamRoute.Search -> {
                        BeamSearchScreen(
                            contentPadding = PaddingValues(0.dp),
                            voiceQuery = voiceQuery,
                            onVoiceQueryConsumed = { voiceQuery = null },
                            onOpenLibrary = { libraryId, libraryName, libraryType ->
                                selectedLibraryId = libraryId.toString()
                                selectedLibraryName = libraryName
                                selectedLibraryType = libraryType.name
                                currentRoute = BeamRoute.Library
                            },
                            onOpenShow = { showId ->
                                selectedShowId = showId.toString()
                                showBackRoute = BeamRoute.Search
                                currentRoute = BeamRoute.Show
                            },
                            onOpenSeason = { seasonId ->
                                selectedSeasonId = seasonId.toString()
                                seasonBackRoute = BeamRoute.Search
                                currentRoute = BeamRoute.Season
                            },
                            onOpenItem = { itemId ->
                                selectedDetailItemId = itemId.toString()
                                detailBackRoute = BeamRoute.Search
                                currentRoute = BeamRoute.Detail
                            },
                            onOpenJellyfinAudioDetail = { itemId, title, detailType ->
                                openJellyfinAudioDetail(
                                    itemId = itemId,
                                    title = title,
                                    detailType = detailType,
                                    backRoute = BeamRoute.Search,
                                )
                            },
                            onOpenMaSearch = { currentRoute = BeamRoute.MaSearch },
                        )
                    }
                    currentRoute == BeamRoute.Library -> {
                val libraryId = selectedLibraryId?.let(UUID::fromString)
                val libraryName = selectedLibraryName
                val libraryType = selectedLibraryType?.let(CollectionType::valueOf)
                if (libraryId == null || libraryName == null || libraryType == null) {
                    currentRoute = BeamRoute.Home
                } else {
                    if (libraryType in AUDIO_COLLECTION_TYPES) {
                        JellyfinAudioLibraryScreen(
                            libraryId = libraryId,
                            libraryName = libraryName,
                            libraryType = libraryType,
                            onBack = { currentRoute = BeamRoute.Home },
                            onDetailClick = { id, title, detailType ->
                                openJellyfinAudioDetail(
                                    itemId = id,
                                    title = title,
                                    detailType = detailType,
                                    parentId = libraryId,
                                    backRoute = BeamRoute.Library,
                                )
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        BeamLibraryScreen(
                            contentPadding = PaddingValues(0.dp),
                            parentId = libraryId,
                            title = libraryName,
                            type = libraryType,
                            onBack = { currentRoute = BeamRoute.Home },
                            onOpenLibrary = { nestedId, nestedName, nestedType ->
                                selectedLibraryId = nestedId.toString()
                                selectedLibraryName = nestedName
                                selectedLibraryType = nestedType.name
                                currentRoute = BeamRoute.Library
                            },
                            onOpenShow = { showId ->
                                selectedShowId = showId.toString()
                                showBackRoute = BeamRoute.Library
                                currentRoute = BeamRoute.Show
                            },
                            onOpenSeason = { seasonId ->
                                selectedSeasonId = seasonId.toString()
                                seasonBackRoute = BeamRoute.Library
                                currentRoute = BeamRoute.Season
                            },
                            onOpenItem = { itemId ->
                                selectedDetailItemId = itemId.toString()
                                detailBackRoute = BeamRoute.Library
                                currentRoute = BeamRoute.Detail
                            },
                        )
                    }
                }
                    }
                    currentRoute == BeamRoute.AudioDetail -> {
                val itemId = selectedAudioItemId?.let(UUID::fromString)
                val title = selectedAudioTitle
                val detailType = selectedAudioDetailType?.let(JellyfinAudioDetailType::valueOf)
                if (itemId == null || title == null || detailType == null) {
                    currentRoute = audioBackRoute
                } else {
                    JellyfinAudioDetailScreen(
                        itemId = itemId,
                        title = title,
                        detailType = detailType,
                        parentId = selectedAudioParentId?.let(UUID::fromString),
                        onBack = { currentRoute = audioBackRoute },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                    }
                    currentRoute == BeamRoute.Show -> {
                val showId = selectedShowId?.let(UUID::fromString)
                if (showId == null) {
                    currentRoute = BeamRoute.Home
                } else {
                    BeamShowScreen(
                        contentPadding = PaddingValues(0.dp),
                        showId = showId,
                        onBack = { currentRoute = showBackRoute },
                        onOpenSeason = { seasonId ->
                            selectedSeasonId = seasonId.toString()
                            seasonBackRoute = BeamRoute.Show
                            currentRoute = BeamRoute.Season
                        },
                        onOpenItem = { itemId ->
                            selectedDetailItemId = itemId.toString()
                            detailBackRoute = BeamRoute.Show
                            currentRoute = BeamRoute.Detail
                        },
                        onOpenPerson = { personId ->
                            selectedPersonId = personId.toString()
                            personBackRoute = BeamRoute.Show
                            currentRoute = BeamRoute.Person
                        },
                    )
                }
                    }
                    currentRoute == BeamRoute.Season -> {
                val seasonId = selectedSeasonId?.let(UUID::fromString)
                if (seasonId == null) {
                    currentRoute = BeamRoute.Home
                } else {
                    BeamSeasonScreen(
                        contentPadding = PaddingValues(0.dp),
                        seasonId = seasonId,
                        onBack = { currentRoute = seasonBackRoute },
                        onOpenItem = { itemId ->
                            selectedDetailItemId = itemId.toString()
                            detailBackRoute = BeamRoute.Season
                            currentRoute = BeamRoute.Detail
                        },
                    )
                }
                    }
                    currentRoute == BeamRoute.Detail -> {
                val itemId = selectedDetailItemId?.let(UUID::fromString)
                if (itemId == null) {
                    currentRoute = detailBackRoute
                } else {
                    BeamItemDetailScreen(
                        contentPadding = PaddingValues(0.dp),
                        itemId = itemId,
                        onBack = { currentRoute = detailBackRoute },
                        onOpenLibrary = { nestedId, nestedName, nestedType ->
                            selectedLibraryId = nestedId.toString()
                            selectedLibraryName = nestedName
                            selectedLibraryType = nestedType.name
                            currentRoute = BeamRoute.Library
                        },
                        onOpenShow = { showId ->
                            selectedShowId = showId.toString()
                            showBackRoute = BeamRoute.Detail
                            currentRoute = BeamRoute.Show
                        },
                        onOpenSeason = { seasonId ->
                            selectedSeasonId = seasonId.toString()
                            seasonBackRoute = BeamRoute.Detail
                            currentRoute = BeamRoute.Season
                        },
                        onOpenPerson = { personId ->
                            selectedPersonId = personId.toString()
                            personBackRoute = BeamRoute.Detail
                            currentRoute = BeamRoute.Person
                        },
                    )
                }
                    }
                    currentRoute == BeamRoute.Person -> {
                val pid = selectedPersonId?.let(UUID::fromString)
                if (pid == null) {
                    currentRoute = personBackRoute
                } else {
                    dev.jdtech.jellyfin.presentation.film.PersonScreen(
                        personId = pid,
                        navigateBack = { currentRoute = personBackRoute },
                        navigateHome = { currentRoute = BeamRoute.Home },
                        navigateToItem = { item ->
                            when (item) {
                                is dev.jdtech.jellyfin.models.SpatialFinShow -> {
                                    selectedShowId = item.id.toString()
                                    showBackRoute = BeamRoute.Person
                                    currentRoute = BeamRoute.Show
                                }
                                is dev.jdtech.jellyfin.models.SpatialFinSeason -> {
                                    selectedSeasonId = item.id.toString()
                                    seasonBackRoute = BeamRoute.Person
                                    currentRoute = BeamRoute.Season
                                }
                                else -> {
                                    selectedDetailItemId = item.id.toString()
                                    detailBackRoute = BeamRoute.Person
                                    currentRoute = BeamRoute.Detail
                                }
                            }
                        },
                    )
                }
                    }
                    currentRoute == BeamRoute.Network -> {
                BeamNetworkScreen(
                    contentPadding = PaddingValues(0.dp),
                    onShareClick = { shareId ->
                        selectedNetworkShareId = shareId
                        currentRoute = BeamRoute.NetworkShare
                    },
                    onAddShareClick = {
                        currentRoute = BeamRoute.NetworkAddShare
                    },
                    onItemClick = { item ->
                        context.startActivity(
                            BeamPlayerActivity.createIntentForNetworkMedia(
                                context = context,
                                networkVideoId = item.networkVideoId,
                                startFromBeginning = false,
                            )
                        )
                    },
                )
                    }
                    currentRoute == BeamRoute.Downloads -> {
                BeamDownloadsScreen(
                    contentPadding = PaddingValues(0.dp),
                    onOpenShow = { showId ->
                        selectedShowId = showId.toString()
                        showBackRoute = BeamRoute.Downloads
                        currentRoute = BeamRoute.Show
                    },
                    onOpenSeason = { seasonId ->
                        selectedSeasonId = seasonId.toString()
                        seasonBackRoute = BeamRoute.Downloads
                        currentRoute = BeamRoute.Season
                    },
                    onOpenItem = { itemId ->
                        selectedDetailItemId = itemId.toString()
                        detailBackRoute = BeamRoute.Downloads
                        currentRoute = BeamRoute.Detail
                    },
                )
                    }
                    currentRoute == BeamRoute.Settings -> {
                        BeamSettingsScreen(
                            contentPadding = PaddingValues(0.dp),
                            appPreferences = appPreferences,
                            onOpenCompanion = { currentRoute = BeamRoute.Welcome },
                            onOpenPlugins = { currentRoute = BeamRoute.Plugins },
                        )
                    }
                    currentRoute == BeamRoute.Plugins -> {
                        dev.jdtech.jellyfin.plugins.ui.PluginSettingsScreen(
                            onPluginClick = { pluginId ->
                                selectedPluginId = pluginId
                                selectedPluginRowId = null
                                currentRoute = BeamRoute.PluginBrowse
                            },
                            onJellyfinClick = {
                                currentRoute = BeamRoute.Servers
                            },
                            onMusicAssistantClick = {
                                showMusicAssistantSettings = true
                            },
                            musicAssistantSubtitle = musicAssistantSubtitle,
                            onLocalClick = {
                                currentRoute = BeamRoute.Local
                            },
                            onNetworkClick = {
                                currentRoute = BeamRoute.Network
                            }
                        )
                    }
                    currentRoute == BeamRoute.NetworkShare -> {

                val shareId = selectedNetworkShareId
                if (shareId == null) {
                    currentRoute = BeamRoute.Network
                } else {
                    BeamNetworkShareScreen(
                        contentPadding = PaddingValues(0.dp),
                        shareId = shareId,
                        onBack = { currentRoute = BeamRoute.Network },
                        onItemClick = { item ->
                            context.startActivity(
                                BeamPlayerActivity.createIntentForNetworkMedia(
                                    context = context,
                                    networkVideoId = item.networkVideoId,
                                    startFromBeginning = false,
                                )
                            )
                        },
                        onShareRemoved = {
                            selectedNetworkShareId = null
                            currentRoute = BeamRoute.Network
                        },
                    )
                }
                    }
                    currentRoute == BeamRoute.NetworkAddShare -> {
                        dev.jdtech.jellyfin.presentation.network.AddShareScreen(
                            navigateBack = { currentRoute = BeamRoute.Network }
                        )
                    }
                    currentRoute == BeamRoute.MaSearch -> {
                        dev.spatialfin.unified.music.MaSearchScreen(
                            onBack = { currentRoute = BeamRoute.Home },
                            onOpenItem = ::openMaBrowse,
                        )
                    }
                    currentRoute == BeamRoute.MaDetail -> {
                        val seed = maDetailSeed
                        val kind = maDetailKind
                        if (seed == null || kind == null) {
                            // Defensive fallback: shouldn't happen but if state
                            // was lost (process death without saveable seed),
                            // bounce the user back rather than render an empty
                            // detail screen.
                            currentRoute = maDetailBackRoute
                        } else {
                            dev.spatialfin.unified.music.MaDetailScreen(
                                seed = seed,
                                kind = kind,
                                onBack = { currentRoute = maDetailBackRoute },
                                onOpenItem = ::openMaBrowse,
                            )
                        }
                    }
                    else -> {
                BeamSignedInShell(
                    state = state,
                    currentRoute = currentRoute,
                    appPreferences = appPreferences,
                    contentPadding = PaddingValues(0.dp),
                    onNavigateToAddServer = { currentRoute = BeamRoute.AddServer },
                    onNavigateToCompanion = { currentRoute = BeamRoute.Companion },
                    onNavigateToLogin = { username ->
                        prefilledUsername = username
                        currentRoute = BeamRoute.Login
                    },
                    onNavigateToUsers = { currentRoute = BeamRoute.Users },
                    onNavigateToHome = { timber.log.Timber.d("[UserSwitch] onNavigateToHome lambda: setting currentRoute from $currentRoute to Home"); currentRoute = BeamRoute.Home },
                    onNavigateToServers = { currentRoute = BeamRoute.Servers },
                    onResetOnboarding = {
                        appPreferences.setValue(appPreferences.onboardingCompleted, false)
                        currentRoute = BeamRoute.Welcome
                    },
                )
                    }
                }
            }
        }
    }
            BeamVoiceFeedbackOverlay(
                feedback = voiceController.voiceFeedback,
                isListening = voiceState == VoiceState.LISTENING,
                partialTranscript = voicePartial,
            )
            BeamRecommendationSheet(
                recommendationContext = voiceController.recommendationContext,
                onSelect = { item ->
                    if (navigation.launchItem(item)) {
                        voiceController.clearRecommendationContext()
                    }
                },
                onDismiss = { voiceController.clearRecommendationContext() },
            )
            if (showMusicAssistantSettings) {
                Dialog(onDismissRequest = { showMusicAssistantSettings = false }) {
                    MusicAssistantAuthDialog(onDismiss = { showMusicAssistantSettings = false })
                }
            }
            dev.spatialfin.fcast.session.FCastGlobalPickerHost(sessionManager = fcastSession)
            val maNowPlayingFs by maSession.session.collectAsStateWithLifecycle()
            dev.spatialfin.sendspin.SendspinFullscreenPlayer(
                suppressed = maNowPlayingFs.nowPlaying != null,
            )
            var showPlayerPicker by remember { mutableStateOf(false) }
            var showParty by remember { mutableStateOf(false) }
            // Open the right MA overlay when a queue/party deep link arrives.
            if (maDeepLink != null) {
                val pendingDeepLink by maDeepLink.collectAsStateWithLifecycle()
                LaunchedEffect(pendingDeepLink) {
                    when (pendingDeepLink) {
                        dev.jdtech.jellyfin.deeplink.MaDeepLink.Parsed.Queue -> showNowPlaying = true
                        dev.jdtech.jellyfin.deeplink.MaDeepLink.Parsed.Party -> showParty = true
                        else -> Unit
                    }
                    if (pendingDeepLink != null) onMaDeepLinkHandled()
                }
            }
            if (showNowPlaying) {
                BackHandler(onBack = { showNowPlaying = false })
                dev.spatialfin.unified.music.MaNowPlayingScreen(
                    session = maSession,
                    onBack = { showNowPlaying = false },
                    onPickPlayer = { showPlayerPicker = true },
                    onOpenSearch = {
                        showNowPlaying = false
                        currentRoute = BeamRoute.MaSearch
                    },
                    onOpenParty = { showParty = true },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            jellyfinAudioDispatcher?.let { dispatcher ->
                if (showJellyfinAudioNowPlaying) {
                    BackHandler(onBack = { showJellyfinAudioNowPlaying = false })
                    JellyfinAudioNowPlayingScreen(
                        dispatcher = dispatcher,
                        onBack = { showJellyfinAudioNowPlaying = false },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            if (showParty) {
                BackHandler(onBack = { showParty = false })
                dev.spatialfin.unified.music.MaPartyScreen(
                    session = maSession,
                    onBack = { showParty = false },
                    onPickPlayer = { showPlayerPicker = true },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (showPlayerPicker) {
                dev.spatialfin.unified.music.MaPlayerPickerSheet(
                    session = maSession,
                    dispatcher = maPlayDispatcher,
                    onDismiss = { showPlayerPicker = false },
                )
            }
}
}
}

@Composable
private fun BeamBottomNavigationRow(
    currentRoute: BeamRoute,
    isOfflineMode: Boolean = false,
    onNavigate: (BeamRoute) -> Unit,
) {
    val visibleTabs = if (isOfflineMode) {
        primaryTabs.filter { it.route != BeamRoute.Home && it.route != BeamRoute.Search }
    } else {
        primaryTabs
    }
    val fcastSession = dev.spatialfin.fcast.session.LocalFCastSession.current
    // Material 3 navigation bar (NavBar.jsx): the active destination gets a tonal
    // pill indicator behind its icon, accent label below. surfaceContainer ground
    // with a hairline top divider. The bar scrolls because Beam carries more
    // destinations (Users + the Cast picker) than the 5-tab design reference.
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 3.dp,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            HorizontalDivider(
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                visibleTabs.forEach { tab ->
                    BeamNavCell(
                        icon = { tint ->
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = tab.label,
                                modifier = Modifier.size(22.dp),
                                tint = tint,
                            )
                        },
                        label = tab.label,
                        selected = currentRoute == tab.route,
                        onClick = { onNavigate(tab.route) },
                    )
                }
                // Cast peer — a tab-shaped cell that routes to the global FCast
                // picker instead of a navigation destination.
                if (fcastSession != null) {
                    val pickedTarget by fcastSession.pickedTarget.collectAsStateWithLifecycle()
                    BeamNavCell(
                        icon = { tint ->
                            Icon(
                                painter = painterResource(dev.jdtech.jellyfin.core.R.drawable.ic_cast),
                                contentDescription = "Cast",
                                modifier = Modifier.size(22.dp),
                                tint = tint,
                            )
                        },
                        label = "Cast",
                        selected = pickedTarget != null,
                        onClick = { fcastSession.showPicker() },
                    )
                }
            }
        }
    }
}

/** A single Material-3 bottom-nav cell: pill indicator behind the icon + label. */
@Composable
private fun BeamNavCell(
    icon: @Composable (tint: Color) -> Unit,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val indicatorColor =
        if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    val iconTint =
        if (selected) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant
    val labelColor =
        if (selected) MaterialTheme.colorScheme.onSurface
        else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        onClick = onClick,
        color = Color.Transparent,
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(width = 56.dp, height = 32.dp)
                    .clip(CircleShape)
                    .background(indicatorColor),
                contentAlignment = Alignment.Center,
            ) {
                icon(iconTint)
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                softWrap = false,
                color = labelColor,
            )
        }
    }
}

@Composable
private fun BeamSidebar(
    currentRoute: BeamRoute,
    isOfflineMode: Boolean = false,
    onNavigate: (BeamRoute) -> Unit,
    onReconnect: () -> Unit = {},
    voiceState: VoiceState = VoiceState.IDLE,
    voicePartial: String = "",
    onVoiceClick: () -> Unit = {},
    showVoice: Boolean = true,
    forceExpanded: Boolean = false,
) {
    val visibleTabs = if (isOfflineMode) {
        primaryTabs.filter { it.route != BeamRoute.Home && it.route != BeamRoute.Search }
    } else {
        primaryTabs
    }
    val fcastSession = dev.spatialfin.fcast.session.LocalFCastSession.current

    var userExpanded by rememberSaveable { mutableStateOf(false) }
    val isExpanded = forceExpanded || userExpanded
    val sidebarWidth by androidx.compose.animation.core.animateDpAsState(
        targetValue = if (isExpanded) 200.dp else 80.dp,
        label = "sidebarWidth"
    )

    Surface(
        modifier = if (forceExpanded) {
            Modifier.fillMaxWidth().fillMaxHeight()
        } else {
            Modifier.width(sidebarWidth).fillMaxHeight()
        },
        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.7f),
        shape = RoundedCornerShape(24.dp),
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            horizontalAlignment = if (isExpanded) Alignment.Start else Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = if (isExpanded) 16.dp else 0.dp, end = if (isExpanded) 4.dp else 0.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (isExpanded) Arrangement.Start else Arrangement.Center,
            ) {
                androidx.compose.foundation.Image(
                    painter = painterResource(id = R.drawable.ic_beam_launcher),
                    contentDescription = "SpatialFin",
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { if (!forceExpanded) userExpanded = !userExpanded },
                )
                if (isExpanded) {
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "SpatialFin",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                    if (showVoice) {
                        IconButton(onClick = onVoiceClick) {
                            Icon(
                                imageVector = Icons.Rounded.Mic,
                                contentDescription = "Voice",
                                modifier = Modifier.size(22.dp),
                                tint = if (voiceState == VoiceState.LISTENING) Color(0xFF4FC3F7) else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            if (showVoice && voiceState == VoiceState.LISTENING && voicePartial.isNotBlank() && isExpanded) {
                Text(
                    text = voicePartial,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF4FC3F7),
                    maxLines = 2,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            visibleTabs.forEach { tab ->
                val selected = currentRoute == tab.route
                Surface(
                    onClick = { onNavigate(tab.route) },
                    color =
                        if (selected) MaterialTheme.colorScheme.primaryContainer
                        else Color.Transparent,
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = if (isExpanded) 16.dp else 0.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = if (isExpanded) Arrangement.spacedBy(12.dp) else Arrangement.Center,
                    ) {
                        Icon(
                            imageVector = tab.icon,
                            contentDescription = tab.label,
                            modifier = Modifier.size(22.dp),
                            tint =
                                if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (isExpanded) {
                            Text(
                                text = tab.label,
                                style = MaterialTheme.typography.labelLarge,
                                color =
                                    if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            )
                        }
                    }
                }
            }
            // Cast peer rendered with the same row geometry so it lives in the
            // sidebar next to the Network tab. It opens the global FCast picker
            // rather than navigating to a destination.
            if (fcastSession != null) {
                val pickedTarget by fcastSession.pickedTarget.collectAsStateWithLifecycle()
                val castSelected = pickedTarget != null
                Surface(
                    onClick = { fcastSession.showPicker() },
                    color =
                        if (castSelected) MaterialTheme.colorScheme.primaryContainer
                        else Color.Transparent,
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = if (isExpanded) 16.dp else 0.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = if (isExpanded) Arrangement.spacedBy(12.dp) else Arrangement.Center,
                    ) {
                        Icon(
                            painter = painterResource(dev.jdtech.jellyfin.core.R.drawable.ic_cast),
                            contentDescription = "Cast",
                            modifier = Modifier.size(22.dp),
                            tint =
                                if (castSelected) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (isExpanded) {
                            Text(
                                text = "Cast",
                                style = MaterialTheme.typography.labelLarge,
                                color =
                                    if (castSelected) MaterialTheme.colorScheme.onPrimaryContainer
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = if (castSelected) FontWeight.SemiBold else FontWeight.Medium,
                            )
                        }
                    }
                }
            }
            if (isOfflineMode) {
                Spacer(Modifier.weight(1f))
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalAlignment = if (isExpanded) Alignment.Start else Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = if (isExpanded) Arrangement.spacedBy(8.dp) else Arrangement.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.WifiOff,
                                contentDescription = "Offline",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            if (isExpanded) {
                                Text(
                                    text = "Offline",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            }
                        }
                        Surface(
                            onClick = onReconnect,
                            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = if (isExpanded) 10.dp else 0.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = if (isExpanded) Arrangement.spacedBy(6.dp) else Arrangement.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Refresh,
                                    contentDescription = "Reconnect",
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                )
                                if (isExpanded) {
                                    Text(
                                        text = "Reconnect",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BeamWelcomeScreen(
    contentPadding: PaddingValues,
    onImportFromCompanion: () -> Unit,
    onConnectToServer: () -> Unit,
    onUseLocalMedia: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.7f)
                .padding(vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Text(
                text = "SpatialFin",
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "Your personal media, everywhere.",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Button(
                    onClick = onImportFromCompanion,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Import from Companion App")
                }
                FilledTonalButton(
                    onClick = onConnectToServer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Connect to Jellyfin Server")
                }
                OutlinedButton(
                    onClick = onUseLocalMedia,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Browse Local Media")
                }
            }
        }
    }
}

@Composable
private fun BeamSignedInShell(
    state: MainState,
    currentRoute: BeamRoute,
    appPreferences: AppPreferences,
    contentPadding: PaddingValues,
    onNavigateToAddServer: () -> Unit,
    onNavigateToCompanion: () -> Unit,
    onNavigateToLogin: (String?) -> Unit,
    onNavigateToUsers: () -> Unit,
    onNavigateToHome: () -> Unit,
    onNavigateToServers: () -> Unit,
    onResetOnboarding: () -> Unit,
) {
    when (currentRoute) {
        BeamRoute.Home -> Unit // Home is handled directly by BeamNavigationRoot
        BeamRoute.Local -> {
            BeamLocalMediaScreen(
                contentPadding = contentPadding,
                onResetOnboarding = onResetOnboarding,
            )
        }
            BeamRoute.Servers -> {
                BeamServersScreen(
                    contentPadding = contentPadding,
                    onServerSelected = onNavigateToUsers,
                    onAddServerClick = onNavigateToAddServer,
                    onCompanionImportClick = onNavigateToCompanion,
                    onResetOnboarding = onResetOnboarding,
                )
            }
        BeamRoute.Users -> {
            BeamUsersScreen(
                contentPadding = contentPadding,
                onNavigateToHome = onNavigateToHome,
                onChangeServerClick = onNavigateToServers,
                onAddClick = { onNavigateToLogin(null) },
                onPublicUserClick = onNavigateToLogin,
                onResetOnboarding = onResetOnboarding,
            )
        }
        BeamRoute.AddServer,
        BeamRoute.Companion,
        BeamRoute.Login,
        BeamRoute.Search,
        BeamRoute.Library,
        BeamRoute.Show,
        BeamRoute.Season,
        BeamRoute.Detail,
        BeamRoute.Person,
        BeamRoute.Network,
        BeamRoute.Downloads,
        BeamRoute.Plugins,
        BeamRoute.PluginBrowse,
        BeamRoute.Settings,
        BeamRoute.NetworkAddShare,
        BeamRoute.NetworkShare,
        BeamRoute.MaSearch,
        BeamRoute.MaDetail,
        BeamRoute.AudioDetail ->
            Unit

        BeamRoute.Welcome -> Unit
    }
}

// BeamStateSummary removed — home screen is now served by BeamHomeScreen

@Composable
private fun BeamStatusScreen(
    title: String,
    body: String,
    contentPadding: PaddingValues,
) {
    Box(
        modifier = Modifier.fillMaxSize().padding(contentPadding),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            androidx.compose.material3.CircularProgressIndicator(
                modifier = Modifier.size(32.dp),
                strokeWidth = 3.dp,
            )
        }
    }
}

// BeamCardListScreen removed — replaced by dedicated screen composables

@Composable
internal fun BeamScaffoldBody(
    contentPadding: PaddingValues,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        content = content,
    )
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.BeamVoiceFeedbackOverlay(
    feedback: String?,
    isListening: Boolean,
    partialTranscript: String,
) {
    val message = when {
        !feedback.isNullOrBlank() -> feedback
        isListening && partialTranscript.isNotBlank() -> partialTranscript
        else -> null
    }
    // Glass pill, top-center, with a state icon chip — VoiceFeedback.jsx. Listening
    // tints with the accent + mic; a reply uses the soft tertiary + sparkles.
    val colorScheme = MaterialTheme.colorScheme
    val tint = if (isListening) colorScheme.primary else colorScheme.tertiary
    val stateIcon = if (isListening) Icons.Rounded.Mic else Icons.Rounded.AutoAwesome
    androidx.compose.animation.AnimatedVisibility(
        visible = message != null,
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = 56.dp, start = 24.dp, end = 24.dp),
    ) {
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .background(BeamTokens.GlassFillStrong)
                .border(1.dp, BeamTokens.GlassBorder, CircleShape)
                .padding(start = 10.dp, top = 10.dp, end = 18.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(tint.copy(alpha = 0.22f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = stateIcon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(18.dp),
                )
            }
            Text(
                text = message.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.widthIn(max = 340.dp),
            )
        }
    }
}

@Composable
private fun BeamVoiceFab(
    voiceState: VoiceState,
    isTtsSpeaking: Boolean,
    partialTranscript: String,
    onClick: () -> Unit,
) {
    // Design-system colors only — the mic FAB rests on accent-container and fills
    // with the accent while listening (VoiceFab.jsx). Neon mascot tints are
    // reserved for logos/marketing and never appear in UI (DESIGN.md).
    val colorScheme = MaterialTheme.colorScheme
    val listening = voiceState == VoiceState.LISTENING
    val isError = voiceState == VoiceState.ERROR
    val containerColor = when {
        listening -> colorScheme.primary
        isError -> colorScheme.errorContainer
        else -> colorScheme.primaryContainer
    }
    val contentColor = when {
        listening -> colorScheme.onPrimary
        isError -> colorScheme.onErrorContainer
        else -> colorScheme.onPrimaryContainer
    }
    val label = when {
        isTtsSpeaking -> "Tap to stop"
        voiceState == VoiceState.LISTENING ->
            if (partialTranscript.isNotBlank()) partialTranscript else "Listening…"
        voiceState == VoiceState.PROCESSING -> "Thinking…"
        else -> null
    }
    // Expanding ring while listening (sf-voice-pulse): a stroke that grows out
    // from the FAB and fades — the one decorative loop the design system allows.
    val ringFraction by androidx.compose.animation.core.rememberInfiniteTransition(label = "voiceFabPulse")
        .animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                animation = androidx.compose.animation.core.tween(1400, easing = androidx.compose.animation.core.LinearEasing),
                repeatMode = androidx.compose.animation.core.RepeatMode.Restart,
            ),
            label = "voiceFabPulseValue",
        )
    val ringColor = colorScheme.primary

    androidx.compose.material3.FloatingActionButton(
        onClick = onClick,
        shape = RoundedCornerShape(26.dp),
        containerColor = containerColor,
        contentColor = contentColor,
        elevation = androidx.compose.material3.FloatingActionButtonDefaults.elevation(
            defaultElevation = 3.dp,
            pressedElevation = 4.dp,
            hoveredElevation = 4.dp,
            focusedElevation = 3.dp,
        ),
        modifier = Modifier
            .height(52.dp)
            .widthIn(min = 52.dp)
            .drawBehind {
                if (listening) {
                    val inset = -((2.dp.toPx()) + 8.dp.toPx() * ringFraction)
                    val radius = size.height / 2f - inset
                    drawRoundRect(
                        color = ringColor.copy(alpha = 0.6f * (1f - ringFraction)),
                        topLeft = Offset(inset, inset),
                        size = Size(size.width - 2f * inset, size.height - 2f * inset),
                        cornerRadius = CornerRadius(radius, radius),
                        style = Stroke(width = 3.dp.toPx()),
                    )
                }
            },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = if (label != null) 16.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Mic,
                contentDescription = "Voice Assistant",
                tint = contentColor,
            )
            androidx.compose.animation.AnimatedVisibility(visible = label != null) {
                Row(modifier = Modifier.padding(start = 8.dp)) {
                    Text(
                        text = label.orEmpty(),
                        style = MaterialTheme.typography.labelMedium,
                        color = contentColor,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.widthIn(max = 200.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.BeamRecommendationSheet(
    recommendationContext: dev.jdtech.jellyfin.player.xr.voice.RecommendationContext?,
    onSelect: (SpatialFinItem) -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = recommendationContext ?: return
    Surface(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .heightIn(max = 420.dp)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.97f),
        shape = RoundedCornerShape(24.dp),
        tonalElevation = 8.dp,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = ctx.query.ifBlank { "Recommendations" },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                androidx.compose.material3.TextButton(onClick = onDismiss) {
                    Text("Close")
                }
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(ctx.items) { item ->
                    Surface(
                        onClick = { onSelect(item) },
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                            Text(
                                text = item.name,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                            )
                            (item as? SpatialFinMovie)?.overview?.takeIf { it.isNotBlank() }?.let { overview ->
                                Text(
                                    text = overview,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
