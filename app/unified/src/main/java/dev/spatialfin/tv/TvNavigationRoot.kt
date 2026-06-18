package dev.spatialfin.tv

import dev.spatialfin.R
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.automirrored.rounded.ManageSearch
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MusicNote
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
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
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Carousel
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.NavigationDrawer
import androidx.tv.material3.NavigationDrawerItem
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.skydoves.compose.stability.runtime.TraceRecomposition
import dev.jdtech.jellyfin.core.presentation.components.userPrimaryImageUri
import dev.jdtech.jellyfin.core.presentation.components.FloatingProgressBar
import dev.jdtech.jellyfin.core.presentation.components.MetadataPill
import dev.jdtech.jellyfin.film.presentation.home.HomeState
import dev.jdtech.jellyfin.film.presentation.home.HomeViewModel
import dev.jdtech.jellyfin.models.CollectionType
import dev.jdtech.jellyfin.models.HomeItem
import dev.jdtech.jellyfin.models.SpatialFinAudioBook
import dev.jdtech.jellyfin.models.SpatialFinAudioTrack
import dev.jdtech.jellyfin.models.SpatialFinBoxSet
import dev.jdtech.jellyfin.models.SpatialFinCollection
import dev.jdtech.jellyfin.models.SpatialFinEpisode
import dev.jdtech.jellyfin.models.SpatialFinFolder
import dev.jdtech.jellyfin.models.SpatialFinItem
import dev.jdtech.jellyfin.models.SpatialFinMusicAlbum
import dev.jdtech.jellyfin.models.SpatialFinMusicArtist
import dev.jdtech.jellyfin.models.SpatialFinMovie
import dev.jdtech.jellyfin.models.SpatialFinPlaylist
import dev.jdtech.jellyfin.models.SpatialFinSeason
import dev.jdtech.jellyfin.models.SpatialFinShow
import dev.jdtech.jellyfin.models.View
import dev.jdtech.jellyfin.models.isDownloaded
import dev.jdtech.jellyfin.models.toAudioQueueItem
import dev.jdtech.jellyfin.models.versionChipLabel
import dev.jdtech.jellyfin.plugins.model.UniversalSpatialFinItem
import dev.jdtech.jellyfin.player.tv.TvPlayerActivity
import dev.jdtech.jellyfin.presentation.film.components.RatingsRow
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.utils.getShowDateString
import dev.jdtech.jellyfin.viewmodels.MainState
import dev.spatialfin.unified.audio.JellyfinAudioDetailScreen
import dev.spatialfin.unified.audio.JellyfinAudioDetailType
import dev.spatialfin.unified.audio.JellyfinAudioLibraryScreen
import dev.spatialfin.unified.audio.JellyfinAudioMiniPlayer
import dev.spatialfin.unified.audio.JellyfinAudioNowPlayingScreen
import dev.spatialfin.unified.audio.LocalAudioPlaybackDispatcher
import java.util.UUID

private enum class TvRoute {
    Home, Search, Library, Plugins, PluginBrowse, Detail, Show, Season, Person, Companion, Settings, Users,
    /** Music Assistant search + detail (Phase 2). */
    MaSearch, MaDetail,
    /** Native Jellyfin albums/artists/playlists/books. */
    AudioDetail,
}

private data class TvNavItem(val route: TvRoute, val label: String, val icon: ImageVector)

private val tvNavItems = listOf(
    TvNavItem(TvRoute.Home, "Home", Icons.Rounded.Home),
    TvNavItem(TvRoute.Search, "Search", Icons.AutoMirrored.Rounded.ManageSearch),
    TvNavItem(TvRoute.MaSearch, "Music", Icons.Rounded.MusicNote),
    TvNavItem(TvRoute.Library, "Libraries", Icons.Rounded.LiveTv),
    TvNavItem(TvRoute.Plugins, "Sources", Icons.Rounded.Apps),
    TvNavItem(TvRoute.Companion, "Companion", Icons.Rounded.Link),
    TvNavItem(TvRoute.Settings, "Settings", Icons.Rounded.Settings),
)

val LocalFocusedBackground = compositionLocalOf<(Any?) -> Unit> { {} }

private val AUDIO_COLLECTION_TYPES =
    setOf(CollectionType.Music, CollectionType.Playlists, CollectionType.Books)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
@TraceRecomposition(tag = "tv-shell", threshold = 3)
fun TvNavigationRoot(
    state: MainState,
    appPreferences: AppPreferences,
    maSession: dev.jdtech.jellyfin.data.musicassistant.repository.MaSessionRepository,
    maServiceClient: dev.jdtech.jellyfin.data.musicassistant.api.ServiceClient,
    onFinishApp: () -> Unit = {},
    initialSearchQuery: String? = null,
    maDeepLink: kotlinx.coroutines.flow.StateFlow<dev.jdtech.jellyfin.deeplink.MaDeepLink.Parsed?>? = null,
    onMaDeepLinkHandled: () -> Unit = {},
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
    var selectedAudioItemId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedAudioTitle by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedAudioDetailType by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedAudioParentId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedPluginId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedPluginRowId by rememberSaveable { mutableStateOf<String?>(null) }
    var focusedBackgroundUrl by remember { mutableStateOf<Any?>(null) }

    // Make the MA session track local SendSpin playback → one unified player.
    dev.spatialfin.unified.music.MaLocalPlaybackBridge(maSession)

    // Shared MA play dispatcher — drives home-row taps (music/audiobooks/
    // podcasts play on tap, not "open detail") and the player picker sheet.
    val tvAppContext = LocalContext.current
    val maDispatcher = remember(tvAppContext, maSession, maServiceClient) {
        dev.spatialfin.unified.MaPlayDispatcher(
            context = tvAppContext,
            session = maSession,
            serviceClient = maServiceClient,
        )
    }
    val jellyfinAudioDispatcher = LocalAudioPlaybackDispatcher.current

    LaunchedEffect(Unit) { homeViewModel.loadData() }

    LaunchedEffect(initialSearchQuery) {
        val query = initialSearchQuery?.trim().orEmpty()
        if (query.isNotEmpty()) {
            searchViewModel.setQuery(query)
            searchViewModel.search()
            navigate(TvRoute.Search)
        }
    }

    // MA detail nav state — hoisted above openItem so a home-row tap on a
    // podcast/audiobook can route into the detail screen.
    var maDetailSeed by remember {
        mutableStateOf<dev.jdtech.jellyfin.data.musicassistant.data.model.server.ServerMediaItem?>(null)
    }
    var maDetailKind by remember {
        mutableStateOf<dev.spatialfin.unified.music.MaDetailViewModel.DetailKind?>(null)
    }
    fun openMaBrowse(target: dev.spatialfin.unified.music.MaBrowseTarget) {
        maDetailSeed = target.item
        maDetailKind = target.detailKind
        navigate(TvRoute.MaDetail)
    }

    fun openJellyfinAudioDetail(
        itemId: UUID,
        title: String,
        detailType: JellyfinAudioDetailType,
        parentId: UUID? = null,
    ) {
        selectedAudioItemId = itemId.toString()
        selectedAudioTitle = title
        selectedAudioDetailType = detailType.name
        selectedAudioParentId = parentId?.toString()
        navigate(TvRoute.AudioDetail)
    }

    fun openPluginBrowse(pluginId: String, rowId: String?) {
        selectedPluginId = pluginId
        selectedPluginRowId = rowId
        navigate(TvRoute.PluginBrowse)
    }

    fun openItem(item: SpatialFinItem) {
        when (item) {
            is UniversalSpatialFinItem -> {
                tvAppContext.startActivity(
                    TvPlayerActivity.createIntentForUniversalMedia(
                        context = tvAppContext,
                        pluginId = item.universalMediaItem.pluginId,
                        itemId = item.universalMediaItem.id,
                        videoUrl = item.universalMediaItem.videoUrl,
                        title = item.name,
                    )
                )
            }
            is SpatialFinShow -> { selectedShowId = item.id.toString(); navigate(TvRoute.Show) }
            is SpatialFinSeason -> { selectedSeasonId = item.id.toString(); navigate(TvRoute.Season) }
            is SpatialFinCollection -> Unit
            is SpatialFinMusicAlbum ->
                openJellyfinAudioDetail(item.id, item.name, JellyfinAudioDetailType.Album)
            is SpatialFinMusicArtist ->
                openJellyfinAudioDetail(item.id, item.name, JellyfinAudioDetailType.Artist)
            is SpatialFinPlaylist ->
                openJellyfinAudioDetail(item.id, item.name, JellyfinAudioDetailType.Playlist)
            is SpatialFinAudioBook ->
                openJellyfinAudioDetail(item.id, item.name, JellyfinAudioDetailType.Book)
            is SpatialFinAudioTrack ->
                jellyfinAudioDispatcher?.playQueue(listOf(item.toAudioQueueItem()))
            else -> {
                // MA library items carry their URI in originalTitle — tap = play,
                // not open-Jellyfin-detail (which would 404 on an MA uri).
                val maUri = item.originalTitle
                if (!maUri.isNullOrBlank() && maUri.contains("://")) {
                    // Podcasts/audiobooks open a detail screen; everything else plays.
                    val target = dev.spatialfin.unified.music.maDetailTargetForUri(maUri, item.name)
                    if (target != null) {
                        openMaBrowse(target)
                        return
                    }
                    maDispatcher.playUri(maUri, title = item.name, artworkUrl = item.images.primary?.toString())
                } else {
                    selectedItemId = item.id.toString(); navigate(TvRoute.Detail)
                }
            }
        }
    }

    CompositionLocalProvider(
        LocalFocusedBackground provides { focusedBackgroundUrl = it },
        dev.spatialfin.unified.LocalMaPlayDispatcher provides maDispatcher,
    ) {
        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            TvAmbientBackground(backgroundModel = focusedBackgroundUrl)
            val showTopBar = currentRoute in listOf(TvRoute.Home, TvRoute.Search, TvRoute.MaSearch, TvRoute.Library, TvRoute.Plugins, TvRoute.Companion, TvRoute.Settings)
            var showUserSwitcher by remember { mutableStateOf(false) }

            Column(modifier = Modifier.fillMaxSize()) {
                if (showTopBar) {
                    val visibleNavItems = if (state.isOfflineMode) tvNavItems.filter { it.route != TvRoute.Home && it.route != TvRoute.Search } else tvNavItems
                    TvTopBar(
                        currentRouteName = currentRoute.name,
                        navItems = visibleNavItems.map { it.route.name to (it.label to it.icon) },
                        onNavigate = { routeName ->
                            val itemRoute = TvRoute.valueOf(routeName)
                            if (itemRoute == TvRoute.Library) selectedView = null
                            navigate(itemRoute)
                        },
                        user = state.currentUser,
                        serverName = homeState.server?.name,
                        serverAddress = state.currentServerAddress,
                        onUserClick = { showUserSwitcher = true }
                    )
                }

                val contentFocusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
                LaunchedEffect(currentRoute) {
                    kotlinx.coroutines.delay(50)
                    try { contentFocusRequester.requestFocus() } catch (e: Exception) {}
                }
                Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 48.dp).focusRequester(contentFocusRequester).focusGroup()) {
                    when (currentRoute) {
                        TvRoute.Home -> TvHomeScreen(homeState, state, appPreferences, { selectedView = it; navigate(TvRoute.Library) }, ::openItem, ::openPluginBrowse, { navigate(TvRoute.Companion) }, { navigate(TvRoute.Search) }, { homeViewModel.onAction(dev.jdtech.jellyfin.film.presentation.home.HomeAction.OnRetryClick) })
                        TvRoute.Search -> TvSearchScreen(::openItem)
                        TvRoute.Library -> {
                            val view = selectedView
                            if (view != null && view.type in AUDIO_COLLECTION_TYPES) {
                                JellyfinAudioLibraryScreen(
                                    libraryId = view.id,
                                    libraryName = view.name,
                                    libraryType = view.type,
                                    onBack = { popBack() },
                                    onDetailClick = { id, title, detailType ->
                                        openJellyfinAudioDetail(
                                            itemId = id,
                                            title = title,
                                            detailType = detailType,
                                            parentId = view.id,
                                        )
                                    },
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else {
                                TvLibraryScreen(
                                    selectedView,
                                    homeState.views.map { it.view },
                                    { popBack() },
                                    { selectedView = it },
                                    ::openItem,
                                )
                            }
                        }
                        TvRoute.Plugins -> TvSourcesScreen(
                            sections = homeState.universalPluginSections,
                            onBackToHome = { navigate(TvRoute.Home) },
                            onOpenItem = ::openItem,
                            onOpenPluginBrowse = ::openPluginBrowse,
                        )
                        TvRoute.PluginBrowse -> {
                            val pluginId = selectedPluginId
                            if (pluginId == null) {
                                popBack()
                            } else {
                                dev.jdtech.jellyfin.plugins.ui.PluginBrowseScreen(
                                    pluginId = pluginId,
                                    rowId = selectedPluginRowId,
                                    onBack = { popBack() },
                                    onItemClick = ::openItem,
                                )
                            }
                        }
                        TvRoute.Detail -> TvItemDetailScreen(selectedItemId?.let(UUID::fromString), { popBack() }, ::openItem, { selectedPersonId = it.toString(); navigate(TvRoute.Person) }, { selectedShowId = it.toString(); navigate(TvRoute.Show) }, { selectedSeasonId = it.toString(); navigate(TvRoute.Season) })
                        TvRoute.Show -> TvShowScreen(selectedShowId?.let(UUID::fromString), { popBack() }, { selectedSeasonId = it.toString(); navigate(TvRoute.Season) }, { selectedItemId = it.toString(); navigate(TvRoute.Detail) }, { selectedPersonId = it.toString(); navigate(TvRoute.Person) })
                        TvRoute.Season -> TvSeasonScreen(selectedSeasonId?.let(UUID::fromString), { popBack() }, { selectedItemId = it.toString(); navigate(TvRoute.Detail) })
                        TvRoute.Person -> selectedPersonId?.let(UUID::fromString)?.let { pid -> dev.jdtech.jellyfin.presentation.film.PersonScreen(pid, { popBack() }, { navigate(TvRoute.Home) }, ::openItem) } ?: popBack()
                        TvRoute.Settings -> TvSettingsScreen(state, appPreferences, homeState.server?.name, { navigate(TvRoute.Companion) }, { navigate(TvRoute.Search) }, { navigate(TvRoute.Users) })
                        TvRoute.Users -> TvUsersScreen({ popBack() }, { navigate(TvRoute.Home) })
                        TvRoute.Companion -> TvCompanionScreen(onBack = { popBack() }, onFinishApp = onFinishApp)
                        TvRoute.MaSearch -> dev.spatialfin.unified.music.MaSearchScreen(
                            onBack = { popBack() },
                            onOpenItem = ::openMaBrowse,
                        )
                        TvRoute.MaDetail -> {
                            val seed = maDetailSeed
                            val kind = maDetailKind
                            if (seed == null || kind == null) {
                                popBack()
                            } else {
                                dev.spatialfin.unified.music.MaDetailScreen(
                                    seed = seed,
                                    kind = kind,
                                    onBack = { popBack() },
                                    onOpenItem = ::openMaBrowse,
                                )
                            }
                        }
                        TvRoute.AudioDetail -> {
                            val itemId = selectedAudioItemId?.let(UUID::fromString)
                            val title = selectedAudioTitle
                            val detailType =
                                selectedAudioDetailType?.let(JellyfinAudioDetailType::valueOf)
                            if (itemId == null || title == null || detailType == null) {
                                popBack()
                            } else {
                                JellyfinAudioDetailScreen(
                                    itemId = itemId,
                                    title = title,
                                    detailType = detailType,
                                    parentId = selectedAudioParentId?.let(UUID::fromString),
                                    onBack = { popBack() },
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }
                }
            }

            if (showUserSwitcher) {
                TvUserSwitcherDialog(
                    state = state,
                    onDismissRequest = { showUserSwitcher = false },
                    onUserSwitched = { showUserSwitcher = false; navigate(TvRoute.Home) },
                    onAddUser = { showUserSwitcher = false; navigate(TvRoute.Users) }
                )
            }

            val maNowPlayingTv by maSession.session.collectAsStateWithLifecycle()
            // pendingPlayUri covers the optimistic window after an MA play tap,
            // before MA echoes nowPlaying.
            val maPresentsPlayback =
                maNowPlayingTv.nowPlaying != null || maNowPlayingTv.pendingPlayUri != null
            var showNowPlaying by remember { mutableStateOf(false) }
            var showJellyfinAudioNowPlaying by remember { mutableStateOf(false) }
            // Bottom-of-screen MA mini-player. D-pad reaches it after the
            // last focusable row in the active screen.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
            ) {
                Column {
                    // Network remote: control any other SpatialFin instance playing on the LAN.
                    dev.spatialfin.unified.RemoteControlMiniPlayerHost()
                    dev.spatialfin.unified.music.MaMiniPlayer(
                        session = maSession,
                        onExpand = { showNowPlaying = true },
                    )
                    jellyfinAudioDispatcher?.let { dispatcher ->
                        JellyfinAudioMiniPlayer(
                            dispatcher = dispatcher,
                            onExpand = { showJellyfinAudioNowPlaying = true },
                            suppressed = maPresentsPlayback,
                        )
                    }
                }
            }
            var showPickerSheet by remember { mutableStateOf(false) }
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
                    onPickPlayer = { showPickerSheet = true },
                    onOpenSearch = {
                        showNowPlaying = false
                        navigate(TvRoute.MaSearch)
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
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (showPickerSheet) {
                val sendspinState by dev.jdtech.jellyfin.sendspin.receiver.SendspinReceiverSession
                    .state.collectAsStateWithLifecycle()
                dev.spatialfin.unified.music.MaPlayerPickerSheet(
                    session = maSession,
                    dispatcher = maDispatcher,
                    onDismiss = { showPickerSheet = false },
                )
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
private fun TvHomeScreen(
    homeState: HomeState,
    state: MainState,
    appPreferences: AppPreferences,
    onOpenLibrary: (View) -> Unit,
    onOpenItem: (SpatialFinItem) -> Unit,
    onOpenPluginBrowse: (String, String?) -> Unit,
    onOpenCompanion: () -> Unit,
    onOpenSearch: () -> Unit,
    onRefresh: () -> Unit,
) {
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
    val status = homeState.tvStatusModel()
    // portrait = poster browse rows (design); landscape = Continue / Next Up.
    data class ShelfSpec(val title: String, val items: List<SpatialFinItem>, val showProgress: Boolean = false, val portrait: Boolean = false)
    val shelves = buildList {
        homeState.resumeSection?.let { add(ShelfSpec(it.homeSection.name.asString(), it.homeSection.items, showProgress = true)) }
        homeState.nextUpSection?.let { add(ShelfSpec(it.homeSection.name.asString(), it.homeSection.items)) }
        homeState.views.map { it.view }.firstOrNull { it.type == CollectionType.Movies }?.takeIf { it.items.isNotEmpty() }?.let { add(ShelfSpec("Recently added movies", it.items, portrait = true)) }
        homeState.views.map { it.view }.firstOrNull { it.type == CollectionType.TvShows }?.takeIf { it.items.isNotEmpty() }?.let { add(ShelfSpec("Recently added TV", it.items, portrait = true)) }
        // Music Assistant rows (recently played / playlists / recommendations /
        // audiobooks / podcasts) — same data the phone & XR homes show, so MA
        // is a first-class source on TV too. Tapping a card plays it (openItem
        // routes MA uris to the dispatcher).
        homeState.musicAssistantSections.forEach { section ->
            section.homeSection.items.takeIf { it.isNotEmpty() }
                ?.let { add(ShelfSpec(section.homeSection.name.asString(), it)) }
        }
    }
    androidx.compose.foundation.lazy.LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        contentPadding = PaddingValues(top = 64.dp, bottom = 48.dp),
    ) {
        if (featuredItems.isNotEmpty()) item {
            Carousel(itemCount = featuredItems.size, autoScrollDurationMillis = 5000L, modifier = Modifier.fillMaxWidth().height(LocalConfiguration.current.screenHeightDp.dp * 0.46f)) { index ->
                val item = featuredItems[index]
                TvHomeHeroCard(item, featuredEyebrow, !heroFocusParked && index == 0, { heroFocusParked = true }, { onOpenItem(item) }, { onOpenItem(item) })
            }
        }
        status?.let { model ->
            item(key = "tv_status") {
                TvHomeStatusCard(model = model, onReconnect = onRefresh)
            }
        }
        if (homeState.isLoading) item { TvPlaceholderScreen("Loading your room", "Fetching Continue Watching, Next Up, suggestions, and library rails from Jellyfin.") }
        else if (homeState.error != null) {
            item { TvPlaceholderScreen("Home unavailable", homeState.error?.message ?: "Failed to load TV home content.") }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    TvActionTile("Search library", "Jump straight into direct Jellyfin search from the TV.", Icons.AutoMirrored.Rounded.ManageSearch, Modifier.weight(1f), onOpenSearch)
                    TvActionTile("Companion setup", "Open QR pairing and import settings from your phone companion.", Icons.Rounded.Link, Modifier.weight(1f), onOpenCompanion)
                    TvActionTile("Refresh home", "Retry the Jellyfin home request for this TV.", Icons.Rounded.Home, Modifier.weight(1f), onRefresh)
                }
            }
        } else {
            items(shelves, key = { it.title }) { shelf -> TvContentShelf(shelf.title, shelf.items, shelf.showProgress, onOpenItem, portrait = shelf.portrait) }
            items(homeState.offlineLibrarySections, key = { it.id }) { section ->
                TvContentShelf(
                    title = section.homeSection.name.asString(),
                    items = section.homeSection.items,
                    showProgress = true,
                    onOpenItem = onOpenItem,
                )
            }
            items(homeState.universalPluginSections, key = { it.id }) { section ->
                val firstItem = section.homeSection.items.firstOrNull() as? UniversalSpatialFinItem
                val pluginId = firstItem?.universalMediaItem?.pluginId
                val rowId = firstItem?.universalMediaItem?.homeRowId
                TvContentShelf(
                    title = section.homeSection.name.asString(),
                    items = section.homeSection.items,
                    showProgress = false,
                    onOpenItem = onOpenItem,
                    actionLabel = if (pluginId != null) "See all" else null,
                    onAction = if (pluginId != null) {
                        { onOpenPluginBrowse(pluginId, rowId) }
                    } else {
                        null
                    },
                )
            }
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
private fun TvSourcesScreen(
    sections: List<HomeItem.Section>,
    onBackToHome: () -> Unit,
    onOpenItem: (SpatialFinItem) -> Unit,
    onOpenPluginBrowse: (String, String?) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(22.dp),
        contentPadding = PaddingValues(top = 64.dp, bottom = 48.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvHeroButton("Back home", Icons.Rounded.Home, false, onClick = onBackToHome)
            }
        }
        item {
            TvPageHeaderCard(
                title = "Sources",
                body = "Browse universal source plugins and rows from your connected services.",
            )
        }
        if (sections.isEmpty()) {
            item {
                TvPlaceholderScreen(
                    "No sources yet",
                    "Install or enable a universal source plugin to show rows here.",
                )
            }
        } else {
            items(sections, key = { it.id }) { section ->
                val firstItem = section.homeSection.items.firstOrNull() as? UniversalSpatialFinItem
                val pluginId = firstItem?.universalMediaItem?.pluginId
                val rowId = firstItem?.universalMediaItem?.homeRowId
                TvContentShelf(
                    title = section.homeSection.name.asString(),
                    items = section.homeSection.items,
                    onOpenItem = onOpenItem,
                    actionLabel = if (pluginId != null) "See all" else null,
                    onAction = if (pluginId != null) {
                        { onOpenPluginBrowse(pluginId, rowId) }
                    } else {
                        null
                    },
                )
            }
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

private data class TvHomeStatusModel(
    val title: String,
    val body: String,
    val detail: String? = null,
    val showReconnect: Boolean = false,
)

private fun HomeState.tvStatusModel(): TvHomeStatusModel? {
    val syncSummary =
        when {
            syncStatus.isRunning ->
                "Syncing ${syncStatus.runningChangeCount.coerceAtLeast(syncStatus.pendingChanges)} changes"
            syncStatus.pendingChanges > 0 && !syncStatus.lastErrorMessage.isNullOrBlank() ->
                "Sync needs attention · ${syncStatus.pendingChanges} pending"
            syncStatus.pendingChanges > 0 ->
                "${syncStatus.pendingChanges} changes waiting to sync"
            else -> null
        }

    return when {
        manualOfflineMode ->
            TvHomeStatusModel(
                title = "Offline mode",
                body = "You are browsing downloaded and cached media.",
                detail = syncSummary,
                showReconnect = true,
            )
        isConnectionDegraded ->
            TvHomeStatusModel(
                title = "Reconnecting",
                body = "SpatialFin is using local content while it checks your Jellyfin server.",
                detail = syncSummary,
                showReconnect = true,
            )
        isOfflineMode ->
            TvHomeStatusModel(
                title = "Server offline",
                body = "Downloaded movies, shows, and favorites are still available.",
                detail = syncSummary,
                showReconnect = true,
            )
        syncSummary != null ->
            TvHomeStatusModel(
                title = "Offline sync",
                body = syncSummary,
                showReconnect = syncStatus.pendingChanges > 0 && !syncStatus.isRunning,
            )
        else -> null
    }
}

@Composable
private fun TvHomeStatusCard(model: TvHomeStatusModel, onReconnect: () -> Unit) {
    val shape = RoundedCornerShape(TV_RADIUS_PANEL)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f)),
        shape = shape,
        border = Border(BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.28f))),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.WifiOff, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(model.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(model.body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                model.detail?.let {
                    Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
            }
            if (model.showReconnect) {
                TvHeroButton("Reconnect", Icons.Rounded.Refresh, false, onClick = onReconnect)
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
private fun TvContentShelf(
    title: String,
    items: List<SpatialFinItem>,
    showProgress: Boolean = false,
    onOpenItem: (SpatialFinItem) -> Unit,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    // Design uses portrait posters for browse rows (Movies / TV Shows / Sources)
    // and landscape stills for Continue Watching / Next Up.
    portrait: Boolean = false,
) {
    if (items.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(24.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(MaterialTheme.colorScheme.primary),
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (actionLabel != null && onAction != null) {
                TvHeroButton(
                    label = actionLabel,
                    icon = Icons.Rounded.ChevronRight,
                    primary = false,
                    onClick = onAction,
                )
            }
        }
        LazyRow(
            modifier = Modifier.focusRestorer(),
            horizontalArrangement = Arrangement.spacedBy(if (portrait) 18.dp else 16.dp),
            state = rememberTvShelfListState(),
        ) {
            itemsIndexed(items.take(12), key = { _, it -> it.id }) { _, item ->
                TvMediaCard(
                    item,
                    showProgress,
                    Modifier.width(if (portrait) 158.dp else 300.dp),
                    portrait = portrait,
                ) { onOpenItem(item) }
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
        columns = GridCells.Fixed(6),
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(28.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        contentPadding = PaddingValues(top = 64.dp, bottom = 48.dp),
    ) {
        if (view == null) { item(span = { GridItemSpan(6) }) { TvLibraryShelf("Available libraries", availableViews, onSelectView) }; return@LazyVerticalGrid }
        item(span = { GridItemSpan(6) }) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.width(4.dp).height(28.dp).clip(RoundedCornerShape(999.dp)).background(MaterialTheme.colorScheme.primary))
                Text(view.name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                TvHeroButton("Back home", Icons.Rounded.Home, false, onClick = onBackToHome)
                if (availableViews.size > 1) TvHeroButton("Jump to first library", Icons.AutoMirrored.Rounded.ManageSearch, false, onClick = { onSelectView(availableViews.first()) })
                TvHeroButton(if (offlineOnly) "Available offline" else "All titles", if (offlineOnly) Icons.Rounded.CloudDone else Icons.Rounded.CloudDownload, false, offlineOnly, onClick = { offlineOnly = !offlineOnly })
            }
        }
        if (filteredItems.isEmpty()) item(span = { GridItemSpan(6) }) { TvPlaceholderScreen("No titles", "This library is empty.") }
        else gridItems(filteredItems, key = { it.id }) { TvMediaCard(it, false, Modifier.fillMaxWidth(), portrait = true) { onOpenItem(it) } }
    }
}

@Composable
private fun TvSearchScreen(onOpenItem: (SpatialFinItem) -> Unit, viewModel: dev.spatialfin.tv.TvSearchViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Live search as the query changes (on-screen keyboard, paired hardware
    // keyboard, or the system IME) — debounced so each keypress doesn't fire a
    // request. The explicit Search action / IME action still force a search.
    LaunchedEffect(state.query) {
        if (state.query.trim().length >= 2) {
            kotlinx.coroutines.delay(350L)
            viewModel.search()
        }
    }
    // Land focus on the keyboard (not the text field) so entering Search doesn't
    // pop the system IME; the field stays available for hardware keyboards.
    val keyboardFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(120L)
        runCatching { keyboardFocus.requestFocus() }
    }
    // Two-column 10-foot layout (design ui_kits/tv/TvSearchScreen.jsx): query
    // field + on-screen keyboard on the left, live results grid on the right.
    Row(
        modifier = Modifier.fillMaxSize().padding(top = 64.dp, bottom = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(40.dp),
    ) {
        Column(
            modifier = Modifier.width(452.dp).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { androidx.compose.material3.Text("Search movies & shows") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { viewModel.search() }),
            )
            TvOnScreenKeyboard(
                onKey = { ch -> viewModel.setQuery(state.query + ch) },
                onSpace = { viewModel.setQuery(state.query + " ") },
                onDelete = { viewModel.setQuery(state.query.dropLast(1)) },
                onClear = { viewModel.setQuery("") },
                firstKeyFocusRequester = keyboardFocus,
            )
        }
        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            val header = when {
                state.query.isBlank() -> "Search your library"
                else -> "Results for “${state.query.trim()}” · ${state.items.size}"
            }
            Text(
                header,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(18.dp))
            when {
                state.isLoading -> TvPlaceholderScreen("Searching", "Looking through Jellyfin...")
                state.error != null -> TvPlaceholderScreen("Search failed", state.error?.localizedMessage ?: "Unknown error")
                state.hasSearched && state.items.isEmpty() -> TvPlaceholderScreen("No results", "Try another title.")
                state.items.isNotEmpty() -> LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(26.dp),
                    horizontalArrangement = Arrangement.spacedBy(22.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    gridItems(state.items, key = { it.id }) { TvMediaCard(it, false, Modifier.fillMaxWidth(), portrait = true) { onOpenItem(it) } }
                }
                else -> TvPlaceholderScreen("Ready to search", "Use the keyboard to find movies and shows.")
            }
        }
    }
}

// On-screen keyboard grid (design components/tv/TvKeyboard.jsx). Six rows of six
// keys cover A–Z + 0–9; the action row adds Space / Delete / Clear. Every key is
// a focusable TV Surface with the standard focus-scale + primary fill, so the
// D-pad reaches each one — no system IME required.
@Composable
private fun TvOnScreenKeyboard(
    onKey: (Char) -> Unit,
    onSpace: () -> Unit,
    onDelete: () -> Unit,
    onClear: () -> Unit,
    firstKeyFocusRequester: androidx.compose.ui.focus.FocusRequester? = null,
) {
    val rows = listOf("ABCDEF", "GHIJKL", "MNOPQR", "STUVWX", "YZ0123", "456789")
    Column(
        modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        rows.forEachIndexed { ri, row ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEachIndexed { ci, ch ->
                    val keyModifier = Modifier.weight(1f).let { m ->
                        if (ri == 0 && ci == 0 && firstKeyFocusRequester != null) m.focusRequester(firstKeyFocusRequester) else m
                    }
                    TvKeyboardKey(label = ch.toString(), modifier = keyModifier, onClick = { onKey(ch) })
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TvKeyboardKey(label = "SPACE", modifier = Modifier.weight(3f), onClick = onSpace)
            TvKeyboardKey(icon = Icons.AutoMirrored.Rounded.Backspace, contentDescription = "Delete", modifier = Modifier.weight(1.5f), onClick = onDelete)
            TvKeyboardKey(label = "CLEAR", modifier = Modifier.weight(1.5f), onClick = onClear)
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvKeyboardKey(
    modifier: Modifier = Modifier,
    label: String? = null,
    icon: ImageVector? = null,
    contentDescription: String? = null,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Surface(
        onClick = onClick,
        modifier = modifier.height(56.dp),
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.06f),
            focusedContainerColor = MaterialTheme.colorScheme.primary,
            contentColor = Color.White,
            focusedContentColor = MaterialTheme.colorScheme.onPrimary,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = TV_FOCUS_SCALE),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(TV_FOCUS_RING, MaterialTheme.colorScheme.primary), shape = shape),
        ),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (icon != null) {
                Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(24.dp))
            } else {
                Text(label.orEmpty(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
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
                        TvIconHeroButton(Icons.Rounded.CloudDownload, "Download") { }
                        
                        Box {
                            var menuOpen by remember { mutableStateOf(false) }
                            TvIconHeroButton(Icons.Rounded.MoreVert, "More actions") { menuOpen = true }
                            if (menuOpen) {
                                val actions = buildList {
                                    add(TvOverflowAction("watchlist", Icons.Rounded.Add, null, label = "Watchlist", onClick = { menuOpen = false }))
                                    add(TvOverflowAction("syncplay", null, { Icon(painterResource(dev.jdtech.jellyfin.core.R.drawable.ic_tv), null, Modifier.size(24.dp), tint = Color.White) }, "SyncPlay", "Watch together with others", onClick = { menuOpen = false; TvPlayerActivity.createIntentForSpatialItem(context, i, openSyncPlayDialogOnStart = true)?.let(context::startActivity) }))
                                    add(TvOverflowAction("playback_options", Icons.Rounded.Refresh, null, "Playback options", "Auto Quality", onClick = { menuOpen = false; TvPlayerActivity.createIntentForSpatialItem(context, i, maxBitrate = 0L)?.let(context::startActivity) }))
                                    if (trailerUrl != null) add(TvOverflowAction("trailer", Icons.Rounded.Tv, null, "Trailer", onClick = { menuOpen = false; TvPlayerActivity.createIntent(context, i.id, kind, startFromBeginning = true, trailer = true)?.let(context::startActivity) }))
                                    if (i is SpatialFinEpisode) {
                                        add(TvOverflowAction("go_to_series", Icons.Rounded.Tv, null, "Go to series", onClick = { menuOpen = false; onOpenShow(i.seriesId) }))
                                        add(TvOverflowAction("go_to_season", Icons.AutoMirrored.Rounded.List, null, "Go to season", onClick = { menuOpen = false; onOpenSeason(i.seasonId) }))
                                    }
                                    add(TvOverflowAction("edit_external_ids", Icons.Rounded.Link, null, "Edit external IDs", onClick = { menuOpen = false }))
                                    add(TvOverflowAction("cast", Icons.Rounded.Tv, null, "Cast & audio output", onClick = { menuOpen = false }))
                                    add(TvOverflowAction("refresh", Icons.Rounded.Refresh, null, "Refresh metadata", onClick = { menuOpen = false }))
                                    add(TvOverflowAction("share", Icons.Rounded.Share, null, "Share", onClick = { menuOpen = false }))
                                    add(TvOverflowAction("delete", Icons.AutoMirrored.Rounded.Backspace, null, "Delete", danger = true, onClick = { menuOpen = false }))
                                }
                                TvOverflowSheet("More actions", item.name, actions) { menuOpen = false }
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
                    Box {
                        var menuOpen by remember { mutableStateOf(false) }
                        TvIconHeroButton(Icons.Rounded.MoreVert, "More actions") { menuOpen = true }
                        if (menuOpen) {
                            val actions = buildList {
                                val showTrailer = show.trailer
                                if (showTrailer != null) add(TvOverflowAction("trailer", Icons.Rounded.Tv, null, "Trailer", onClick = { menuOpen = false; TvPlayerActivity.createIntent(context, show.id, "Series", startFromBeginning = true, trailer = true)?.let(context::startActivity) }))
                                add(TvOverflowAction("watchlist", Icons.Rounded.Add, null, label = "Watchlist", onClick = { menuOpen = false }))
                                add(TvOverflowAction("edit_external_ids", Icons.Rounded.Link, null, "Edit external IDs", onClick = { menuOpen = false }))
                                add(TvOverflowAction("refresh", Icons.Rounded.Refresh, null, "Refresh metadata", onClick = { menuOpen = false }))
                                add(TvOverflowAction("share", Icons.Rounded.Share, null, "Share", onClick = { menuOpen = false }))
                                add(TvOverflowAction("delete", Icons.AutoMirrored.Rounded.Backspace, null, "Delete", danger = true, onClick = { menuOpen = false }))
                            }
                            TvOverflowSheet("More actions", show.name, actions) { menuOpen = false }
                        }
                    }
                })
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                    contentPadding = PaddingValues(bottom = 48.dp),
                ) {
                    state.nextUp?.let { item { TvEpisodeHighlightCard(it) { onOpenEpisode(it.id) } } }
                    if (state.seasons.isNotEmpty()) {
                        item(key = "episodes") {
                            TvShowEpisodesSection(
                                seasons = state.seasons,
                                selectedSeasonId = state.selectedSeasonId,
                                episodes = state.episodes,
                                loading = state.episodesLoading,
                                onSelectSeason = { viewModel.selectSeason(it) },
                                onOpenEpisode = onOpenEpisode,
                            )
                        }
                    }
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
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(22.dp),
        horizontalArrangement = Arrangement.spacedBy(22.dp),
        contentPadding = PaddingValues(top = 64.dp, bottom = 48.dp),
    ) {
        item(span = { GridItemSpan(3) }) { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { TvHeroButton("Back", Icons.AutoMirrored.Rounded.ArrowBack, false, onClick = onBack) } }
        if (state.episodes.isEmpty()) item(span = { GridItemSpan(3) }) { TvPlaceholderScreen("No episodes", "Empty season.") }
        else gridItems(state.episodes, key = { it.id }) { TvEpisodeCard(it, Modifier.fillMaxWidth()) { onOpenEpisode(it.id) } }
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
    Box(modifier = modifier.clip(RoundedCornerShape(24.dp)).background(Color.White.copy(alpha = 0.05f))) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) { Text(title, style = MaterialTheme.typography.labelLarge); Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold); Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2) }
    }
}

@Composable
private fun TvActionTile(title: String, body: String, icon: ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var isFocused by remember { mutableStateOf(false) }
    val tileShape = RoundedCornerShape(TV_RADIUS_CARD)
    Card(modifier = modifier.height(152.dp).onFocusChanged { isFocused = it.isFocused }, onClick = onClick, colors = CardDefaults.colors(containerColor = if (isFocused) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.88f) else Color.White.copy(alpha = 0.05f)), shape = CardDefaults.shape(tileShape), scale = tvCardScale(), border = tvCardBorder(tileShape), glow = tvCardGlow()) {
        Column(modifier = Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(imageVector = icon, contentDescription = null, tint = if (isFocused) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Text(text = title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = if (isFocused) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
            Text(text = body, style = MaterialTheme.typography.bodyMedium, color = if (isFocused) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.86f) else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TvHomeHeroCard(item: SpatialFinItem, eyebrow: String, parkInitialFocus: Boolean, onDidParkFocus: () -> Unit, onPrimaryAction: () -> Unit, onDetails: () -> Unit) {
    val primaryFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(parkInitialFocus) { if (parkInitialFocus) { runCatching { primaryFocus.requestFocus() }; onDidParkFocus() } }
    val pills = buildList {
        when (val i = item) {
            is SpatialFinMovie -> {
                i.productionYear?.let { add(it.toString()) }
                tvRuntimeLabel(i.runtimeTicks)?.let(::add)
                i.officialRating?.takeIf { it.isNotBlank() }?.let(::add)
                addAll(i.genres.take(2))
            }
            is SpatialFinShow -> {
                getShowDateString(i).takeIf { it.isNotBlank() }?.let(::add)
                addAll(i.genres.take(3))
            }
            is SpatialFinEpisode -> {
                add(tvEpisodeLabel(i))
                tvRuntimeLabel(i.runtimeTicks)?.let(::add)
                i.premiereDate?.year?.let { add(it.toString()) }
            }
            else -> Unit
        }
    }
    Box(modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(32.dp)).background(Color(0x77131A24))) {
        Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(model = tvBackdropArtwork(item), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = 0.72f)
            Box(modifier = Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color(0xF506111B), Color(0xD006111B), Color.Transparent), endX = 1250f)))
            Box(modifier = Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Color(0x9906111B)))))
            Column(modifier = Modifier.fillMaxWidth(0.55f).align(Alignment.BottomStart).padding(start = 24.dp, end = 24.dp, bottom = 24.dp, top = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(text = eyebrow.uppercase(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                item.images.logo?.let { AsyncImage(model = it, contentDescription = null, modifier = Modifier.heightIn(max = 72.dp).fillMaxWidth(0.7f), contentScale = ContentScale.Fit, alignment = Alignment.BottomStart) }
                ?: Text(text = item.name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (pills.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        pills.forEach { TvMetadataPill(it) }
                    }
                }
                if (item.overview.isNotBlank()) {
                    Text(text = item.overview, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.82f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 4.dp)) {
                    TvHeroButton(if (item.playbackPositionTicks > 0L) "Resume" else "Play S1 E1", Icons.Rounded.PlayArrow, true, modifier = Modifier.focusRequester(primaryFocus), onClick = onPrimaryAction)
                    TvHeroButton("More info", Icons.Rounded.Info, false, onClick = onDetails)
                    
                    val detailViewModel: dev.spatialfin.tv.TvItemDetailViewModel = hiltViewModel(key = item.id.toString())
                    LaunchedEffect(item.id) { detailViewModel.load(item.id) }
                    
                    TvIconHeroButton(if (item.favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, "Favorite") { detailViewModel.toggleFavorite() }
                    TvIconHeroButton(if (item.played) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, "Mark watched") { detailViewModel.togglePlayed() }

                    var showOverflow by remember { mutableStateOf(false) }
                    TvIconHeroButton(Icons.Rounded.MoreVert, "More actions") { showOverflow = true }
                    
                    if (showOverflow) {
                        val actions = buildList {
                            add(TvOverflowAction("watchlist", Icons.Rounded.Add, null, label = "Watchlist", onClick = {}))
                        }
                        TvOverflowSheet(
                            title = "More actions",
                            subtitle = item.name,
                            actions = actions,
                            onDismissRequest = { showOverflow = false }
                        )
                    }
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
    portrait: Boolean = false,
    onClick: () -> Unit,
) {
    var isFocused by remember { mutableStateOf(false) }
    val cardShape = RoundedCornerShape(TV_RADIUS_CARD)
    Card(
        modifier = modifier.onFocusChanged { isFocused = it.isFocused },
        onClick = onClick,
        colors = CardDefaults.colors(containerColor = Color(0x77131A24)),
        shape = CardDefaults.shape(cardShape),
        scale = tvCardScale(),
        border = tvCardBorder(cardShape),
        glow = tvCardGlow(),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(modifier = Modifier.fillMaxWidth().aspectRatio(if (portrait) 2f / 3f else 1.77f)) {
                val img = if (portrait) {
                    tvPrimaryArtwork(item)
                } else {
                    item.images.showBackdrop ?: item.images.backdrop ?: item.images.primary
                }
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
                if (item is SpatialFinEpisode) {
                    TvEpisodeStillOverlay(item, isFocused)
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
            val titleColor by androidx.compose.animation.animateColorAsState(
                if (isFocused) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                tween(120),
                label = "tvCardTitle",
            )
            Text(
                item.name,
                Modifier.padding(8.dp),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = titleColor,
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TvLibraryCard(view: View, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val cardShape = RoundedCornerShape(TV_RADIUS_CARD)
    Card(modifier = modifier.width(320.dp).height(188.dp), onClick = onClick, colors = CardDefaults.colors(containerColor = Color(0x77131A24)), shape = CardDefaults.shape(cardShape), scale = tvCardScale(), border = tvCardBorder(cardShape), glow = tvCardGlow()) {
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
    val shape = RoundedCornerShape(999.dp)
    Column(
        Modifier
            .width(140.dp)
            .onFocusChanged { isFocused = it.isFocused }
            .tvFocus(isFocused, shape)
            .clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(140.dp).clip(shape).background(Color(0xFF1A2433))) {
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
    val shape = RoundedCornerShape(TV_RADIUS_CARD)
    Column(
        Modifier
            .width(240.dp)
            .onFocusChanged { isFocused = it.isFocused }
            .tvFocus(isFocused, shape)
            .clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1.77f).clip(shape).background(Color(0xFF161D28))) {
            if (chapter.imageUri != null) AsyncImage(chapter.imageUri, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Text(tvFormatChapterTime(chapter.startPosition), Modifier.align(Alignment.BottomStart).padding(6.dp).background(Color.Black.copy(0.5f), RoundedCornerShape(4.dp)).padding(4.dp), color = Color.White, style = MaterialTheme.typography.labelSmall)
        }
    }
}

// Show-detail episodes block (design ui_kits/tv/ShowDetailScreen.jsx): an
// "Episodes" header with inline SeasonTabs, then a shelf of rich EpisodeCards for
// the selected season — episodes are browsable from the show screen itself
// instead of only via a separate season screen.
@Composable
private fun TvShowEpisodesSection(
    seasons: List<SpatialFinSeason>,
    selectedSeasonId: UUID?,
    episodes: List<SpatialFinEpisode>,
    loading: Boolean,
    onSelectSeason: (UUID) -> Unit,
    onOpenEpisode: (UUID) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(24.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(MaterialTheme.colorScheme.primary),
            )
            Text("Episodes", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            if (seasons.size > 1) {
                TvSeasonTabs(seasons, selectedSeasonId, onSelectSeason, modifier = Modifier.weight(1f))
            }
        }
        when {
            loading -> Box(
                modifier = Modifier.fillMaxWidth().height(220.dp),
                contentAlignment = Alignment.CenterStart,
            ) { CircularProgressIndicator(color = MaterialTheme.colorScheme.primary) }
            episodes.isEmpty() -> Text(
                "No episodes in this season.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> LazyRow(
                modifier = Modifier.focusRestorer(),
                horizontalArrangement = Arrangement.spacedBy(22.dp),
                state = rememberTvShelfListState(),
            ) {
                itemsIndexed(episodes, key = { _, e -> e.id }) { _, episode ->
                    TvEpisodeCard(episode, Modifier.width(330.dp)) { onOpenEpisode(episode.id) }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvSeasonTabs(
    seasons: List<SpatialFinSeason>,
    selectedId: UUID?,
    onSelect: (UUID) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.focusRestorer(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 4.dp),
    ) {
        items(seasons, key = { it.id }) { season ->
            val shape = RoundedCornerShape(999.dp)
            val active = season.id == selectedId
            Surface(
                onClick = { onSelect(season.id) },
                modifier = Modifier.height(44.dp),
                shape = ClickableSurfaceDefaults.shape(shape),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = if (active) MaterialTheme.colorScheme.primary else Color.Transparent,
                    contentColor = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    focusedContainerColor = MaterialTheme.colorScheme.primary,
                    focusedContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = TV_FOCUS_SCALE),
                border = ClickableSurfaceDefaults.border(
                    border = Border(BorderStroke(1.dp, if (active) Color.Transparent else MaterialTheme.colorScheme.border), shape = shape),
                    focusedBorder = Border(BorderStroke(TV_FOCUS_RING, MaterialTheme.colorScheme.primary), shape = shape),
                ),
            ) {
                Box(
                    modifier = Modifier.fillMaxHeight().padding(horizontal = 22.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(tvSeasonLabel(season), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
        }
    }
}

// Rich episode tile (design components/tv/EpisodeCard.jsx): landscape still with
// episode title and watched state in the visible overlay, then runtime and a
// two-line synopsis below. Used by the show-detail episode shelf and the season grid.
@Composable
private fun TvEpisodeCard(episode: SpatialFinEpisode, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var isFocused by remember { mutableStateOf(false) }
    val cardShape = RoundedCornerShape(TV_RADIUS_CARD)
    val runtime = tvRuntimeLabel(episode.runtimeTicks)
    val fraction = buildPlaybackFraction(episode)
    val episodeNumber = tvEpisodeLabel(episode)
    Column(modifier) {
        Card(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().aspectRatio(1.77f).onFocusChanged { isFocused = it.isFocused },
            colors = CardDefaults.colors(containerColor = Color(0x77131A24)),
            shape = CardDefaults.shape(cardShape),
            scale = tvCardScale(),
            border = tvCardBorder(cardShape),
            glow = tvCardGlow(),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                val img = tvBackdropArtwork(episode)
                if (img != null) {
                    AsyncImage(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant))
                }
                TvEpisodeStillOverlay(episode, isFocused, showInlineDetails = true)
                fraction?.let {
                    FloatingProgressBar(
                        progress = it,
                        modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 74.dp).align(Alignment.BottomCenter),
                        progressColor = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        val titleColor by androidx.compose.animation.animateColorAsState(
            if (isFocused) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            tween(120),
            label = "tvEpisodeTitle",
        )
        Row(
            modifier = Modifier.padding(top = 10.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = episode.name.ifBlank { episodeNumber },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TvEpisodeWatchStatusPill(played = episode.played)
        }
        Row(
            modifier = Modifier.padding(top = 4.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = episodeNumber,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            runtime?.let {
                Text("•", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (episode.overview.isNotBlank()) {
            Text(
                text = episode.overview,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}

@Composable
private fun TvEpisodeWatchStatusPill(played: Boolean, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(999.dp)
    val containerColor = if (played) MaterialTheme.colorScheme.primary.copy(alpha = 0.92f) else Color(0xCC0D1824)
    val contentColor = if (played) MaterialTheme.colorScheme.onPrimary else Color(0xFFD7DEE8)
    Row(
        modifier = modifier
            .clip(shape)
            .background(containerColor)
            .border(1.dp, if (played) Color.Transparent else Color.White.copy(alpha = 0.18f), shape)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = if (played) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(13.dp),
        )
        Text(
            text = if (played) "Watched" else "Unwatched",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = contentColor,
            maxLines = 1,
        )
    }
}

// Design-system EpisodeCard affordances (components/tv/EpisodeCard.jsx): an
// "EP n" badge over a top/bottom legibility scrim, plus a glass play circle
// that appears on focus. Drawn over an episode still so episodes read as
// playable units distinct from poster tiles. Movies/shows do not get this.
@Composable
private fun BoxScope.TvEpisodeStillOverlay(
    episode: SpatialFinEpisode,
    isFocused: Boolean,
    showInlineDetails: Boolean = false,
) {
    val episodeNumber = tvEpisodeLabel(episode)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    listOf(Color(0x59000000), Color.Transparent, Color(0x8C000000)),
                )
            )
    )
    if (episode.indexNumber > 0) {
        Text(
            text = "EP ${episode.indexNumber}",
            modifier = Modifier.align(Alignment.TopStart).padding(horizontal = 10.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
    }
    if (showInlineDetails) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        listOf(Color.Transparent, Color(0xE60D1824)),
                    )
                )
                .padding(start = 12.dp, end = 12.dp, top = 30.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = episode.name.ifBlank { episodeNumber },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = episodeNumber,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.82f),
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                TvEpisodeWatchStatusPill(played = episode.played)
            }
        }
    }
    if (isFocused) {
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(52.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(Color(0xE00D1824))
                .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(999.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp))
        }
    }
}

@Composable
private fun TvEpisodeHighlightCard(episode: SpatialFinEpisode, onClick: () -> Unit) {
    var isFocused by remember { mutableStateOf(false) }
    val cardShape = RoundedCornerShape(TV_RADIUS_CARD)
    val fraction = buildPlaybackFraction(episode)
    val supporting = listOfNotNull(
        tvEpisodeLabel(episode),
        tvRuntimeLabel(episode.runtimeTicks),
        episode.premiereDate?.year?.toString(),
    ).joinToString("  ·  ")
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(196.dp).onFocusChanged { isFocused = it.isFocused },
        colors = CardDefaults.colors(containerColor = Color(0x77131A24)),
        shape = CardDefaults.shape(cardShape),
        scale = tvCardScale(),
        border = tvCardBorder(cardShape),
        glow = tvCardGlow(),
    ) {
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxHeight().aspectRatio(16f / 9f).background(Color(0xFF0F1720))) {
                AsyncImage(tvBackdropArtwork(episode), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                TvEpisodeStillOverlay(episode, isFocused)
                fraction?.let {
                    FloatingProgressBar(
                        progress = it,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp).align(Alignment.BottomCenter),
                        progressColor = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight().padding(24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("NEXT UP", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text(episode.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (supporting.isNotBlank()) Text(supporting, style = MaterialTheme.typography.bodySmall, color = Color(0xFFD5DCE6))
                if (episode.overview.isNotBlank()) Text(episode.overview, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.PlayArrow, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Text(
                        if (episode.playbackPositionTicks > 0L) "Resume episode" else "Play episode",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color(0xFFD7DEE8),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun TvUserCard(name: String, role: String, avatarUri: android.net.Uri?, onClick: () -> Unit) {
    var isFocused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(24.dp)
    Surface(
        onClick = onClick,
        modifier = Modifier.width(180.dp).onFocusChanged { isFocused = it.isFocused }.tvFocus(isFocused, shape),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.White.copy(alpha = 0.1f)),
        shape = ClickableSurfaceDefaults.shape(shape = shape)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(128.dp).clip(RoundedCornerShape(999.dp)).background(Color(0xFF1A2433)).border(if (isFocused) 2.dp else 0.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(999.dp))) {
                if (avatarUri != null) AsyncImage(avatarUri, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Text(name.take(1).uppercase(), Modifier.align(Alignment.Center), style = MaterialTheme.typography.headlineMedium, color = Color.White)
            }
            Text(name, fontWeight = FontWeight.SemiBold); Text(role, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun TvMetadataPill(text: String) { MetadataPill { Text(text, style = MaterialTheme.typography.labelMedium, color = Color.White) } }

@Composable
private fun TvPageHeaderCard(title: String, body: String) {
    Surface(modifier = Modifier.fillMaxWidth(), colors = SurfaceDefaults.colors(containerColor = Color.White.copy(0.04f)), shape = RoundedCornerShape(30.dp)) {
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
    val cardShape = RoundedCornerShape(TV_RADIUS_CARD)
    Card(
        onClick = onClick,
        modifier = Modifier
            .width(200.dp)
            .height(100.dp)
            .onFocusChanged { isFocused = it.isFocused }
            .border(if (selected && !isFocused) 2.dp else 0.dp, if (selected && !isFocused) MaterialTheme.colorScheme.secondary else Color.Transparent, cardShape),
        colors = CardDefaults.colors(containerColor = if (selected) Color(0xAA1A2433) else Color(0x77131A24)),
        shape = CardDefaults.shape(cardShape),
        scale = tvCardScale(),
        border = tvCardBorder(cardShape),
        glow = tvCardGlow(),
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

// ---------------------------------------------------------------------------
// TV focus treatment (DESIGN.md): selection is communicated by focus-scale
// (~1.06) + a cyan focus ring + a soft cyan glow, on a fast 120ms tween —
// NEVER shadow elevation, NEVER a spring. These helpers keep every focusable
// surface on the same vocabulary instead of ad-hoc per-card borders.
private const val TV_FOCUS_SCALE = 1.06f
private val TV_FOCUS_RING = 3.dp
private val TV_FOCUS_GLOW = 14.dp
private val TV_RADIUS_CARD = 16.dp    // --radius-md: media cards & focus cards
private val TV_RADIUS_PANEL = 32.dp   // --radius-lg: hero cards, dialogs, panels
private val TvFocusTween = tween<Float>(durationMillis = 120)

/** Cyan glow + ring + scale for non-Card focusables (clickable / Surface). */
@Composable
internal fun Modifier.tvFocus(focused: Boolean, shape: Shape, scaleOnFocus: Boolean = true): Modifier {
    val target = if (focused && scaleOnFocus) TV_FOCUS_SCALE else 1f
    val scale by animateFloatAsState(target, TvFocusTween, label = "tvFocusScale")
    val primary = MaterialTheme.colorScheme.primary
    return this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .shadow(if (focused) TV_FOCUS_GLOW else 0.dp, shape, spotColor = primary, ambientColor = primary)
        .border(if (focused) TV_FOCUS_RING else 0.dp, if (focused) primary else Color.Transparent, shape)
}

@Composable
private fun tvCardScale() = CardDefaults.scale(focusedScale = TV_FOCUS_SCALE)

@Composable
private fun tvCardBorder(shape: Shape) = CardDefaults.border(
    focusedBorder = Border(BorderStroke(TV_FOCUS_RING, MaterialTheme.colorScheme.primary), shape = shape),
)

@Composable
private fun tvCardGlow() = CardDefaults.glow(
    focusedGlow = Glow(elevationColor = MaterialTheme.colorScheme.primary, elevation = TV_FOCUS_GLOW),
)

private fun tvViewArtwork(view: View): Any? = view.items.firstOrNull()?.let { it.images.backdrop ?: it.images.primary ?: it.images.showBackdrop ?: it.images.showPrimary }
private fun buildPlaybackFraction(item: SpatialFinItem): Float? { val r = item.runtimeTicks; val p = item.playbackPositionTicks; return if (r > 0 && p > 0) (p.toFloat()/r).coerceIn(0f,1f) else null }
private fun tvItemLabel(item: SpatialFinItem): String = when(item){is SpatialFinMovie->"Movie";is SpatialFinEpisode->"Episode";is SpatialFinSeason->"Season";is SpatialFinShow->"Series";is SpatialFinCollection->"Library";is dev.jdtech.jellyfin.plugins.model.UniversalSpatialFinItem->item.universalMediaItem.author?.takeIf { it.isNotBlank() }?:"";else->""}
private fun tvPrimaryArtwork(item: SpatialFinItem): Any? = when(item){is SpatialFinEpisode->item.images.showPrimary?:item.images.primary?:item.images.showBackdrop?:item.images.backdrop;else->item.images.primary?:item.images.showPrimary?:item.images.backdrop?:item.images.showBackdrop}
private fun tvBackdropArtwork(item: SpatialFinItem): Any? = when(item){is SpatialFinEpisode->item.images.showBackdrop?:item.images.backdrop?:item.images.showPrimary?:item.images.primary;else->item.images.backdrop?:item.images.showBackdrop?:item.images.primary?:item.images.showPrimary}
private fun tvRuntimeLabel(ticks: Long): String? = ticks.takeIf { it > 0 }?.div(600000000)?.takeIf { it > 0 }?.let { "$it min" }
private fun tvSeasonLabel(s: SpatialFinSeason): String = if (s.indexNumber > 0) "Season ${s.indexNumber}" else s.name.ifBlank { "Season" }
private fun tvEpisodeLabel(e: SpatialFinEpisode): String = buildString { if (e.parentIndexNumber > 0) append("S${e.parentIndexNumber}"); if (e.indexNumber > 0) append("E${e.indexNumber}"); if (isEmpty()) append("Episode") }
private fun tvFormatChapterTime(ms: Long): String { val s = ms/1000; val h = s/3600; val m = (s%3600)/60; val sec = s%60; return if (h>0) "%d:%02d:%02d".format(h,m,sec) else "%d:%02d".format(m,sec) }
private fun tvCompanionConfigured(p: AppPreferences): Boolean = p.getValue(p.companionUrl).isNotBlank() && p.getValue(p.companionToken).isNotBlank()
