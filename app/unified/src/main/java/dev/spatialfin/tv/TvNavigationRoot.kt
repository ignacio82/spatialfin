package dev.spatialfin.tv

import dev.spatialfin.R
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListPrefetchStrategy
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.automirrored.rounded.ManageSearch
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.painter.Painter
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
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Carousel
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.NavigationDrawer
import androidx.tv.material3.NavigationDrawerItem
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import dev.jdtech.jellyfin.core.presentation.components.userPrimaryImageUri
import dev.jdtech.jellyfin.core.presentation.components.FloatingProgressBar
import dev.jdtech.jellyfin.core.presentation.components.MetadataPill
import dev.jdtech.jellyfin.film.presentation.home.HomeState
import dev.jdtech.jellyfin.film.presentation.home.HomeViewModel
import dev.jdtech.jellyfin.models.CollectionType
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
import dev.jdtech.jellyfin.presentation.film.components.RatingsRow
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.utils.getShowDateString
import dev.jdtech.jellyfin.viewmodels.MainState
import java.util.UUID

private enum class TvRoute {
    Home, Search, Library, Detail, Show, Season, Person, Companion, Settings, Users
}

private data class TvNavItem(val route: TvRoute, val label: String, val icon: ImageVector)

private val tvNavItems = listOf(
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
    initialSearchQuery: String? = null,
) {
    val homeViewModel: HomeViewModel = hiltViewModel()
    val homeState by homeViewModel.state.collectAsStateWithLifecycle()
    // Activity-scoped: the same VM that TvSearchScreen resolves via hiltViewModel().
    // Pre-populating it before the user navigates lets ACTION_SEARCH / global
    // search suggestions land on the Search route with the query already run.
    val searchViewModel: dev.spatialfin.tv.TvSearchViewModel = hiltViewModel()

    val navStack = rememberSaveable(
        saver = listSaver<MutableList<TvRoute>, String>(
            save = { it.map { route -> route.name } },
            restore = { saved -> mutableStateListOf<TvRoute>().apply { addAll(saved.map { TvRoute.valueOf(it) }) } }
        )
    ) { mutableStateListOf(TvRoute.Home) }

    val currentRoute = navStack.lastOrNull() ?: TvRoute.Home

    fun navigate(route: TvRoute) {
        if (route == TvRoute.Home) { navStack.clear(); navStack.add(TvRoute.Home) }
        else if (navStack.lastOrNull() != route) navStack.add(route)
    }

    fun popBack() { if (navStack.size > 1) navStack.removeAt(navStack.size - 1) }

    BackHandler(enabled = navStack.size > 1) { popBack() }

    var selectedView by remember { mutableStateOf<View?>(null) }
    var selectedItemId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedShowId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedSeasonId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedPersonId by rememberSaveable { mutableStateOf<String?>(null) }
    var focusedBackgroundUrl by remember { mutableStateOf<Any?>(null) }

    LaunchedEffect(Unit) { homeViewModel.loadData() }

    LaunchedEffect(initialSearchQuery) {
        val query = initialSearchQuery?.trim().orEmpty()
        if (query.isNotEmpty()) {
            searchViewModel.setQuery(query)
            searchViewModel.search()
            navigate(TvRoute.Search)
        }
    }

    fun openItem(item: SpatialFinItem) {
        when (item) {
            is SpatialFinShow -> { selectedShowId = item.id.toString(); navigate(TvRoute.Show) }
            is SpatialFinSeason -> { selectedSeasonId = item.id.toString(); navigate(TvRoute.Season) }
            is SpatialFinCollection -> Unit
            else -> { selectedItemId = item.id.toString(); navigate(TvRoute.Detail) }
        }
    }

    CompositionLocalProvider(LocalFocusedBackground provides { focusedBackgroundUrl = it }) {
        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            TvAmbientBackground(backgroundModel = focusedBackgroundUrl)
            NavigationDrawer(
                drawerContent = { _ ->
                    Column(
                        modifier = Modifier.fillMaxHeight().padding(vertical = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        androidx.compose.foundation.Image(
                            painter = painterResource(id = R.drawable.ic_launcher_foreground),
                            contentDescription = "SpatialFin",
                            modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)),
                        )
                        Spacer(Modifier.height(8.dp))
                        val visibleNavItems = if (state.isOfflineMode) tvNavItems.filter { it.route != TvRoute.Home && it.route != TvRoute.Search } else tvNavItems
                        visibleNavItems.forEach { item ->
                            NavigationDrawerItem(
                                selected = currentRoute == item.route,
                                onClick = { if (item.route == TvRoute.Library) selectedView = null; navigate(item.route) },
                                leadingContent = { Icon(imageVector = item.icon, contentDescription = null) },
                            ) { Text(text = item.label) }
                        }

                        val user = state.currentUser
                        if (user != null) {
                            Spacer(Modifier.weight(1f))
                            NavigationDrawerItem(
                                selected = currentRoute == TvRoute.Users,
                                onClick = { navigate(TvRoute.Users) },
                                leadingContent = {
                                    val avatarUri = userPrimaryImageUri(state.currentServerAddress, user.id)
                                    if (avatarUri != null) {
                                        AsyncImage(
                                            model = avatarUri,
                                            contentDescription = "Profile",
                                            modifier = Modifier.size(24.dp).clip(RoundedCornerShape(12.dp)),
                                            contentScale = ContentScale.Crop
                                        )
                                    } else {
                                        val initial = user.name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
                                        Box(
                                            modifier = Modifier.size(24.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.secondaryContainer),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(initial, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                                        }
                                    }
                                },
                            ) { Text(text = user.name) }
                        }
                    }
                }
            ) {
                Box(modifier = Modifier.fillMaxSize().padding(start = 32.dp, end = 48.dp)) {
                    when (currentRoute) {
                        TvRoute.Home -> TvHomeScreen(homeState, state, appPreferences, { selectedView = it; navigate(TvRoute.Library) }, ::openItem, { navigate(TvRoute.Companion) }, { navigate(TvRoute.Search) }, { homeViewModel.loadData() })
                        TvRoute.Search -> TvSearchScreen(::openItem)
                        TvRoute.Library -> TvLibraryScreen(selectedView, homeState.views.map { it.view }, { popBack() }, { selectedView = it }, ::openItem)
                        TvRoute.Detail -> TvItemDetailScreen(selectedItemId?.let(UUID::fromString), { popBack() }, ::openItem, { selectedPersonId = it.toString(); navigate(TvRoute.Person) }, { selectedShowId = it.toString(); navigate(TvRoute.Show) }, { selectedSeasonId = it.toString(); navigate(TvRoute.Season) })
                        TvRoute.Show -> TvShowScreen(selectedShowId?.let(UUID::fromString), { popBack() }, { selectedSeasonId = it.toString(); navigate(TvRoute.Season) }, { selectedItemId = it.toString(); navigate(TvRoute.Detail) }, { selectedPersonId = it.toString(); navigate(TvRoute.Person) })
                        TvRoute.Season -> TvSeasonScreen(selectedSeasonId?.let(UUID::fromString), { popBack() }, { selectedItemId = it.toString(); navigate(TvRoute.Detail) })
                        TvRoute.Person -> selectedPersonId?.let(UUID::fromString)?.let { pid -> dev.jdtech.jellyfin.presentation.film.PersonScreen(pid, { popBack() }, { navigate(TvRoute.Home) }, ::openItem) } ?: popBack()
                        TvRoute.Settings -> TvSettingsScreen(state, appPreferences, homeState.server?.name, { navigate(TvRoute.Companion) }, { navigate(TvRoute.Search) }, { navigate(TvRoute.Users) })
                        TvRoute.Users -> TvUsersScreen({ popBack() }, { navigate(TvRoute.Home) })
                        TvRoute.Companion -> TvCompanionScreen({ popBack() }, { homeViewModel.loadData() })
                    }
                }
            }
        }
    }
}

@Composable
private fun TvAmbientBackground(backgroundModel: Any?) {
    val context = LocalContext.current
    var settledModel by remember { mutableStateOf<Any?>(backgroundModel) }
    LaunchedEffect(backgroundModel) { kotlinx.coroutines.delay(220); settledModel = backgroundModel }
    val sizedModel = remember(settledModel) { settledModel?.let { coil3.request.ImageRequest.Builder(context).data(it).size(960, 540).build() } }
    Box(modifier = Modifier.fillMaxSize()) {
        AsyncImage(model = sizedModel, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = 0.32f)
        Box(modifier = Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0x6606111B), Color(0xE006111B)))))
        Box(modifier = Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.radialGradient(listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.20f), Color.Transparent), center = androidx.compose.ui.geometry.Offset(350f, 300f), radius = 650f)))
        Box(modifier = Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.radialGradient(listOf(MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f), Color.Transparent), center = androidx.compose.ui.geometry.Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY), radius = 600f)))
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvHomeScreen(homeState: HomeState, state: MainState, appPreferences: AppPreferences, onOpenLibrary: (View) -> Unit, onOpenItem: (SpatialFinItem) -> Unit, onOpenCompanion: () -> Unit, onOpenSearch: () -> Unit, onRefresh: () -> Unit) {
    var heroFocusParked by rememberSaveable { mutableStateOf(false) }
    if (!state.hasCurrentUser || !state.hasServers) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            contentPadding = PaddingValues(top = 64.dp, bottom = 48.dp),
        ) {
            item { TvOnboardingHero(tvCompanionConfigured(appPreferences), onOpenCompanion) }
        }
        return
    }
    val suggestions = homeState.suggestionsSection?.items.orEmpty()
    val nextUp = homeState.nextUpSection?.homeSection?.items.orEmpty()
    val featuredItems = suggestions.take(5).ifEmpty { nextUp.take(5) }
    val featuredEyebrow = if (suggestions.isNotEmpty()) "Featured" else "Next up for you"
    data class ShelfSpec(val title: String, val items: List<SpatialFinItem>, val showProgress: Boolean = false)
    val shelves = buildList {
        homeState.resumeSection?.let { add(ShelfSpec(it.homeSection.name.asString(), it.homeSection.items, true)) }
        homeState.nextUpSection?.let { add(ShelfSpec(it.homeSection.name.asString(), it.homeSection.items)) }
        homeState.views.map { it.view }.firstOrNull { it.type == CollectionType.Movies }?.takeIf { it.items.isNotEmpty() }?.let { add(ShelfSpec("Recently added movies", it.items)) }
        homeState.views.map { it.view }.firstOrNull { it.type == CollectionType.TvShows }?.takeIf { it.items.isNotEmpty() }?.let { add(ShelfSpec("Recently added TV", it.items)) }
    }
    androidx.compose.foundation.lazy.LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        contentPadding = PaddingValues(top = 64.dp, bottom = 48.dp),
    ) {
        if (featuredItems.isNotEmpty()) item {
            Carousel(itemCount = featuredItems.size, autoScrollDurationMillis = 5000L, modifier = Modifier.fillMaxWidth().height(LocalConfiguration.current.screenHeightDp.dp * 0.35f)) { index ->
                val item = featuredItems[index]
                TvHomeHeroCard(item, featuredEyebrow, !heroFocusParked && index == 0, { heroFocusParked = true }, { onOpenItem(item) }, { onOpenItem(item) })
            }
        }
        if (homeState.isLoading) item { TvPlaceholderScreen("Loading your room", "Fetching Continue Watching, Next Up, suggestions, and library rails from Jellyfin.") }
        else if (homeState.error != null) {
            item { TvPlaceholderScreen("Home unavailable", homeState.error?.localizedMessage ?: "Failed to load TV home content.") }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    TvActionTile("Search library", "Jump straight into direct Jellyfin search from the TV.", Icons.AutoMirrored.Rounded.ManageSearch, Modifier.weight(1f), onOpenSearch)
                    TvActionTile("Companion setup", "Open QR pairing and import settings from your phone companion.", Icons.Rounded.Link, Modifier.weight(1f), onOpenCompanion)
                    TvActionTile("Refresh home", "Retry the Jellyfin home request for this TV.", Icons.Rounded.Home, Modifier.weight(1f), onRefresh)
                }
            }
        } else {
            items(shelves, key = { it.title }) { shelf -> TvContentShelf(shelf.title, shelf.items, shelf.showProgress, onOpenItem) }
            if (homeState.views.isNotEmpty()) item { TvLibraryShelf("Library Hub", homeState.views.map { it.view }, onOpenLibrary) }
        }
    }
}

@Composable
private fun TvSettingsScreen(state: MainState, appPreferences: AppPreferences, serverName: String?, onOpenCompanion: () -> Unit, onOpenSearch: () -> Unit, onOpenUsers: () -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        contentPadding = PaddingValues(top = 64.dp, bottom = 48.dp),
    ) {
        item {
            Text("Preferences", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        }
        item {
            TvSettingsPreferences(appPreferences = appPreferences)
        }
    }
}

@Composable
private fun TvUsersScreen(onBack: () -> Unit, onUserSwitched: () -> Unit, viewModel: dev.jdtech.jellyfin.setup.presentation.users.UsersViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.loadUsers() }
    LaunchedEffect(viewModel) { viewModel.events.collect { if (it is dev.jdtech.jellyfin.setup.presentation.users.UsersEvent.NavigateToHome) onUserSwitched() } }
    androidx.compose.foundation.lazy.LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        contentPadding = PaddingValues(top = 64.dp, bottom = 48.dp),
    ) {
        item { Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) { TvHeroButton("Back", Icons.AutoMirrored.Rounded.ArrowBack, false, onClick = onBack) } }
        item { TvPageHeaderCard("Switch user", state.serverName?.let { "Connected to $it" } ?: "Choose a user to continue.") }
        if (state.users.isEmpty() && state.publicUsers.isEmpty()) item { TvPlaceholderScreen("No users", "No saved or public users are available for this server yet.") }
        else item {
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                state.users.forEach { user -> TvUserCard(user.name, "Saved user", dev.jdtech.jellyfin.core.presentation.components.userPrimaryImageUri(state.serverAddress, user.id)) { viewModel.onAction(dev.jdtech.jellyfin.setup.presentation.users.UsersAction.OnUserClick(user.id)) } }
                state.publicUsers.forEach { user -> TvUserCard(user.name, "Public user", dev.jdtech.jellyfin.core.presentation.components.userPrimaryImageUri(state.serverAddress, user.id)) { viewModel.onAction(dev.jdtech.jellyfin.setup.presentation.users.UsersAction.OnUserClick(user.id)) } }
            }
        }
    }
}

// Home rails are nested inside the TvHomeScreen's LazyColumn, so
// `initialNestedPrefetchItemCount` controls how many cards of a not-yet-visible
// shelf get pre-composed as the user D-pads down. The default is 2; on weak TV
// GPUs (Chromecast w/ Google TV, Amlogic Mali-G31) that leaves visible pop-in
// when a new shelf slides in. 4 covers roughly the left-hand bias of the row.
private const val TV_NESTED_PREFETCH_COUNT = 4

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun rememberTvShelfListState() =
    rememberLazyListState(
        prefetchStrategy = remember { LazyListPrefetchStrategy(TV_NESTED_PREFETCH_COUNT) },
    )

@Composable
private fun TvContentShelf(title: String, items: List<SpatialFinItem>, showProgress: Boolean = false, onOpenItem: (SpatialFinItem) -> Unit) {
    if (items.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(text = title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        LazyRow(
            modifier = Modifier.focusRestorer(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            state = rememberTvShelfListState(),
        ) {
            itemsIndexed(items.take(12), key = { _, it -> it.id }) { _, item ->
                TvMediaCard(item, showProgress, Modifier.width(180.dp), { onOpenItem(item) })
            }
        }
    }
}

@Composable
private fun TvLibraryShelf(title: String, views: List<View>, onOpenLibrary: (View) -> Unit) {
    if (views.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(text = title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        LazyRow(
            modifier = Modifier.focusRestorer(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            state = rememberTvShelfListState(),
        ) {
            itemsIndexed(views, key = { _, it -> it.id }) { _, view ->
                TvLibraryCard(view, onClick = { onOpenLibrary(view) })
            }
        }
    }
}

@Composable
private fun TvLibraryScreen(view: View?, availableViews: List<View>, onBackToHome: () -> Unit, onSelectView: (View) -> Unit, onOpenItem: (SpatialFinItem) -> Unit) {
    if (view == null && availableViews.isEmpty()) { TvPlaceholderScreen("Library unavailable", "No libraries available."); return }
    var offlineOnly by remember { mutableStateOf(false) }
    val filteredItems = remember(view?.items, offlineOnly) { val base = view?.items.orEmpty(); if (offlineOnly) base.filter { it.isDownloaded() } else base }
    LazyVerticalGrid(
        columns = GridCells.Fixed(5),
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(22.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(top = 64.dp, bottom = 48.dp),
    ) {
        if (view == null) { item(span = { GridItemSpan(5) }) { TvLibraryShelf("Available libraries", availableViews, onSelectView) }; return@LazyVerticalGrid }
        item(span = { GridItemSpan(5) }) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvHeroButton("Back home", Icons.Rounded.Home, false, onClick = onBackToHome)
                if (availableViews.size > 1) TvHeroButton("Jump to first library", Icons.AutoMirrored.Rounded.ManageSearch, false, onClick = { onSelectView(availableViews.first()) })
                TvHeroButton(if (offlineOnly) "Available offline" else "All titles", if (offlineOnly) Icons.Rounded.CloudDone else Icons.Rounded.CloudDownload, false, offlineOnly, onClick = { offlineOnly = !offlineOnly })
            }
        }
        if (filteredItems.isEmpty()) item(span = { GridItemSpan(5) }) { TvPlaceholderScreen("No titles", "This library is empty.") }
        else gridItems(filteredItems, key = { it.id }) { TvMediaCard(it, false, Modifier.fillMaxWidth(), { onOpenItem(it) }) }
    }
}

@Composable
private fun TvSearchScreen(onOpenItem: (SpatialFinItem) -> Unit, viewModel: dev.spatialfin.tv.TvSearchViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LazyVerticalGrid(
        columns = GridCells.Fixed(5),
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(22.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(top = 64.dp, bottom = 48.dp),
    ) {
        item(span = { GridItemSpan(5) }) { Spacer(Modifier.height(0.dp)) }
        item(span = { GridItemSpan(5) }) {
            Card(onClick = {}, colors = CardDefaults.colors(containerColor = Color.White.copy(alpha = 0.04f)), shape = CardDefaults.shape(RoundedCornerShape(28.dp))) {
                Row(modifier = Modifier.fillMaxWidth().padding(22.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(value = state.query, onValueChange = viewModel::setQuery, modifier = Modifier.weight(1f), singleLine = true, label = { androidx.compose.material3.Text("Search...") }, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { viewModel.search() }))
                    TvHeroButton("Search", Icons.Rounded.Search, true, onClick = { viewModel.search() })
                }
            }
        }
        when {
            state.isLoading -> item(span = { GridItemSpan(5) }) { TvPlaceholderScreen("Searching", "Looking through Jellyfin...") }
            state.error != null -> item(span = { GridItemSpan(5) }) { TvPlaceholderScreen("Search failed", state.error?.localizedMessage ?: "Unknown error") }
            state.hasSearched && state.items.isEmpty() -> item(span = { GridItemSpan(5) }) { TvPlaceholderScreen("No results", "Try another title.") }
            state.items.isNotEmpty() -> {
                item(span = { GridItemSpan(5) }) { Text("Results", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) }
                gridItems(state.items, key = { it.id }) { TvMediaCard(it, false, Modifier.fillMaxWidth(), { onOpenItem(it) }) }
            }
            else -> item(span = { GridItemSpan(5) }) { TvPlaceholderScreen("Ready to search", "Enter a title above.") }
        }
    }
}

@Composable
private fun TvItemDetailScreen(itemId: UUID?, onBack: () -> Unit, onOpenItem: (SpatialFinItem) -> Unit, onOpenPerson: (UUID) -> Unit, onOpenShow: (UUID) -> Unit, onOpenSeason: (UUID) -> Unit, viewModel: TvItemDetailViewModel = hiltViewModel()) {
    if (itemId == null) { TvPlaceholderScreen("Item unavailable", "No item selected."); return }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val playFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(itemId) { viewModel.load(itemId) }
    LaunchedEffect(state.item?.id) {
        if (state.item != null) {
            runCatching { playFocus.requestFocus() }
        }
    }
    when {
        state.isLoading -> TvPlaceholderScreen("Loading details", "Fetching item...")
        state.error != null -> TvPlaceholderScreen("Couldn't load item", state.error?.localizedMessage ?: "Error")
        state.item == null -> TvPlaceholderScreen("Missing item", "Not available.")
        else -> {
            val item = state.item!!
            val onFocusBackground = LocalFocusedBackground.current
            LaunchedEffect(item.id) { onFocusBackground(tvBackdropArtwork(item)) }
            val supportingLine = when (item) {
                is SpatialFinMovie -> item.originalTitle?.takeIf { it.isNotBlank() && it != item.name } ?: item.genres.take(3).joinToString(" • ")
                is SpatialFinEpisode -> listOfNotNull(item.seriesName, item.seasonName, tvEpisodeLabel(item)).filter { it.isNotBlank() }.joinToString(" • ")
                is SpatialFinSeason -> item.seriesName
                else -> item.originalTitle?.takeIf { it.isNotBlank() && it != item.name }
            }
            val metadata = buildList {
                when (val i = item) {
                    is SpatialFinMovie -> {
                        i.productionYear?.let { add(it.toString()) }
                        tvRuntimeLabel(i.runtimeTicks)?.let(::add)
                        i.officialRating?.takeIf { it.isNotBlank() }?.let(::add)
                        addAll(i.genres.take(2))
                    }
                    is SpatialFinEpisode -> {
                        add(tvEpisodeLabel(i))
                        i.premiereDate?.year?.let { add(it.toString()) }
                        tvRuntimeLabel(i.runtimeTicks)?.let(::add)
                    }
                    is SpatialFinSeason -> { add(tvSeasonLabel(i)); i.unplayedItemCount?.takeIf { it > 0 }?.let { add("$it unwatched") } }
                    else -> Unit
                }
            }
            Column(modifier = Modifier.fillMaxSize().padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                TvDetailHeroCard(item, tvItemLabel(item), supportingLine, metadata, item.overview, onBack = onBack, actions = {
                    val i = item
                    if (i is SpatialFinMovie || i is SpatialFinEpisode) {
                        val kind = if (i is SpatialFinMovie) "Movie" else "Episode"
                        val trailerUrl = if (i is SpatialFinMovie) i.trailer else null
                        TvHeroButton(if (i.playbackPositionTicks > 0L) "Resume" else "Play", Icons.Rounded.PlayArrow, true, modifier = Modifier.focusRequester(playFocus)) { TvPlayerActivity.createIntentForSpatialItem(context, i)?.let(context::startActivity) }
                        if (i.playbackPositionTicks > 0L) TvIconHeroButton(Icons.Rounded.Replay, "Restart") { TvPlayerActivity.createIntentForSpatialItem(context, i, startFromBeginning = true)?.let(context::startActivity) }
                        TvIconHeroButton(if (item.played) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, if (item.played) "Watched" else "Mark watched", item.played) { viewModel.togglePlayed() }
                        TvIconHeroButton(if (item.favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (item.favorite) "Favorited" else "Favorite", item.favorite) { viewModel.toggleFavorite() }
                        Box {
                            var menuOpen by remember { mutableStateOf(false) }
                            TvIconHeroButton(Icons.Rounded.MoreVert, "More actions") { menuOpen = true }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(text = { androidx.compose.material3.Text("SyncPlay") }, leadingIcon = { androidx.compose.material3.Icon(painterResource(dev.jdtech.jellyfin.core.R.drawable.ic_tv), null, Modifier.size(20.dp)) }, onClick = { menuOpen = false; TvPlayerActivity.createIntentForSpatialItem(context, i, openSyncPlayDialogOnStart = true)?.let(context::startActivity) })
                                DropdownMenuItem(text = { androidx.compose.material3.Text("Auto Quality") }, leadingIcon = { androidx.compose.material3.Icon(Icons.Rounded.Refresh, null, Modifier.size(20.dp)) }, onClick = { menuOpen = false; TvPlayerActivity.createIntentForSpatialItem(context, i, maxBitrate = 0L)?.let(context::startActivity) })
                                if (trailerUrl != null) DropdownMenuItem(text = { androidx.compose.material3.Text("Trailer") }, leadingIcon = { androidx.compose.material3.Icon(Icons.Rounded.Tv, null, Modifier.size(20.dp)) }, onClick = { menuOpen = false; TvPlayerActivity.createIntent(context, i.id, kind, startFromBeginning = true, trailer = true)?.let(context::startActivity) })
                                if (i is SpatialFinEpisode) {
                                    DropdownMenuItem(text = { androidx.compose.material3.Text("Series") }, leadingIcon = { androidx.compose.material3.Icon(Icons.Rounded.Tv, null, Modifier.size(20.dp)) }, onClick = { menuOpen = false; onOpenShow(i.seriesId) })
                                    DropdownMenuItem(text = { androidx.compose.material3.Text("Season") }, leadingIcon = { androidx.compose.material3.Icon(Icons.AutoMirrored.Rounded.List, null, Modifier.size(20.dp)) }, onClick = { menuOpen = false; onOpenSeason(i.seasonId) })
                                }
                            }
                        }
                    }
                })
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                    contentPadding = PaddingValues(bottom = 48.dp),
                ) {
                    if (state.availableVersions.size > 1) item { TvVersionRow(state.availableVersions, currentId = item.id, onVersionClick = { onOpenItem(it) }) }
                    if (item.chapters.isNotEmpty()) item { TvChaptersRow(item.chapters) { TvPlayerActivity.createIntentForSpatialItem(context, item, startPositionMs = it.startPosition)?.let(context::startActivity) } }
                    if (state.people.isNotEmpty()) item { TvCastRow(state.people, onOpenPerson) }
                }
            }
        }
    }
}

@Composable
private fun TvShowScreen(showId: UUID?, onBack: () -> Unit, onOpenSeason: (UUID) -> Unit, onOpenEpisode: (UUID) -> Unit, onOpenPerson: (UUID) -> Unit, viewModel: TvShowViewModel = hiltViewModel()) {
    if (showId == null) { TvPlaceholderScreen("Series unavailable", "No series selected."); return }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val playFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(showId) { viewModel.load(showId) }
    LaunchedEffect(state.show?.id) {
        if (state.show != null) {
            runCatching { playFocus.requestFocus() }
        }
    }
    when {
        state.isLoading -> TvPlaceholderScreen("Loading series", "Fetching seasons...")
        state.error != null -> TvPlaceholderScreen("Error", state.error?.localizedMessage ?: "Unknown")
        state.show == null -> TvPlaceholderScreen("Series unavailable", "Not found.")
        else -> {
            val show = state.show!!
            val onFocusBackground = LocalFocusedBackground.current
            LaunchedEffect(show.id) { onFocusBackground(tvBackdropArtwork(show)) }
            val metadata = buildList { getShowDateString(show).takeIf { it.isNotBlank() }?.let(::add); if (state.seasons.isNotEmpty()) add("${state.seasons.size} seasons") }
            Column(modifier = Modifier.fillMaxSize().padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                TvDetailHeroCard(show, "Series", show.genres.take(3).joinToString(" • "), metadata, show.overview, onBack = onBack, actions = {
                    val next = state.nextUp
                    if (next != null) {
                        TvHeroButton(if (next.playbackPositionTicks > 0L) "Resume Episode" else "Play Next", Icons.Rounded.PlayArrow, true, modifier = Modifier.focusRequester(playFocus)) { TvPlayerActivity.createIntentForSpatialItem(context, next)?.let(context::startActivity) }
                    }
                    TvIconHeroButton(if (show.played) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, if (show.played) "Watched" else "Mark watched", show.played) { viewModel.togglePlayed() }
                    TvIconHeroButton(if (show.favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (show.favorite) "Favorited" else "Favorite", show.favorite) { viewModel.toggleFavorite() }
                    val showTrailer = show.trailer
                    if (showTrailer != null) {
                        Box {
                            var menuOpen by remember { mutableStateOf(false) }
                            TvIconHeroButton(Icons.Rounded.MoreVert, "More actions") { menuOpen = true }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(text = { androidx.compose.material3.Text("Trailer") }, leadingIcon = { androidx.compose.material3.Icon(Icons.Rounded.Tv, null, Modifier.size(20.dp)) }, onClick = { menuOpen = false; TvPlayerActivity.createIntent(context, show.id, "Series", startFromBeginning = true, trailer = true)?.let(context::startActivity) })
                            }
                        }
                    }
                })
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                    contentPadding = PaddingValues(bottom = 48.dp),
                ) {
                    state.nextUp?.let { item { TvEpisodeHighlightCard(it) { onOpenEpisode(it.id) } } }
                    if (state.seasons.isNotEmpty()) item { TvSeasonStrip(state.seasons, onOpenSeason) }
                    if (state.people.isNotEmpty()) item { TvCastRow(state.people, onOpenPerson) }
                }
            }
        }
    }
}

@Composable
private fun TvSeasonScreen(seasonId: UUID?, onBack: () -> Unit, onOpenEpisode: (UUID) -> Unit, viewModel: TvSeasonViewModel = hiltViewModel()) {
    if (seasonId == null) { TvPlaceholderScreen("Season unavailable", "No season selected."); return }
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(seasonId) { viewModel.load(seasonId) }
    LazyVerticalGrid(
        columns = GridCells.Fixed(5),
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(top = 64.dp, bottom = 48.dp),
    ) {
        item(span = { GridItemSpan(5) }) { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { TvHeroButton("Back", Icons.AutoMirrored.Rounded.ArrowBack, false, onClick = onBack) } }
        if (state.episodes.isEmpty()) item(span = { GridItemSpan(5) }) { TvPlaceholderScreen("No episodes", "Empty season.") }
        else gridItems(state.episodes, key = { it.id }) { TvMediaCard(it, false, Modifier.fillMaxWidth(), { onOpenEpisode(it.id) }) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TvDetailHeroCard(item: SpatialFinItem, eyebrow: String, supportingLine: String?, metadata: List<String>, overview: String, onBack: (() -> Unit)? = null, actions: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(30.dp)).background(Color(0x77131A24))) {
        AsyncImage(model = tvBackdropArtwork(item), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = 0.55f)
        Box(modifier = Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color(0xF20C1016), Color(0xB00C1016), Color.Transparent), endX = 1400f)))
        Row(modifier = Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.Top) {
            Box(modifier = Modifier.fillMaxHeight().aspectRatio(2f / 3f).clip(RoundedCornerShape(20.dp)).background(Color(0xFF0F1720))) { AsyncImage(model = tvPrimaryArtwork(item), contentDescription = item.name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            Column(modifier = Modifier.weight(1f).padding(end = 56.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = eyebrow, style = MaterialTheme.typography.labelLarge, color = Color(0xFFD7DEE8))
                Text(text = item.name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                supportingLine?.let { Text(text = it, style = MaterialTheme.typography.titleSmall, color = Color(0xFFE6EBF2), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                if (metadata.isNotEmpty()) { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { metadata.forEach { TvMetadataPill(it) } } }
                if (item.ratings.isNotEmpty()) RatingsRow(item.ratings)
                Text(text = overview, style = MaterialTheme.typography.bodyMedium, color = Color(0xFFD5DCE6), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.weight(1f))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { actions() }
            }
        }
        if (onBack != null) {
            Box(modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)) {
                TvIconHeroButton(icon = Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", onClick = onBack)
            }
        }
    }
}

@Composable
private fun TvStatusCard(title: String, value: String, detail: String, modifier: Modifier = Modifier) {
    Card(onClick = {}, modifier = modifier, colors = CardDefaults.colors(containerColor = Color.White.copy(alpha = 0.05f)), shape = CardDefaults.shape(RoundedCornerShape(24.dp))) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) { Text(title, style = MaterialTheme.typography.labelLarge); Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold); Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2) }
    }
}

@Composable
private fun TvActionTile(title: String, body: String, icon: ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var isFocused by remember { mutableStateOf(false) }
    val glow = MaterialTheme.colorScheme.primary
    Card(modifier = modifier.height(152.dp).onFocusChanged { isFocused = it.isFocused }.ultrachromicFocus(isFocused, RoundedCornerShape(26.dp), glow), onClick = onClick, colors = CardDefaults.colors(containerColor = if (isFocused) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.88f) else Color.White.copy(alpha = 0.05f)), shape = CardDefaults.shape(RoundedCornerShape(26.dp))) {
        Column(modifier = Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(imageVector = icon, contentDescription = null, tint = if (isFocused) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Text(text = title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = if (isFocused) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
            Text(text = body, style = MaterialTheme.typography.bodyMedium, color = if (isFocused) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.86f) else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun TvHomeHeroCard(item: SpatialFinItem, eyebrow: String, parkInitialFocus: Boolean, onDidParkFocus: () -> Unit, onPrimaryAction: () -> Unit, onDetails: () -> Unit) {
    val primaryFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(parkInitialFocus) { if (parkInitialFocus) { runCatching { primaryFocus.requestFocus() }; onDidParkFocus() } }
    Card(onClick = {}, colors = CardDefaults.colors(containerColor = Color(0x77131A24)), shape = CardDefaults.shape(RoundedCornerShape(32.dp))) {
        Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(model = tvBackdropArtwork(item), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = 0.72f)
            Box(modifier = Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color(0xF506111B), Color(0xD006111B), Color.Transparent), endX = 1250f)))
            Column(modifier = Modifier.fillMaxWidth(0.5f).align(Alignment.BottomStart).padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = eyebrow, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.SemiBold)
                item.images.logo?.let { AsyncImage(model = it, contentDescription = null, modifier = Modifier.heightIn(max = 56.dp).fillMaxWidth(0.65f), contentScale = ContentScale.Fit, alignment = Alignment.BottomStart) }
                ?: Text(text = item.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvHeroButton(if (item.playbackPositionTicks > 0L) "Resume" else "Play", Icons.Rounded.PlayArrow, true, modifier = Modifier.focusRequester(primaryFocus), onClick = onPrimaryAction)
                    TvHeroButton("Details", Icons.Rounded.Info, false, onClick = onDetails)
                }
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
    Card(
        modifier = modifier,
        onClick = onClick,
        colors = CardDefaults.colors(containerColor = Color(0x77131A24)),
        shape = CardDefaults.shape(RoundedCornerShape(22.dp)),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(modifier = Modifier.fillMaxWidth().aspectRatio(1.77f)) {
                val img = item.images.showBackdrop ?: item.images.backdrop ?: item.images.primary
                if (img != null) {
                    AsyncImage(
                        model = img,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            item.name.take(1).uppercase(),
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                if (showProgress) {
                    buildPlaybackFraction(item)?.let { fraction ->
                        FloatingProgressBar(
                            progress = fraction,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                                .align(Alignment.BottomCenter),
                            progressColor = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            // minLines = 2 keeps a rail of mixed-length titles aligned — short
            // names reserve the same height as wrapped ones so the row lands
            // on a single baseline instead of jittering per card.
            Text(
                item.name,
                Modifier.padding(8.dp),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TvLibraryCard(view: View, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(modifier = modifier.width(320.dp).height(188.dp), onClick = onClick, colors = CardDefaults.colors(containerColor = Color(0x77131A24)), shape = CardDefaults.shape(RoundedCornerShape(24.dp))) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomStart) {
            AsyncImage(model = tvViewArtwork(view), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = 0.72f)
            Box(modifier = Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Color(0xDD08121B)))))
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { Text(view.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = Color.White); Text("${view.items.size} titles", style = MaterialTheme.typography.bodyMedium, color = Color(0xFFD8E3EF)) }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvOnboardingHero(companionReady: Boolean, onOpenCompanion: () -> Unit) {
    Carousel(itemCount = 3, modifier = Modifier.fillMaxWidth().height(420.dp)) { index ->
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.radialGradient(listOf(Color(0x664FC3F7), Color(0x331A2A3A), Color.Transparent), radius = 1200f)))
            Column(Modifier.fillMaxSize().padding(48.dp), verticalArrangement = Arrangement.Bottom) {
                Text("Welcome to SpatialFin", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(Modifier.height(24.dp))
                Button(onClick = onOpenCompanion) { Text(if (companionReady) "Open companion" else "Pair companion") }
            }
        }
    }
}

@Composable
private fun TvCastRow(actors: List<dev.jdtech.jellyfin.models.SpatialFinItemPerson>, onActorClick: (UUID) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Cast & Crew", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) { items(actors, key = { it.id }) { TvCastCard(it) { onActorClick(it.id) } } }
    }
}

@Composable
private fun TvCastCard(person: dev.jdtech.jellyfin.models.SpatialFinItemPerson, onClick: () -> Unit) {
    var isFocused by remember { mutableStateOf(false) }
    Column(Modifier.width(140.dp).onFocusChanged { isFocused = it.isFocused }.clickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(140.dp).clip(RoundedCornerShape(999.dp)).background(Color(0xFF1A2433)).border(if (isFocused) 2.dp else 0.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(999.dp))) {
            if (person.image.uri != null) AsyncImage(person.image.uri, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Text(person.name.take(1), Modifier.align(Alignment.Center), style = MaterialTheme.typography.headlineMedium, color = Color.White)
        }
        Text(person.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color.White)
    }
}

@Composable
private fun TvChaptersRow(chapters: List<dev.jdtech.jellyfin.models.SpatialFinChapter>, onChapterClick: (dev.jdtech.jellyfin.models.SpatialFinChapter) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Chapters", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) { items(chapters, key = { it.startPosition }) { TvChapterCard(it) { onChapterClick(it) } } }
    }
}

@Composable
private fun TvChapterCard(chapter: dev.jdtech.jellyfin.models.SpatialFinChapter, onClick: () -> Unit) {
    var isFocused by remember { mutableStateOf(false) }
    Column(Modifier.width(240.dp).onFocusChanged { isFocused = it.isFocused }.clickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1.77f).clip(RoundedCornerShape(16.dp)).background(Color(0xFF161D28)).border(if (isFocused) 2.dp else 0.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp))) {
            if (chapter.imageUri != null) AsyncImage(chapter.imageUri, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Text(tvFormatChapterTime(chapter.startPosition), Modifier.align(Alignment.BottomStart).padding(6.dp).background(Color.Black.copy(0.5f), RoundedCornerShape(4.dp)).padding(4.dp), color = Color.White, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun TvSeasonStrip(seasons: List<SpatialFinSeason>, onOpenSeason: (UUID) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Seasons", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        @OptIn(ExperimentalLayoutApi::class) FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) { seasons.forEach { s -> TvSeasonCard(s) { onOpenSeason(s.id) } } }
    }
}

@Composable
private fun TvSeasonCard(season: SpatialFinSeason, onClick: () -> Unit) {
    var isFocused by remember { mutableStateOf(false) }
    Card(onClick = onClick, modifier = Modifier.width(250.dp).height(132.dp).onFocusChanged { isFocused = it.isFocused }, colors = CardDefaults.colors(containerColor = Color(0x77131A24)), shape = CardDefaults.shape(RoundedCornerShape(22.dp))) {
        Row(Modifier.fillMaxSize().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Card(onClick = {}, Modifier.width(74.dp).fillMaxHeight(), shape = CardDefaults.shape(RoundedCornerShape(16.dp))) { AsyncImage(tvPrimaryArtwork(season), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            Column { Text(tvSeasonLabel(season), fontWeight = FontWeight.SemiBold); Text(season.seriesName, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun TvEpisodeHighlightCard(episode: SpatialFinEpisode, onClick: () -> Unit) {
    var isFocused by remember { mutableStateOf(false) }
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().height(176.dp).onFocusChanged { isFocused = it.isFocused }, colors = CardDefaults.colors(containerColor = Color(0x77131A24)), shape = CardDefaults.shape(RoundedCornerShape(24.dp))) {
        Row(Modifier.fillMaxSize()) {
            AsyncImage(tvBackdropArtwork(episode), null, Modifier.width(280.dp).fillMaxHeight(), contentScale = ContentScale.Crop)
            Column(Modifier.padding(22.dp)) { Text("Next Up", color = MaterialTheme.colorScheme.primary); Text(episode.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
private fun TvUserCard(name: String, role: String, avatarUri: android.net.Uri?, onClick: () -> Unit) {
    var isFocused by remember { mutableStateOf(false) }
    Column(Modifier.width(180.dp).onFocusChanged { isFocused = it.isFocused }.clickable(onClick = onClick).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(128.dp).clip(RoundedCornerShape(999.dp)).background(Color(0xFF1A2433)).border(if (isFocused) 2.dp else 0.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(999.dp))) {
            if (avatarUri != null) AsyncImage(avatarUri, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Text(name.take(1).uppercase(), Modifier.align(Alignment.Center), style = MaterialTheme.typography.headlineMedium, color = Color.White)
        }
        Text(name, fontWeight = FontWeight.SemiBold); Text(role, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun TvMetadataPill(text: String) { MetadataPill { Text(text, style = MaterialTheme.typography.labelMedium, color = Color.White) } }

@Composable
private fun TvPageHeaderCard(title: String, body: String) {
    Card(onClick = {}, colors = CardDefaults.colors(containerColor = Color.White.copy(0.04f)), shape = CardDefaults.shape(RoundedCornerShape(30.dp))) {
        Column(Modifier.fillMaxWidth().padding(28.dp)) { Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold); Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
internal fun TvPlaceholderScreen(title: String, body: String) {
    Surface(modifier = Modifier.fillMaxWidth(), colors = SurfaceDefaults.colors(containerColor = Color(0x77131A24)), shape = RoundedCornerShape(30.dp)) {
        Column(Modifier.fillMaxWidth().padding(32.dp)) { Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvHeroButton(label: String, icon: ImageVector? = null, primary: Boolean = true, selected: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val baseContainer = if (primary || selected) Color.White.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.16f)
    val baseContent = if (primary || selected) Color.Black else Color.White
    Button(
        onClick = onClick,
        modifier = modifier,
        shape = ButtonDefaults.shape(shape = RoundedCornerShape(999.dp)),
        colors = ButtonDefaults.colors(
            containerColor = baseContainer,
            contentColor = baseContent,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
            pressedContainerColor = Color.White,
            pressedContentColor = Color.Black,
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvHeroButton(label: String, icon: Painter, primary: Boolean = true, selected: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val baseContainer = if (primary || selected) Color.White.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.16f)
    val baseContent = if (primary || selected) Color.Black else Color.White
    Button(
        onClick = onClick,
        modifier = modifier,
        shape = ButtonDefaults.shape(shape = RoundedCornerShape(999.dp)),
        colors = ButtonDefaults.colors(
            containerColor = baseContainer,
            contentColor = baseContent,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
            pressedContainerColor = Color.White,
            pressedContentColor = Color.Black,
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Icon(icon, null, Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvIconHeroButton(icon: ImageVector, contentDescription: String, selected: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val baseContainer = if (selected) Color.White.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.16f)
    val baseContent = if (selected) Color.Black else Color.White
    Button(
        onClick = onClick,
        modifier = modifier,
        shape = ButtonDefaults.shape(shape = RoundedCornerShape(999.dp)),
        colors = ButtonDefaults.colors(
            containerColor = baseContainer,
            contentColor = baseContent,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
            pressedContainerColor = Color.White,
            pressedContentColor = Color.Black,
        ),
        contentPadding = PaddingValues(10.dp),
    ) {
        Icon(icon, contentDescription, Modifier.size(18.dp))
    }
}

@Composable
private fun TvVersionRow(versions: List<SpatialFinMovie>, currentId: UUID, onVersionClick: (SpatialFinMovie) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Other Versions", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(versions, key = { it.id }) { version ->
                TvVersionCard(version, selected = version.id == currentId) { onVersionClick(version) }
            }
        }
    }
}

@Composable
private fun TvVersionCard(version: SpatialFinMovie, selected: Boolean, onClick: () -> Unit) {
    var isFocused by remember { mutableStateOf(false) }
    val borderColor = if (isFocused) MaterialTheme.colorScheme.primary else if (selected) MaterialTheme.colorScheme.secondary else Color.Transparent
    val borderWidth = if (isFocused || selected) 2.dp else 0.dp
    
    Card(
        onClick = onClick,
        modifier = Modifier
            .width(200.dp)
            .height(100.dp)
            .onFocusChanged { isFocused = it.isFocused }
            .border(borderWidth, borderColor, RoundedCornerShape(18.dp)),
        colors = CardDefaults.colors(containerColor = if (selected) Color(0xAA1A2433) else Color(0x77131A24)),
        shape = CardDefaults.shape(RoundedCornerShape(18.dp))
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = version.versionChipLabel(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                color = if (selected) MaterialTheme.colorScheme.primary else Color.White
            )
            if (selected) {
                Text(
                    text = "Current",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
    }
}

private fun Modifier.ultrachromicFocus(focused: Boolean, shape: androidx.compose.ui.graphics.Shape, glow: Color): Modifier = this.border(if (focused) 3.dp else 1.dp, if (focused) glow else glow.copy(alpha = 0.18f), shape)

private fun tvViewArtwork(view: View): Any? = view.items.firstOrNull()?.let { it.images.backdrop ?: it.images.primary ?: it.images.showBackdrop ?: it.images.showPrimary }
private fun buildPlaybackFraction(item: SpatialFinItem): Float? { val r = item.runtimeTicks; val p = item.playbackPositionTicks; return if (r > 0 && p > 0) (p.toFloat()/r).coerceIn(0f,1f) else null }
private fun tvItemLabel(item: SpatialFinItem): String = when(item){is SpatialFinMovie->"Movie";is SpatialFinEpisode->"Episode";is SpatialFinSeason->"Season";is SpatialFinShow->"Series";is SpatialFinCollection->"Library";else->"Item"}
private fun tvPrimaryArtwork(item: SpatialFinItem): Any? = when(item){is SpatialFinEpisode->item.images.showPrimary?:item.images.primary?:item.images.showBackdrop?:item.images.backdrop;else->item.images.primary?:item.images.showPrimary?:item.images.backdrop?:item.images.showBackdrop}
private fun tvBackdropArtwork(item: SpatialFinItem): Any? = when(item){is SpatialFinEpisode->item.images.showBackdrop?:item.images.backdrop?:item.images.showPrimary?:item.images.primary;else->item.images.backdrop?:item.images.showBackdrop?:item.images.primary?:item.images.showPrimary}
private fun tvRuntimeLabel(ticks: Long): String? = ticks.takeIf { it > 0 }?.div(600000000)?.takeIf { it > 0 }?.let { "$it min" }
private fun tvSeasonLabel(s: SpatialFinSeason): String = if (s.indexNumber > 0) "Season ${s.indexNumber}" else s.name.ifBlank { "Season" }
private fun tvEpisodeLabel(e: SpatialFinEpisode): String = buildString { if (e.parentIndexNumber > 0) append("S${e.parentIndexNumber}"); if (e.indexNumber > 0) append("E${e.indexNumber}"); if (isEmpty()) append("Episode") }
private fun tvFormatChapterTime(ms: Long): String { val s = ms/1000; val h = s/3600; val m = (s%3600)/60; val sec = s%60; return if (h>0) "%d:%02d:%02d".format(h,m,sec) else "%d:%02d".format(m,sec) }
private fun tvCompanionConfigured(p: AppPreferences): Boolean = p.getValue(p.companionUrl).isNotBlank() && p.getValue(p.companionToken).isNotBlank()
