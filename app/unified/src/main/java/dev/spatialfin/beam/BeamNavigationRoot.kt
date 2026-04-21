package dev.spatialfin.beam

import dev.spatialfin.R
import android.Manifest
import android.content.pm.PackageManager
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
import androidx.compose.material.icons.rounded.Dns
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import coil3.compose.AsyncImage
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.background
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
import dev.jdtech.jellyfin.player.beam.BeamPlayerActivity
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.viewmodels.MainState
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
    Downloads,
    Settings,
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
        BeamTab(BeamRoute.Settings, "Settings", Icons.Rounded.Settings),
        BeamTab(BeamRoute.Users, "Users", Icons.Rounded.People),
        BeamTab(BeamRoute.Local, "Local", Icons.Rounded.Folder),
        BeamTab(BeamRoute.Network, "Network", Icons.Rounded.Lan),
    )

val LocalBeamBackground = androidx.compose.runtime.compositionLocalOf<(Any?) -> Unit> { {} }

@Composable
fun BeamNavigationRoot(
    state: MainState,
    appPreferences: AppPreferences,
    repository: JellyfinRepository,
    llmModelManager: LlmModelManager,
    voiceTelemetryStore: VoiceTelemetryStore,
    onReconnect: () -> Unit = {},
    onFinishApp: () -> Unit = {},
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var currentRoute by rememberSaveable { mutableStateOf(BeamRoute.Welcome) }
    var voiceQuery by rememberSaveable { mutableStateOf<String?>(null) }

    val voiceController = remember(context) {
        HomeVoiceController(
            applicationContext = context.applicationContext,
            appPreferences = appPreferences,
            repository = repository,
            llmModelManager = llmModelManager,
            voiceTelemetryStore = voiceTelemetryStore,
        )
    }
    val voiceState by voiceController.voiceService.state.collectAsStateWithLifecycle()
    val voicePartial by voiceController.voiceService.partialTranscript.collectAsStateWithLifecycle()
    val isTtsSpeaking by voiceController.tts.isSpeaking.collectAsStateWithLifecycle()
    val voiceCapability by llmModelManager.voiceCapability.collectAsStateWithLifecycle()
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

    val navigation = remember(context) {
        object : HomeVoiceNavigation {
            override fun launchItem(item: SpatialFinItem): Boolean {
                val intent = BeamPlayerActivity.createIntentForSpatialItem(context, item)
                if (intent != null &&
                    runCatching { context.startActivity(intent) }.isSuccess
                ) {
                    return true
                }
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
                    else -> {
                        voiceController.voiceSearchQuery = item.name
                        true
                    }
                }
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
            voiceController.interruptVoiceCommand(
                scope = coroutineScope,
                reason = "beam-mic-tap",
                currentVoiceState = voiceState,
                currentTtsSpeaking = isTtsSpeaking,
                requestFollowUp = { startVoiceCommand("beam-follow-up-interrupt") },
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
            return@LaunchedEffect
        }

        val onboardingCompleted = appPreferences.getValue(appPreferences.onboardingCompleted)
        currentRoute =
            when {
                !onboardingCompleted -> BeamRoute.Welcome
                state.hasServers && state.hasCurrentServer && state.hasCurrentUser ->
                    if (state.isOfflineMode) BeamRoute.Downloads else BeamRoute.Home
                state.hasServers && state.hasCurrentServer -> BeamRoute.Users
                state.hasServers -> BeamRoute.Servers
                else -> BeamRoute.Local
            }
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
                    if (useBottomNav) {
                        BeamBottomNavigationRow(
                            currentRoute = currentRoute,
                            isOfflineMode = state.isOfflineMode,
                            onNavigate = { currentRoute = it },
                        )
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
                                appPreferences.setValue(appPreferences.onboardingCompleted, true)
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
                            onSuccess = { currentRoute = BeamRoute.Home },
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
                        )
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
                        )
                    }
                    currentRoute == BeamRoute.Library -> {
                val libraryId = selectedLibraryId?.let(UUID::fromString)
                val libraryName = selectedLibraryName
                val libraryType = selectedLibraryType?.let(CollectionType::valueOf)
                if (libraryId == null || libraryName == null || libraryType == null) {
                    currentRoute = BeamRoute.Home
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
                    onOpenCompanion = { currentRoute = BeamRoute.Companion },
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
                    onNavigateToHome = { currentRoute = BeamRoute.Home },
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
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.92f),
        tonalElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            visibleTabs.forEach { tab ->
                val selected = currentRoute == tab.route
                Surface(
                    onClick = { onNavigate(tab.route) },
                    color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Icon(
                            imageVector = tab.icon,
                            contentDescription = tab.label,
                            modifier = Modifier.size(22.dp),
                            tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                   else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = tab.label,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            softWrap = false,
                            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
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
        BeamRoute.Settings,
        BeamRoute.NetworkShare ->
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
    androidx.compose.animation.AnimatedVisibility(
        visible = message != null,
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = 56.dp, start = 24.dp, end = 24.dp),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.95f),
            shape = RoundedCornerShape(20.dp),
            tonalElevation = 6.dp,
        ) {
            Text(
                text = message.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .widthIn(max = 360.dp)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
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
    val listeningTint = Color(0xFF4FC3F7)
    val thinkingTint = Color(0xFFFFB74D)
    val speakingTint = Color(0xFFBA68C8)
    val errorTint = Color(0xFFEF5350)

    val tint = when {
        isTtsSpeaking -> speakingTint
        voiceState == VoiceState.LISTENING -> listeningTint
        voiceState == VoiceState.PROCESSING -> thinkingTint
        voiceState == VoiceState.ERROR -> errorTint
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val label = when {
        isTtsSpeaking -> "Tap to stop"
        voiceState == VoiceState.LISTENING ->
            if (partialTranscript.isNotBlank()) partialTranscript else "Listening…"
        voiceState == VoiceState.PROCESSING -> "Thinking…"
        else -> null
    }
    val pulse by androidx.compose.animation.core.rememberInfiniteTransition(label = "voiceFabPulse")
        .animateFloat(
            initialValue = 0.85f,
            targetValue = 1f,
            animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                animation = androidx.compose.animation.core.tween(600, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
            ),
            label = "voiceFabPulseValue",
        )
    val iconAlpha = if (voiceState == VoiceState.LISTENING || voiceState == VoiceState.PROCESSING || isTtsSpeaking) pulse else 1f

    androidx.compose.material3.FloatingActionButton(
        onClick = onClick,
        shape = RoundedCornerShape(26.dp),
        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
        elevation = androidx.compose.material3.FloatingActionButtonDefaults.elevation(
            defaultElevation = 1.dp,
            pressedElevation = 2.dp,
            hoveredElevation = 2.dp,
            focusedElevation = 1.dp,
        ),
        modifier = Modifier.height(52.dp).widthIn(min = 52.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = if (label != null) 16.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Mic,
                contentDescription = "Voice Assistant",
                tint = tint.copy(alpha = iconAlpha),
            )
            androidx.compose.animation.AnimatedVisibility(visible = label != null) {
                Row(modifier = Modifier.padding(start = 8.dp)) {
                    Text(
                        text = label.orEmpty(),
                        style = MaterialTheme.typography.labelMedium,
                        color = tint,
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
