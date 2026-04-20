package dev.spatialfin.tv

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ManageSearch
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Home
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Link
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.WifiOff
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Carousel
import androidx.tv.material3.CarouselDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.NavigationDrawer
import androidx.tv.material3.NavigationDrawerItem
import androidx.tv.material3.Text
import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.jdtech.jellyfin.core.presentation.components.FloatingProgressBar
import dev.jdtech.jellyfin.core.presentation.components.MetadataPill
import dev.jdtech.jellyfin.core.presentation.downloader.BulkDownloadState
import dev.jdtech.jellyfin.film.presentation.downloads.DownloadsViewModel
import dev.jdtech.jellyfin.film.presentation.downloads.DownloadSortOrder
import dev.jdtech.jellyfin.film.presentation.home.HomeState
import dev.jdtech.jellyfin.film.presentation.home.HomeViewModel
import dev.jdtech.jellyfin.models.BulkDownloadSettings
import dev.jdtech.jellyfin.models.CollectionType
import dev.jdtech.jellyfin.presentation.film.components.RatingsRow
import dev.jdtech.jellyfin.models.DownloadMode
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
import dev.jdtech.jellyfin.models.isDownloaded
import dev.jdtech.jellyfin.models.versionChipLabel
import dev.jdtech.jellyfin.player.tv.TvPlayerActivity
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.utils.ActiveDownloadEntry
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
    Person,
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

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvNavigationRoot(
    state: MainState,
    appPreferences: AppPreferences,
    onReconnect: () -> Unit = {},
) {
    val homeViewModel: HomeViewModel = hiltViewModel()
    val homeState by homeViewModel.state.collectAsStateWithLifecycle()
    var currentRoute by rememberSaveable { mutableStateOf(TvRoute.Home) }
    var selectedView by remember { mutableStateOf<View?>(null) }
    var selectedItemId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedShowId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedSeasonId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedPersonId by rememberSaveable { mutableStateOf<String?>(null) }
    var personBackRoute by rememberSaveable { mutableStateOf(TvRoute.Home) }
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

            NavigationDrawer(
                drawerContent = { drawerValue ->
                    Column(
                        modifier = Modifier
                            .fillMaxHeight()
                            .padding(vertical = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        androidx.compose.foundation.Image(
                            painter = painterResource(id = R.drawable.ic_launcher_foreground),
                            contentDescription = "SpatialFin",
                            modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)),
                        )
                        Spacer(Modifier.height(8.dp))

                        val visibleNavItems = if (state.isOfflineMode) {
                            tvNavItems.filter { it.route != TvRoute.Home && it.route != TvRoute.Search }
                        } else {
                            tvNavItems
                        }

                        visibleNavItems.forEach { item ->
                            NavigationDrawerItem(
                                selected = currentRoute == item.route,
                                onClick = {
                                    if (item.route == TvRoute.Library) selectedView = null
                                    currentRoute = item.route
                                },
                                leadingContent = {
                                    Icon(
                                        imageVector = item.icon,
                                        contentDescription = null,
                                    )
                                },
                            ) {
                                Text(text = item.label)
                            }
                        }
                    }
                }
            ) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(start = 32.dp, end = 48.dp, top = 24.dp, bottom = 12.dp),
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
                                onOpenPerson = { personId ->
                                    personBackRoute = TvRoute.Detail
                                    selectedPersonId = personId.toString()
                                    currentRoute = TvRoute.Person
                                },
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
                                onOpenPerson = { personId ->
                                    personBackRoute = TvRoute.Show
                                    selectedPersonId = personId.toString()
                                    currentRoute = TvRoute.Person
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
                            TvRoute.Person -> {
                                val pid = selectedPersonId?.let(UUID::fromString)
                                if (pid != null) {
                                    dev.jdtech.jellyfin.presentation.film.PersonScreen(
                                        personId = pid,
                                        navigateBack = { currentRoute = personBackRoute },
                                        navigateHome = { currentRoute = TvRoute.Home },
                                        navigateToItem = { item -> openItem(item) },
                                    )
                                } else {
                                    currentRoute = TvRoute.Home
                                }
                            }
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
    }    }

@Composable
private fun TvAmbientBackground(backgroundModel: Any?) {
    val primaryGlow = MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)
    val secondaryGlow = MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f)
    val context = LocalContext.current
    // Debounce the backdrop URL so rapid D-pad focus changes don't kick off a new
    // Coil load per tick — on Chromecast (armv7) that chain of decode + crossfade
    // was causing 700-1000ms main-thread stalls and eating D-pad events.
    var settledModel by remember { mutableStateOf<Any?>(backgroundModel) }
    LaunchedEffect(backgroundModel) {
        kotlinx.coroutines.delay(220)
        settledModel = backgroundModel
    }
    val sizedModel = remember(settledModel) {
        if (settledModel == null) null
        else coil3.request.ImageRequest.Builder(context)
            .data(settledModel)
            .size(960, 540)
            .build()
    }
    Box(modifier = Modifier.fillMaxSize()) {
        AsyncImage(
            model = sizedModel,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            alpha = 0.32f,
        )
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            colors = listOf(Color(0x6606111B), Color(0xE006111B)),
                        )
                    ),
        )
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(
                        androidx.compose.ui.graphics.Brush.radialGradient(
                            colors = listOf(primaryGlow, Color.Transparent),
                            center = androidx.compose.ui.geometry.Offset(350f, 300f),
                            radius = 650f,
                        )
                    ),
        )
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(
                        androidx.compose.ui.graphics.Brush.radialGradient(
                            colors = listOf(secondaryGlow, Color.Transparent),
                            center = androidx.compose.ui.geometry.Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
                            radius = 600f,
                        )
                    ),
        )
    }
}

@Composable
private fun TvSidebar(
    currentRoute: TvRoute,
    serverName: String?,
    companionConfigured: Boolean,
    hasCurrentUser: Boolean,
    isOfflineMode: Boolean = false,
    onNavigate: (TvRoute) -> Unit,
    onReconnect: () -> Unit = {},
) {
    val visibleNavItems = if (isOfflineMode) {
        tvNavItems.filter { it.route != TvRoute.Home && it.route != TvRoute.Search }
    } else {
        tvNavItems
    }
    Card(
        onClick = {},
        modifier = Modifier.width(292.dp).fillMaxHeight(),
        colors = CardDefaults.colors(containerColor = Color(0x5C111B28)),
        shape = CardDefaults.shape(RoundedCornerShape(32.dp)),
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

            visibleNavItems.forEach { item ->
                TvSidebarButton(
                    item = item,
                    selected = currentRoute == item.route,
                    onClick = { onNavigate(item.route) },
                )
            }

            Spacer(Modifier.weight(1f))

            if (isOfflineMode) {
                Card(
                    onClick = {},
                    colors = CardDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
                    ),
                    shape = CardDefaults.shape(RoundedCornerShape(20.dp)),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.WifiOff,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Text(
                                text = "Offline",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                        Text(
                            text = "Server unreachable. Showing downloaded content.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
                        )
                        Button(
                            onClick = onReconnect,
                            modifier = Modifier.fillMaxWidth(),
                            shape = ButtonDefaults.shape(RoundedCornerShape(14.dp)),
                            colors = ButtonDefaults.colors(
                                containerColor = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.15f),
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            ),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Try reconnecting", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }

            Card(
                onClick = {},
                colors = CardDefaults.colors(containerColor = Color.White.copy(alpha = 0.04f)),
                shape = CardDefaults.shape(RoundedCornerShape(24.dp)),
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

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvOnboardingHero(
    hasServers: Boolean,
    companionConfigured: Boolean,
    onOpenCompanion: () -> Unit,
) {
    val slides = listOf(
        Pair("Welcome to SpatialFin", if (hasServers) "Choose a user to start playback." else "Pair your phone companion to push your Jellyfin server."),
        Pair("Cinematic Experience", "Enjoy your media on the big screen with a tailored 10-foot UI."),
        Pair("Companion Setup", "Fastest path to live content. Import everything from your phone.")
    )

    Carousel(
        itemCount = slides.size,
        modifier = Modifier
            .fillMaxWidth()
            .height(420.dp),
    ) { index ->
        val (title, body) = slides[index]
        
        Box(modifier = Modifier.fillMaxSize()) {
            // Background gradient
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        androidx.compose.ui.graphics.Brush.radialGradient(
                            colors = listOf(
                                Color(0x664FC3F7),
                                Color(0x331A2A3A),
                                Color.Transparent,
                            ),
                            radius = 1200f,
                        ),
                    ),
            )
            
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(48.dp),
                verticalArrangement = Arrangement.Bottom,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = body,
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.widthIn(max = 800.dp)
                )
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = onOpenCompanion,
                    shape = ButtonDefaults.shape(RoundedCornerShape(12.dp))
                ) {
                    Icon(
                        imageVector = if (index == 2 || !companionConfigured) Icons.Rounded.Link else Icons.Rounded.Home,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(if (companionConfigured) "Open companion" else "Pair companion")
                }
            }
        }
    }
}

@Composable
private fun TvOnboardingTile(
    title: String,
    body: String,
    icon: ImageVector,
    primary: Boolean,
    modifier: Modifier = Modifier,
    focusRequester: androidx.compose.ui.focus.FocusRequester? = null,
    onClick: () -> Unit,
) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(animationSpec = tween(durationMillis = 120), targetValue = if (isFocused) 1.04f else 1f, label = "tvOnboardingScale")
    val container = if (primary) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    val onContainer = if (primary) MaterialTheme.colorScheme.onPrimaryContainer
                      else MaterialTheme.colorScheme.onSurface
    val focusMod = if (focusRequester != null) {
        Modifier.focusRequester(focusRequester)
    } else {
        Modifier
    }
    Card(
        onClick = onClick,
        modifier = modifier
            .then(focusMod)
            .onFocusChanged { isFocused = it.isFocused }
            .graphicsLayer { scaleX = scale; scaleY = scale },
        shape = CardDefaults.shape(RoundedCornerShape(24.dp)),
        colors = CardDefaults.colors(containerColor = container, contentColor = onContainer),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(40.dp))
            Text(text = title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(
                text = body,
                style = MaterialTheme.typography.bodyLarge,
                color = onContainer.copy(alpha = 0.82f),
            )
        }
    }
}

@Composable
private fun TvTopBar(
    currentRoute: TvRoute,
    serverName: String?,
    hasCurrentUser: Boolean,
    isOfflineMode: Boolean,
    onNavigate: (TvRoute) -> Unit,
    onReconnect: () -> Unit,
) {
    val visibleNavItems = if (isOfflineMode) {
        tvNavItems.filter { it.route != TvRoute.Home && it.route != TvRoute.Search }
    } else {
        tvNavItems
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 36.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        androidx.compose.foundation.Image(
            painter = painterResource(id = R.drawable.ic_launcher_foreground),
            contentDescription = "SpatialFin",
            modifier = Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)),
        )
        Text(
            text = "SpatialFin",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 6.dp, end = 18.dp),
        )
        visibleNavItems.forEach { item ->
            TvTopBarButton(
                item = item,
                selected = currentRoute == item.route,
                onClick = { onNavigate(item.route) },
            )
        }
        Spacer(Modifier.weight(1f))
        if (isOfflineMode) {
            Button(
                onClick = onReconnect,
                shape = ButtonDefaults.shape(RoundedCornerShape(14.dp)),
                colors = ButtonDefaults.colors(
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) {
                Icon(Icons.Rounded.WifiOff, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Offline", style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
            }
        } else {
            Text(
                text = serverName ?: if (hasCurrentUser) "Signed in" else "Sign in",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TvTopBarButton(
    item: TvNavItem,
    selected: Boolean,
    onClick: () -> Unit,
) {
    var isFocused by rememberSaveable(item.route) { mutableStateOf(false) }
    val scale by animateFloatAsState(animationSpec = tween(durationMillis = 120), targetValue = if (isFocused) 1.05f else 1f, label = "tvTopBarScale")
    val highlight =
        when {
            selected -> MaterialTheme.colorScheme.primaryContainer
            isFocused -> Color.White.copy(alpha = 0.10f)
            else -> Color.Transparent
        }
    Button(
        onClick = onClick,
        modifier = Modifier
            .onFocusChanged { isFocused = it.isFocused }
            .graphicsLayer { scaleX = scale; scaleY = scale },
        shape = ButtonDefaults.shape(RoundedCornerShape(14.dp)),
        colors = ButtonDefaults.colors(
            containerColor = highlight,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Icon(
            imageVector = item.icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(text = item.label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun TvSidebarButton(
    item: TvNavItem,
    selected: Boolean,
    onClick: () -> Unit,
) {
    var isFocused by rememberSaveable(item.route) { mutableStateOf(false) }
    val scale by animateFloatAsState(animationSpec = tween(durationMillis = 120), targetValue = if (isFocused) 1.03f else 1f, label = "scale")
    val highlight =
        when {
            selected -> MaterialTheme.colorScheme.primaryContainer
            isFocused -> Color.White.copy(alpha = 0.08f)
            else -> Color.White.copy(alpha = 0.02f)
        }

    Button(
        onClick = onClick,
        modifier =
            Modifier
                .fillMaxWidth()
                .onFocusChanged { isFocused = it.isFocused }
                .graphicsLayer { scaleX = scale; scaleY = scale },
        shape = ButtonDefaults.shape(RoundedCornerShape(18.dp)),
        colors =
            ButtonDefaults.colors(
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

@OptIn(ExperimentalTvMaterial3Api::class)
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
    var heroFocusParked by rememberSaveable { mutableStateOf(false) }

    val showOnboardingHero = !state.hasCurrentUser || !state.hasServers

    // jellyfin-androidtv uses Leanback's VerticalGridView for the home, which has
    // DOWN-always-exits-the-row behaviour baked in. Compose's LazyColumn can't
    // match that: cards in unrendered shelves don't exist as focus targets, so
    // Compose's 2D focus search picks the nearest in-row card and DOWN ends up
    // reinterpreted as RIGHT. Two mitigations make the Compose version behave:
    //   1. Use Column + verticalScroll so every shelf row composes up-front,
    //      giving the focus engine a deterministic first-card target per row.
    //   2. Intercept DPAD_UP / DPAD_DOWN on each shelf's LazyRow via
    //      onPreviewKeyEvent and forward focus to the next/previous shelf's
    //      first-card FocusRequester — bypassing spatial search entirely.

    if (showOnboardingHero) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 42.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            TvOnboardingHero(
                hasServers = state.hasServers,
                companionConfigured = companionConfigured,
                onOpenCompanion = onOpenCompanion,
            )
        }
        return
    }

    val suggestions = homeState.suggestionsSection?.items.orEmpty()
    val nextUp = homeState.nextUpSection?.homeSection?.items.orEmpty()
    val featuredItems = suggestions.take(5).ifEmpty { nextUp.take(5) }
    val featuredEyebrow = if (suggestions.isNotEmpty()) "Featured" else "Next up for you"

    data class ShelfSpec(
        val title: String,
        val items: List<SpatialFinItem>,
        val showProgress: Boolean = false,
    )

    val shelves = buildList {
        homeState.resumeSection?.let {
            add(ShelfSpec(it.homeSection.name.asString(), it.homeSection.items, showProgress = true))
        }
        homeState.nextUpSection?.let {
            add(ShelfSpec(it.homeSection.name.asString(), it.homeSection.items))
        }
        homeState.views
            .map { it.view }
            .firstOrNull { it.type == CollectionType.Movies }
            ?.takeIf { it.items.isNotEmpty() }
            ?.let { add(ShelfSpec("Recently added movies", it.items)) }
        homeState.views
            .map { it.view }
            .firstOrNull { it.type == CollectionType.TvShows }
            ?.takeIf { it.items.isNotEmpty() }
            ?.let { add(ShelfSpec("Recently added TV", it.items)) }
    }

    // Focus chain: one requester per shelf (first card) + one for the library shelf.
    // Built from an ID so requesters are stable across recomposition but re-created
    // when the number of shelves changes (e.g. data loads in).
    val shelfCount = shelves.size + if (homeState.views.isNotEmpty()) 1 else 0
    val shelfRequesters = remember(shelfCount) {
        List(shelfCount) { androidx.compose.ui.focus.FocusRequester() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 42.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        if (featuredItems.isNotEmpty()) {
            val heroHeight = LocalConfiguration.current.screenHeightDp.dp * 0.35f
            Carousel(
                itemCount = featuredItems.size,
                autoScrollDurationMillis = Long.MAX_VALUE,
                modifier = Modifier.fillMaxWidth().height(heroHeight),
            ) { index ->
                val item = featuredItems[index]
                TvHomeHeroCard(
                    item = item,
                    eyebrow = featuredEyebrow,
                    parkInitialFocus = !heroFocusParked && index == 0,
                    onDidParkFocus = { heroFocusParked = true },
                    onPrimaryAction = { onOpenItem(item) },
                    onDetails = { onOpenItem(item) },
                )
            }
        }

        if (homeState.isLoading) {
            TvPlaceholderScreen(
                title = "Loading your room",
                body = "Fetching Continue Watching, Next Up, suggestions, and library rails from Jellyfin.",
            )
            return@Column
        }

        if (homeState.error != null) {
            TvPlaceholderScreen(
                title = "Home unavailable",
                body = homeState.error?.localizedMessage ?: "Failed to load TV home content.",
            )
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
            return@Column
        }

        shelves.forEachIndexed { i, shelf ->
            TvContentShelf(
                title = shelf.title,
                items = shelf.items,
                showProgress = shelf.showProgress,
                rowFocusRequester = shelfRequesters[i],
                onDpadDown = shelfRequesters.getOrNull(i + 1)?.let { next ->
                    { runCatching { next.requestFocus() }.isSuccess }
                },
                onDpadUp = shelfRequesters.getOrNull(i - 1)?.let { prev ->
                    { runCatching { prev.requestFocus() }.isSuccess }
                },
                onOpenItem = onOpenItem,
            )
        }

        if (homeState.views.isNotEmpty()) {
            val libIndex = shelves.size
            TvLibraryShelf(
                title = "Library Hub",
                views = homeState.views.map { it.view },
                rowFocusRequester = shelfRequesters[libIndex],
                onDpadUp = shelfRequesters.getOrNull(libIndex - 1)?.let { prev ->
                    { runCatching { prev.requestFocus() }.isSuccess }
                },
                onOpenLibrary = onOpenLibrary,
            )
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
        onClick = {},
        modifier = modifier,
        colors = CardDefaults.colors(containerColor = Color.White.copy(alpha = 0.05f)),
        shape = CardDefaults.shape(RoundedCornerShape(24.dp)),
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
        onClick = {},
        colors = CardDefaults.colors(containerColor = Color.White.copy(alpha = 0.04f)),
        shape = CardDefaults.shape(RoundedCornerShape(30.dp)),
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
    val scale by animateFloatAsState(animationSpec = tween(durationMillis = 120), targetValue = if (isFocused) 1.03f else 1f, label = "actionTileScale")
    val glow = MaterialTheme.colorScheme.primary

    Card(
        modifier =
            modifier
                .height(152.dp)
                .onFocusChanged { isFocused = it.isFocused }
                .ultrachromicFocus(isFocused, scale, RoundedCornerShape(26.dp), glow),
        onClick = onClick,
        colors =
            CardDefaults.colors(
                containerColor =
                    if (isFocused) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.88f)
                    } else {
                        Color.White.copy(alpha = 0.05f)
                    },
            ),
        shape = CardDefaults.shape(RoundedCornerShape(26.dp)),
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
    parkInitialFocus: Boolean,
    onDidParkFocus: () -> Unit,
    onPrimaryAction: () -> Unit,
    onDetails: () -> Unit,
) {
    val onFocusChange = LocalFocusedBackground.current
    val primaryFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    // Update the ambient backdrop whenever the hero item changes (carousel paging).
    LaunchedEffect(item.id) { onFocusChange(tvBackdropArtwork(item)) }
    // Park focus on the Resume/Play button ONCE on first app render of the home
    // screen — so the first OK press starts playback. The `parkInitialFocus` flag
    // is hoisted to TvHomeScreen so LazyColumn disposing/recomposing this card
    // when scrolled off-screen can't reset it and yank focus back to the top.
    LaunchedEffect(parkInitialFocus) {
        if (parkInitialFocus) {
            runCatching { primaryFocus.requestFocus() }
            onDidParkFocus()
        }
    }

    Card(
        onClick = {},
        colors = CardDefaults.colors(containerColor = Color(0x77131A24)),
        shape = CardDefaults.shape(RoundedCornerShape(32.dp)),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
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
                        .fillMaxWidth(0.5f)
                        .align(Alignment.BottomStart)
                        .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = eyebrow,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary,
                    fontWeight = FontWeight.SemiBold,
                )
                val heroLogoUri =
                    when (item) {
                        is SpatialFinEpisode -> item.images.showLogo
                        else -> item.images.logo
                    }
                if (heroLogoUri != null) {
                    AsyncImage(
                        model = heroLogoUri,
                        contentDescription = item.name,
                        modifier = Modifier.heightIn(max = 56.dp).fillMaxWidth(0.65f),
                        contentScale = ContentScale.Fit,
                        alignment = Alignment.BottomStart,
                    )
                } else {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    tvFeaturedMetadata(item, companionConfigured = false).forEach { token ->
                        TvMetadataPill(text = token)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvHeroButton(
                        label = if (item.playbackPositionTicks > 0L) "Resume" else if (item.canPlay) "Play" else "Open",
                        icon = Icons.Rounded.PlayArrow,
                        primary = true,
                        modifier = Modifier.focusRequester(primaryFocus),
                        onClick = onPrimaryAction,
                    )
                    TvHeroButton(
                        label = "Details",
                        icon = Icons.Rounded.Info,
                        primary = false,
                        onClick = onDetails,
                    )
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
    val scale by animateFloatAsState(animationSpec = tween(durationMillis = 120), targetValue = if (isFocused) 1.05f else 1f, label = "scale")
    val glow = MaterialTheme.colorScheme.primary
    Card(
        onClick = {},
        modifier =
            Modifier
                .width(240.dp)
                .height(150.dp)
                .onFocusChanged { isFocused = it.isFocused }
                .ultrachromicFocus(isFocused, scale, RoundedCornerShape(24.dp), glow),
        colors = CardDefaults.colors(containerColor = Color(0x77131A24)),
        shape = CardDefaults.shape(RoundedCornerShape(24.dp)),
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
    rowFocusRequester: androidx.compose.ui.focus.FocusRequester? = null,
    onDpadDown: (() -> Boolean)? = null,
    onDpadUp: (() -> Boolean)? = null,
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
            modifier = Modifier
                .focusRestorer()
                .focusGroup()
                .shelfKeyNav(onDpadDown = onDpadDown, onDpadUp = onDpadUp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val capped = items.take(12)
            itemsIndexed(capped, key = { _, item -> item.id }) { index, item ->
                val cardModifier = Modifier
                    .width(180.dp)
                    .let { if (index == 0 && rowFocusRequester != null) it.focusRequester(rowFocusRequester) else it }
                TvMediaCard(
                    item = item,
                    showProgress = showProgress,
                    modifier = cardModifier,
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
    rowFocusRequester: androidx.compose.ui.focus.FocusRequester? = null,
    onDpadDown: (() -> Boolean)? = null,
    onDpadUp: (() -> Boolean)? = null,
    onOpenLibrary: (View) -> Unit,
) {
    if (views.isEmpty()) return

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        LazyRow(
            modifier = Modifier
                .focusRestorer()
                .focusGroup()
                .shelfKeyNav(onDpadDown = onDpadDown, onDpadUp = onDpadUp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            itemsIndexed(views, key = { _, v -> v.id }) { index, view ->
                val cardModifier = if (index == 0 && rowFocusRequester != null) {
                    Modifier.focusRequester(rowFocusRequester)
                } else {
                    Modifier
                }
                TvLibraryCard(
                    view = view,
                    modifier = cardModifier,
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
    val scale by animateFloatAsState(animationSpec = tween(durationMillis = 120), targetValue = if (isFocused) 1.05f else 1f, label = "scale")
    val onFocusChange = LocalFocusedBackground.current

    LaunchedEffect(isFocused) {
        if (isFocused) {
            onFocusChange(item.images.backdrop ?: item.images.primary)
        }
    }

    val glow = MaterialTheme.colorScheme.primary
    Card(
        modifier =
            modifier
                .onFocusChanged { isFocused = it.isFocused }
                .ultrachromicFocus(isFocused, scale, RoundedCornerShape(22.dp), glow),
        onClick = onClick,
        colors = CardDefaults.colors(containerColor = Color(0x77131A24)),
        shape = CardDefaults.shape(RoundedCornerShape(22.dp)),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(modifier = Modifier.fillMaxWidth().aspectRatio(1.77f)) {
                val imageModel = item.images.showBackdrop ?: item.images.backdrop ?: item.images.primary
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
                if (item.isDownloaded()) {
                    Box(
                        modifier =
                            Modifier
                                .padding(6.dp)
                                .align(Alignment.TopEnd)
                                .background(
                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f),
                                    shape = RoundedCornerShape(999.dp),
                                )
                                .padding(4.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CloudDone,
                            contentDescription = "Downloaded",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
                if (showProgress) {
                    buildPlaybackFraction(item)?.let { progress ->
                        FloatingProgressBar(
                            progress = progress,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 6.dp)
                                    .align(Alignment.BottomCenter),
                            progressColor = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = tvCompactMetadata(item),
                    style = MaterialTheme.typography.labelSmall,
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
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    var isFocused by remember(view.id) { mutableStateOf(false) }
    val scale by animateFloatAsState(animationSpec = tween(durationMillis = 120), targetValue = if (isFocused) 1.03f else 1f, label = "scale")
    val onFocusChange = LocalFocusedBackground.current

    LaunchedEffect(isFocused) {
        if (isFocused) {
            onFocusChange(tvViewArtwork(view))
        }
    }

    val glow = MaterialTheme.colorScheme.primary
    Card(
        modifier =
            modifier
                .width(320.dp)
                .height(188.dp)
                .onFocusChanged { isFocused = it.isFocused }
                .ultrachromicFocus(isFocused, scale, RoundedCornerShape(24.dp), glow),
        onClick = onClick,
        colors = CardDefaults.colors(containerColor = Color(0x77131A24)),
        shape = CardDefaults.shape(RoundedCornerShape(24.dp)),
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

    var offlineOnly by remember { mutableStateOf(false) }
    val filteredItems = remember(view?.items, offlineOnly) {
        val base = view?.items.orEmpty()
        if (offlineOnly) base.filter { it.isDownloaded() } else base
    }
    val rows = remember(filteredItems) { filteredItems.chunked(5) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(22.dp),
        contentPadding = PaddingValues(bottom = 36.dp),
    ) {
        if (view == null) {
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
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvHeroButton(
                    label = "Back home",
                    icon = Icons.Rounded.Home,
                    primary = false,
                    onClick = onBackToHome,
                )
                if (availableViews.size > 1) {
                    TvHeroButton(
                        label = "Jump to first library",
                        icon = Icons.AutoMirrored.Rounded.ManageSearch,
                        primary = false,
                        onClick = { onSelectView(availableViews.first()) },
                    )
                }
                TvHeroButton(
                    label = if (offlineOnly) "Available offline" else "All titles",
                    icon = if (offlineOnly) Icons.Rounded.CloudDone else Icons.Rounded.CloudDownload,
                    primary = false,
                    selected = offlineOnly,
                    onClick = { offlineOnly = !offlineOnly },
                )
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
            items(rows, key = { row -> row.firstOrNull()?.id ?: row.hashCode() }) { rowItems ->
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
            Spacer(Modifier.height(0.dp))
        }
        item {
            Card(
                onClick = {},
                colors = CardDefaults.colors(containerColor = Color.White.copy(alpha = 0.04f)),
                shape = CardDefaults.shape(RoundedCornerShape(28.dp)),
            ) {                Row(
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
                    TvHeroButton(
                        label = "Search",
                        icon = Icons.Rounded.Search,
                        primary = true,
                        onClick = { viewModel.search() },
                    )
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
                items(rows, key = { row -> row.firstOrNull()?.id ?: row.hashCode() }) { rowItems ->
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

/**
 * Intercepts DPAD_UP / DPAD_DOWN on a shelf's LazyRow and routes focus to the
 * next/previous shelf's first-card FocusRequester. Returning true from the
 * key handler consumes the event so Compose's 2D spatial focus search (which
 * would otherwise pick the next horizontal card in the same row) is skipped.
 */
private fun Modifier.shelfKeyNav(
    onDpadDown: (() -> Boolean)?,
    onDpadUp: (() -> Boolean)?,
): Modifier =
    if (onDpadDown == null && onDpadUp == null) this
    else this.then(
        Modifier.onPreviewKeyEvent { event ->
            if (event.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) {
                return@onPreviewKeyEvent false
            }
            when (event.key) {
                androidx.compose.ui.input.key.Key.DirectionDown ->
                    onDpadDown?.invoke() ?: false
                androidx.compose.ui.input.key.Key.DirectionUp ->
                    onDpadUp?.invoke() ?: false
                else -> false
            }
        }
    )

@Composable
private fun TvMetadataPill(text: String) {
    MetadataPill {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
        )
    }
}

/**
 * Hero / detail action button with hardcoded contrast. We avoid
 * `androidx.tv.material3.Button` because its focus-state color logic tends to
 * flip text invisible on some themes — hardcoded colors + an optional leading
 * icon keep the button readable regardless of theme.
 *
 * [primary] controls the default visual (primary = white filled, secondary =
 * translucent white). [selected] overrides for segmented controls (version
 * chips, toggle pills). [icon] is optional — omit for text-only chips.
 */
@Composable
private fun TvHeroButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    primary: Boolean = true,
    selected: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    var isFocused by remember { mutableStateOf(false) }
    val container =
        when {
            isFocused -> Color.White
            selected -> Color(0xFFB6D3F4)
            primary -> Color.White
            else -> Color.White.copy(alpha = 0.16f)
        }
    val content =
        when {
            isFocused -> Color(0xFF06111B)
            selected -> Color(0xFF06111B)
            primary -> Color(0xFF06111B)
            else -> Color.White
        }

    // Modifier.clickable already registers focus + key handling on TV (DPAD_CENTER
    // triggers onClick). Stacking an extra Modifier.focusable() on top creates a
    // second focus target that ate the first OK press, so users had to press OK
    // twice — same bug the Card(onClick) comment warns about.
    Box(
        modifier =
            modifier
                .wrapContentWidth(unbounded = true)
                .clip(RoundedCornerShape(999.dp))
                .background(container)
                .border(
                    width = if (isFocused) 2.dp else 0.dp,
                    color = if (isFocused) MaterialTheme.colorScheme.primary else Color.Transparent,
                    shape = RoundedCornerShape(999.dp),
                )
                .onFocusChanged { isFocused = it.isFocused }
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = content,
                )
            }
            Text(
                text = label,
                fontSize = 12.sp,
                lineHeight = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = content,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Visible,
            )
        }
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
    footer: @Composable (ColumnScope.() -> Unit)? = null,
) {
    Card(
        onClick = {},
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.colors(containerColor = Color(0x77131A24)),
        shape = CardDefaults.shape(RoundedCornerShape(30.dp)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(340.dp),
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
                    onClick = {},
                    modifier = Modifier.width(200.dp).height(300.dp),
                    colors = CardDefaults.colors(containerColor = Color(0xAA111821)),
                    shape = CardDefaults.shape(RoundedCornerShape(24.dp)),
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
                    if (item.ratings.isNotEmpty()) {
                        RatingsRow(ratings = item.ratings)
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
        if (footer != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = footer,
            )
        }
    }
}

@Composable
private fun TvCastRow(
    actors: List<dev.jdtech.jellyfin.models.SpatialFinItemPerson>,
    onActorClick: (UUID) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Cast & Crew",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(actors, key = { it.id }) { person ->
                TvCastCard(person = person, onClick = { onActorClick(person.id) })
            }
        }
    }
}

@Composable
private fun TvCastCard(
    person: dev.jdtech.jellyfin.models.SpatialFinItemPerson,
    onClick: () -> Unit,
) {
    var isFocused by remember(person.id) { mutableStateOf(false) }
    val scale by animateFloatAsState(
        animationSpec = tween(durationMillis = 120),
        targetValue = if (isFocused) 1.06f else 1f,
        label = "castScale",
    )
    Column(
        modifier = Modifier
            .width(140.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .onFocusChanged { isFocused = it.isFocused }
            .clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val glow = MaterialTheme.colorScheme.primary
        Box(
            modifier = Modifier
                .size(140.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(Color(0xFF1A2433))
                .border(
                    width = if (isFocused) 1.dp else 0.dp,
                    color = if (isFocused) glow.copy(alpha = 0.6f) else Color.Transparent,
                    shape = RoundedCornerShape(999.dp),
                ),
        ) {
            if (person.image.uri != null) {
                AsyncImage(
                    model = person.image.uri,
                    contentDescription = person.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Text(
                    text = person.name.take(1).uppercase(),
                    modifier = Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Text(
            text = person.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = Color.White,
        )
        if (person.role.isNotBlank()) {
            Text(
                text = person.role,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFB8C2CE),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TvChaptersRow(
    chapters: List<dev.jdtech.jellyfin.models.SpatialFinChapter>,
    onChapterClick: (dev.jdtech.jellyfin.models.SpatialFinChapter) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Chapters",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(chapters, key = { it.startPosition }) { chapter ->
                TvChapterCard(chapter = chapter, onClick = { onChapterClick(chapter) })
            }
        }
    }
}

@Composable
private fun TvChapterCard(
    chapter: dev.jdtech.jellyfin.models.SpatialFinChapter,
    onClick: () -> Unit,
) {
    var isFocused by remember(chapter.startPosition) { mutableStateOf(false) }
    val scale by animateFloatAsState(
        animationSpec = tween(durationMillis = 120),
        targetValue = if (isFocused) 1.05f else 1f,
        label = "chapterScale",
    )
    val glow = MaterialTheme.colorScheme.primary
    val title =
        chapter.name?.takeIf { it.isNotBlank() } ?: tvFormatChapterTime(chapter.startPosition)

    Column(
        modifier = Modifier
            .width(240.dp)
            .onFocusChanged { isFocused = it.isFocused }
            .ultrachromicFocus(isFocused, scale, RoundedCornerShape(16.dp), glow)
            .clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.77f)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF161D28)),
        ) {
            if (chapter.imageUri != null) {
                AsyncImage(
                    model = chapter.imageUri,
                    contentDescription = title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
            Box(
                modifier = Modifier
                    .padding(6.dp)
                    .align(Alignment.BottomStart)
                    .background(
                        color = Color.Black.copy(alpha = 0.55f),
                        shape = RoundedCornerShape(6.dp),
                    )
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Text(
                    text = tvFormatChapterTime(chapter.startPosition),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

/**
 * Ultrachromic-inspired focus style: 1dp accent-60% border + a soft accent
 * shadow halo via framework elevation. Single source of truth for all TV
 * cards so the focus treatment stays consistent.
 */
private fun Modifier.ultrachromicFocus(
    isFocused: Boolean,
    scale: Float,
    shape: RoundedCornerShape,
    glow: Color,
): Modifier =
    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
            if (isFocused) {
                shadowElevation = 6.dp.toPx()
                ambientShadowColor = glow
                spotShadowColor = glow
            }
        }
        .border(
            width = if (isFocused) 1.dp else 0.dp,
            color = if (isFocused) glow.copy(alpha = 0.6f) else Color.Transparent,
            shape = shape,
        )

private fun peopleOf(item: SpatialFinItem): List<dev.jdtech.jellyfin.models.SpatialFinItemPerson> =
    when (item) {
        is SpatialFinMovie -> item.people
        is SpatialFinEpisode -> item.people
        is dev.jdtech.jellyfin.models.SpatialFinShow -> item.people
        else -> emptyList()
    }

private fun tvFormatChapterTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
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
    val scale by animateFloatAsState(animationSpec = tween(durationMillis = 120), targetValue = if (isFocused) 1.04f else 1f, label = "seasonScale")
    val onFocusChange = LocalFocusedBackground.current

    LaunchedEffect(isFocused) {
        if (isFocused) {
            onFocusChange(tvBackdropArtwork(season))
        }
    }

    val glow = MaterialTheme.colorScheme.primary
    Card(
        modifier =
            Modifier
                .width(250.dp)
                .height(132.dp)
                .onFocusChanged { isFocused = it.isFocused }
                .ultrachromicFocus(isFocused, scale, RoundedCornerShape(22.dp), glow),
        onClick = onClick,
        colors = CardDefaults.colors(containerColor = Color(0x77131A24)),
        shape = CardDefaults.shape(RoundedCornerShape(22.dp)),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Card(
                onClick = {},
                modifier = Modifier.width(74.dp).fillMaxHeight(),
                colors = CardDefaults.colors(containerColor = Color(0xAA111821)),
                shape = CardDefaults.shape(RoundedCornerShape(16.dp)),
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
    val scale by animateFloatAsState(animationSpec = tween(durationMillis = 120), targetValue = if (isFocused) 1.02f else 1f, label = "episodeScale")
    val onFocusChange = LocalFocusedBackground.current

    LaunchedEffect(isFocused) {
        if (isFocused) {
            onFocusChange(tvBackdropArtwork(episode))
        }
    }

    val glow = MaterialTheme.colorScheme.primary
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(176.dp)
                .onFocusChanged { isFocused = it.isFocused }
                .ultrachromicFocus(isFocused, scale, RoundedCornerShape(24.dp), glow),
        onClick = onClick,
        colors = CardDefaults.colors(containerColor = Color(0x77131A24)),
        shape = CardDefaults.shape(RoundedCornerShape(24.dp)),
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
    val scale by animateFloatAsState(animationSpec = tween(durationMillis = 120), targetValue = if (isFocused) 1.02f else 1f, label = "heroScale")
    val onFocusChange = LocalFocusedBackground.current

    LaunchedEffect(isFocused) {
        if (isFocused) {
            onFocusChange(item.images.backdrop ?: item.images.primary)
        }
    }

    val glow = MaterialTheme.colorScheme.primary
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(280.dp)
            .onFocusChanged { isFocused = it.isFocused }
            .ultrachromicFocus(isFocused, scale, RoundedCornerShape(30.dp), glow),
        onClick = onClick,
        colors = CardDefaults.colors(containerColor = Color(0x77131A24)),
        shape = CardDefaults.shape(RoundedCornerShape(30.dp)),
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
        onClick = {},
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.colors(containerColor = Color(0x77131A24)),
        shape = CardDefaults.shape(RoundedCornerShape(30.dp)),
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
    onOpenPerson: (UUID) -> Unit,
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
    val playFocus = remember { androidx.compose.ui.focus.FocusRequester() }

    LaunchedEffect(itemId) {
        viewModel.load(itemId)
    }
    // Park focus on the Play/Resume button whenever a new item loads so the
    // first D-pad OK press starts playback instead of activating whichever
    // nav-bar tab was last focused.
    LaunchedEffect(state.item?.id) {
        if (state.item != null) {
            runCatching { playFocus.requestFocus() }
        }
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
                                TvHeroButton(
                                    label = if (item.playbackPositionTicks > 0L) "Resume" else "Play",
                                    icon = Icons.Rounded.PlayArrow,
                                    primary = true,
                                    modifier = Modifier.focusRequester(playFocus),
                                    onClick = {
                                        TvPlayerActivity.createIntentForSpatialItem(context, item)?.let(context::startActivity)
                                    },
                                )
                                if (item.playbackPositionTicks > 0L) {
                                    TvHeroButton(
                                        label = "Restart",
                                        icon = Icons.Rounded.Replay,
                                        primary = false,
                                        onClick = {
                                            TvPlayerActivity.createIntentForSpatialItem(
                                                context = context,
                                                item = item,
                                                startFromBeginning = true,
                                            )?.let(context::startActivity)
                                        },
                                    )
                                }
                            }
                            TvHeroButton(
                                label = if (item.favorite) "Favorited" else "Favorite",
                                icon = if (item.favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                primary = false,
                                selected = item.favorite,
                                onClick = { viewModel.toggleFavorite() },
                            )
                            TvHeroButton(
                                label = if (item.played) "Watched" else "Mark watched",
                                icon = if (item.played) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                                primary = false,
                                selected = item.played,
                                onClick = { viewModel.togglePlayed() },
                            )
                            TvHeroButton(
                                label = "Back",
                                icon = Icons.AutoMirrored.Rounded.ArrowBack,
                                primary = false,
                                onClick = onBack,
                            )
                        },
                        footer = if (versions.size > 1) {
                            {
                                Text(
                                    text = "Versions",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    versions.forEach { version ->
                                        val selected = version.id == item.id
                                        TvHeroButton(
                                            label = version.versionChipLabel(),
                                            primary = false,
                                            selected = selected,
                                            onClick = { if (!selected) viewModel.load(version.id) },
                                        )
                                    }
                                }
                            }
                        } else null,
                    )
                }
                if (item.chapters.isNotEmpty()) {
                    item {
                        TvChaptersRow(
                            chapters = item.chapters,
                            onChapterClick = {
                                TvPlayerActivity.createIntentForSpatialItem(context, item)?.let(context::startActivity)
                            },
                        )
                    }
                }
                val actors = peopleOf(item).filter { person ->
                    person.type == org.jellyfin.sdk.model.api.PersonKind.ACTOR
                }
                if (actors.isNotEmpty()) {
                    item {
                        TvCastRow(actors = actors, onActorClick = onOpenPerson)
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
    onOpenPerson: (UUID) -> Unit,
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
    val playFocus = remember { androidx.compose.ui.focus.FocusRequester() }

    LaunchedEffect(showId) {
        viewModel.load(showId)
    }
    LaunchedEffect(state.show?.id) {
        if (state.show != null) {
            runCatching { playFocus.requestFocus() }
        }
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
                            val nextEpisode = state.nextUp
                            if (nextEpisode != null) {
                                TvHeroButton(
                                    label = if (nextEpisode.playbackPositionTicks > 0L) "Resume Episode" else "Play Next",
                                    icon = Icons.Rounded.PlayArrow,
                                    primary = true,
                                    modifier = Modifier.focusRequester(playFocus),
                                    onClick = {
                                        TvPlayerActivity.createIntentForSpatialItem(context, nextEpisode)?.let(context::startActivity)
                                    },
                                )
                            }
                            TvHeroButton(
                                label = if (show.favorite) "Favorited" else "Favorite",
                                icon = if (show.favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                primary = false,
                                selected = show.favorite,
                                onClick = { viewModel.toggleFavorite() },
                            )
                            TvHeroButton(
                                label = if (show.played) "Watched" else "Mark watched",
                                icon = if (show.played) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                                primary = false,
                                selected = show.played,
                                onClick = { viewModel.togglePlayed() },
                            )
                            TvHeroButton(
                                label = "Back",
                                icon = Icons.AutoMirrored.Rounded.ArrowBack,
                                primary = false,
                                onClick = onBack,
                            )
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
                val actors = show.people.filter { person ->
                    person.type == org.jellyfin.sdk.model.api.PersonKind.ACTOR
                }
                if (actors.isNotEmpty()) {
                    item {
                        TvCastRow(actors = actors, onActorClick = onOpenPerson)
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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TvHeroButton(
                            label = "Back",
                            icon = Icons.AutoMirrored.Rounded.ArrowBack,
                            primary = false,
                            onClick = onBack,
                        )
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
                    items(rows, key = { row -> row.firstOrNull()?.id ?: row.hashCode() }) { rowItems ->
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

