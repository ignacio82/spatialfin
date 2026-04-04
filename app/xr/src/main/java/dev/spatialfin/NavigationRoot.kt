package dev.spatialfin

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import dev.jdtech.jellyfin.models.SpatialFinBoxSet
import dev.jdtech.jellyfin.models.SpatialFinCollection
import dev.jdtech.jellyfin.models.SpatialFinEpisode
import dev.jdtech.jellyfin.models.SpatialFinFolder
import dev.jdtech.jellyfin.models.SpatialFinItem
import dev.jdtech.jellyfin.models.SpatialFinMovie
import dev.jdtech.jellyfin.models.SpatialFinSeason
import dev.jdtech.jellyfin.models.SpatialFinShow
import dev.jdtech.jellyfin.presentation.film.CollectionScreen
import dev.jdtech.jellyfin.presentation.film.DownloadsScreen
import dev.jdtech.jellyfin.presentation.film.EpisodeScreen
import dev.jdtech.jellyfin.presentation.film.FavoritesScreen
import dev.jdtech.jellyfin.presentation.film.HomeScreen
import dev.jdtech.jellyfin.presentation.film.LibraryScreen
import dev.jdtech.jellyfin.presentation.film.MediaScreen
import dev.jdtech.jellyfin.presentation.film.MovieScreen
import dev.jdtech.jellyfin.presentation.film.PersonScreen
import dev.jdtech.jellyfin.presentation.film.SeasonScreen
import dev.jdtech.jellyfin.presentation.film.ShowScreen
import dev.jdtech.jellyfin.presentation.local.LocalMediaScreen
import dev.jdtech.jellyfin.presentation.local.LocalVideoScreen
import dev.jdtech.jellyfin.presentation.network.AddShareScreen
import dev.jdtech.jellyfin.presentation.network.NetworkScreen
import dev.jdtech.jellyfin.presentation.network.NetworkShareScreen
import dev.jdtech.jellyfin.presentation.network.NetworkVideoScreen
import dev.jdtech.jellyfin.presentation.settings.AboutScreen
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

@Serializable
data class LibraryRoute(
    val libraryId: String,
    val libraryName: String,
    val libraryType: CollectionType,
)

@Serializable data class CollectionRoute(val collectionId: String, val collectionName: String)

@Serializable data object FavoritesRoute

@Serializable data class MovieRoute(val movieId: String)

@Serializable data class ShowRoute(val showId: String)

@Serializable data class EpisodeRoute(val episodeId: String)

@Serializable data class LocalVideoRoute(val mediaStoreId: Long)

@Serializable data class SeasonRoute(val seasonId: String)

@Serializable data class PersonRoute(val personId: String)

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
                    listOf(homeTab, mediaTab, localTab, networkTab, downloadsTab)
                } else {
                    listOf(localTab, networkTab)
                }
            true ->
                if (hasServers) {
                    listOf(homeTab, localTab, networkTab, downloadsTab)
                } else {
                    listOf(localTab, networkTab)
                }
        }
    val navigationItemClassNames = navigationItems.map { it.route::class.qualifiedName }

    val navBackStackEntry by navController.currentBackStackEntryAsState()

    var searchExpanded by remember { mutableStateOf(false) }
    var pendingInitialSearchQuery by remember(initialSearchQuery) { mutableStateOf(initialSearchQuery) }

    val currentRoute = navBackStackEntry?.destination?.route
    val showBottomBar = currentRoute in navigationItemClassNames && !searchExpanded

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
    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.material3.MaterialTheme.colorScheme.surface.copy(alpha = 0.65f))
    ) {
        AnimatedVisibility(visible = showBottomBar) {
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
            }
        }
        NavHost(
            navController = navController,
            startDestination = startDestination,
            enterTransition = { fadeIn(tween(300)) },
            exitTransition = { fadeOut(tween(300)) },
        ) {
            composable<WelcomeRoute> {
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
            composable<HomeRoute> {
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
                        navigateToItem(navController = navController, item = item)
                    },
                )
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
                LibraryScreen(
                    libraryId = UUID.fromString(route.libraryId),
                    libraryName = route.libraryName,
                    libraryType = route.libraryType,
                    onItemClick = { item ->
                        navigateToItem(navController = navController, item = item)
                    },
                    navigateBack = { navController.safePopBackStack() },
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
                )
            }
            composable<LocalVideoRoute> { backStackEntry ->
                val route: LocalVideoRoute = backStackEntry.toRoute()
                LocalVideoScreen(
                    mediaStoreId = route.mediaStoreId,
                    navigateBack = { navController.safePopBackStack() },
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
                )
            }
            composable<AboutRoute> {
                AboutScreen(navigateBack = { navController.safePopBackStack() })
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
    when (item) {
        is SpatialFinBoxSet ->
            navController.safeNavigate(
                CollectionRoute(collectionId = item.id.toString(), collectionName = item.name)
            )
        is SpatialFinMovie -> navController.safeNavigate(MovieRoute(movieId = item.id.toString()))
        is SpatialFinShow -> navController.safeNavigate(ShowRoute(showId = item.id.toString()))
        is SpatialFinSeason -> navController.safeNavigate(SeasonRoute(seasonId = item.id.toString()))
        is SpatialFinEpisode ->
            navController.safeNavigate(EpisodeRoute(episodeId = item.id.toString()))
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
