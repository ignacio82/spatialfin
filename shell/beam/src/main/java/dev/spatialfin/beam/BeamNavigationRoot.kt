package dev.spatialfin.beam

import dev.jdtech.jellyfin.setup.R as SetupR
import androidx.compose.ui.res.stringResource
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
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
import dev.spatialfin.unified.MaVoiceState
import dev.spatialfin.unified.MusicVoiceCommand
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
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.runtime.saveable.Saver
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import dev.jdtech.jellyfin.onboarding.OnboardingFailureCode
import dev.jdtech.jellyfin.presentation.network.AddShareViewModel
import dev.jdtech.jellyfin.session.RouteRole
import dev.jdtech.jellyfin.session.SessionMode
import dev.jdtech.jellyfin.session.SessionNavigationPolicy
import dev.spatialfin.unified.applock.LocalSystemModalGraceHook
import dev.spatialfin.unified.audio.LocalAudioPlaybackDispatcher
import java.util.UUID

internal enum class BeamRoute {
    Welcome,
    SetUpFromDevice,
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

internal fun BeamRoute.toRouteRole(): RouteRole =
    when (this) {
        BeamRoute.Welcome -> RouteRole.SOURCE_PICKER
        BeamRoute.SetUpFromDevice,
        BeamRoute.AddServer -> RouteRole.SETUP_FLOW
        BeamRoute.Servers,
        BeamRoute.Users,
        BeamRoute.Login -> RouteRole.REAUTH_GATE
        BeamRoute.Home,
        BeamRoute.Search,
        BeamRoute.Library,
        BeamRoute.Person,
        BeamRoute.AudioDetail -> RouteRole.SERVER_CONTENT
        BeamRoute.Detail,
        BeamRoute.Show,
        BeamRoute.Season,
        BeamRoute.Local,
        BeamRoute.Network,
        BeamRoute.NetworkShare,
        BeamRoute.NetworkAddShare,
        BeamRoute.Downloads,
        BeamRoute.Plugins,
        BeamRoute.PluginBrowse -> RouteRole.OFFLINE_OR_LOCAL_CONTENT
        BeamRoute.MaSearch,
        BeamRoute.MaDetail -> RouteRole.MA_CONTENT
        BeamRoute.Settings -> RouteRole.SETTINGS
    }

internal fun parseBeamRoute(raw: String?): BeamRoute =
    when (raw) {
        "Companion" -> BeamRoute.SetUpFromDevice
        null -> BeamRoute.Welcome
        else -> runCatching { BeamRoute.valueOf(raw) }.getOrDefault(BeamRoute.Welcome)
    }

internal fun parseNullableBeamRoute(raw: String?): BeamRoute? =
    when (raw) {
        null -> null
        "Companion" -> BeamRoute.SetUpFromDevice
        else -> runCatching { BeamRoute.valueOf(raw) }.getOrNull()
    }

private val BeamRouteSaver: Saver<BeamRoute, String> =
    Saver(
        save = { it.name },
        restore = { parseBeamRoute(it) },
    )

private val NullableBeamRouteSaver: Saver<BeamRoute?, String> =
    Saver(
        save = { it?.name.orEmpty() },
        restore = { saved -> saved.takeIf { it.isNotEmpty() }?.let(::parseNullableBeamRoute) },
    )

internal fun beamDefaultRootRoute(
    sessionMode: SessionMode,
    offlineBrowseEntered: Boolean,
): BeamRoute? =
    when (sessionMode) {
        SessionMode.SERVER_AUTHENTICATED -> BeamRoute.Home
        SessionMode.STANDALONE_SOURCES -> BeamRoute.Local
        SessionMode.SERVER_NEEDS_AUTH -> if (offlineBrowseEntered) BeamRoute.Downloads else null
        SessionMode.UNCONFIGURED -> null
    }

internal fun isBeamVoiceAllowed(
    sessionMode: SessionMode,
    offlineBrowseEntered: Boolean,
): Boolean =
    when (sessionMode) {
        SessionMode.SERVER_AUTHENTICATED,
        SessionMode.STANDALONE_SOURCES -> true
        SessionMode.SERVER_NEEDS_AUTH -> offlineBrowseEntered
        SessionMode.UNCONFIGURED -> false
    }

internal fun resolveBeamBackRoute(
    currentRoute: BeamRoute,
    hasServers: Boolean,
    sessionMode: SessionMode,
    offlineBrowseEntered: Boolean,
    detailBackRoute: BeamRoute,
    seasonBackRoute: BeamRoute,
    showBackRoute: BeamRoute,
    personBackRoute: BeamRoute,
    audioBackRoute: BeamRoute,
    maDetailBackRoute: BeamRoute,
    setUpFromDeviceBackRoute: BeamRoute,
    addServerBackRoute: BeamRoute,
    networkBackRoute: BeamRoute,
    serversBackRoute: BeamRoute?,
    usersBackRoute: BeamRoute?,
): BeamRoute {
    val fallback = beamDefaultRootRoute(sessionMode, offlineBrowseEntered)
        ?: if (hasServers) BeamRoute.Servers else BeamRoute.Welcome
    return when (currentRoute) {
        BeamRoute.Detail -> detailBackRoute
        BeamRoute.Season -> seasonBackRoute
        BeamRoute.Show -> showBackRoute
        BeamRoute.Person -> personBackRoute
        BeamRoute.AudioDetail -> audioBackRoute
        BeamRoute.MaDetail -> maDetailBackRoute
        BeamRoute.PluginBrowse -> BeamRoute.Plugins
        BeamRoute.Library,
        BeamRoute.MaSearch -> fallback
        BeamRoute.NetworkShare,
        BeamRoute.NetworkAddShare -> networkBackRoute
        BeamRoute.AddServer -> if (!hasServers) BeamRoute.Welcome else addServerBackRoute
        BeamRoute.SetUpFromDevice -> setUpFromDeviceBackRoute
        BeamRoute.Servers -> serversBackRoute ?: fallback
        BeamRoute.Users -> usersBackRoute ?: if (hasServers) BeamRoute.Servers else BeamRoute.Welcome
        BeamRoute.Login -> BeamRoute.Users
        else -> fallback
    }
}

internal fun isBeamRootRoute(
    currentRoute: BeamRoute,
    serversBackRoute: BeamRoute?,
    usersBackRoute: BeamRoute?,
): Boolean {
    if (currentRoute == BeamRoute.Servers) return serversBackRoute == null
    if (currentRoute == BeamRoute.Users) return usersBackRoute == null
    return currentRoute in
        setOf(
            BeamRoute.Welcome,
            BeamRoute.Home,
            BeamRoute.Search,
            BeamRoute.Downloads,
            BeamRoute.Plugins,
            BeamRoute.Settings,
            BeamRoute.Local,
            BeamRoute.Network,
        )
}

internal data class BeamSessionObserverState(
    val routeInitialized: Boolean = false,
    val offlineBrowseEntered: Boolean = false,
    val savedCoordinatorInstanceId: String = "",
    val lastObservedActivationEpoch: Long = -1L,
    val lastObservedSessionMode: SessionMode? = null,
)

internal data class BeamSessionTransitionResult(
    val nextRoute: BeamRoute,
    val nextObserverState: BeamSessionObserverState,
)

internal fun evaluateBeamSessionTransition(
    currentRoute: BeamRoute,
    state: MainState,
    effectiveOffline: Boolean,
    targetServerId: String?,
    observer: BeamSessionObserverState,
): BeamSessionTransitionResult {
    if (state.isLoading) {
        return BeamSessionTransitionResult(currentRoute, observer)
    }
    val mode = state.sessionMode
    val coordinatorChanged =
        observer.savedCoordinatorInstanceId.isNotEmpty() &&
            state.coordinatorInstanceId.isNotEmpty() &&
            observer.savedCoordinatorInstanceId != state.coordinatorInstanceId

    val effectivePrevMode = if (coordinatorChanged) null else observer.lastObservedSessionMode
    val activationEpochChanged =
        !coordinatorChanged &&
            observer.lastObservedActivationEpoch >= 0L &&
            state.activationEpoch != observer.lastObservedActivationEpoch
    val wasAuthenticated = effectivePrevMode == SessionMode.SERVER_AUTHENTICATED

    var offlineBrowse =
        when {
            !observer.routeInitialized -> observer.offlineBrowseEntered
            mode != SessionMode.SERVER_NEEDS_AUTH -> false
            else -> observer.offlineBrowseEntered
        }

    var nextRoute = currentRoute
    val role = currentRoute.toRouteRole()
    val reauthTarget = if (state.hasCurrentServer) BeamRoute.Users else BeamRoute.Servers
    val authenticatedRoot = if (effectiveOffline) BeamRoute.Downloads else BeamRoute.Home

    if (!observer.routeInitialized) {
        nextRoute =
            when (mode) {
                SessionMode.UNCONFIGURED -> BeamRoute.Welcome
                SessionMode.STANDALONE_SOURCES ->
                    if (SessionNavigationPolicy.shouldRedirectFromStandalone(mode, role)) {
                        BeamRoute.Local
                    } else {
                        currentRoute
                    }
                SessionMode.SERVER_NEEDS_AUTH ->
                    if (SessionNavigationPolicy.shouldRedirectToReauthGate(mode, role, offlineBrowse)) {
                        reauthTarget
                    } else {
                        currentRoute
                    }
                SessionMode.SERVER_AUTHENTICATED ->
                    if (role == RouteRole.SOURCE_PICKER || role == RouteRole.REAUTH_GATE) {
                        authenticatedRoot
                    } else {
                        currentRoute
                    }
            }
    } else {
        // 1. Missing persisted server check (Users / Login targeting a deleted server)
        val missingServerPop =
            (currentRoute == BeamRoute.Users || currentRoute == BeamRoute.Login) &&
                SessionNavigationPolicy.shouldPopMissingServerRoute(
                    role = role,
                    targetServerId = targetServerId,
                    isPersistedServer = true,
                    savedServerIds = state.savedServerIds,
                )

        nextRoute =
            when {
                SessionNavigationPolicy.shouldResetToSourcePickerOnUnconfiguredTransition(
                    previousMode = effectivePrevMode,
                    currentMode = mode,
                    role = role,
                ) -> BeamRoute.Welcome

                SessionNavigationPolicy.shouldNavigateHomeOnActivation(
                    mode = mode,
                    role = role,
                    activationEpochChanged = activationEpochChanged,
                    wasAuthenticated = wasAuthenticated,
                ) -> {
                    offlineBrowse = false
                    if (mode == SessionMode.STANDALONE_SOURCES) BeamRoute.Local else authenticatedRoot
                }

                SessionNavigationPolicy.shouldRedirectToSetup(mode, role) -> BeamRoute.Welcome

                missingServerPop -> if (state.hasServers) BeamRoute.Servers else BeamRoute.Welcome

                SessionNavigationPolicy.shouldRedirectToReauthGate(mode, role, offlineBrowse) -> reauthTarget

                mode == SessionMode.SERVER_NEEDS_AUTH &&
                    offlineBrowse &&
                    role == RouteRole.MA_CONTENT &&
                    !SessionNavigationPolicy.isMaOverlayAllowed(mode) -> BeamRoute.Downloads

                SessionNavigationPolicy.shouldRedirectFromStandalone(mode, role) -> BeamRoute.Local

                SessionNavigationPolicy.shouldExitSourcePicker(mode, role) ->
                    if (mode == SessionMode.STANDALONE_SOURCES) BeamRoute.Local else authenticatedRoot

                else -> currentRoute
            }
    }

    return BeamSessionTransitionResult(
        nextRoute = nextRoute,
        nextObserverState =
            BeamSessionObserverState(
                routeInitialized = true,
                offlineBrowseEntered = offlineBrowse,
                savedCoordinatorInstanceId = state.coordinatorInstanceId,
                lastObservedActivationEpoch = state.activationEpoch,
                lastObservedSessionMode = mode,
            ),
    )
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

private val standaloneTabs =
    listOf(
        BeamTab(BeamRoute.Local, "Local & Network", Icons.Rounded.Folder),
        BeamTab(BeamRoute.Plugins, "Sources", Icons.Rounded.Apps),
        BeamTab(BeamRoute.Settings, "Settings", Icons.Rounded.Settings),
    )

private val offlineBrowseTabs =
    listOf(
        BeamTab(BeamRoute.Local, "Local & Network", Icons.Rounded.Folder),
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
    onActivateStandalone: () -> Unit = {},
    onLogout: () -> Unit = {},
    onFinishAffinity: () -> Unit = {},
    maDeepLink: kotlinx.coroutines.flow.StateFlow<dev.jdtech.jellyfin.deeplink.MaDeepLink.Parsed?>? = null,
    onMaDeepLinkHandled: () -> Unit = {},
) {
    val context = LocalContext.current
    val graceHook = LocalSystemModalGraceHook.current
    val coroutineScope = rememberCoroutineScope()
    val addShareViewModel: AddShareViewModel = hiltViewModel()
    var currentRoute by rememberSaveable(stateSaver = BeamRouteSaver) { mutableStateOf(BeamRoute.Welcome) }
    var routeInitialized by rememberSaveable { mutableStateOf(false) }
    var offlineBrowseEntered by rememberSaveable { mutableStateOf(false) }
    var savedCoordinatorInstanceId by rememberSaveable { mutableStateOf("") }
    var lastObservedActivationEpoch by rememberSaveable { mutableStateOf(-1L) }
    var lastObservedSessionModeName by rememberSaveable { mutableStateOf<String?>(null) }
    var voiceQuery by rememberSaveable { mutableStateOf<String?>(null) }
    var showMusicAssistantSettings by remember { mutableStateOf(false) }
    val maPlayDispatcher = remember(context, maSession, maServiceClient) {
        dev.spatialfin.unified.MaPlayDispatcher(context, maSession, maServiceClient)
    }
    var showNowPlaying by rememberSaveable { mutableStateOf(false) }
    var showJellyfinAudioNowPlaying by rememberSaveable { mutableStateOf(false) }
    var showPlayerPicker by remember { mutableStateOf(false) }
    var showParty by remember { mutableStateOf(false) }
    val jellyfinAudioDispatcher = LocalAudioPlaybackDispatcher.current

    val sendspinState by dev.jdtech.jellyfin.sendspin.receiver.SendspinReceiverSession.state.collectAsStateWithLifecycle()
    val maOverlayAllowed = SessionNavigationPolicy.isMaOverlayAllowed(state.sessionMode)
    val isServerAuthenticated = state.sessionMode == SessionMode.SERVER_AUTHENTICATED
    val effectiveOffline = state.isOfflineMode && (!maOverlayAllowed || !sendspinState.musicAssistantRemoteReady)
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
            maStateProvider = { beamMaVoiceState(maSession) },
        )
    }
    val voiceState by voiceController.voiceService.state.collectAsStateWithLifecycle()
    val voicePartial by voiceController.voiceService.partialTranscript.collectAsStateWithLifecycle()
    val isTtsSpeaking by voiceController.tts.isSpeaking.collectAsStateWithLifecycle()
    val voiceCapability by llmModelManager.voiceCapability.collectAsStateWithLifecycle()

    val voiceAllowed = isBeamVoiceAllowed(state.sessionMode, offlineBrowseEntered)
    val showVoiceUi = voiceCapability != VoiceCapability.CPU_ONLY && voiceAllowed
    val assistantSpokenReplies = appPreferences.getValue(appPreferences.voiceAssistantSpokenReplies)
    val assistantVoiceName = appPreferences.getValue(appPreferences.voiceAssistantVoice)

    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    var targetServerId by rememberSaveable { mutableStateOf<String?>(null) }
    var targetServerName by rememberSaveable { mutableStateOf<String?>(null) }
    var targetServerAddress by rememberSaveable { mutableStateOf<String?>(null) }
    var prefilledUsername by rememberSaveable { mutableStateOf<String?>(null) }
    var loginInitialFailureCodeWire by rememberSaveable { mutableStateOf<String?>(null) }
    var setUpFromDeviceBackRoute by rememberSaveable(stateSaver = BeamRouteSaver) {
        mutableStateOf(BeamRoute.Welcome)
    }
    var addServerBackRoute by rememberSaveable(stateSaver = BeamRouteSaver) {
        mutableStateOf(BeamRoute.Welcome)
    }
    var networkBackRoute by rememberSaveable(stateSaver = BeamRouteSaver) {
        mutableStateOf(BeamRoute.Network)
    }
    var serversBackRoute by rememberSaveable(stateSaver = NullableBeamRouteSaver) {
        mutableStateOf<BeamRoute?>(null)
    }
    var usersBackRoute by rememberSaveable(stateSaver = NullableBeamRouteSaver) {
        mutableStateOf<BeamRoute?>(null)
    }
    var selectedNetworkShareId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedLibraryId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedLibraryName by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedLibraryType by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedShowId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedSeasonId by rememberSaveable { mutableStateOf<String?>(null) }
    var showBackRoute by rememberSaveable(stateSaver = BeamRouteSaver) { mutableStateOf(BeamRoute.Home) }
    var seasonBackRoute by rememberSaveable(stateSaver = BeamRouteSaver) { mutableStateOf(BeamRoute.Home) }
    var selectedDetailItemId by rememberSaveable { mutableStateOf<String?>(null) }
    var detailBackRoute by rememberSaveable(stateSaver = BeamRouteSaver) { mutableStateOf(BeamRoute.Home) }
    var selectedPersonId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedAudioItemId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedAudioTitle by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedAudioDetailType by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedAudioParentId by rememberSaveable { mutableStateOf<String?>(null) }
    var audioBackRoute by rememberSaveable(stateSaver = BeamRouteSaver) { mutableStateOf(BeamRoute.Home) }
    var maDetailSeed by remember {
        mutableStateOf<dev.jdtech.jellyfin.data.musicassistant.data.model.server.ServerMediaItem?>(null)
    }
    var maDetailKind by remember {
        mutableStateOf<dev.spatialfin.unified.music.MaDetailViewModel.DetailKind?>(null)
    }
    var maDetailBackRoute by rememberSaveable(stateSaver = BeamRouteSaver) { mutableStateOf(BeamRoute.MaSearch) }

    fun resolveActiveTargetServerId(): String =
        targetServerId?.takeIf { it.isNotBlank() } ?: state.activeServerId.orEmpty()

    fun resolveActiveTargetServerName(): String =
        targetServerName?.takeIf { it.isNotBlank() } ?: state.activeServerName.orEmpty()

    fun resolveActiveTargetServerAddress(): String =
        targetServerAddress?.takeIf { it.isNotBlank() } ?: state.currentServerAddress.orEmpty()

    fun openUsersFor(
        serverId: String,
        serverName: String,
        serverAddress: String,
        backRoute: BeamRoute?,
    ) {
        targetServerId = serverId
        targetServerName = serverName
        targetServerAddress = serverAddress
        usersBackRoute = backRoute
        currentRoute = BeamRoute.Users
    }

    fun openLoginFor(
        serverId: String,
        serverName: String,
        serverAddress: String,
        username: String?,
        failureCode: OnboardingFailureCode? = null,
    ) {
        targetServerId = serverId
        targetServerName = serverName
        targetServerAddress = serverAddress
        prefilledUsername = username
        loginInitialFailureCodeWire = failureCode?.wireCode
        currentRoute = BeamRoute.Login
    }

    fun openMaBrowse(target: dev.spatialfin.unified.music.MaBrowseTarget) {
        if (!SessionNavigationPolicy.isMaOverlayAllowed(state.sessionMode)) return
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
    var personBackRoute by rememberSaveable(stateSaver = BeamRouteSaver) { mutableStateOf(BeamRoute.Home) }
    var beamBackgroundUrl by remember { mutableStateOf<Any?>(null) }

    LaunchedEffect(maOverlayAllowed) {
        if (!maOverlayAllowed) {
            showNowPlaying = false
            showParty = false
            showPlayerPicker = false
            maDetailSeed = null
            maDetailKind = null
        }
    }
    LaunchedEffect(isServerAuthenticated) {
        if (!isServerAuthenticated) {
            showJellyfinAudioNowPlaying = false
        }
    }

    // When the AI layer produces a search action, it sets voiceSearchQuery; mirror
    // that into the Beam Search tab only when Search is allowed in the current SessionMode.
    LaunchedEffect(voiceController.voiceSearchQuery, state.sessionMode) {
        val query = voiceController.voiceSearchQuery
        if (!query.isNullOrBlank()) {
            if (SessionNavigationPolicy.isSearchAllowed(state.sessionMode)) {
                voiceQuery = query
                currentRoute = BeamRoute.Search
            }
            voiceController.voiceSearchQuery = null
        }
    }

    val navigateBack: () -> Unit = {
        val next = resolveBeamBackRoute(
            currentRoute = currentRoute,
            hasServers = state.hasServers,
            sessionMode = state.sessionMode,
            offlineBrowseEntered = offlineBrowseEntered,
            detailBackRoute = detailBackRoute,
            seasonBackRoute = seasonBackRoute,
            showBackRoute = showBackRoute,
            personBackRoute = personBackRoute,
            audioBackRoute = audioBackRoute,
            maDetailBackRoute = maDetailBackRoute,
            setUpFromDeviceBackRoute = setUpFromDeviceBackRoute,
            addServerBackRoute = addServerBackRoute,
            networkBackRoute = networkBackRoute,
            serversBackRoute = serversBackRoute,
            usersBackRoute = usersBackRoute,
        )
        if (currentRoute == BeamRoute.Servers) serversBackRoute = null
        if (currentRoute == BeamRoute.Users) usersBackRoute = null
        currentRoute = next
    }

    val isRootRoute = isBeamRootRoute(currentRoute, serversBackRoute, usersBackRoute)

    BackHandler(enabled = !showNowPlaying && !showJellyfinAudioNowPlaying && !isRootRoute) {
        navigateBack()
    }

    val latestSessionMode = rememberUpdatedState(state.sessionMode)
    val latestOfflineBrowseEntered = rememberUpdatedState(offlineBrowseEntered)
    val latestVoiceAllowed = rememberUpdatedState(voiceAllowed)

    val navigation = remember(context, fcastSession, jellyfinAudioDispatcher, maPlayDispatcher) {
        object : HomeVoiceNavigation {
            override fun launchItem(item: SpatialFinItem): Boolean {
                val isUniversal = item is dev.jdtech.jellyfin.plugins.model.UniversalSpatialFinItem
                if (!isUniversal && latestSessionMode.value != SessionMode.SERVER_AUTHENTICATED) {
                    return false
                }
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
                return when (item) {
                    is dev.jdtech.jellyfin.plugins.model.UniversalSpatialFinItem -> {
                        context.startActivity(
                            BeamPlayerActivity.createIntentForUniversalMedia(
                                context = context,
                                pluginId = item.universalMediaItem.pluginId,
                                itemId = item.universalMediaItem.id,
                                videoUrl = item.universalMediaItem.videoUrl,
                                title = item.name,
                            )
                        )
                        true
                    }
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
                        if (SessionNavigationPolicy.isSearchAllowed(latestSessionMode.value)) {
                            voiceController.voiceSearchQuery = item.name
                            true
                        } else {
                            false
                        }
                    }
                }
            }

            override fun launchMusic(query: String): Boolean {
                if (!SessionNavigationPolicy.isMaOverlayAllowed(latestSessionMode.value)) return false
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

            override fun controlMusic(command: MusicVoiceCommand): Boolean {
                if (!SessionNavigationPolicy.isMaOverlayAllowed(latestSessionMode.value)) return false
                if (maSession.session.value.selectedPlayer == null) return false
                when (command) {
                    MusicVoiceCommand.PlayPause -> maPlayDispatcher.playPause()
                    MusicVoiceCommand.Pause -> maPlayDispatcher.pause()
                    MusicVoiceCommand.Resume -> maPlayDispatcher.resume()
                    MusicVoiceCommand.Next -> maPlayDispatcher.next()
                    MusicVoiceCommand.Previous -> maPlayDispatcher.previous()
                    is MusicVoiceCommand.AdjustVolume -> {
                        val pct = command.percentage
                        if (pct != null) maPlayDispatcher.setVolume((pct * 100f).toInt())
                        else maPlayDispatcher.nudgeVolume(command.delta ?: 0.1f)
                    }
                }
                return true
            }

            override fun goHome() {
                beamDefaultRootRoute(latestSessionMode.value, latestOfflineBrowseEntered.value)?.let {
                    currentRoute = it
                }
            }
            override fun goBack() { navigateBack() }
            override fun closeApp() { onFinishAffinity() }
        }
    }

    val latestHasMicPermission = rememberUpdatedState(hasMicPermission)
    val latestAssistantSpoken = rememberUpdatedState(assistantSpokenReplies)
    val latestAssistantVoice = rememberUpdatedState(assistantVoiceName)
    var micPermissionModalActive by remember { mutableStateOf(false) }

    DisposableEffect(graceHook) {
        onDispose {
            if (micPermissionModalActive) {
                micPermissionModalActive = false
                graceHook.setSystemModalActive(false)
            }
        }
    }

    val micPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (micPermissionModalActive) {
                micPermissionModalActive = false
                graceHook.setSystemModalActive(false)
            }
            hasMicPermission = granted
            if (granted && latestVoiceAllowed.value) {
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
        if (!latestVoiceAllowed.value) return
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
                if (!micPermissionModalActive) {
                    micPermissionModalActive = true
                    graceHook.setSystemModalActive(true)
                }
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            },
        )
    }

    val onVoiceMicClick: () -> Unit = {
        if (latestVoiceAllowed.value) {
            if (voiceController.isVoiceTurnBusy(voiceState, isTtsSpeaking)) {
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
    }

    voiceController.RegisterEffects(
        requestFollowUp = { startVoiceCommand("beam-follow-up-auto") },
    )

    DisposableEffect(voiceController) { onDispose { voiceController.destroy() } }

    LaunchedEffect(
        state.isLoading,
        state.sessionMode,
        state.activationEpoch,
        state.coordinatorInstanceId,
        state.savedServerIds,
        state.hasServers,
        state.hasCurrentServer,
        offlineBrowseEntered,
        currentRoute,
    ) {
        if (state.isLoading) return@LaunchedEffect
        val observer = BeamSessionObserverState(
            routeInitialized = routeInitialized,
            offlineBrowseEntered = offlineBrowseEntered,
            savedCoordinatorInstanceId = savedCoordinatorInstanceId,
            lastObservedActivationEpoch = lastObservedActivationEpoch,
            lastObservedSessionMode = lastObservedSessionModeName?.let {
                runCatching { SessionMode.valueOf(it) }.getOrNull()
            },
        )
        val result = evaluateBeamSessionTransition(
            currentRoute = currentRoute,
            state = state,
            effectiveOffline = effectiveOffline,
            targetServerId = targetServerId ?: state.activeServerId,
            observer = observer,
        )
        routeInitialized = result.nextObserverState.routeInitialized
        offlineBrowseEntered = result.nextObserverState.offlineBrowseEntered
        savedCoordinatorInstanceId = result.nextObserverState.savedCoordinatorInstanceId
        lastObservedActivationEpoch = result.nextObserverState.lastObservedActivationEpoch
        lastObservedSessionModeName = result.nextObserverState.lastObservedSessionMode?.name
        if (result.nextRoute != currentRoute) {
            if (result.nextRoute == BeamRoute.Users) {
                targetServerId = state.activeServerId
                targetServerName = state.activeServerName
                targetServerAddress = state.currentServerAddress
            }
            if (result.nextRoute == BeamRoute.Servers || result.nextRoute == BeamRoute.Welcome) {
                serversBackRoute = null
                usersBackRoute = null
            }
            currentRoute = result.nextRoute
        }
    }

    var previousEffectiveOffline by rememberSaveable { mutableStateOf(effectiveOffline) }
    LaunchedEffect(effectiveOffline, state.sessionMode, state.isLoading) {
        if (!state.isLoading && state.sessionMode == SessionMode.SERVER_AUTHENTICATED) {
            if (effectiveOffline && (currentRoute == BeamRoute.Home || currentRoute == BeamRoute.Search)) {
                currentRoute = BeamRoute.Downloads
            } else if (previousEffectiveOffline && !effectiveOffline && currentRoute == BeamRoute.Downloads) {
                currentRoute = BeamRoute.Home
            }
        }
        previousEffectiveOffline = effectiveOffline
    }

    val showPrimaryNavigation =
        !state.isLoading &&
            routeInitialized &&
            when (state.sessionMode) {
                SessionMode.SERVER_AUTHENTICATED,
                SessionMode.STANDALONE_SOURCES -> true
                SessionMode.SERVER_NEEDS_AUTH -> offlineBrowseEntered
                SessionMode.UNCONFIGURED -> false
            } &&
            currentRoute !in
                setOf(
                    BeamRoute.Welcome,
                    BeamRoute.SetUpFromDevice,
                    BeamRoute.AddServer,
                    BeamRoute.Login,
                )

    val visibleTabs = when (state.sessionMode) {
        SessionMode.STANDALONE_SOURCES -> standaloneTabs
        SessionMode.SERVER_NEEDS_AUTH -> offlineBrowseTabs
        SessionMode.SERVER_AUTHENTICATED ->
            if (effectiveOffline) {
                primaryTabs.filter { it.route != BeamRoute.Home && it.route != BeamRoute.Search }
            } else {
                primaryTabs
            }
        SessionMode.UNCONFIGURED -> emptyList()
    }
    val showSignInNavAction = state.sessionMode == SessionMode.SERVER_NEEDS_AUTH && offlineBrowseEntered
    val onSignInFromOffline: () -> Unit = {
        offlineBrowseEntered = false
        serversBackRoute = null
        usersBackRoute = null
        if (state.hasCurrentServer) {
            openUsersFor(
                serverId = state.activeServerId.orEmpty(),
                serverName = state.activeServerName.orEmpty(),
                serverAddress = state.currentServerAddress.orEmpty(),
                backRoute = null,
            )
        } else {
            currentRoute = if (state.hasServers) BeamRoute.Servers else BeamRoute.AddServer
        }
    }

    val beamWidth = rememberBeamWidth()
    val useBottomNav = beamWidth.isCompact && showPrimaryNavigation

    val sidebarContent: @Composable () -> Unit = {
        BeamSidebar(
            currentRoute = currentRoute,
            visibleTabs = visibleTabs,
            isOfflineMode = effectiveOffline && isServerAuthenticated,
            showSignInAction = showSignInNavAction,
            onSignInClick = onSignInFromOffline,
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
        dev.jdtech.jellyfin.presentation.cast.LocalCastButtonController provides
            remember(fcastSession) {
                fcastSession.let { dev.spatialfin.fcast.session.CastButtonControllerAdapter(it) }
            },
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
                contentWindowInsets = WindowInsets.safeDrawing,
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
                    val miniPlayerInsets =
                        if (useBottomNav) {
                            WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
                        } else {
                            WindowInsets.navigationBars
                                .union(WindowInsets.displayCutout)
                                .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
                        }
                    Column {
                        Column(
                            modifier = Modifier.fillMaxWidth().windowInsetsPadding(miniPlayerInsets),
                        ) {
                            dev.spatialfin.fcast.session.FCastMiniController(
                                sessionManager = fcastSession,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                            // Unified music player: MA owns the local SendSpin
                            // receiver UI through MaLocalPlaybackBridge, so the
                            // receiver stays headless and never competes here.
                            val maNowPlaying by maSession.session.collectAsStateWithLifecycle()
                            if (maOverlayAllowed) {
                                dev.spatialfin.unified.music.MaMiniPlayer(
                                    session = maSession,
                                    onExpand = { showNowPlaying = true },
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                )
                            }
                            if (isServerAuthenticated) {
                                jellyfinAudioDispatcher?.let { dispatcher ->
                                    JellyfinAudioMiniPlayer(
                                        dispatcher = dispatcher,
                                        onExpand = { showJellyfinAudioNowPlaying = true },
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                        suppressed = maOverlayAllowed && (
                                            maNowPlaying.nowPlaying != null ||
                                                maNowPlaying.pendingPlayUri != null
                                        ),
                                    )
                                }
                            }
                            dev.jdtech.jellyfin.presentation.shell.LocalRemoteControlMiniPlayerHost.current
                                ?.invoke(Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                        }
                        if (useBottomNav) {
                            BeamBottomNavigationRow(
                                currentRoute = currentRoute,
                                visibleTabs = visibleTabs,
                                showSignInAction = showSignInNavAction,
                                onSignInClick = onSignInFromOffline,
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
                    state.isLoading || !routeInitialized -> {
                        BeamStatusScreen(
                            title = "SpatialFin",
                            body = "Loading your media...",
                            contentPadding = PaddingValues(0.dp),
                        )
                    }
                    currentRoute == BeamRoute.Welcome -> {
                        BeamWelcomeScreen(
                            contentPadding = PaddingValues(0.dp),
                            onConnectToServer = {
                                if (state.hasServers) {
                                    serversBackRoute = BeamRoute.Welcome
                                    currentRoute = BeamRoute.Servers
                                } else {
                                    addServerBackRoute = BeamRoute.Welcome
                                    currentRoute = BeamRoute.AddServer
                                }
                            },
                            onSetUpFromDevice = {
                                setUpFromDeviceBackRoute = BeamRoute.Welcome
                                currentRoute = BeamRoute.SetUpFromDevice
                            },
                            onActivateStandalone = onActivateStandalone,
                        )
                    }
                    currentRoute == BeamRoute.SetUpFromDevice -> {
                        BeamCompanionScreen(
                            contentPadding = PaddingValues(0.dp),
                            onBack = { currentRoute = setUpFromDeviceBackRoute },
                            onBrokerFallbackToLogin = { sId, sName, sAddr, user, code ->
                                openLoginFor(
                                    serverId = sId,
                                    serverName = sName,
                                    serverAddress = sAddr,
                                    username = user,
                                    failureCode = code,
                                )
                            },
                        )
                    }
                    currentRoute == BeamRoute.AddServer -> {
                        BeamAddServerScreen(
                            contentPadding = PaddingValues(0.dp),
                            onSuccess = { serverId, serverName, serverAddress ->
                                openUsersFor(
                                    serverId = serverId,
                                    serverName = serverName,
                                    serverAddress = serverAddress,
                                    backRoute = addServerBackRoute,
                                )
                            },
                            onBackClick = {
                                currentRoute = if (!state.hasServers) BeamRoute.Welcome else addServerBackRoute
                            },
                        )
                    }
                    currentRoute == BeamRoute.Login -> {
                        val loginServerId = resolveActiveTargetServerId()
                        val loginServerName = resolveActiveTargetServerName()
                        val loginServerAddress = resolveActiveTargetServerAddress()
                        BeamLoginScreen(
                            contentPadding = PaddingValues(0.dp),
                            serverId = loginServerId,
                            serverName = loginServerName,
                            serverAddress = loginServerAddress,
                            prefilledUsername = prefilledUsername,
                            initialFailureCode = loginInitialFailureCodeWire?.let(OnboardingFailureCode::fromWireCode),
                            onChangeServerClick = {
                                serversBackRoute = BeamRoute.Login
                                currentRoute = BeamRoute.Servers
                            },
                            onBackClick = { currentRoute = BeamRoute.Users },
                            onBrowseOfflineMedia = if (state.sessionMode == SessionMode.SERVER_NEEDS_AUTH) {
                                {
                                    offlineBrowseEntered = true
                                    currentRoute = BeamRoute.Downloads
                                }
                            } else {
                                null
                            },
                        )
                    }
                    currentRoute == BeamRoute.Home -> {
                        BeamHomeScreen(
                            contentPadding = PaddingValues(0.dp),
                            onOpenLibrary = { libraryId, libraryName, libraryType ->
                                selectedLibraryId = libraryId.toString()
                                selectedLibraryName = libraryName
                                selectedLibraryType = libraryType.type
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
                            onOpenNetworkShare = { shareId ->
                                selectedNetworkShareId = shareId
                                networkBackRoute = BeamRoute.Home
                                currentRoute = BeamRoute.NetworkShare
                            },
                            onOpenMaBrowse = { uri, name ->
                                dev.spatialfin.unified.music.maDetailTargetForUri(uri, name)
                                    ?.let(::openMaBrowse)
                            },
                            userName = state.currentUser?.name,
                            onOpenServer = {
                                serversBackRoute = BeamRoute.Home
                                currentRoute = BeamRoute.Servers
                            },
                            onOpenUser = {
                                openUsersFor(
                                    serverId = state.activeServerId.orEmpty(),
                                    serverName = state.activeServerName.orEmpty(),
                                    serverAddress = state.currentServerAddress.orEmpty(),
                                    backRoute = BeamRoute.Home,
                                )
                            },
                        )
                    }
                    currentRoute == BeamRoute.PluginBrowse -> {
                        val pluginId = selectedPluginId
                        if (pluginId == null) {
                            currentRoute = BeamRoute.Plugins
                        } else {
                            dev.jdtech.jellyfin.plugins.ui.PluginBrowseScreen(
                                pluginId = pluginId,
                                rowId = selectedPluginRowId,
                                onBack = { currentRoute = BeamRoute.Plugins },
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
                                selectedLibraryType = libraryType.type
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
                val libraryType = selectedLibraryType?.let(CollectionType::fromString)
                if (libraryId == null || libraryName == null || libraryType == null) {
                    beamDefaultRootRoute(state.sessionMode, offlineBrowseEntered)?.let { currentRoute = it }
                } else {
                    if (libraryType in AUDIO_COLLECTION_TYPES) {
                        JellyfinAudioLibraryScreen(
                            libraryId = libraryId,
                            libraryName = libraryName,
                            libraryType = libraryType,
                            onBack = {
                                beamDefaultRootRoute(state.sessionMode, offlineBrowseEntered)?.let { currentRoute = it }
                            },
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
                            onBack = {
                                beamDefaultRootRoute(state.sessionMode, offlineBrowseEntered)?.let { currentRoute = it }
                            },
                            onOpenLibrary = { nestedId, nestedName, nestedType ->
                                selectedLibraryId = nestedId.toString()
                                selectedLibraryName = nestedName
                                selectedLibraryType = nestedType.type
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
                    beamDefaultRootRoute(state.sessionMode, offlineBrowseEntered)?.let { currentRoute = it }
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
                        // "More like this": swap the series in place; Back still
                        // returns to wherever the first series was opened from.
                        onOpenShow = { similarShowId -> selectedShowId = similarShowId.toString() },
                    )
                }
                    }
                    currentRoute == BeamRoute.Season -> {
                val seasonId = selectedSeasonId?.let(UUID::fromString)
                if (seasonId == null) {
                    beamDefaultRootRoute(state.sessionMode, offlineBrowseEntered)?.let { currentRoute = it }
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
                            selectedLibraryType = nestedType.type
                            currentRoute = BeamRoute.Library
                        },
                        onOpenShow = { showId ->
                            selectedShowId = showId.toString()
                            if (detailBackRoute != BeamRoute.Show && detailBackRoute != BeamRoute.Season) {
                                showBackRoute = BeamRoute.Detail
                            }
                            currentRoute = BeamRoute.Show
                        },
                        onOpenSeason = { seasonId ->
                            selectedSeasonId = seasonId.toString()
                            if (detailBackRoute != BeamRoute.Season) {
                                seasonBackRoute = BeamRoute.Detail
                            }
                            currentRoute = BeamRoute.Season
                        },
                        onOpenPerson = { personId ->
                            selectedPersonId = personId.toString()
                            personBackRoute = BeamRoute.Detail
                            currentRoute = BeamRoute.Person
                        },
                        // Season siblings and "More like this" swap the item in
                        // place; Back keeps returning to the original origin.
                        onOpenItem = { relatedId -> selectedDetailItemId = relatedId.toString() },
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
                        navigateHome = {
                            beamDefaultRootRoute(state.sessionMode, offlineBrowseEntered)?.let { currentRoute = it }
                        },
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
                        networkBackRoute = BeamRoute.Network
                        currentRoute = BeamRoute.NetworkShare
                    },
                    onAddShareClick = {
                        addShareViewModel.resetTransientState()
                        networkBackRoute = BeamRoute.Network
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
                            isServerAuthenticated = isServerAuthenticated,
                            hasServers = state.hasServers,
                            onOpenCompanion = {
                                setUpFromDeviceBackRoute = BeamRoute.Settings
                                currentRoute = BeamRoute.SetUpFromDevice
                            },
                            onConnectToServer = {
                                if (state.hasServers) {
                                    serversBackRoute = BeamRoute.Settings
                                    currentRoute = BeamRoute.Servers
                                } else {
                                    addServerBackRoute = BeamRoute.Settings
                                    currentRoute = BeamRoute.AddServer
                                }
                            },
                            onOpenUsers = {
                                val sid = state.activeServerId
                                val addr = state.currentServerAddress
                                if (!sid.isNullOrBlank() && !addr.isNullOrBlank()) {
                                    targetServerId = sid
                                    targetServerName = state.activeServerName.orEmpty()
                                    targetServerAddress = addr
                                    usersBackRoute = BeamRoute.Settings
                                    currentRoute = BeamRoute.Users
                                } else {
                                    serversBackRoute = BeamRoute.Settings
                                    currentRoute = BeamRoute.Servers
                                }
                            },
                            onLogout = onLogout,
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
                                if (state.hasServers) {
                                    serversBackRoute = BeamRoute.Plugins
                                    currentRoute = BeamRoute.Servers
                                } else {
                                    addServerBackRoute = BeamRoute.Plugins
                                    currentRoute = BeamRoute.AddServer
                                }
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
                    currentRoute = networkBackRoute
                } else {
                    BeamNetworkShareScreen(
                        contentPadding = PaddingValues(0.dp),
                        shareId = shareId,
                        onBack = { currentRoute = networkBackRoute },
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
                            currentRoute = networkBackRoute
                        },
                    )
                }
                    }
                    currentRoute == BeamRoute.NetworkAddShare -> {
                        dev.jdtech.jellyfin.presentation.network.AddShareScreen(
                            navigateBack = { currentRoute = networkBackRoute },
                            viewModel = addShareViewModel,
                        )
                    }
                    currentRoute == BeamRoute.MaSearch -> {
                        dev.spatialfin.unified.music.MaSearchScreen(
                            onBack = {
                                beamDefaultRootRoute(state.sessionMode, offlineBrowseEntered)?.let { currentRoute = it }
                            },
                            onOpenItem = ::openMaBrowse,
                        )
                    }
                    currentRoute == BeamRoute.MaDetail -> {
                        val seed = maDetailSeed
                        val kind = maDetailKind
                        if (seed == null || kind == null) {
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
                    currentRoute == BeamRoute.Local -> {
                        BeamLocalAndNetworkScreen(
                            contentPadding = PaddingValues(0.dp),
                            hasServers = state.hasServers,
                            onConnectToServer = {
                                if (state.hasServers) {
                                    serversBackRoute = BeamRoute.Local
                                    currentRoute = BeamRoute.Servers
                                } else {
                                    addServerBackRoute = BeamRoute.Local
                                    currentRoute = BeamRoute.AddServer
                                }
                            },
                            onShareClick = { shareId ->
                                selectedNetworkShareId = shareId
                                networkBackRoute = BeamRoute.Local
                                currentRoute = BeamRoute.NetworkShare
                            },
                            onAddShareClick = {
                                addShareViewModel.resetTransientState()
                                networkBackRoute = BeamRoute.Local
                                currentRoute = BeamRoute.NetworkAddShare
                            },
                            onNetworkVideoClick = { item ->
                                context.startActivity(
                                    BeamPlayerActivity.createIntentForNetworkMedia(
                                        context = context,
                                        networkVideoId = item.networkVideoId,
                                        startFromBeginning = false,
                                    ),
                                )
                            },
                        )
                    }
                    currentRoute == BeamRoute.Servers -> {
                        BeamServersScreen(
                            contentPadding = PaddingValues(0.dp),
                            onNavigateToUsers = { sId, sName, sAddr ->
                                openUsersFor(
                                    serverId = sId,
                                    serverName = sName,
                                    serverAddress = sAddr,
                                    backRoute = BeamRoute.Servers,
                                )
                            },
                            onAddServerClick = {
                                addServerBackRoute = BeamRoute.Servers
                                currentRoute = BeamRoute.AddServer
                            },
                            onCompanionImportClick = {
                                setUpFromDeviceBackRoute = BeamRoute.Servers
                                currentRoute = BeamRoute.SetUpFromDevice
                            },
                            onBackClick = serversBackRoute?.let { back ->
                                {
                                    serversBackRoute = null
                                    currentRoute = back
                                }
                            },
                        )
                    }
                    currentRoute == BeamRoute.Users -> {
                        val uServerId = resolveActiveTargetServerId()
                        val uServerName = resolveActiveTargetServerName()
                        val uServerAddress = resolveActiveTargetServerAddress()
                        BeamUsersScreen(
                            contentPadding = PaddingValues(0.dp),
                            serverId = uServerId,
                            serverName = uServerName,
                            serverAddress = uServerAddress,
                            onChangeServerClick = {
                                serversBackRoute = BeamRoute.Users
                                currentRoute = BeamRoute.Servers
                            },
                            onAddClick = {
                                openLoginFor(
                                    serverId = uServerId,
                                    serverName = uServerName,
                                    serverAddress = uServerAddress,
                                    username = null,
                                )
                            },
                            onNeedsLogin = { username, failureCode ->
                                openLoginFor(
                                    serverId = uServerId,
                                    serverName = uServerName,
                                    serverAddress = uServerAddress,
                                    username = username,
                                    failureCode = failureCode,
                                )
                            },
                            onBrowseOfflineMedia = if (state.sessionMode == SessionMode.SERVER_NEEDS_AUTH) {
                                {
                                    offlineBrowseEntered = true
                                    currentRoute = BeamRoute.Downloads
                                }
                            } else {
                                null
                            },
                            onLogout = onLogout.takeIf { isServerAuthenticated },
                        )
                    }
                    else -> Unit
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
            var pendingTheaterQrCallback by remember { mutableStateOf<((String) -> Unit)?>(null) }
            var theaterCalibrationTarget by remember { mutableStateOf<dev.jdtech.jellyfin.cast.CastReceiver?>(null) }
            dev.spatialfin.fcast.session.FCastGlobalPickerHost(
                sessionManager = fcastSession,
                onRequestTheaterQrScan = if (dev.jdtech.jellyfin.presentation.setup.welcome.isCompanionCameraAvailable(context)) {
                    { callback -> pendingTheaterQrCallback = callback }
                } else {
                    null
                },
                onOpenTheaterCalibration = { receiver -> theaterCalibrationTarget = receiver },
            )
            theaterCalibrationTarget?.let { target ->
                BackHandler(onBack = { theaterCalibrationTarget = null })
                dev.spatialfin.fcast.session.calibration.TheaterCalibrationScreen(
                    receiver = target,
                    onBack = { theaterCalibrationTarget = null },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            pendingTheaterQrCallback?.let { qrCallback ->
                Dialog(onDismissRequest = { pendingTheaterQrCallback = null }) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(440.dp),
                        shape = RoundedCornerShape(24.dp),
                    ) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                dev.jdtech.jellyfin.presentation.setup.welcome.RawQrCodeScanner(
                                    acceptRawValue = { it.startsWith("spatialfin-avr://", ignoreCase = true) },
                                    onQrScanned = { raw ->
                                        pendingTheaterQrCallback = null
                                        qrCallback(raw)
                                    },
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                androidx.compose.material3.TextButton(onClick = { pendingTheaterQrCallback = null }) {
                                    Text("Cancel")
                                }
                            }
                        }
                    }
                }
            }
            // Open the right MA overlay when a queue/party deep link arrives (gated on MA overlay permission).
            if (maDeepLink != null) {
                val pendingDeepLink by maDeepLink.collectAsStateWithLifecycle()
                LaunchedEffect(pendingDeepLink, state.isLoading, maOverlayAllowed) {
                    if (pendingDeepLink != null && !state.isLoading) {
                        if (maOverlayAllowed) {
                            when (pendingDeepLink) {
                                dev.jdtech.jellyfin.deeplink.MaDeepLink.Parsed.Queue -> showNowPlaying = true
                                dev.jdtech.jellyfin.deeplink.MaDeepLink.Parsed.Party -> showParty = true
                                else -> Unit
                            }
                        }
                        onMaDeepLinkHandled()
                    }
                }
            }
            if (showNowPlaying && maOverlayAllowed) {
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
            if (isServerAuthenticated) {
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
            }
            if (showParty && maOverlayAllowed) {
                BackHandler(onBack = { showParty = false })
                dev.spatialfin.unified.music.MaPartyScreen(
                    session = maSession,
                    onBack = { showParty = false },
                    onPickPlayer = { showPlayerPicker = true },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (showPlayerPicker && maOverlayAllowed) {
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
    visibleTabs: List<BeamTab>,
    showSignInAction: Boolean = false,
    onSignInClick: () -> Unit = {},
    onNavigate: (BeamRoute) -> Unit,
) {
    val fcastSession = dev.spatialfin.fcast.session.LocalFCastSession.current
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
                    .windowInsetsPadding(
                        WindowInsets.navigationBars
                            .union(WindowInsets.displayCutout)
                            .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
                    )
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
                if (showSignInAction) {
                    BeamNavCell(
                        icon = { tint ->
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.Login,
                                contentDescription = "Sign in",
                                modifier = Modifier.size(22.dp),
                                tint = tint,
                            )
                        },
                        label = "Sign in",
                        selected = false,
                        onClick = onSignInClick,
                    )
                }
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
    visibleTabs: List<BeamTab>,
    isOfflineMode: Boolean = false,
    showSignInAction: Boolean = false,
    onSignInClick: () -> Unit = {},
    onNavigate: (BeamRoute) -> Unit,
    onReconnect: () -> Unit = {},
    voiceState: VoiceState = VoiceState.IDLE,
    voicePartial: String = "",
    onVoiceClick: () -> Unit = {},
    showVoice: Boolean = true,
    forceExpanded: Boolean = false,
) {
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
                    painter = painterResource(id = SetupR.drawable.ic_beam_launcher),
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
            if (showSignInAction) {
                Surface(
                    onClick = onSignInClick,
                    color = Color.Transparent,
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = if (isExpanded) 16.dp else 0.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = if (isExpanded) Arrangement.spacedBy(12.dp) else Arrangement.Center,
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.Login,
                            contentDescription = "Sign in",
                            modifier = Modifier.size(22.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        if (isExpanded) {
                            Text(
                                text = "Sign in",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold,
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
    onConnectToServer: () -> Unit,
    onSetUpFromDevice: () -> Unit,
    onActivateStandalone: () -> Unit,
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
                .fillMaxWidth(0.75f)
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
                    onClick = onConnectToServer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Connect to Jellyfin server")
                }
                FilledTonalButton(
                    onClick = onSetUpFromDevice,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(SetupR.string.set_up_from_another_device))
                }
                OutlinedButton(
                    onClick = onActivateStandalone,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Continue without a server")
                }
            }
        }
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
            .windowInsetsPadding(
                WindowInsets.statusBars
                    .union(WindowInsets.displayCutout)
                    .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
            )
            .padding(top = 24.dp, start = 24.dp, end = 24.dp),
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

/**
 * Snapshot the live Music Assistant session for the Beam voice layer. Null when
 * nothing controllable is loaded, so transport routing falls back to
 * navigation/search. Mirrors the XR host's `currentMaVoiceState`.
 */
private fun beamMaVoiceState(
    session: dev.jdtech.jellyfin.data.musicassistant.repository.MaSessionRepository,
): MaVoiceState? {
    val s = session.session.value
    val nowPlaying = s.nowPlaying ?: return null
    if (s.selectedPlayer == null) return null
    return MaVoiceState(
        active = true,
        isPlaying = s.playbackPhase ==
            dev.jdtech.jellyfin.data.musicassistant.repository.PlaybackPhase.Playing,
        track = nowPlaying.title,
        artist = nowPlaying.artist,
        album = null,
    )
}
