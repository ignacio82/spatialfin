package dev.spatialfin.tv

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
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
        TvNavItem(TvRoute.Library, "Library", Icons.Rounded.LiveTv),
        TvNavItem(TvRoute.Settings, "Settings", Icons.Rounded.Settings),
    )

val LocalFocusedBackground = compositionLocalOf<(Any?) -> Unit> { {} }

@Composable
fun TvNavigationRoot(
    state: MainState,
    appPreferences: AppPreferences,
) {
    var currentRoute by rememberSaveable { mutableStateOf(TvRoute.Home) }
    var selectedView by remember { mutableStateOf<View?>(null) }
    var selectedItemId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedShowId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedSeasonId by rememberSaveable { mutableStateOf<String?>(null) }
    var focusedBackgroundUrl by remember { mutableStateOf<Any?>(null) }

    CompositionLocalProvider(LocalFocusedBackground provides { focusedBackgroundUrl = it }) {
        Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0F141C))) {
            AsyncImage(
                model = focusedBackgroundUrl,
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
                            .padding(horizontal = 28.dp, vertical = 22.dp),
                ) {
                    TvSidebar(
                        currentRoute = currentRoute,
                        onNavigate = { currentRoute = it },
                    )
                    Spacer(Modifier.width(24.dp))
                    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                when (currentRoute) {
                    TvRoute.Home -> TvHomeScreen(
                        state = state,
                        appPreferences = appPreferences,
                        onOpenLibrary = { view ->
                            selectedView = view
                            currentRoute = TvRoute.Library
                        },
                        onOpenItem = { item ->
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
                        },
                    )
                    TvRoute.Search -> TvSearchScreen(
                        onOpenItem = { item ->
                            when (item) {
                                is SpatialFinShow -> {
                                    selectedShowId = item.id.toString()
                                    currentRoute = TvRoute.Show
                                }
                                is SpatialFinSeason -> {
                                    selectedSeasonId = item.id.toString()
                                    currentRoute = TvRoute.Season
                                }
                                else -> {
                                    selectedItemId = item.id.toString()
                                    currentRoute = TvRoute.Detail
                                }
                            }
                        },
                    )
                    TvRoute.Library -> TvLibraryScreen(
                        view = selectedView,
                        onBackToHome = { currentRoute = TvRoute.Home },
                        onOpenItem = { item ->
                            when (item) {
                                is SpatialFinShow -> {
                                    selectedShowId = item.id.toString()
                                    currentRoute = TvRoute.Show
                                }
                                is SpatialFinSeason -> {
                                    selectedSeasonId = item.id.toString()
                                    currentRoute = TvRoute.Season
                                }
                                else -> {
                                    selectedItemId = item.id.toString()
                                    currentRoute = TvRoute.Detail
                                }
                            }
                        },
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
                        onOpenCompanion = { currentRoute = TvRoute.Companion },
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

@Composable
private fun TvSidebar(
    currentRoute: TvRoute,
    onNavigate: (TvRoute) -> Unit,
) {
    var isExpanded by rememberSaveable { mutableStateOf(false) }
    val sidebarWidth by animateDpAsState(targetValue = if (isExpanded) 260.dp else 90.dp, label = "sidebarWidth")

    Card(
        modifier = Modifier.width(sidebarWidth).fillMaxHeight(),
        colors = CardDefaults.cardColors(containerColor = Color(0x77131A24)),
        shape = RoundedCornerShape(28.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = if (isExpanded) Alignment.Start else Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (isExpanded) Arrangement.Start else Arrangement.Center,
            ) {
                androidx.compose.foundation.Image(
                    painter = painterResource(id = R.drawable.ic_launcher_foreground),
                    contentDescription = "App Logo",
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { isExpanded = !isExpanded }
                )
                if (isExpanded) {
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Fin Player",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "Google TV Streamer",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            tvNavItems.forEach { item ->
                TvSidebarButton(
                    item = item,
                    selected = currentRoute == item.route,
                    isExpanded = isExpanded,
                    onClick = { onNavigate(item.route) },
                )
            }
        }
    }
}

@Composable
private fun TvSidebarButton(
    item: TvNavItem,
    selected: Boolean,
    isExpanded: Boolean,
    onClick: () -> Unit,
) {
    var isFocused by rememberSaveable(item.route) { mutableStateOf(false) }
    val scale by animateFloatAsState(targetValue = if (isFocused) 1.05f else 1f, label = "scale")
    val highlight =
        when {
            selected -> MaterialTheme.colorScheme.primaryContainer
            isFocused -> MaterialTheme.colorScheme.surfaceVariant
            else -> Color.Transparent
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
        contentPadding = if (isExpanded) PaddingValues(horizontal = 16.dp, vertical = 16.dp) else PaddingValues(horizontal = 0.dp, vertical = 16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (isExpanded) Arrangement.Start else Arrangement.Center,
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
            )
            if (isExpanded) {
                Spacer(Modifier.width(14.dp))
                Text(
                    text = item.label,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}

@Composable
private fun TvHomeScreen(
    state: MainState,
    appPreferences: AppPreferences,
    onOpenLibrary: (View) -> Unit,
    onOpenItem: (SpatialFinItem) -> Unit,
) {
    val viewModel: HomeViewModel = hiltViewModel()
    val homeState by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.loadData()
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0x77131A24)),
            shape = RoundedCornerShape(30.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = homeState.server?.name ?: "Google TV Streamer baseline",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text =
                        if (state.hasCurrentUser) {
                            "TV home is now reading live Jellyfin content, with TV-native playback and companion pairing support."
                        } else {
                            "TV home is wired up. Sign in to a server to populate live shelves."
                        },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            TvStatusCard(
                title = "Servers",
                value = if (state.hasServers) "Configured" else "Not configured",
                modifier = Modifier.weight(1f),
            )
            TvStatusCard(
                title = "Session",
                value = if (state.hasCurrentUser) "Signed in" else "Needs user",
                modifier = Modifier.weight(1f),
            )
            TvStatusCard(
                title = "Onboarding",
                value = if (appPreferences.getValue(appPreferences.onboardingCompleted)) "Complete" else "Pending",
                modifier = Modifier.weight(1f),
            )
        }

        if (homeState.isLoading) {
            TvPlaceholderScreen(
                title = "Loading library",
                body = "Fetching Continue Watching, Next Up, and library rows from Jellyfin.",
            )
        } else if (homeState.error != null) {
            TvPlaceholderScreen(
                title = "Home unavailable",
                body = homeState.error?.message ?: "Failed to load TV home content.",
            )
        } else {
            val heroItem = homeState.resumeSection?.homeSection?.items?.firstOrNull()
                ?: homeState.suggestionsSection?.items?.firstOrNull()

            if (heroItem != null) {
                TvHeroCard(
                    item = heroItem,
                    onClick = { onOpenItem(heroItem) }
                )
            }

            homeState.resumeSection?.let { section ->
                TvContentShelf(
                    title = section.homeSection.name.asString(),
                    items = section.homeSection.items,
                    onOpenItem = onOpenItem,
                )
            }
            homeState.nextUpSection?.let { section ->
                TvContentShelf(
                    title = section.homeSection.name.asString(),
                    items = section.homeSection.items,
                    onOpenItem = onOpenItem,
                )
            }
            homeState.suggestionsSection?.let { section ->
                TvContentShelf(
                    title = "Suggestions",
                    items = section.items,
                    onOpenItem = onOpenItem,
                )
            }
            if (homeState.views.isNotEmpty()) {
                TvLibraryShelf(
                    title = "Libraries",
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
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color(0x77131A24)),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
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
        }
    }
}

@Composable
private fun TvSettingsScreen(
    onOpenCompanion: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        TvPlaceholderScreen(
            title = "Settings",
            body = "Configure Fin Player on Google TV, including setup from the companion app on your phone.",
        )
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0x77131A24)),
            shape = RoundedCornerShape(30.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Link,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp),
                    )
                    Text(
                        text = "Companion Pairing",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    text = "Show a QR code on TV so the companion app on your phone can pair and push servers, users, and preferences to this device.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = onOpenCompanion,
                    colors = ButtonDefaults.textButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                ) {
                    Text("Pair With Companion")
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
    onOpenItem: (SpatialFinItem) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items.take(5).forEach { item ->
                TvMediaCard(
                    item = item,
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
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            views.take(6).forEach { view ->
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
    onClick: () -> Unit,
) {
    var isFocused by remember(item.id) { mutableStateOf(false) }
    val scale by animateFloatAsState(targetValue = if (isFocused) 1.05f else 1f, label = "scale")
    val onFocusChange = LocalFocusedBackground.current
    
    LaunchedEffect(isFocused) {
        if (isFocused) {
            onFocusChange(item.images.backdrop ?: item.images.primary)
        }
    }

    Card(
        modifier =
            Modifier
                .width(220.dp)
                .height(332.dp)
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
            AsyncImage(
                model = item.images.primary ?: item.images.showPrimary ?: item.images.backdrop,
                contentDescription = item.name,
                modifier = Modifier.fillMaxWidth().height(240.dp),
                contentScale = ContentScale.Crop,
            )
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
                    text = tvItemLabel(item),
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
        onClick = onClick,
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
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = view.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "${view.items.size} items",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TvLibraryScreen(
    view: View?,
    onBackToHome: () -> Unit,
    onOpenItem: (SpatialFinItem) -> Unit,
) {
    if (view == null) {
        TvPlaceholderScreen(
            title = "Library unavailable",
            body = "Select a library from Home first.",
        )
        return
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = view.name,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "${view.items.size} items",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onBackToHome) {
                Text("Back to Home")
            }
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            view.items.take(18).forEach { item ->
                TvMediaCard(
                    item = item,
                    onClick = { onOpenItem(item) },
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

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Search",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Search your Jellyfin library directly from the TV shell.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                modifier = Modifier.width(520.dp),
                singleLine = true,
                label = { Text("Title, show, season, person") },
            )
            TextButton(
                onClick = { viewModel.search() },
                colors = ButtonDefaults.textButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                Text("Search")
            }
        }

        when {
            state.isLoading -> TvPlaceholderScreen(
                title = "Searching",
                body = "Looking through your Jellyfin libraries.",
            )
            state.error != null -> TvPlaceholderScreen(
                title = "Search failed",
                body = state.error?.localizedMessage ?: "Unknown error",
            )
            state.hasSearched && state.items.isEmpty() -> TvPlaceholderScreen(
                title = "No results",
                body = "Try another title or broader search term.",
            )
            state.items.isNotEmpty() -> TvContentShelf(
                title = "Results",
                items = state.items.take(12),
                onOpenItem = onOpenItem,
            )
            else -> TvPlaceholderScreen(
                title = "Ready to search",
                body = "Enter a title above to search movies, shows, seasons, episodes, and more.",
            )
        }
    }
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
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
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
                if (versions.size > 1) {
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
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
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
                if (state.seasons.isNotEmpty()) {
                    TvSeasonStrip(
                        seasons = state.seasons,
                        onOpenSeason = onOpenSeason,
                    )
                }
                state.nextUp?.let { episode ->
                    TvEpisodeHighlightCard(
                        episode = episode,
                        onClick = { onOpenEpisode(episode.id) },
                    )
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
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = season.name,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = season.seriesName,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = onBack) {
                        Text("Back")
                    }
                }
                if (state.episodes.isEmpty()) {
                    TvPlaceholderScreen(
                        title = "No episodes",
                        body = "This season does not have visible episodes.",
                    )
                } else {
                    TvContentShelf(
                        title = "Episodes",
                        items = state.episodes,
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
