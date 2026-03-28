package dev.spatialfin.tv

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ManageSearch
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.jdtech.jellyfin.film.presentation.home.HomeState
import dev.jdtech.jellyfin.film.presentation.home.HomeViewModel
import dev.jdtech.jellyfin.models.HomeItem
import dev.jdtech.jellyfin.models.SpatialFinBoxSet
import dev.jdtech.jellyfin.models.SpatialFinCollection
import dev.jdtech.jellyfin.models.SpatialFinEpisode
import dev.jdtech.jellyfin.models.SpatialFinFolder
import dev.jdtech.jellyfin.models.SpatialFinItem
import dev.jdtech.jellyfin.models.SpatialFinMovie
import dev.jdtech.jellyfin.models.SpatialFinSeason
import dev.jdtech.jellyfin.models.SpatialFinShow
import dev.jdtech.jellyfin.models.View
import dev.jdtech.jellyfin.models.versionChipLabel
import dev.jdtech.jellyfin.player.tv.TvPlayerActivity
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.utils.getShowDateString
import dev.jdtech.jellyfin.viewmodels.MainState
import java.util.UUID

private enum class TvRoute {
    Home,
    Search,
    Library,
    Detail,
    Show,
    Season,
    Companion,
    Settings,
}

private data class TvNavItem(
    val route: TvRoute,
    val label: String,
    val icon: ImageVector,
)

private val tvNavItems =
    listOf(
        TvNavItem(TvRoute.Home, "Home", Icons.Rounded.Home),
        TvNavItem(TvRoute.Search, "Search", Icons.AutoMirrored.Rounded.ManageSearch),
        TvNavItem(TvRoute.Library, "Libraries", Icons.Rounded.LiveTv),
        TvNavItem(TvRoute.Companion, "Companion", Icons.Rounded.Link),
        TvNavItem(TvRoute.Settings, "Settings", Icons.Rounded.Settings),
    )

val LocalFocusedBackground = compositionLocalOf<(Any?) -> Unit> { {} }

@Composable
fun TvNavigationRoot(
    state: MainState,
    appPreferences: AppPreferences,
) {
    val homeViewModel: HomeViewModel = hiltViewModel()
    val homeState by homeViewModel.state.collectAsStateWithLifecycle()
    var currentRoute by rememberSaveable { mutableStateOf(TvRoute.Home) }
    var selectedView by remember { mutableStateOf<View?>(null) }
    var selectedItemId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedShowId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedSeasonId by rememberSaveable { mutableStateOf<String?>(null) }
    var focusedBackgroundUrl by remember { mutableStateOf<Any?>(null) }
    val companionConfigured = tvCompanionConfigured(appPreferences)

    LaunchedEffect(Unit) {
        homeViewModel.loadData()
    }

    fun openItem(item: SpatialFinItem) {
        when (item) {
            is SpatialFinShow -> {
                selectedShowId = item.id.toString()
                currentRoute = TvRoute.Show
            }
            is SpatialFinSeason -> {
                selectedSeasonId = item.id.toString()
                currentRoute = TvRoute.Season
            }
            is SpatialFinCollection -> Unit
            else -> {
                selectedItemId = item.id.toString()
                currentRoute = TvRoute.Detail
            }
        }
    }

    CompositionLocalProvider(LocalFocusedBackground provides { focusedBackgroundUrl = it }) {
        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            TvAmbientBackground(backgroundModel = focusedBackgroundUrl)
            Scaffold(
                containerColor = Color.Transparent,
            ) { innerPadding ->
                Row(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .padding(horizontal = 26.dp, vertical = 22.dp),
                ) {
                    TvSidebar(
                        currentRoute = currentRoute,
                        serverName = homeState.server?.name,
                        companionConfigured = companionConfigured,
                        hasCurrentUser = state.hasCurrentUser,
                        onNavigate = { route ->
                            if (route == TvRoute.Library && selectedView == null) {
                                selectedView = homeState.views.firstOrNull()?.view
                            }
                            currentRoute = route
                        },
                    )
                    Spacer(Modifier.width(22.dp))
                    Card(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        colors = CardDefaults.cardColors(containerColor = Color(0x4D0E1924)),
                        shape = RoundedCornerShape(36.dp),
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .background(Color.White.copy(alpha = 0.015f))
                                    .padding(horizontal = 30.dp, vertical = 28.dp),
                        ) {
                            when (currentRoute) {
                                TvRoute.Home -> TvHomeScreen(
                                    homeState = homeState,
                                    state = state,
                                    appPreferences = appPreferences,
                                    onOpenLibrary = { view ->
                                        selectedView = view
                                        currentRoute = TvRoute.Library
                                    },
                                    onOpenItem = ::openItem,
                                    onOpenCompanion = { currentRoute = TvRoute.Companion },
                                    onOpenSearch = { currentRoute = TvRoute.Search },
                                    onRefresh = { homeViewModel.loadData() },
                                )
                                TvRoute.Search -> TvSearchScreen(
                                    onOpenItem = ::openItem,
                                )
                                TvRoute.Library -> TvLibraryScreen(
                                    view = selectedView,
                                    availableViews = homeState.views.map { it.view },
                                    onBackToHome = { currentRoute = TvRoute.Home },
                                    onSelectView = { selectedView = it },
                                    onOpenItem = ::openItem,
                                )
                                TvRoute.Detail -> TvItemDetailScreen(
                                    itemId = selectedItemId?.let(UUID::fromString),
                                    onBack = { currentRoute = TvRoute.Home },
                                )
                                TvRoute.Show -> TvShowScreen(
                                    showId = selectedShowId?.let(UUID::fromString),
                                    onBack = { currentRoute = TvRoute.Home },
                                    onOpenSeason = { seasonId ->
                                        selectedSeasonId = seasonId.toString()
                                        currentRoute = TvRoute.Season
                                    },
                                    onOpenEpisode = { episodeId ->
                                        selectedItemId = episodeId.toString()
                                        currentRoute = TvRoute.Detail
                                    },
                                )
                                TvRoute.Season -> TvSeasonScreen(
                                    seasonId = selectedSeasonId?.let(UUID::fromString),
                                    onBack = { currentRoute = TvRoute.Show },
                                    onOpenEpisode = { episodeId ->
                                        selectedItemId = episodeId.toString()
                                        currentRoute = TvRoute.Detail
                                    },
                                )
                                TvRoute.Settings -> TvSettingsScreen(
                                    state = state,
                                    appPreferences = appPreferences,
                                    serverName = homeState.server?.name,
                                    onOpenCompanion = { currentRoute = TvRoute.Companion },
                                    onOpenSearch = { currentRoute = TvRoute.Search },
                                )
                                TvRoute.Companion -> TvCompanionScreen(
                                    onBack = { currentRoute = TvRoute.Settings },
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
private fun TvAmbientBackground(backgroundModel: Any?) {
    Box(modifier = Modifier.fillMaxSize()) {
        AsyncImage(
            model = backgroundModel,
            contentDescription = null,
            modifier = Modifier.fillMaxSize().blur(88.dp),
            contentScale = ContentScale.Crop,
            alpha = 0.32f,
        )
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            colors = listOf(Color(0xC406111B), Color(0xFF06111B)),
                        )
                    ),
        )
        Box(
            modifier =
                Modifier
                    .size(420.dp)
                    .padding(top = 40.dp, start = 60.dp)
                    .blur(120.dp)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.20f), RoundedCornerShape(999.dp)),
        )
        Box(
            modifier =
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(360.dp)
                    .padding(end = 50.dp, bottom = 70.dp)
                    .blur(110.dp)
                    .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f), RoundedCornerShape(999.dp)),
        )
    }
}

@Composable
private fun TvSidebar(
    currentRoute: TvRoute,
    serverName: String?,
    companionConfigured: Boolean,
    hasCurrentUser: Boolean,
    onNavigate: (TvRoute) -> Unit,
) {
    Card(
        modifier = Modifier.width(292.dp).fillMaxHeight(),
        colors = CardDefaults.cardColors(containerColor = Color(0x5C111B28)),
        shape = RoundedCornerShape(32.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                androidx.compose.foundation.Image(
                    painter = painterResource(id = R.drawable.ic_launcher_foreground),
                    contentDescription = "App Logo",
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(12.dp))
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "SpatialFin TV",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Cinematic Jellyfin with companion sync",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            TvStatusCard(
                title = "Current server",
                value = serverName ?: "No server selected",
                detail = if (hasCurrentUser) "Signed in and ready for playback" else "Sign in to unlock live shelves",
            )

            tvNavItems.forEach { item ->
                TvSidebarButton(
                    item = item,
                    selected = currentRoute == item.route,
                    onClick = { onNavigate(item.route) },
                )
            }

            Spacer(Modifier.weight(1f))

            Card(
                colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.04f)),
                shape = RoundedCornerShape(24.dp),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = if (companionConfigured) "Companion linked" else "Companion available",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text =
                            if (companionConfigured) {
                                "Servers, preferences, and follow-up sync can be pushed from your phone."
                            } else {
                                "Open Companion from the rail to pair this TV with your SpatialFin companion."
                            },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun TvSidebarButton(
    item: TvNavItem,
    selected: Boolean,
    onClick: () -> Unit,
) {
    var isFocused by rememberSaveable(item.route) { mutableStateOf(false) }
    val scale by animateFloatAsState(targetValue = if (isFocused) 1.03f else 1f, label = "scale")
    val highlight =
        when {
            selected -> MaterialTheme.colorScheme.primaryContainer
            isFocused -> Color.White.copy(alpha = 0.08f)
            else -> Color.White.copy(alpha = 0.02f)
        }

    TextButton(
        onClick = onClick,
        modifier =
            Modifier
                .fillMaxWidth()
                .onFocusChanged { isFocused = it.isFocused }
                .focusable()
                .graphicsLayer { scaleX = scale; scaleY = scale },
        shape = RoundedCornerShape(18.dp),
        colors =
            ButtonDefaults.textButtonColors(
                containerColor = highlight,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start,
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(14.dp))
            Text(
                text = item.label,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
private fun TvHomeScreen(
    homeState: HomeState,
    state: MainState,
    appPreferences: AppPreferences,
    onOpenLibrary: (View) -> Unit,
    onOpenItem: (SpatialFinItem) -> Unit,
    onOpenCompanion: () -> Unit,
    onOpenSearch: () -> Unit,
    onRefresh: () -> Unit,
) {
    val context = LocalContext.current
    val companionConfigured = tvCompanionConfigured(appPreferences)
    val featuredPair =
        when {
            !homeState.resumeSection?.homeSection?.items.isNullOrEmpty() ->
                "Continue watching" to homeState.resumeSection!!.homeSection.items.first()
            !homeState.nextUpSection?.homeSection?.items.isNullOrEmpty() ->
                "Play next" to homeState.nextUpSection!!.homeSection.items.first()
            !homeState.suggestionsSection?.items.isNullOrEmpty() ->
                "Featured for this room" to homeState.suggestionsSection!!.items.first()
            else -> null
        }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        contentPadding = PaddingValues(bottom = 42.dp),
    ) {
        item {
            TvPageHeaderCard(
                title = homeState.server?.name ?: "SpatialFin TV",
                body =
                    if (state.hasCurrentUser) {
                        "Wholphin-inspired media rails, a stronger hero focus, and SpatialFin companion controls built for D-pad browsing."
                    } else {
                        "Sign in to a Jellyfin server to unlock live shelves, resume rows, and companion-driven setup."
                    },
            )
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                TvStatusCard(
                    title = "Servers",
                    value = if (state.hasServers) "Ready" else "Missing",
                    detail = if (state.hasServers) "A Jellyfin server is configured" else "Add a server to populate TV shelves",
                    modifier = Modifier.weight(1f),
                )
                TvStatusCard(
                    title = "Session",
                    value = if (state.hasCurrentUser) "Signed in" else "Waiting",
                    detail = if (state.hasCurrentUser) "Playback and search are fully enabled" else "Choose a user to continue",
                    modifier = Modifier.weight(1f),
                )
                TvStatusCard(
                    title = "Companion",
                    value = if (companionConfigured) "Linked" else "Available",
                    detail =
                        if (companionConfigured) {
                            "Phone-driven setup and sync are active"
                        } else {
                            "Pair from your phone for fast onboarding"
                        },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (homeState.isLoading) {
            item {
                TvPlaceholderScreen(
                    title = "Loading your room",
                    body = "Fetching Continue Watching, Next Up, suggestions, and library rails from Jellyfin.",
                )
            }
            return@LazyColumn
        }

        if (homeState.error != null) {
            item {
                TvPlaceholderScreen(
                    title = "Home unavailable",
                    body = homeState.error?.localizedMessage ?: "Failed to load TV home content.",
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    TvActionTile(
                        title = "Search library",
                        body = "Jump straight into direct Jellyfin search from the TV.",
                        icon = Icons.AutoMirrored.Rounded.ManageSearch,
                        modifier = Modifier.weight(1f),
                        onClick = onOpenSearch,
                    )
                    TvActionTile(
                        title = "Companion setup",
                        body = "Open QR pairing and import settings from your phone companion.",
                        icon = Icons.Rounded.Link,
                        modifier = Modifier.weight(1f),
                        onClick = onOpenCompanion,
                    )
                    TvActionTile(
                        title = "Refresh home",
                        body = "Retry the Jellyfin home request for this TV.",
                        icon = Icons.Rounded.Home,
                        modifier = Modifier.weight(1f),
                        onClick = onRefresh,
                    )
                }
            }
            return@LazyColumn
        }

        featuredPair?.let { (eyebrow, item) ->
            item {
                TvHomeHeroCard(
                    item = item,
                    eyebrow = eyebrow,
                    companionConfigured = companionConfigured,
                    onPrimaryAction = {
                        if (item.canPlay) {
                            TvPlayerActivity.createIntentForSpatialItem(context, item)?.let(context::startActivity)
                        } else {
                            onOpenItem(item)
                        }
                    },
                    onDetails = { onOpenItem(item) },
                    onOpenSearch = onOpenSearch,
                    onOpenCompanion = onOpenCompanion,
                )
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                TvActionTile(
                    title = "Search",
                    body = "Find movies, series, seasons, episodes, and people without leaving the TV shell.",
                    icon = Icons.AutoMirrored.Rounded.ManageSearch,
                    modifier = Modifier.weight(1f),
                    onClick = onOpenSearch,
                )
                TvActionTile(
                    title = "Libraries",
                    body = "Browse the latest rows from every Jellyfin collection and jump into any library.",
                    icon = Icons.Rounded.LiveTv,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        homeState.views.firstOrNull()?.view?.let(onOpenLibrary) ?: onOpenSearch()
                    },
                )
                TvActionTile(
                    title = if (companionConfigured) "Companion linked" else "Pair companion",
                    body =
                        if (companionConfigured) {
                            "Push preferences, users, and diagnostics from your phone companion."
                        } else {
                            "Open the TV pairing QR and sync setup from your phone."
                        },
                    icon = Icons.Rounded.Link,
                    modifier = Modifier.weight(1f),
                    onClick = onOpenCompanion,
                )
            }
        }

        homeState.resumeSection?.let { section ->
            item {
                TvContentShelf(
                    title = section.homeSection.name.asString(),
                    items = section.homeSection.items,
                    showProgress = true,
                    onOpenItem = onOpenItem,
                )
            }
        }
        homeState.nextUpSection?.let { section ->
            item {
                TvContentShelf(
                    title = section.homeSection.name.asString(),
                    items = section.homeSection.items,
                    onOpenItem = onOpenItem,
                )
            }
        }
        homeState.suggestionsSection?.let { section ->
            val items = section.items.filterNot { it.id == featuredPair?.second?.id }
            if (items.isNotEmpty()) {
                item {
                    TvContentShelf(
                        title = "Suggestions",
                        items = items,
                        onOpenItem = onOpenItem,
                    )
                }
            }
        }
        if (homeState.views.isNotEmpty()) {
            item {
                TvLibraryShelf(
                    title = "Library Hub",
                    views = homeState.views.map { it.view },
                    onOpenLibrary = onOpenLibrary,
                )
            }
        }
    }
}

@Composable
private fun TvStatusCard(
    title: String,
    value: String,
    detail: String,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.05f)),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TvSettingsScreen(
    state: MainState,
    appPreferences: AppPreferences,
    serverName: String?,
    onOpenCompanion: () -> Unit,
    onOpenSearch: () -> Unit,
) {
    val companionConfigured = tvCompanionConfigured(appPreferences)

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        contentPadding = PaddingValues(bottom = 38.dp),
    ) {
        item {
            TvPageHeaderCard(
                title = "Settings & connectivity",
                body = "Keep the SpatialFin TV shell distinct: your companion lives here, but so do search, playback readiness, and TV-specific setup state.",
            )
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                TvStatusCard(
                    title = "Server",
                    value = serverName ?: "Not selected",
                    detail = if (state.hasServers) "TV content routes are configured" else "Add a server from companion or manual setup",
                    modifier = Modifier.weight(1f),
                )
                TvStatusCard(
                    title = "Account",
                    value = if (state.hasCurrentUser) "Ready" else "Needs sign in",
                    detail = if (state.hasCurrentUser) "Playback, search, and details are active" else "Choose a user to personalize shelves",
                    modifier = Modifier.weight(1f),
                )
                TvStatusCard(
                    title = "Companion",
                    value = if (companionConfigured) "Connected" else "Not paired",
                    detail = if (companionConfigured) "Companion sync is available in the background" else "Use QR or code pairing from your phone",
                    modifier = Modifier.weight(1f),
                )
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                TvActionTile(
                    title = if (companionConfigured) "Reconnect companion" else "Pair companion",
                    body = "Show a TV QR or manual code so your phone can push servers, users, preferences, and sync state.",
                    icon = Icons.Rounded.Link,
                    modifier = Modifier.weight(1f),
                    onClick = onOpenCompanion,
                )
                TvActionTile(
                    title = "Search from TV",
                    body = "Jump into title and people search without returning to the home hero.",
                    icon = Icons.AutoMirrored.Rounded.ManageSearch,
                    modifier = Modifier.weight(1f),
                    onClick = onOpenSearch,
                )
            }
        }
    }
}

private fun tvCompanionConfigured(appPreferences: AppPreferences): Boolean =
    appPreferences.getValue(appPreferences.companionUrl).isNotBlank() &&
        appPreferences.getValue(appPreferences.companionToken).isNotBlank()

@Composable
private fun TvPageHeaderCard(
    title: String,
    body: String,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.04f)),
        shape = RoundedCornerShape(30.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TvActionTile(
    title: String,
    body: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    var isFocused by remember(title) { mutableStateOf(false) }
    val scale by animateFloatAsState(targetValue = if (isFocused) 1.03f else 1f, label = "actionTileScale")

    Card(
        modifier =
            modifier
                .height(152.dp)
                .onFocusChanged { isFocused = it.isFocused }
                .focusable()
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .border(
                    width = if (isFocused) 2.dp else 0.dp,
                    color = if (isFocused) MaterialTheme.colorScheme.primary else Color.Transparent,
                    shape = RoundedCornerShape(26.dp),
                ),
        onClick = onClick,
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (isFocused) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.88f)
                    } else {
                        Color.White.copy(alpha = 0.05f)
                    },
            ),
        shape = RoundedCornerShape(26.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isFocused) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color =
                    if (isFocused) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color =
                    if (isFocused) {
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.86f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TvHomeHeroCard(
    item: SpatialFinItem,
    eyebrow: String,
    companionConfigured: Boolean,
    onPrimaryAction: () -> Unit,
    onDetails: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenCompanion: () -> Unit,
) {
    val onFocusChange = LocalFocusedBackground.current
    LaunchedEffect(item.id) {
        onFocusChange(tvBackdropArtwork(item))
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0x77131A24)),
        shape = RoundedCornerShape(32.dp),
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().height(368.dp),
        ) {
            AsyncImage(
                model = tvBackdropArtwork(item),
                contentDescription = item.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                alpha = 0.72f,
            )
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(
                            androidx.compose.ui.graphics.Brush.horizontalGradient(
                                colors = listOf(Color(0xF506111B), Color(0xD006111B), Color.Transparent),
                                startX = 0f,
                                endX = 1250f,
                            ),
                        ),
            )
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(
                            androidx.compose.ui.graphics.Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color(0xD106111B)),
                            ),
                        ),
            )
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth(0.66f)
                        .align(Alignment.BottomStart)
                        .padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = eyebrow,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.secondary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = item.overview.ifBlank { "Open this title to browse details and playback options." },
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color(0xFFD6E2EE),
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    tvFeaturedMetadata(item, companionConfigured).forEach { token ->
                        TvMetadataPill(text = token)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(
                        onClick = onPrimaryAction,
                        colors =
                            ButtonDefaults.textButtonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            ),
                    ) {
                        Text(if (item.playbackPositionTicks > 0L) "Resume" else if (item.canPlay) "Play" else "Open")
                    }
                    TextButton(
                        onClick = onDetails,
                        colors =
                            ButtonDefaults.textButtonColors(
                                containerColor = Color.White.copy(alpha = 0.12f),
                                contentColor = Color.White,
                            ),
                    ) {
                        Text("Details")
                    }
                    TextButton(
                        onClick = onOpenSearch,
                        colors =
                            ButtonDefaults.textButtonColors(
                                containerColor = Color.White.copy(alpha = 0.10f),
                                contentColor = Color.White,
                            ),
                    ) {
                        Text("Search")
                    }
                    TextButton(
                        onClick = onOpenCompanion,
                        colors =
                            ButtonDefaults.textButtonColors(
                                containerColor = Color.White.copy(alpha = 0.10f),
                                contentColor = Color.White,
                            ),
                    ) {
                        Text(if (companionConfigured) "Companion" else "Pair companion")
                    }
                }
            }
        }
    }
}

@Composable
private fun TvPlaceholderShelf(
    title: String,
    items: List<String>,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items.forEach { item ->
                TvShelfCard(title = item)
            }
        }
    }
}

@Composable
private fun TvShelfCard(title: String) {
    var isFocused by rememberSaveable(title) { mutableStateOf(false) }
    val scale by animateFloatAsState(targetValue = if (isFocused) 1.05f else 1f, label = "scale")
    Card(
        modifier =
            Modifier
                .width(240.dp)
                .height(150.dp)
                .onFocusChanged { isFocused = it.isFocused }
                .focusable()
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .border(
                    width = if (isFocused) 2.dp else 0.dp,
                    color = if (isFocused) MaterialTheme.colorScheme.primary else Color.Transparent,
                    shape = RoundedCornerShape(24.dp),
                ),
        colors = CardDefaults.cardColors(containerColor = Color(0x77131A24)),
        shape = RoundedCornerShape(24.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(
                        if (isFocused) {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                        } else {
                            Color.Transparent
                        }
                    )
                    .padding(18.dp),
            contentAlignment = Alignment.BottomStart,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun TvContentShelf(
    title: String,
    items: List<SpatialFinItem>,
    showProgress: Boolean = false,
    onOpenItem: (SpatialFinItem) -> Unit,
) {
    if (items.isEmpty()) return

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(items.take(12), key = { it.id }) { item ->
                TvMediaCard(
                    item = item,
                    showProgress = showProgress,
                    modifier = Modifier.width(214.dp),
                    onClick = { onOpenItem(item) },
                )
            }
        }
    }
}

@Composable
private fun TvLibraryShelf(
    title: String,
    views: List<View>,
    onOpenLibrary: (View) -> Unit,
) {
    if (views.isEmpty()) return

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            items(views, key = { it.id }) { view ->
                TvLibraryCard(
                    view = view,
                    onClick = { onOpenLibrary(view) },
                )
            }
        }
    }
}

@Composable
private fun TvMediaCard(
    item: SpatialFinItem,
    showProgress: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    var isFocused by remember(item.id) { mutableStateOf(false) }
    val scale by animateFloatAsState(targetValue = if (isFocused) 1.04f else 1f, label = "scale")
    val onFocusChange = LocalFocusedBackground.current
    
    LaunchedEffect(isFocused) {
        if (isFocused) {
            onFocusChange(item.images.backdrop ?: item.images.primary)
        }
    }

    Card(
        modifier =
            modifier
                .height(338.dp)
                .onFocusChanged { isFocused = it.isFocused }
                .focusable()
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .border(
                    width = if (isFocused) 2.dp else 0.dp,
                    color = if (isFocused) MaterialTheme.colorScheme.primary else Color.Transparent,
                    shape = RoundedCornerShape(22.dp),
                ),
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = Color(0x77131A24)),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxWidth().height(250.dp)) {
                val imageModel = item.images.primary ?: item.images.showPrimary ?: item.images.backdrop
                if (imageModel != null) {
                    AsyncImage(
                        model = imageModel,
                        contentDescription = item.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = item.name.take(1).uppercase(),
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(
                                androidx.compose.ui.graphics.Brush.verticalGradient(
                                    colors = listOf(Color.Transparent, Color(0xAA08121B)),
                                ),
                            ),
                )
                TvMetadataPill(
                    text = tvItemLabel(item),
                )
                if (showProgress) {
                    buildPlaybackFraction(item)?.let { progress ->
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .align(Alignment.BottomCenter),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = Color.Black.copy(alpha = 0.5f),
                        )
                    }
                }
            }
            Column(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = tvCompactMetadata(item),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun TvLibraryCard(
    view: View,
    onClick: () -> Unit,
) {
    var isFocused by remember(view.id) { mutableStateOf(false) }
    val scale by animateFloatAsState(targetValue = if (isFocused) 1.03f else 1f, label = "scale")
    val onFocusChange = LocalFocusedBackground.current

    LaunchedEffect(isFocused) {
        if (isFocused) {
            onFocusChange(tvViewArtwork(view))
        }
    }

    Card(
        modifier =
            Modifier
                .width(320.dp)
                .height(188.dp)
                .onFocusChanged { isFocused = it.isFocused }
                .focusable()
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .border(
                    width = if (isFocused) 2.dp else 0.dp,
                    color = if (isFocused) MaterialTheme.colorScheme.primary else Color.Transparent,
                    shape = RoundedCornerShape(24.dp),
                ),
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = Color(0x77131A24)),
        shape = RoundedCornerShape(24.dp),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.BottomStart,
        ) {
            AsyncImage(
                model = tvViewArtwork(view),
                contentDescription = view.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                alpha = 0.72f,
            )
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(
                            androidx.compose.ui.graphics.Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color(0xDD08121B)),
                            ),
                        ),
            )
            Column(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = view.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                )
                Text(
                    text = "${view.items.size} titles ready on TV",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFD8E3EF),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TvLibraryScreen(
    view: View?,
    availableViews: List<View>,
    onBackToHome: () -> Unit,
    onSelectView: (View) -> Unit,
    onOpenItem: (SpatialFinItem) -> Unit,
) {
    if (view == null && availableViews.isEmpty()) {
        TvPlaceholderScreen(
            title = "Library unavailable",
            body = "No libraries are available yet. Add a server or finish companion setup first.",
        )
        return
    }

    val rows = remember(view?.items) { view?.items?.chunked(5).orEmpty() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(22.dp),
        contentPadding = PaddingValues(bottom = 36.dp),
    ) {
        if (view == null) {
            item {
                TvPageHeaderCard(
                    title = "Library hub",
                    body = "Pick a library rail and jump directly into the latest media available on this TV profile.",
                )
            }
            item {
                TvLibraryShelf(
                    title = "Available libraries",
                    views = availableViews,
                    onOpenLibrary = onSelectView,
                )
            }
            return@LazyColumn
        }

        item {
            TvPageHeaderCard(
                title = view.name,
                body = "${view.items.size} items ready for TV browsing. Use the rail to change sections or keep moving down for the full grid.",
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = onBackToHome) {
                    Text("Back home")
                }
                if (availableViews.size > 1) {
                    TextButton(onClick = { onSelectView(availableViews.first()) }) {
                        Text("Jump to first library")
                    }
                }
            }
        }
        if (rows.isEmpty()) {
            item {
                TvPlaceholderScreen(
                    title = "No titles",
                    body = "This library is empty or no items are visible to the current TV user.",
                )
            }
        } else {
            items(rows) { rowItems ->
                TvPosterGridRow(
                    items = rowItems,
                    onOpenItem = onOpenItem,
                )
            }
        }
    }
}

@Composable
private fun TvSearchScreen(
    onOpenItem: (SpatialFinItem) -> Unit,
    viewModel: TvSearchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val rows = remember(state.items) { state.items.chunked(5) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(22.dp),
        contentPadding = PaddingValues(bottom = 36.dp),
    ) {
        item {
            TvPageHeaderCard(
                title = "Search the full library",
                body = "Wholphin-style direct access, but tuned for SpatialFin: type once, browse a full TV grid, and jump into details or playback.",
            )
        }
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.04f)),
                shape = RoundedCornerShape(28.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(22.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = viewModel::setQuery,
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text("Title, show, season, person") },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions =
                            androidx.compose.foundation.text.KeyboardActions(
                                onSearch = { viewModel.search() },
                            ),
                    )
                    TextButton(
                        onClick = { viewModel.search() },
                        colors = ButtonDefaults.textButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    ) {
                        Text("Search")
                    }
                }
            }
        }

        when {
            state.isLoading -> item {
                TvPlaceholderScreen(
                    title = "Searching",
                    body = "Looking through your Jellyfin libraries from the TV shell.",
                )
            }
            state.error != null -> item {
                TvPlaceholderScreen(
                    title = "Search failed",
                    body = state.error?.localizedMessage ?: "Unknown error",
                )
            }
            state.hasSearched && state.items.isEmpty() -> item {
                TvPlaceholderScreen(
                    title = "No results",
                    body = "Try another title, a broader query, or a person name.",
                )
            }
            state.items.isNotEmpty() -> {
                item {
                    Text(
                        text = "Results",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                items(rows) { rowItems ->
                    TvPosterGridRow(
                        items = rowItems,
                        onOpenItem = onOpenItem,
                    )
                }
            }
            else -> item {
                TvPlaceholderScreen(
                    title = "Ready to search",
                    body = "Enter a title above to search movies, series, seasons, episodes, and people from the TV.",
                )
            }
        }
    }
}

@Composable
private fun TvPosterGridRow(
    items: List<SpatialFinItem>,
    onOpenItem: (SpatialFinItem) -> Unit,
    columns: Int = 5,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items.forEach { item ->
            Box(modifier = Modifier.weight(1f)) {
                TvMediaCard(
                    item = item,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onOpenItem(item) },
                )
            }
        }
        repeat(columns - items.size) {
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

private fun tvViewArtwork(view: View): Any? =
    view.items.firstOrNull()?.let { item ->
        item.images.backdrop ?: item.images.primary ?: item.images.showBackdrop ?: item.images.showPrimary
    }

private fun buildPlaybackFraction(item: SpatialFinItem): Float? {
    val runtime = item.runtimeTicks
    val position = item.playbackPositionTicks
    if (runtime <= 0L || position <= 0L) return null
    return (position.toFloat() / runtime.toFloat()).coerceIn(0f, 1f)
}

private fun tvItemLabel(item: SpatialFinItem): String =
    when (item) {
        is SpatialFinMovie -> "Movie"
        is SpatialFinEpisode -> "Episode"
        is SpatialFinSeason -> "Season"
        is SpatialFinShow -> "Series"
        is SpatialFinCollection -> "Library"
        is SpatialFinFolder -> "Folder"
        is SpatialFinBoxSet -> "Box Set"
        else -> item::class.simpleName.orEmpty()
    }

private fun tvCompactMetadata(item: SpatialFinItem): String =
    when (item) {
        is SpatialFinMovie ->
            listOfNotNull(
                item.productionYear?.toString(),
                tvRuntimeLabel(item.runtimeTicks),
                item.communityRating?.let { "${"%.1f".format(it)}/10" },
            )
        is SpatialFinEpisode ->
            listOfNotNull(
                tvEpisodeLabel(item),
                tvRuntimeLabel(item.runtimeTicks),
                item.communityRating?.let { "${"%.1f".format(it)}/10" },
            )
        is SpatialFinSeason ->
            listOfNotNull(
                tvSeasonLabel(item),
                item.unplayedItemCount?.takeIf { it > 0 }?.let { "$it unwatched" },
            )
        is SpatialFinShow ->
            listOfNotNull(
                getShowDateString(item).takeIf { it.isNotBlank() },
                item.communityRating?.let { "${"%.1f".format(it)}/10" },
                item.unplayedItemCount?.takeIf { it > 0 }?.let { "$it left" },
            )
        else -> emptyList()
    }.joinToString(" • ").ifBlank { tvItemLabel(item) }

private fun tvFeaturedMetadata(
    item: SpatialFinItem,
    companionConfigured: Boolean,
): List<String> =
    buildList {
        add(tvItemLabel(item))
        when (item) {
            is SpatialFinMovie -> {
                item.productionYear?.toString()?.let(::add)
                tvRuntimeLabel(item.runtimeTicks)?.let(::add)
                item.communityRating?.let { add("${"%.1f".format(it)}/10") }
            }
            is SpatialFinEpisode -> {
                add(tvEpisodeLabel(item))
                tvRuntimeLabel(item.runtimeTicks)?.let(::add)
                item.communityRating?.let { add("${"%.1f".format(it)}/10") }
            }
            is SpatialFinSeason -> {
                add(tvSeasonLabel(item))
                item.unplayedItemCount?.takeIf { it > 0 }?.let { add("$it unwatched") }
            }
            is SpatialFinShow -> {
                getShowDateString(item).takeIf { it.isNotBlank() }?.let(::add)
                item.communityRating?.let { add("${"%.1f".format(it)}/10") }
            }
            else -> Unit
        }
        if (companionConfigured) {
            add("Companion ready")
        }
    }

private fun tvPrimaryArtwork(item: SpatialFinItem): Any? =
    when (item) {
        is SpatialFinEpisode ->
            item.images.showPrimary ?: item.images.primary ?: item.images.showBackdrop ?: item.images.backdrop
        else ->
            item.images.primary ?: item.images.showPrimary ?: item.images.backdrop ?: item.images.showBackdrop
    }

private fun tvBackdropArtwork(item: SpatialFinItem): Any? =
    when (item) {
        is SpatialFinEpisode ->
            item.images.showBackdrop ?: item.images.backdrop ?: item.images.showPrimary ?: item.images.primary
        else ->
            item.images.backdrop ?: item.images.showBackdrop ?: item.images.primary ?: item.images.showPrimary
    }

private fun tvRuntimeLabel(runtimeTicks: Long): String? =
    runtimeTicks
        .takeIf { it > 0L }
        ?.div(600000000)
        ?.takeIf { it > 0L }
        ?.let { "$it min" }

private fun tvSeasonLabel(season: SpatialFinSeason): String =
    if (season.indexNumber > 0) {
        "Season ${season.indexNumber}"
    } else {
        season.name.ifBlank { "Season" }
    }

private fun tvEpisodeLabel(episode: SpatialFinEpisode): String =
    buildString {
        if (episode.parentIndexNumber > 0) {
            append("S${episode.parentIndexNumber}")
        }
        if (episode.indexNumber > 0) {
            append("E${episode.indexNumber}")
        }
        if (isEmpty()) {
            append("Episode")
        }
    }

@Composable
private fun TvMetadataPill(text: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.28f)),
        shape = RoundedCornerShape(999.dp),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
        )
    }
}

@Composable
private fun TvDetailHeroCard(
    item: SpatialFinItem,
    eyebrow: String,
    supportingLine: String?,
    metadata: List<String>,
    overview: String,
    actions: @Composable RowScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0x77131A24)),
        shape = RoundedCornerShape(30.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(430.dp),
        ) {
            AsyncImage(
                model = tvBackdropArtwork(item),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                alpha = 0.55f,
            )
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(
                            androidx.compose.ui.graphics.Brush.horizontalGradient(
                                colors = listOf(Color(0xF20C1016), Color(0xB00C1016), Color.Transparent),
                                startX = 0f,
                                endX = 1200f,
                            )
                        )
            )
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(
                            androidx.compose.ui.graphics.Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color(0xCC0F141C)),
                            )
                        )
            )
            Row(
                modifier = Modifier.fillMaxSize().padding(28.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Card(
                    modifier = Modifier.width(250.dp).height(372.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xAA111821)),
                    shape = RoundedCornerShape(24.dp),
                ) {
                    AsyncImage(
                        model = tvPrimaryArtwork(item),
                        contentDescription = item.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
                Column(
                    modifier = Modifier.weight(1f).padding(bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = eyebrow,
                        style = MaterialTheme.typography.labelLarge,
                        color = Color(0xFFD7DEE8),
                    )
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    supportingLine?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.titleMedium,
                            color = Color(0xFFE6EBF2),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (metadata.isNotEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            metadata.forEach { token ->
                                TvMetadataPill(text = token)
                            }
                        }
                    }
                    Text(
                        text = overview.ifBlank { "No overview available." },
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color(0xFFD5DCE6),
                        maxLines = 5,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), content = actions)
                }
            }
        }
    }
}

@Composable
private fun TvSeasonStrip(
    seasons: List<SpatialFinSeason>,
    onOpenSeason: (UUID) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Seasons",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            seasons.forEach { season ->
                TvSeasonCard(
                    season = season,
                    onClick = { onOpenSeason(season.id) },
                )
            }
        }
    }
}

@Composable
private fun TvSeasonCard(
    season: SpatialFinSeason,
    onClick: () -> Unit,
) {
    var isFocused by remember(season.id) { mutableStateOf(false) }
    val scale by animateFloatAsState(targetValue = if (isFocused) 1.04f else 1f, label = "seasonScale")
    val onFocusChange = LocalFocusedBackground.current

    LaunchedEffect(isFocused) {
        if (isFocused) {
            onFocusChange(tvBackdropArtwork(season))
        }
    }

    Card(
        modifier =
            Modifier
                .width(250.dp)
                .height(132.dp)
                .onFocusChanged { isFocused = it.isFocused }
                .focusable()
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .border(
                    width = if (isFocused) 2.dp else 0.dp,
                    color = if (isFocused) MaterialTheme.colorScheme.primary else Color.Transparent,
                    shape = RoundedCornerShape(22.dp),
                ),
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = Color(0x77131A24)),
        shape = RoundedCornerShape(22.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Card(
                modifier = Modifier.width(74.dp).fillMaxHeight(),
                colors = CardDefaults.cardColors(containerColor = Color(0xAA111821)),
                shape = RoundedCornerShape(16.dp),
            ) {
                AsyncImage(
                    model = tvPrimaryArtwork(season),
                    contentDescription = season.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = tvSeasonLabel(season),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text =
                        season.unplayedItemCount
                            ?.takeIf { it > 0 }
                            ?.let { "$it unwatched" }
                            ?: season.seriesName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun TvEpisodeHighlightCard(
    episode: SpatialFinEpisode,
    onClick: () -> Unit,
) {
    var isFocused by remember(episode.id) { mutableStateOf(false) }
    val scale by animateFloatAsState(targetValue = if (isFocused) 1.02f else 1f, label = "episodeScale")
    val onFocusChange = LocalFocusedBackground.current

    LaunchedEffect(isFocused) {
        if (isFocused) {
            onFocusChange(tvBackdropArtwork(episode))
        }
    }

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(176.dp)
                .onFocusChanged { isFocused = it.isFocused }
                .focusable()
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .border(
                    width = if (isFocused) 2.dp else 0.dp,
                    color = if (isFocused) MaterialTheme.colorScheme.primary else Color.Transparent,
                    shape = RoundedCornerShape(24.dp),
                ),
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = Color(0x77131A24)),
        shape = RoundedCornerShape(24.dp),
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            AsyncImage(
                model = tvBackdropArtwork(episode),
                contentDescription = episode.name,
                modifier = Modifier.width(280.dp).fillMaxHeight(),
                contentScale = ContentScale.Crop,
            )
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Next Up",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "${tvEpisodeLabel(episode)}  ${episode.name}",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = episode.seasonName ?: episode.seriesName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = episode.overview.ifBlank { "Open this episode for more details." },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun TvHeroCard(
    item: SpatialFinItem,
    onClick: () -> Unit,
) {
    var isFocused by remember(item.id) { mutableStateOf(false) }
    val scale by animateFloatAsState(targetValue = if (isFocused) 1.02f else 1f, label = "heroScale")
    val onFocusChange = LocalFocusedBackground.current

    LaunchedEffect(isFocused) {
        if (isFocused) {
            onFocusChange(item.images.backdrop ?: item.images.primary)
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(280.dp)
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .border(
                width = if (isFocused) 2.dp else 0.dp,
                color = if (isFocused) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = RoundedCornerShape(30.dp),
            ),
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = Color(0x77131A24)),
        shape = RoundedCornerShape(30.dp),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(
                model = item.images.backdrop ?: item.images.primary ?: item.images.showPrimary,
                contentDescription = item.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                alpha = 0.6f,
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        androidx.compose.ui.graphics.Brush.horizontalGradient(
                            colors = listOf(Color(0xDD0F141C), Color.Transparent),
                            startX = 0f,
                            endX = 1000f,
                        )
                    )
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(32.dp)
                    .fillMaxWidth(0.6f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = item.overview.ifBlank { tvItemLabel(item) },
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.LightGray,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun TvPlaceholderScreen(
    title: String,
    body: String,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0x77131A24)),
        shape = RoundedCornerShape(30.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TvItemDetailScreen(
    itemId: UUID?,
    onBack: () -> Unit,
    viewModel: TvItemDetailViewModel = hiltViewModel(),
) {
    if (itemId == null) {
        TvPlaceholderScreen(
            title = "Item unavailable",
            body = "No item was selected.",
        )
        return
    }

    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(itemId) {
        viewModel.load(itemId)
    }

    when {
        state.isLoading -> TvPlaceholderScreen(
            title = "Loading details",
            body = "Fetching item metadata and playback options.",
        )
        state.error != null -> TvPlaceholderScreen(
            title = "Couldn't load item",
            body = state.error?.localizedMessage ?: "Unknown error",
        )
        state.item == null -> TvPlaceholderScreen(
            title = "Missing item",
            body = "This item is no longer available.",
        )
        else -> {
            val item = state.item ?: return
            val versions = state.availableVersions
            val onFocusBackground = LocalFocusedBackground.current

            LaunchedEffect(item.id) {
                onFocusBackground(tvBackdropArtwork(item))
            }

            val supportingLine =
                when (item) {
                    is SpatialFinMovie ->
                        item.originalTitle?.takeIf { !it.isNullOrBlank() && it != item.name }
                            ?: item.genres.take(3).takeIf { it.isNotEmpty() }?.joinToString(" • ")
                    is SpatialFinEpisode ->
                        listOf(item.seriesName, item.seasonName, tvEpisodeLabel(item))
                            .filterNotNull()
                            .filter { it.isNotBlank() }
                            .joinToString(" • ")
                            .ifBlank { null }
                    is SpatialFinSeason -> item.seriesName
                    else -> item.originalTitle?.takeIf { !it.isNullOrBlank() && it != item.name }
                }
            val metadata =
                buildList {
                    when (item) {
                        is SpatialFinMovie -> {
                            item.productionYear?.let { add(it.toString()) }
                            tvRuntimeLabel(item.runtimeTicks)?.let(::add)
                            item.officialRating?.takeIf { it.isNotBlank() }?.let(::add)
                            item.communityRating?.let { add("${"%.1f".format(it)}/10") }
                            addAll(item.genres.take(2))
                        }
                        is SpatialFinEpisode -> {
                            add(tvEpisodeLabel(item))
                            item.premiereDate?.year?.let { add(it.toString()) }
                            tvRuntimeLabel(item.runtimeTicks)?.let(::add)
                            item.communityRating?.let { add("${"%.1f".format(it)}/10") }
                        }
                        is SpatialFinSeason -> {
                            add(tvSeasonLabel(item))
                            item.unplayedItemCount?.takeIf { it > 0 }?.let { add("$it unwatched") }
                        }
                            else -> Unit
                    }
                }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                contentPadding = PaddingValues(bottom = 36.dp),
            ) {
                item {
                    TvDetailHeroCard(
                        item = item,
                        eyebrow = tvItemLabel(item),
                        supportingLine = supportingLine,
                        metadata = metadata,
                        overview = item.overview,
                        actions = {
                            if (item is SpatialFinMovie || item is SpatialFinEpisode) {
                                TextButton(
                                    onClick = {
                                        TvPlayerActivity.createIntentForSpatialItem(context, item)?.let(context::startActivity)
                                    },
                                    colors =
                                        ButtonDefaults.textButtonColors(
                                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                        ),
                                ) {
                                    Text(if (item.playbackPositionTicks > 0L) "Resume" else "Play")
                                }
                                if (item.playbackPositionTicks > 0L) {
                                    TextButton(
                                        onClick = {
                                            TvPlayerActivity.createIntentForSpatialItem(
                                                context = context,
                                                item = item,
                                                startFromBeginning = true,
                                            )?.let(context::startActivity)
                                        },
                                        colors =
                                            ButtonDefaults.textButtonColors(
                                                containerColor = Color.White.copy(alpha = 0.12f),
                                                contentColor = Color.White,
                                            ),
                                    ) {
                                        Text("Restart")
                                    }
                                }
                            }
                            TextButton(
                                onClick = onBack,
                                colors =
                                    ButtonDefaults.textButtonColors(
                                        containerColor = Color.White.copy(alpha = 0.12f),
                                        contentColor = Color.White,
                                    ),
                            ) {
                                Text("Back")
                            }
                        },
                    )
                }
                if (versions.size > 1) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                text = "Versions",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                versions.forEach { version ->
                                    val selected = version.id == item.id
                                    TextButton(
                                        onClick = { if (!selected) viewModel.load(version.id) },
                                        colors =
                                            ButtonDefaults.textButtonColors(
                                                containerColor =
                                                    if (selected) {
                                                        MaterialTheme.colorScheme.primaryContainer
                                                    } else {
                                                        Color(0x55131A24)
                                                    },
                                                contentColor =
                                                    if (selected) {
                                                        MaterialTheme.colorScheme.onPrimaryContainer
                                                    } else {
                                                        MaterialTheme.colorScheme.onSurface
                                                    },
                                            ),
                                    ) {
                                        Text(version.versionChipLabel())
                                    }
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
private fun TvShowScreen(
    showId: UUID?,
    onBack: () -> Unit,
    onOpenSeason: (UUID) -> Unit,
    onOpenEpisode: (UUID) -> Unit,
    viewModel: TvShowViewModel = hiltViewModel(),
) {
    if (showId == null) {
        TvPlaceholderScreen(
            title = "Series unavailable",
            body = "No series was selected.",
        )
        return
    }

    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(showId) {
        viewModel.load(showId)
    }

    when {
        state.isLoading -> TvPlaceholderScreen(
            title = "Loading series",
            body = "Fetching seasons and next episode.",
        )
        state.error != null -> TvPlaceholderScreen(
            title = "Couldn't load series",
            body = state.error?.localizedMessage ?: "Unknown error",
        )
        state.show == null -> TvPlaceholderScreen(
            title = "Series unavailable",
            body = "This series is no longer available.",
        )
        else -> {
            val show = state.show ?: return
            val onFocusBackground = LocalFocusedBackground.current

            LaunchedEffect(show.id) {
                onFocusBackground(tvBackdropArtwork(show))
            }

            val supportingLine =
                show.originalTitle?.takeIf { !it.isNullOrBlank() && it != show.name }
                    ?: show.genres.take(3).takeIf { it.isNotEmpty() }?.joinToString(" • ")
            val metadata =
                buildList {
                    getShowDateString(show).takeIf { it.isNotBlank() }?.let(::add)
                    if (state.seasons.isNotEmpty()) {
                        add("${state.seasons.size} seasons")
                    }
                    show.officialRating?.takeIf { it.isNotBlank() }?.let(::add)
                    show.communityRating?.let { add("${"%.1f".format(it)}/10") }
                    show.unplayedItemCount?.takeIf { it > 0 }?.let { add("$it unwatched") }
                }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                contentPadding = PaddingValues(bottom = 36.dp),
            ) {
                item {
                    TvDetailHeroCard(
                        item = show,
                        eyebrow = "Series",
                        supportingLine = supportingLine,
                        metadata = metadata,
                        overview = show.overview,
                        actions = {
                            state.nextUp?.let { nextEpisode ->
                                TextButton(
                                    onClick = {
                                        TvPlayerActivity.createIntentForSpatialItem(context, nextEpisode)?.let(context::startActivity)
                                    },
                                    colors =
                                        ButtonDefaults.textButtonColors(
                                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                        ),
                                ) {
                                    Text(if (nextEpisode.playbackPositionTicks > 0L) "Resume Episode" else "Play Next")
                                }
                            }
                            TextButton(
                                onClick = onBack,
                                colors =
                                    ButtonDefaults.textButtonColors(
                                        containerColor = Color.White.copy(alpha = 0.12f),
                                        contentColor = Color.White,
                                    ),
                            ) {
                                Text("Back")
                            }
                        },
                    )
                }
                state.nextUp?.let { episode ->
                    item {
                        TvEpisodeHighlightCard(
                            episode = episode,
                            onClick = { onOpenEpisode(episode.id) },
                        )
                    }
                }
                if (state.seasons.isNotEmpty()) {
                    item {
                        TvSeasonStrip(
                            seasons = state.seasons,
                            onOpenSeason = onOpenSeason,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TvSeasonScreen(
    seasonId: UUID?,
    onBack: () -> Unit,
    onOpenEpisode: (UUID) -> Unit,
    viewModel: TvSeasonViewModel = hiltViewModel(),
) {
    if (seasonId == null) {
        TvPlaceholderScreen(
            title = "Season unavailable",
            body = "No season was selected.",
        )
        return
    }

    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(seasonId) {
        viewModel.load(seasonId)
    }

    when {
        state.isLoading -> TvPlaceholderScreen(
            title = "Loading season",
            body = "Fetching episodes for this season.",
        )
        state.error != null -> TvPlaceholderScreen(
            title = "Couldn't load season",
            body = state.error?.localizedMessage ?: "Unknown error",
        )
        state.season == null -> TvPlaceholderScreen(
            title = "Season unavailable",
            body = "This season is no longer available.",
        )
        else -> {
            val season = state.season ?: return
            val rows = remember(state.episodes) { state.episodes.chunked(5) }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                contentPadding = PaddingValues(bottom = 36.dp),
            ) {
                item {
                    TvPageHeaderCard(
                        title = season.name,
                        body = listOf(season.seriesName, "${state.episodes.size} visible episodes").joinToString(" • "),
                    )
                }
                item {
                    TextButton(onClick = onBack) {
                        Text("Back")
                    }
                }
                if (rows.isEmpty()) {
                    item {
                        TvPlaceholderScreen(
                            title = "No episodes",
                            body = "This season does not have visible episodes.",
                        )
                    }
                } else {
                    items(rows) { rowItems ->
                        TvPosterGridRow(
                            items = rowItems,
                            onOpenItem = { episode ->
                                if (episode is SpatialFinEpisode) {
                                    onOpenEpisode(episode.id)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}
