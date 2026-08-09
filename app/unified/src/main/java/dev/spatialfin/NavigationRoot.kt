package dev.spatialfin

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import coil3.compose.AsyncImage
import dev.jdtech.jellyfin.core.presentation.components.userPrimaryImageUri
import dev.jdtech.jellyfin.models.User
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.ViewInAr
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.xr.compose.spatial.SpatialDialog
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavOptions
import androidx.navigation.NavOptionsBuilder
import androidx.navigation.Navigator
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.toRoute
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.models.CollectionType
import dev.jdtech.jellyfin.models.LocalVideoItem
import dev.jdtech.jellyfin.models.NetworkVideoItem
import dev.jdtech.jellyfin.models.SpatialFinAudioBook
import dev.jdtech.jellyfin.models.SpatialFinBoxSet
import dev.jdtech.jellyfin.models.SpatialFinCollection
import dev.jdtech.jellyfin.models.SpatialFinEpisode
import dev.jdtech.jellyfin.models.SpatialFinFolder
import dev.jdtech.jellyfin.models.SpatialFinItem
import dev.jdtech.jellyfin.models.SpatialFinMusicAlbum
import dev.jdtech.jellyfin.models.SpatialFinMusicArtist
import dev.jdtech.jellyfin.models.SpatialFinMovie
import dev.jdtech.jellyfin.models.SpatialFinSeason
import dev.jdtech.jellyfin.models.SpatialFinShow
import dev.jdtech.jellyfin.models.SpatialFinPhoto
import dev.jdtech.jellyfin.models.SpatialFinPlaylist
import dev.jdtech.jellyfin.player.xr.XrPlayerActivity
import dev.jdtech.jellyfin.player.xr.voice.GeminiNanoService
import dev.jdtech.jellyfin.plugins.model.UniversalSpatialFinItem
import dev.jdtech.jellyfin.presentation.film.CollectionScreen
import dev.jdtech.jellyfin.presentation.film.DownloadsScreen
import dev.jdtech.jellyfin.presentation.film.EpisodeScreen
import dev.jdtech.jellyfin.presentation.film.FavoritesScreen
import dev.jdtech.jellyfin.plugins.ui.PluginSettingsScreen
import dev.jdtech.jellyfin.presentation.film.HomeScreen
import dev.jdtech.jellyfin.presentation.film.LibraryScreen
import dev.jdtech.jellyfin.presentation.film.MediaScreen
import dev.jdtech.jellyfin.presentation.film.MovieScreen
import dev.jdtech.jellyfin.presentation.film.PersonScreen
import dev.jdtech.jellyfin.presentation.film.PhotoViewerScreen
import dev.jdtech.jellyfin.presentation.film.SeasonScreen
import dev.jdtech.jellyfin.presentation.film.ShowScreen
import dev.jdtech.jellyfin.presentation.local.LocalMediaScreen
import dev.jdtech.jellyfin.presentation.local.LocalVideoScreen
import dev.jdtech.jellyfin.presentation.network.AddShareScreen
import dev.jdtech.jellyfin.presentation.network.NetworkScreen
import dev.jdtech.jellyfin.presentation.network.NetworkShareScreen
import dev.jdtech.jellyfin.presentation.network.NetworkVideoScreen
import dev.jdtech.jellyfin.presentation.settings.AboutScreen
import dev.jdtech.jellyfin.presentation.settings.MusicAssistantAuthDialog
import dev.jdtech.jellyfin.presentation.settings.SettingsScreen
import dev.jdtech.jellyfin.presentation.setup.addresses.ServerAddressesScreen
import dev.jdtech.jellyfin.presentation.setup.addserver.AddServerScreen
import dev.jdtech.jellyfin.presentation.setup.login.LoginScreen
import dev.jdtech.jellyfin.presentation.setup.servers.ServersScreen
import dev.jdtech.jellyfin.presentation.setup.users.UsersScreen
import dev.jdtech.jellyfin.presentation.setup.welcome.WelcomeScreen
import dev.jdtech.jellyfin.presentation.utils.LocalOfflineMode
import dev.jdtech.jellyfin.settings.R as SettingsR
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.spatialfin.unified.XrSpaceMode
import dev.spatialfin.unified.audio.JellyfinAudioDetailScreen
import dev.spatialfin.unified.audio.JellyfinAudioDetailType
import dev.spatialfin.unified.audio.JellyfinAudioLibraryScreen
import dev.spatialfin.unified.audio.JellyfinAudioMiniPlayer
import dev.spatialfin.unified.audio.JellyfinAudioNowPlayingScreen
import dev.spatialfin.unified.audio.LocalAudioPlaybackDispatcher
import java.util.UUID
import kotlinx.serialization.Serializable

@Serializable data object WelcomeRoute

@Serializable data object ServersRoute

@Serializable data object AddServerRoute

@Serializable data class ServerAddressesRoute(val serverId: String)

@Serializable data object UsersRoute

@Serializable data class LoginRoute(val username: String? = null)

@Serializable data object HomeRoute

@Serializable data object MediaRoute

@Serializable data object LocalRoute

@Serializable data object NetworkRoute

@Serializable data class NetworkShareRoute(val shareId: String)

@Serializable data class NetworkVideoRoute(val videoId: String)

@Serializable data object NetworkAddShareRoute

@Serializable data object DownloadsRoute

@Serializable data object UniversalPluginsRoute

@Serializable data class PluginBrowseRoute(val pluginId: String, val rowId: String? = null)

@Serializable
data class LibraryRoute(
    val libraryId: String,
    val libraryName: String,
    val libraryType: CollectionType,
)

@Serializable
data class AudioDetailRoute(
    val itemId: String,
    val title: String,
    val detailType: JellyfinAudioDetailType,
    val parentId: String? = null,
)

@Serializable data class CollectionRoute(val collectionId: String, val collectionName: String)

@Serializable data object FavoritesRoute

@Serializable data class MovieRoute(val movieId: String)

@Serializable data class ShowRoute(val showId: String)

@Serializable data class EpisodeRoute(val episodeId: String)

@Serializable data class LocalVideoRoute(val mediaStoreId: Long)

@Serializable data class SeasonRoute(val seasonId: String)

@Serializable data class PersonRoute(val personId: String)

/** [parentId] lets the viewer page through the rest of the containing folder. */
@Serializable data class PhotoRoute(val photoId: String, val parentId: String? = null)

@Serializable data class SettingsRoute(val indexes: IntArray)

@Serializable data object AboutRoute

data class TabBarItem(
    @param:StringRes val title: Int,
    @param:DrawableRes val icon: Int,
    val route: Any,
    val enabled: Boolean = true,
)

val homeTab =
    TabBarItem(title = CoreR.string.title_home, icon = CoreR.drawable.ic_home, route = HomeRoute)
val mediaTab =
    TabBarItem(
        title = CoreR.string.title_media,
        icon = CoreR.drawable.ic_library,
        route = MediaRoute,
    )
val localTab =
    TabBarItem(
        title = CoreR.string.title_local,
        icon = CoreR.drawable.ic_folder,
        route = LocalRoute,
    )
val networkTab =
    TabBarItem(
        title = CoreR.string.title_network,
        icon = CoreR.drawable.ic_globe,
        route = NetworkRoute,
    )
val downloadsTab =
    TabBarItem(
        title = CoreR.string.title_download,
        icon = CoreR.drawable.ic_download,
        route = DownloadsRoute,
    )
val sourcesTab =
    TabBarItem(
        title = CoreR.string.title_sources,
        icon = CoreR.drawable.ic_plugins,
        route = UniversalPluginsRoute,
    )

private val AUDIO_COLLECTION_TYPES =
    setOf(CollectionType.Music, CollectionType.Playlists, CollectionType.Books)

@Composable
fun NavigationRoot(
    navController: NavHostController,
    hasServers: Boolean,
    hasCurrentServer: Boolean,
    hasCurrentUser: Boolean,
    onboardingCompleted: Boolean,
    appPreferences: AppPreferences,
    initialSearchQuery: String? = null,
    onReconnect: () -> Unit = {},
    /** Non-null on XR devices; null on TV/phone. */
    xrSpaceMode: XrSpaceMode? = null,
    /** Called to enter Full Space (Immersive) mode. Only relevant in Home Space. */
    onEnterFullSpace: (() -> Unit)? = null,
    /** Called to enter Home Space (Multitask) mode. Only relevant in Full Space. */
    onEnterHomeSpace: (() -> Unit)? = null,
    /** Active Jellyfin user; powers the XR rail's profile-avatar / switch-user button. */
    currentUser: User? = null,
    /** Active Jellyfin server base URL; combined with `currentUser` to build the avatar URL. */
    currentServerAddress: String? = null,
    /** Process-singleton FCast session manager. Null on form factors that don't cast (TV). */
    fcastSession: dev.spatialfin.fcast.session.CastSessionManager? = null,
    /**
     * Intercepts home-row taps that are Music Assistant items (play or open
     * detail). Returns true when it handled the item, so normal Jellyfin item
     * navigation is skipped. Null on surfaces with no MA wiring.
     */
    onMaItemTap: ((dev.jdtech.jellyfin.models.SpatialFinItem) -> Boolean)? = null,
    onVoiceClick: (() -> Unit)? = null,
) {
    val isOfflineMode = LocalOfflineMode.current

    val startDestination =
        when {
            !onboardingCompleted -> WelcomeRoute
            hasServers && hasCurrentServer && hasCurrentUser -> HomeRoute
            hasServers && hasCurrentServer -> UsersRoute
            hasServers -> ServersRoute
            else -> LocalRoute
        }

    val navigationItems =
        when (isOfflineMode) {
            false ->
                if (hasServers) {
                    listOf(homeTab, mediaTab, downloadsTab, sourcesTab)
                } else {
                    listOf(sourcesTab)
                }
            true ->
                if (hasServers) {
                    listOf(homeTab, downloadsTab, sourcesTab)
                } else {
                    listOf(sourcesTab)
                }
        }
    val navigationItemClassNames = navigationItems.map { it.route::class.qualifiedName }

    val navBackStackEntry by navController.currentBackStackEntryAsState()

    var searchExpanded by remember { mutableStateOf(false) }
    var pendingInitialSearchQuery by remember(initialSearchQuery) { mutableStateOf(initialSearchQuery) }
    var showAudioNowPlaying by remember { mutableStateOf(false) }

    val currentRoute = navBackStackEntry?.destination?.route
    val showBottomBar = currentRoute in navigationItemClassNames && !searchExpanded

    // Re-scope Music Assistant config to the active Jellyfin user whenever the
    // user switches — but only while the SendSpin receiver is actually running,
    // so we never spin the service up just to push a user id. The service then
    // reloads the new user's stored MA URL/token (or shows it as unconfigured)
    // and a previous user's token can't linger in the controls UI.
    val maServiceRunning by remember {
        dev.jdtech.jellyfin.sendspin.receiver.SendspinReceiverSession.state
            .map { it.serviceRunning }
            .distinctUntilChanged()
    }.collectAsState(initial = false)
    val maRebindContext = navController.context.applicationContext
    LaunchedEffect(currentUser?.id, maServiceRunning) {
        if (maServiceRunning) {
            dev.jdtech.jellyfin.sendspin.receiver.SendspinReceiverService
                .setMusicAssistantUser(maRebindContext, currentUser?.id?.toString())
        }
    }

    LaunchedEffect(pendingInitialSearchQuery, currentRoute) {
        if (!pendingInitialSearchQuery.isNullOrBlank() && currentRoute != MediaRoute::class.qualifiedName) {
            searchExpanded = true
            navController.navigate(MediaRoute) {
                popUpTo(navController.graph.startDestinationId) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    // Use NavigationRail directly to avoid the broken androidx.xr.compose.material3
    // NavigationSuiteScaffold XR override, which calls Subspace() with an incompatible
    // signature due to a version mismatch between compose.material3:alpha11 and compose:alpha11.
    //
    // The XR panel is wider than the comfortable interaction zone. A full-width Row would push
    // the NavigationRail too far left, outside the XR FOV. We cap the inner row at 1920dp and
    // center it so the rail stays within comfortable viewing range.
    androidx.compose.runtime.CompositionLocalProvider(
        dev.spatialfin.fcast.session.LocalFCastSession provides fcastSession,
        dev.jdtech.jellyfin.presentation.cast.LocalCastButtonController provides
            androidx.compose.runtime.remember(fcastSession) {
                fcastSession?.let { dev.spatialfin.fcast.session.CastButtonControllerAdapter(it) }
            },
        // MA long-press actions seam: the renderer reads LocalMaPlayDispatcher at
        // the consuming screen's position, so the browse UI stays free of the
        // app MA types (MaCardActionsMenu / MaPlayDispatcher).
        dev.jdtech.jellyfin.presentation.music.LocalMaCardActionsRenderer provides
            { item, onDismiss ->
                dev.spatialfin.unified.music.MaCardActionsMenu(
                    item = item,
                    dispatcher = dev.spatialfin.unified.LocalMaPlayDispatcher.current,
                    onDismiss = onDismiss,
                )
            },
        // Settings preview seams: VoicePickerDialog (SpatialVoiceSynthesizer) and
        // SubtitlePreviewCard (LibassRenderer) are :player:xr-backed, so they stay
        // app-side and the moved XR settings screen renders them through these.
        dev.jdtech.jellyfin.presentation.settings.LocalVoicePickerDialog provides
            { initialVoiceName, onSave, onDismissRequest ->
                dev.jdtech.jellyfin.presentation.settings.components.VoicePickerDialog(
                    initialVoiceName = initialVoiceName,
                    onSave = onSave,
                    onDismissRequest = onDismissRequest,
                )
            },
        dev.jdtech.jellyfin.presentation.settings.LocalSubtitlePreviewCard provides
            { modifier ->
                dev.spatialfin.presentation.settings.components.SubtitlePreviewCard(
                    appPreferences = appPreferences,
                    modifier = modifier,
                )
            },
    ) {
    val panelModifier = if (xrSpaceMode == XrSpaceMode.FULL) {
        Modifier
            .fillMaxSize()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(32.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.62f))
            .border(
                1.dp,
                androidx.compose.ui.graphics.Color.White.copy(alpha = 0.15f),
                androidx.compose.foundation.shape.RoundedCornerShape(32.dp)
            )
    } else {
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.65f))
    }

    val railContent = @Composable {
        NavigationRail(
            containerColor = androidx.compose.ui.graphics.Color.Transparent
        ) {
            navigationItems.forEach { item ->
                NavigationRailItem(
                    selected = currentRoute == item.route::class.qualifiedName,
                    onClick = {
                        if (
                            item.route is MediaRoute &&
                                currentRoute == MediaRoute::class.qualifiedName
                        ) {
                            searchExpanded = true
                        }

                        navController.navigate(item.route) {
                            popUpTo(navController.graph.startDestinationId) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    icon = {
                        Icon(
                            painter = painterResource(item.icon),
                            contentDescription = stringResource(item.title),
                        )
                    },
                    enabled = item.enabled,
                    label = { Text(text = stringResource(item.title)) },
                )
            }
            if (isOfflineMode) {
                Spacer(modifier = Modifier.weight(1f))
                Column(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.WifiOff,
                        contentDescription = "Offline",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "Offline",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FilledTonalButton(
                        onClick = onReconnect,
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
            // XR-only footer: cast button sits directly above the
            // profile/switch-user avatar, which stacks above the space-mode
            // toggle. The group shares a single Spacer.weight(1f) so it
            // floats to the bottom of the rail.
            if (xrSpaceMode != null) {
                if (!isOfflineMode) Spacer(modifier = Modifier.weight(1f))
                if (onVoiceClick != null) {
                    NavigationRailItem(
                        selected = false,
                        onClick = onVoiceClick,
                        icon = {
                            Icon(
                                painter = painterResource(CoreR.drawable.ic_microphone),
                                contentDescription = "Voice command",
                            )
                        },
                        label = { Text(text = "Voice") },
                    )
                }
                if (fcastSession != null) {
                    // `pickedTarget` covers all protocols (FCast + Cast + future AirPlay)
                    // so the XR nav-rail "selected" state lights up for any picked device.
                    val pickedTarget by fcastSession.pickedTarget.collectAsState()
                    NavigationRailItem(
                        selected = pickedTarget != null,
                        onClick = { fcastSession.showPicker() },
                        icon = {
                            Icon(
                                painter = painterResource(CoreR.drawable.ic_cast),
                                contentDescription = "Cast",
                            )
                        },
                        label = { Text(text = "Cast") },
                    )
                }
                if (currentUser != null) {
                    XrProfileRailButton(
                        user = currentUser,
                        serverAddress = currentServerAddress,
                        onClick = { navController.safeNavigate(UsersRoute) },
                    )
                }
                val (toggleLabel, toggleAction) = when (xrSpaceMode) {
                    XrSpaceMode.HOME -> "Immersive" to onEnterFullSpace
                    XrSpaceMode.FULL -> "Multitask" to onEnterHomeSpace
                }
                NavigationRailItem(
                    selected = false,
                    onClick = { toggleAction?.invoke() },
                    icon = {
                        Icon(
                            imageVector = Icons.Rounded.ViewInAr,
                            contentDescription = toggleLabel,
                        )
                    },
                    label = { Text(text = toggleLabel) },
                )
            }
        }
    }

    Box(modifier = panelModifier) {
        if (xrSpaceMode == XrSpaceMode.FULL && showBottomBar) {
            androidx.xr.compose.spatial.Orbiter(
                anchorPoint = androidx.xr.compose.spatial.OrbiterAnchorPoint.End,
                offset = androidx.xr.compose.unit.DpVolumeOffset(x = 24.dp)
            ) {
                androidx.compose.material3.Surface(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(32.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.15f))
                ) {
                    railContent()
                }
            }
        }
        Row(
            modifier = Modifier
                .widthIn(max = 1920.dp)
                .fillMaxHeight()
                .align(Alignment.Center)
        ) {
            if (xrSpaceMode != XrSpaceMode.FULL) {
                AnimatedVisibility(visible = showBottomBar) {
                    railContent()
                }
            }
            // App-side playback launcher (the PlayRequest seam). Browse screens
            // emit a PlayRequest; this maps it to XrPlayerActivity / Multitask /
            // FCast so the screens never touch :player:xr directly.
            val playbackLauncher = rememberPlaybackLauncher()
            NavHost(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                navController = navController,
                startDestination = startDestination,
                enterTransition = { fadeIn(tween(300)) },
                exitTransition = { fadeOut(tween(300)) },
            ) {
            composable<WelcomeRoute> {
                // GeminiNanoService lives in :player:xr; the onboarding screen (now in
                // :setup) only needs the resolved "is on-device AI supported?" boolean,
                // so the service is owned here and the result is passed down as a seam.
                val welcomeContext = LocalContext.current
                val geminiNanoService = remember(welcomeContext) {
                    GeminiNanoService(welcomeContext.applicationContext)
                }
                var aiSupported by remember { mutableStateOf<Boolean?>(null) }
                var aiStatusLoading by remember { mutableStateOf(true) }
                LaunchedEffect(Unit) {
                    aiStatusLoading = true
                    aiSupported = runCatching { geminiNanoService.status() }.getOrNull()?.supported
                    aiStatusLoading = false
                }
                DisposableEffect(Unit) {
                    onDispose { geminiNanoService.destroy() }
                }
                WelcomeScreen(
                    appPreferences = appPreferences,
                    onContinueToServerSetup = {
                        navController.safeNavigate(ServersRoute) {
                            popUpTo(WelcomeRoute) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                    onContinueToLocalLibrary = {
                        navController.safeNavigate(LocalRoute) {
                            popUpTo(WelcomeRoute) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                    aiSupported = aiSupported,
                    aiStatusLoading = aiStatusLoading,
                )
            }
            composable<ServersRoute> {
                ServersScreen(
                    navigateToUsers = { navController.safeNavigate(UsersRoute) },
                    navigateToAddresses = { serverId ->
                        navController.safeNavigate(ServerAddressesRoute(serverId))
                    },
                    onAddClick = { navController.safeNavigate(AddServerRoute) },
                    onBackClick = { navController.safePopBackStack() },
                    showBack = navController.previousBackStackEntry != null,
                )
            }
            composable<AddServerRoute> {
                AddServerScreen(
                    onSuccess = { navController.safeNavigate(UsersRoute) },
                    onBackClick = { navController.safePopBackStack() },
                )
            }
            composable<ServerAddressesRoute> { backStackEntry ->
                val route: ServerAddressesRoute = backStackEntry.toRoute()
                ServerAddressesScreen(
                    serverId = route.serverId,
                    navigateBack = { navController.safePopBackStack() },
                )
            }
            composable<UsersRoute> {
                UsersScreen(
                    navigateToHome = { navigateHome(navController) },
                    onChangeServerClick = {
                        navController.safeNavigate(ServersRoute) {
                            popUpTo(ServersRoute) { inclusive = false }
                            launchSingleTop = true
                        }
                    },
                    onAddClick = { navController.safeNavigate(LoginRoute()) },
                    onBackClick = { navController.safePopBackStack() },
                    onPublicUserClick = { username ->
                        navController.safeNavigate(LoginRoute(username = username))
                    },
                    showBack = navController.previousBackStackEntry != null,
                )
            }
            composable<LoginRoute> { backStackEntry ->
                val route: LoginRoute = backStackEntry.toRoute()
                LoginScreen(
                    onSuccess = {
                        navController.safeNavigate(HomeRoute) {
                            popUpTo(0)
                            launchSingleTop = true
                        }
                    },
                    onChangeServerClick = {
                        navController.safeNavigate(ServersRoute) {
                            popUpTo(ServersRoute) { inclusive = false }
                            launchSingleTop = true
                        }
                    },
                    onBackClick = { navController.safePopBackStack() },
                    prefilledUsername = route.username,
                )
            }

            composable<UniversalPluginsRoute> {
                var showMusicAssistantSettings by remember { mutableStateOf(false) }

                val sendspinState by dev.jdtech.jellyfin.sendspin.receiver.SendspinReceiverSession.state.collectAsStateWithLifecycle()
                val musicAssistantSubtitle = when (sendspinState.musicAssistantAuthState) {
                    dev.jdtech.jellyfin.sendspin.receiver.SendspinMusicAssistantAuthState.AUTHENTICATED -> sendspinState.musicAssistantServerUrl ?: "Configured server"
                    dev.jdtech.jellyfin.sendspin.receiver.SendspinMusicAssistantAuthState.AUTHENTICATING -> "Authenticating..."
                    dev.jdtech.jellyfin.sendspin.receiver.SendspinMusicAssistantAuthState.INVALID -> "Invalid credentials"
                    dev.jdtech.jellyfin.sendspin.receiver.SendspinMusicAssistantAuthState.ERROR -> "Connection error"
                    else -> "Not configured"
                }

                PluginSettingsScreen(
                    onPluginClick = { pluginId ->
                        navController.safeNavigate(PluginBrowseRoute(pluginId))
                    },
                    onJellyfinClick = {
                        navController.safeNavigate(ServersRoute)
                    },
                    onMusicAssistantClick = {
                        showMusicAssistantSettings = true
                    },
                    musicAssistantSubtitle = musicAssistantSubtitle,
                    onLocalClick = {
                        navController.safeNavigate(LocalRoute)
                    },
                    onNetworkClick = {
                        navController.safeNavigate(NetworkRoute)
                    }
                )

                if (showMusicAssistantSettings) {
                    SpatialDialog(onDismissRequest = { showMusicAssistantSettings = false }) {
                        MusicAssistantAuthDialog(onDismiss = { showMusicAssistantSettings = false })
                    }
                }
            }
            composable<PluginBrowseRoute> { backStackEntry ->
                val route: PluginBrowseRoute = backStackEntry.toRoute()
                dev.jdtech.jellyfin.plugins.ui.PluginBrowseScreen(
                    pluginId = route.pluginId,
                    rowId = route.rowId,
                    onBack = { navController.safePopBackStack() },
                    onItemClick = { item ->
                        navigateToItem(navController = navController, item = item)
                    }
                )
            }
            composable<HomeRoute> { entry ->
                // Guard against the Navigation-Compose race where the outgoing
                // start destination recomposes after its NavBackStackEntry is
                // destroyed — re-evaluating HomeScreen's `hiltViewModel()` default
                // on a dead entry throws IllegalStateException. Observing the
                // entry's lifecycle stops composing Home (and touching its
                // ViewModel) the moment it's destroyed.
                val homeLifecycleState by entry.lifecycle.currentStateAsState()
                if (homeLifecycleState != Lifecycle.State.DESTROYED) {
                HomeScreen(
                    appPreferences = appPreferences,
                    onLibraryClick = {
                        navController.safeNavigate(
                            LibraryRoute(
                                libraryId = it.id.toString(),
                                libraryName = it.name,
                                libraryType = it.type,
                            )
                        )
                    },
                    onSearchClick = {
                        searchExpanded = true
                        navController.safeNavigate(MediaRoute) {
                            popUpTo(navController.graph.startDestinationId) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onSettingsClick = {
                        navController.safeNavigate(
                            SettingsRoute(indexes = intArrayOf(CoreR.string.title_settings))
                        )
                    },
                    onManageServers = { navController.safeNavigate(ServersRoute) },
                    onReconnectClick = onReconnect,
                    onLanguageSettingsClick = {
                        navController.safeNavigate(
                            SettingsRoute(
                                indexes =
                                    intArrayOf(
                                        SettingsR.string.settings_category_language,
                                    )
                            )
                        )
                    },
                    onItemClick = { item ->
                        // MA home-row items (podcasts/audiobooks → detail, tracks
                        // → play) are handled by the host; fall through to normal
                        // Jellyfin navigation only when it's not an MA item.
                        if (onMaItemTap?.invoke(item) != true) {
                            navigateToItem(navController = navController, item = item)
                        }
                    },
                    onPluginBrowse = { pluginId, rowId ->
                        navController.safeNavigate(PluginBrowseRoute(pluginId = pluginId, rowId = rowId))
                    },
                    onNetworkShareSeeAll = { shareId ->
                        navController.safeNavigate(NetworkShareRoute(shareId = shareId))
                    },
                    onPlay = playbackLauncher,
                )
                }
            }
            composable<MediaRoute> {
                MediaScreen(
                    onItemClick = { item ->
                        navigateToItem(navController = navController, item = item)
                    },
                    onFavoritesClick = { navController.safeNavigate(FavoritesRoute) },
                    searchExpanded = searchExpanded,
                    onSearchExpand = { searchExpanded = it },
                    initialSearchQuery = pendingInitialSearchQuery,
                    onInitialSearchConsumed = { pendingInitialSearchQuery = null },
                )
            }
            composable<LocalRoute> {
                LocalMediaScreen(
                    appPreferences = appPreferences,
                    hasServers = hasServers,
                    onItemClick = { item ->
                        navController.safeNavigate(LocalVideoRoute(item.mediaStoreId))
                    },
                    onManageServersClick = { navController.safeNavigate(ServersRoute) },
                    onSettingsClick = {
                        navController.safeNavigate(
                            SettingsRoute(indexes = intArrayOf(CoreR.string.title_settings))
                        )
                    },
                    onLanguageSettingsClick = {
                        navController.safeNavigate(
                            SettingsRoute(
                                indexes =
                                    intArrayOf(
                                        SettingsR.string.settings_category_language,
                                    )
                            )
                        )
                    },
                    onVoiceSettingsClick = {
                        navController.safeNavigate(
                            SettingsRoute(
                                indexes =
                                    intArrayOf(
                                        SettingsR.string.settings_category_player,
                                        SettingsR.string.voice_controls,
                                    )
                            )
                        )
                    },
                )
            }
            composable<DownloadsRoute> {
                DownloadsScreen(
                    onItemClick = { item ->
                        navigateToItem(navController = navController, item = item)
                    }
                )
            }
            composable<LibraryRoute> { backStackEntry ->
                val route: LibraryRoute = backStackEntry.toRoute()
                val libraryId = UUID.fromString(route.libraryId)
                if (route.libraryType in AUDIO_COLLECTION_TYPES) {
                    JellyfinAudioLibraryScreen(
                        libraryId = libraryId,
                        libraryName = route.libraryName,
                        libraryType = route.libraryType,
                        onBack = { navController.safePopBackStack() },
                        onDetailClick = { id, title, detailType ->
                            navController.safeNavigate(
                                AudioDetailRoute(
                                    itemId = id.toString(),
                                    title = title,
                                    detailType = detailType,
                                    parentId = route.libraryId,
                                )
                            )
                        },
                    )
                } else {
                    LibraryScreen(
                        libraryId = libraryId,
                        libraryName = route.libraryName,
                        libraryType = route.libraryType,
                        onItemClick = { item ->
                            navigateToItem(navController = navController, item = item)
                        },
                        navigateBack = { navController.safePopBackStack() },
                    )
                }
            }
            composable<PhotoRoute> { backStackEntry ->
                val route: PhotoRoute = backStackEntry.toRoute()
                PhotoViewerScreen(
                    photoId = UUID.fromString(route.photoId),
                    parentId = route.parentId?.let(UUID::fromString),
                    onBack = { navController.safePopBackStack() },
                )
            }
            composable<AudioDetailRoute> { backStackEntry ->
                val route: AudioDetailRoute = backStackEntry.toRoute()
                JellyfinAudioDetailScreen(
                    itemId = UUID.fromString(route.itemId),
                    title = route.title,
                    detailType = route.detailType,
                    parentId = route.parentId?.let(UUID::fromString),
                    onBack = { navController.safePopBackStack() },
                )
            }
            composable<CollectionRoute> { backStackEntry ->
                val route: CollectionRoute = backStackEntry.toRoute()
                CollectionScreen(
                    collectionId = UUID.fromString(route.collectionId),
                    collectionName = route.collectionName,
                    onItemClick = { item ->
                        navigateToItem(navController = navController, item = item)
                    },
                    navigateBack = { navController.safePopBackStack() },
                )
            }
            composable<FavoritesRoute> {
                FavoritesScreen(
                    onItemClick = { item ->
                        navigateToItem(navController = navController, item = item)
                    },
                    navigateBack = { navController.safePopBackStack() },
                )
            }
            composable<MovieRoute> { backStackEntry ->
                val route: MovieRoute = backStackEntry.toRoute()
                MovieScreen(
                    movieId = UUID.fromString(route.movieId),
                    navigateBack = { navController.safePopBackStack() },
                    navigateHome = { navigateHome(navController) },
                    navigateToPerson = { personId ->
                        navController.safeNavigate(PersonRoute(personId.toString()))
                    },
                    onPlay = playbackLauncher,
                )
            }
            composable<ShowRoute> { backStackEntry ->
                val route: ShowRoute = backStackEntry.toRoute()
                ShowScreen(
                    showId = UUID.fromString(route.showId),
                    navigateBack = { navController.safePopBackStack() },
                    navigateHome = { navigateHome(navController) },
                    navigateToItem = { item ->
                        navigateToItem(navController = navController, item = item)
                    },
                    navigateToPerson = { personId ->
                        navController.safeNavigate(PersonRoute(personId.toString()))
                    },
                    onPlay = playbackLauncher,
                )
            }
            composable<SeasonRoute> { backStackEntry ->
                val route: SeasonRoute = backStackEntry.toRoute()
                SeasonScreen(
                    seasonId = UUID.fromString(route.seasonId),
                    navigateBack = { navController.safePopBackStack() },
                    navigateHome = { navigateHome(navController) },
                    navigateToItem = { item ->
                        navigateToItem(navController = navController, item = item)
                    },
                    navigateToSeries = { seriesId ->
                        navController.safeNavigate(ShowRoute(showId = seriesId.toString())) {
                            popUpTo(ShowRoute(showId = seriesId.toString()))
                            launchSingleTop = true
                        }
                    },
                    onPlay = playbackLauncher,
                )
            }
            composable<EpisodeRoute> { backStackEntry ->
                val route: EpisodeRoute = backStackEntry.toRoute()
                EpisodeScreen(
                    episodeId = UUID.fromString(route.episodeId),
                    navigateBack = { navController.safePopBackStack() },
                    navigateHome = { navigateHome(navController) },
                    navigateToPerson = { personId ->
                        navController.safeNavigate(PersonRoute(personId.toString()))
                    },
                    navigateToSeason = { seasonId ->
                        navController.safeNavigate(SeasonRoute(seasonId = seasonId.toString())) {
                            popUpTo(SeasonRoute(seasonId = seasonId.toString()))
                            launchSingleTop = true
                        }
                    },
                    onPlay = playbackLauncher,
                )
            }
            composable<LocalVideoRoute> { backStackEntry ->
                val route: LocalVideoRoute = backStackEntry.toRoute()
                LocalVideoScreen(
                    mediaStoreId = route.mediaStoreId,
                    navigateBack = { navController.safePopBackStack() },
                    onPlay = playbackLauncher,
                )
            }
            composable<NetworkRoute> {
                NetworkScreen(
                    onShareClick = { share ->
                        navController.safeNavigate(NetworkShareRoute(shareId = share.id))
                    },
                    onAddShareClick = { navController.safeNavigate(NetworkAddShareRoute) },
                    onItemClick = { item ->
                        navController.safeNavigate(NetworkVideoRoute(videoId = item.networkVideoId))
                    },
                    onSettingsClick = {
                        navController.safeNavigate(
                            SettingsRoute(indexes = intArrayOf(CoreR.string.title_settings))
                        )
                    },
                )
            }
            composable<NetworkAddShareRoute> {
                AddShareScreen(
                    navigateBack = { navController.safePopBackStack() },
                )
            }
            composable<NetworkShareRoute> { backStackEntry ->
                val route: NetworkShareRoute = backStackEntry.toRoute()
                NetworkShareScreen(
                    shareId = route.shareId,
                    navigateBack = { navController.safePopBackStack() },
                    onItemClick = { item ->
                        navController.safeNavigate(NetworkVideoRoute(videoId = item.networkVideoId))
                    },
                )
            }
            composable<NetworkVideoRoute> { backStackEntry ->
                val route: NetworkVideoRoute = backStackEntry.toRoute()
                NetworkVideoScreen(
                    videoId = route.videoId,
                    navigateBack = { navController.safePopBackStack() },
                    onPlay = playbackLauncher,
                )
            }
            composable<PersonRoute> { backStackEntry ->
                val route: PersonRoute = backStackEntry.toRoute()
                PersonScreen(
                    personId = UUID.fromString(route.personId),
                    navigateBack = { navController.safePopBackStack() },
                    navigateHome = { navigateHome(navController) },
                    navigateToItem = { item ->
                        navigateToItem(navController = navController, item = item)
                    },
                )
            }
            composable<SettingsRoute> { backStackEntry ->
                val route: SettingsRoute = backStackEntry.toRoute()
                SettingsScreen(
                    indexes = route.indexes,
                    navigateToSettings = { indexes ->
                        navController.safeNavigate(SettingsRoute(indexes = indexes))
                    },
                    navigateToServers = { navController.safeNavigate(ServersRoute) },
                    navigateToUsers = { navController.safeNavigate(UsersRoute) },
                    navigateToAbout = { navController.safeNavigate(AboutRoute) },
                    navigateBack = { navController.safePopBackStack() },
                    appPreferences = appPreferences,
                )
            }
            composable<AboutRoute> {
                AboutScreen(navigateBack = { navController.safePopBackStack() })
            }
            }
        }
        if (fcastSession != null) {
            dev.spatialfin.fcast.session.FCastGlobalPickerHost(sessionManager = fcastSession)
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 16.dp, end = 16.dp)
                .widthIn(max = 360.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (fcastSession != null) {
                dev.spatialfin.fcast.session.FCastMiniController(sessionManager = fcastSession)
            }
            LocalAudioPlaybackDispatcher.current?.let { dispatcher ->
                JellyfinAudioMiniPlayer(
                    dispatcher = dispatcher,
                    onExpand = { showAudioNowPlaying = true },
                )
            }
        }
        LocalAudioPlaybackDispatcher.current?.let { dispatcher ->
            if (showAudioNowPlaying) {
                androidx.activity.compose.BackHandler(onBack = { showAudioNowPlaying = false })
                JellyfinAudioNowPlayingScreen(
                    dispatcher = dispatcher,
                    onBack = { showAudioNowPlaying = false },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
    }
}

private fun navigateHome(navController: NavHostController) {
    navController.safeNavigate(HomeRoute) {
        popUpTo(navController.graph.startDestinationId)
        launchSingleTop = true
    }
}

private fun navigateToItem(navController: NavHostController, item: SpatialFinItem) {
    val context = navController.context
    when (item) {
        is UniversalSpatialFinItem -> {
            context.startActivity(
                XrPlayerActivity.createIntentForUniversalMedia(
                    context = context,
                    pluginId = item.universalMediaItem.pluginId,
                    itemId = item.universalMediaItem.id,
                    videoUrl = item.universalMediaItem.videoUrl,
                    title = item.name,
                    stereoMode = item.universalMediaItem.stereoMode,
                    projection = item.universalMediaItem.projection,
                )
            )
        }
        is SpatialFinBoxSet ->
            navController.safeNavigate(
                CollectionRoute(collectionId = item.id.toString(), collectionName = item.name)
            )
        is SpatialFinPhoto ->
            navController.safeNavigate(
                PhotoRoute(photoId = item.id.toString(), parentId = item.parentId?.toString())
            )
        is SpatialFinMovie -> navController.safeNavigate(MovieRoute(movieId = item.id.toString()))
        is SpatialFinShow -> navController.safeNavigate(ShowRoute(showId = item.id.toString()))
        is SpatialFinSeason -> navController.safeNavigate(SeasonRoute(seasonId = item.id.toString()))
        is SpatialFinEpisode ->
            navController.safeNavigate(EpisodeRoute(episodeId = item.id.toString()))
        is SpatialFinMusicAlbum ->
            navController.safeNavigate(
                AudioDetailRoute(
                    itemId = item.id.toString(),
                    title = item.name,
                    detailType = JellyfinAudioDetailType.Album,
                )
            )
        is SpatialFinMusicArtist ->
            navController.safeNavigate(
                AudioDetailRoute(
                    itemId = item.id.toString(),
                    title = item.name,
                    detailType = JellyfinAudioDetailType.Artist,
                )
            )
        is SpatialFinPlaylist ->
            navController.safeNavigate(
                AudioDetailRoute(
                    itemId = item.id.toString(),
                    title = item.name,
                    detailType = JellyfinAudioDetailType.Playlist,
                )
            )
        is SpatialFinAudioBook ->
            navController.safeNavigate(
                AudioDetailRoute(
                    itemId = item.id.toString(),
                    title = item.name,
                    detailType = JellyfinAudioDetailType.Book,
                )
            )
        is SpatialFinCollection ->
            navController.safeNavigate(
                LibraryRoute(
                    libraryId = item.id.toString(),
                    libraryName = item.name,
                    libraryType = item.type,
                )
            )
        is LocalVideoItem -> navController.safeNavigate(LocalVideoRoute(item.mediaStoreId))
        is NetworkVideoItem -> navController.safeNavigate(NetworkVideoRoute(videoId = item.networkVideoId))
        is SpatialFinFolder ->
            navController.safeNavigate(
                LibraryRoute(
                    libraryId = item.id.toString(),
                    libraryName = item.name,
                    libraryType = CollectionType.Folders,
                )
            )
        else -> Unit
    }
}

private fun <T : Any> NavHostController.safeNavigate(
    route: T,
    navOptions: NavOptions? = null,
    navigatorExtras: Navigator.Extras? = null,
) {
    if (this.currentBackStackEntry?.lifecycle?.currentState == Lifecycle.State.RESUMED) {
        this.navigate(route, navOptions, navigatorExtras)
    }
}

private fun <T : Any> NavHostController.safeNavigate(
    route: T,
    builder: NavOptionsBuilder.() -> Unit,
) {
    if (this.currentBackStackEntry?.lifecycle?.currentState == Lifecycle.State.RESUMED) {
        this.navigate(route, builder)
    }
}

private fun NavHostController.safePopBackStack(): Boolean {
    return if (this.currentBackStackEntry?.lifecycle?.currentState == Lifecycle.State.RESUMED) {
        this.popBackStack()
    } else {
        false
    }
}

/**
 * Circular avatar that opens the user-switcher when tapped. Loads the active
 * user's Jellyfin PrimaryImage and falls back to a single-letter initials
 * disc when the URL is null or the request fails (server not configured,
 * user has no avatar uploaded, offline cache miss, etc.).
 */
@Composable
private fun XrProfileRailButton(
    user: User,
    serverAddress: String?,
    onClick: () -> Unit,
) {
    val avatarUri = userPrimaryImageUri(serverAddress, user.id)
    val initial = user.name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    Box(
        modifier = Modifier
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .size(48.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (avatarUri != null) {
            AsyncImage(
                model = avatarUri,
                contentDescription = "Switch user (${user.name})",
                modifier = Modifier.fillMaxSize().clip(CircleShape),
                contentScale = ContentScale.Crop,
            )
        }
        // Initials underneath act as the fallback if the image fails to load.
        // AsyncImage paints opaque on success and covers them.
        Text(
            text = initial,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
