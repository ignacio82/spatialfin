package dev.spatialfin.beam

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
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
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.IconButton
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.draw.blur
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
import dev.jdtech.jellyfin.player.beam.voice.BeamVoiceService
import dev.jdtech.jellyfin.player.beam.voice.BeamVoiceState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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
        BeamTab(BeamRoute.Local, "Local", Icons.Rounded.Folder),
        BeamTab(BeamRoute.Network, "Network", Icons.Rounded.Lan),
        BeamTab(BeamRoute.Downloads, "Downloads", Icons.Rounded.CloudDownload),
        BeamTab(BeamRoute.Settings, "Settings", Icons.Rounded.Settings),
        BeamTab(BeamRoute.Servers, "Servers", Icons.Rounded.Dns),
        BeamTab(BeamRoute.Users, "Users", Icons.Rounded.People),
    )

val LocalBeamBackground = androidx.compose.runtime.compositionLocalOf<(Any?) -> Unit> { {} }

@Composable
fun BeamNavigationRoot(
    state: MainState,
    appPreferences: AppPreferences,
) {
    val context = LocalContext.current
    var currentRoute by rememberSaveable { mutableStateOf(BeamRoute.Welcome) }
    var voiceQuery by rememberSaveable { mutableStateOf<String?>(null) }
    val voiceService = remember { BeamVoiceService(context) }
    val voiceState by voiceService.state.collectAsStateWithLifecycle()
    val voicePartial by voiceService.partialTranscript.collectAsStateWithLifecycle()

    DisposableEffect(Unit) { onDispose { voiceService.destroy() } }

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
    var beamBackgroundUrl by remember { mutableStateOf<Any?>(null) }

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
                state.hasServers && state.hasCurrentServer && state.hasCurrentUser -> BeamRoute.Home
                state.hasServers && state.hasCurrentServer -> BeamRoute.Users
                state.hasServers -> BeamRoute.Servers
                else -> BeamRoute.Local
            }
    }

    val showPrimaryNavigation =
        !state.isLoading && appPreferences.getValue(appPreferences.onboardingCompleted)

    CompositionLocalProvider(LocalBeamBackground provides { beamBackgroundUrl = it }) {
        Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0F141C))) {
            AsyncImage(
                model = beamBackgroundUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().blur(80.dp),
                contentScale = ContentScale.Crop,
                alpha = 0.35f,
            )
            Scaffold(
                containerColor = Color.Transparent,
            ) { innerPadding ->
                Row(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .padding(18.dp),
                ) {
            if (showPrimaryNavigation) {
                BeamSidebar(
                    currentRoute = currentRoute,
                    onNavigate = { currentRoute = it },
                    voiceState = voiceState,
                    voicePartial = voicePartial,
                    onVoiceClick = {
                        if (voiceState == BeamVoiceState.LISTENING) {
                            voiceService.stopListening()
                        } else {
                            voiceService.resetState()
                            voiceService.startListening { transcript ->
                                if (transcript.isNotBlank()) {
                                    voiceQuery = transcript
                                    currentRoute = BeamRoute.Search
                                }
                                voiceService.resetState()
                            }
                        }
                    },
                )
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
}
}
}

@Composable
private fun BeamSidebar(
    currentRoute: BeamRoute,
    onNavigate: (BeamRoute) -> Unit,
    voiceState: BeamVoiceState = BeamVoiceState.IDLE,
    voicePartial: String = "",
    onVoiceClick: () -> Unit = {},
) {
    var isExpanded by rememberSaveable { mutableStateOf(false) }
    val sidebarWidth by androidx.compose.animation.core.animateDpAsState(
        targetValue = if (isExpanded) 200.dp else 80.dp,
        label = "sidebarWidth"
    )

    Surface(
        modifier = Modifier.width(sidebarWidth).fillMaxHeight(),
        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.7f),
        shape = RoundedCornerShape(24.dp),
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp),
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
                        .clickable { isExpanded = !isExpanded },
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
                    IconButton(onClick = onVoiceClick) {
                        Icon(
                            imageVector = Icons.Rounded.Mic,
                            contentDescription = "Voice",
                            modifier = Modifier.size(22.dp),
                            tint = if (voiceState == BeamVoiceState.LISTENING) Color(0xFF4FC3F7) else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (voiceState == BeamVoiceState.LISTENING && voicePartial.isNotBlank() && isExpanded) {
                Text(
                    text = voicePartial,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF4FC3F7),
                    maxLines = 2,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            primaryTabs.forEach { tab ->
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
