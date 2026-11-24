package dev.spatialfin

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.ViewInAr
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph
import androidx.navigation.NavHostController
import androidx.navigation.NavOptions
import androidx.navigation.NavOptionsBuilder
import androidx.navigation.Navigator
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.createGraph
import androidx.navigation.toRoute
import androidx.xr.compose.spatial.Orbiter
import androidx.xr.compose.spatial.OrbiterAlignment
import androidx.xr.compose.spatial.SpatialDialog
import androidx.xr.compose.unit.DpVolumeOffset
import coil3.compose.AsyncImage
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.presentation.components.userPrimaryImageUri
import dev.jdtech.jellyfin.models.CollectionType
import dev.jdtech.jellyfin.models.LocalVideoItem
import dev.jdtech.jellyfin.models.NetworkVideoItem
import dev.jdtech.jellyfin.models.SpatialFinAudioBook
import dev.jdtech.jellyfin.models.SpatialFinBoxSet
import dev.jdtech.jellyfin.models.SpatialFinCollection
import dev.jdtech.jellyfin.models.SpatialFinEpisode
import dev.jdtech.jellyfin.models.SpatialFinFolder
import dev.jdtech.jellyfin.models.SpatialFinItem
import dev.jdtech.jellyfin.models.SpatialFinMovie
import dev.jdtech.jellyfin.models.SpatialFinMusicAlbum
import dev.jdtech.jellyfin.models.SpatialFinMusicArtist
import dev.jdtech.jellyfin.models.SpatialFinPhoto
import dev.jdtech.jellyfin.models.SpatialFinPlaylist
import dev.jdtech.jellyfin.models.SpatialFinSeason
import dev.jdtech.jellyfin.models.SpatialFinShow
import dev.jdtech.jellyfin.models.User
import dev.jdtech.jellyfin.onboarding.OnboardingFailureCode
import dev.jdtech.jellyfin.player.xr.XrPlayerActivity
import dev.jdtech.jellyfin.plugins.model.UniversalSpatialFinItem
import dev.jdtech.jellyfin.plugins.ui.PluginBrowseScreen
import dev.jdtech.jellyfin.plugins.ui.PluginSettingsScreen
import dev.jdtech.jellyfin.presentation.cast.LocalCastButtonController
import dev.jdtech.jellyfin.presentation.film.CollectionScreen
import dev.jdtech.jellyfin.presentation.film.DownloadsScreen
import dev.jdtech.jellyfin.presentation.film.EpisodeScreen
import dev.jdtech.jellyfin.presentation.film.FavoritesScreen
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
import dev.jdtech.jellyfin.presentation.music.LocalMaCardActionsRenderer
import dev.jdtech.jellyfin.presentation.network.AddShareScreen
import dev.jdtech.jellyfin.presentation.network.NetworkScreen
import dev.jdtech.jellyfin.presentation.network.NetworkShareScreen
import dev.jdtech.jellyfin.presentation.network.NetworkVideoScreen
import dev.jdtech.jellyfin.presentation.player.PlayRequest
import dev.jdtech.jellyfin.presentation.settings.AboutScreen
import dev.jdtech.jellyfin.presentation.settings.LocalSubtitlePreviewCard
import dev.jdtech.jellyfin.presentation.settings.LocalVoicePickerDialog
import dev.jdtech.jellyfin.presentation.settings.MusicAssistantAuthDialog
import dev.jdtech.jellyfin.presentation.settings.SettingsScreen
import dev.jdtech.jellyfin.presentation.setup.addresses.ServerAddressesScreen
import dev.jdtech.jellyfin.presentation.setup.addserver.AddServerScreen
import dev.jdtech.jellyfin.presentation.setup.login.LoginScreen
import dev.jdtech.jellyfin.presentation.setup.servers.ServersScreen
import dev.jdtech.jellyfin.presentation.setup.users.UsersScreen
import dev.jdtech.jellyfin.presentation.setup.welcome.SetUpFromDeviceScreen
import dev.jdtech.jellyfin.presentation.setup.welcome.WelcomeScreen
import dev.jdtech.jellyfin.presentation.utils.LocalOfflineMode
import dev.jdtech.jellyfin.sendspin.receiver.SendspinMusicAssistantAuthState
import dev.jdtech.jellyfin.sendspin.receiver.SendspinReceiverSession
import dev.jdtech.jellyfin.session.RouteRole
import dev.jdtech.jellyfin.session.SessionMode
import dev.jdtech.jellyfin.session.SessionNavigationPolicy
import dev.jdtech.jellyfin.settings.R as SettingsR
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.setup.R as SetupR
import dev.jdtech.jellyfin.setup.lan.LanSetupController
import dev.jdtech.jellyfin.setup.lan.LanSetupNavigationEvent
import dev.jdtech.jellyfin.viewmodels.MainState
import dev.spatialfin.fcast.session.CastButtonControllerAdapter
import dev.spatialfin.fcast.session.CastSessionManager
import dev.spatialfin.fcast.session.FCastGlobalPickerHost
import dev.spatialfin.fcast.session.FCastMiniController
import dev.spatialfin.fcast.session.LocalFCastSession
import dev.spatialfin.unified.LocalMaPlayDispatcher
import dev.spatialfin.unified.XrSpaceMode
import dev.spatialfin.unified.audio.JellyfinAudioDetailScreen
import dev.spatialfin.unified.audio.JellyfinAudioDetailType
import dev.spatialfin.unified.audio.JellyfinAudioLibraryScreen
import dev.spatialfin.unified.audio.JellyfinAudioMiniPlayer
import dev.spatialfin.unified.audio.JellyfinAudioNowPlayingScreen
import dev.spatialfin.unified.audio.LocalAudioPlaybackDispatcher
import dev.spatialfin.unified.music.MaCardActionsMenu
import java.util.UUID
import kotlin.reflect.KClass
import kotlinx.serialization.Serializable

@Serializable data object WelcomeRoute

@Serializable data class SetUpFromDeviceRoute(val clearCompletedDrain: Boolean = true)

@Serializable data object ServersRoute

@Serializable data object AddServerRoute

@Serializable data class ServerAddressesRoute(val serverId: String)

@Serializable
data class UsersRoute(
    val serverId: String,
    val serverName: String,
    val serverAddress: String,
)

@Serializable
data class LoginRoute(
    val serverId: String,
    val serverName: String,
    val serverAddress: String,
    val prefilledUsername: String? = null,
    val lanSetupSessionId: String? = null,
    val initialFailureCodeWire: String? = null,
    val isPersistedServer: Boolean = true,
)

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

@Serializable
data class SettingsRoute(val indexes: IntArray = intArrayOf(SettingsR.string.title_settings)) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SettingsRoute) return false
        return indexes.contentEquals(other.indexes)
    }

    override fun hashCode(): Int = indexes.contentHashCode()
}

@Serializable data object AboutRoute

typealias PlaybackLauncher = (PlayRequest) -> Unit

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
val settingsTab =
    TabBarItem(
        title = SettingsR.string.title_settings,
        icon = CoreR.drawable.ic_settings,
        route = SettingsRoute(),
    )

private val AUDIO_COLLECTION_TYPES =
    setOf(CollectionType.Music, CollectionType.Playlists, CollectionType.Books)

internal fun xrRouteClassToRole(routeClass: KClass<*>): RouteRole =
    when (routeClass) {
        WelcomeRoute::class -> RouteRole.SOURCE_PICKER
        AddServerRoute::class,
        ServersRoute::class,
        SetUpFromDeviceRoute::class,
        ServerAddressesRoute::class,
        NetworkAddShareRoute::class -> RouteRole.SETUP_FLOW
        UsersRoute::class,
        LoginRoute::class -> RouteRole.REAUTH_GATE
        DownloadsRoute::class,
        LocalRoute::class,
        LocalVideoRoute::class,
        NetworkRoute::class,
        NetworkShareRoute::class,
        NetworkVideoRoute::class,
        UniversalPluginsRoute::class,
        PluginBrowseRoute::class,
        MovieRoute::class,
        ShowRoute::class,
        SeasonRoute::class,
        EpisodeRoute::class -> RouteRole.OFFLINE_OR_LOCAL_CONTENT
        SettingsRoute::class,
        AboutRoute::class -> RouteRole.SETTINGS
        HomeRoute::class,
        MediaRoute::class,
        LibraryRoute::class,
        AudioDetailRoute::class,
        CollectionRoute::class,
        FavoritesRoute::class,
        PersonRoute::class,
        PhotoRoute::class -> RouteRole.SERVER_CONTENT
        else -> RouteRole.SERVER_CONTENT
    }

internal fun Any.toXrRouteRole(): RouteRole = xrRouteClassToRole(this::class)

internal fun NavDestination?.toXrRouteRole(): RouteRole? {
    val dest = this ?: return null
    return when {
        dest.hasRoute(WelcomeRoute::class) -> RouteRole.SOURCE_PICKER
        dest.hasRoute(AddServerRoute::class) ||
            dest.hasRoute(ServersRoute::class) ||
            dest.hasRoute(SetUpFromDeviceRoute::class) ||
            dest.hasRoute(ServerAddressesRoute::class) ||
            dest.hasRoute(NetworkAddShareRoute::class) -> RouteRole.SETUP_FLOW
        dest.hasRoute(UsersRoute::class) ||
            dest.hasRoute(LoginRoute::class) -> RouteRole.REAUTH_GATE
        dest.hasRoute(DownloadsRoute::class) ||
            dest.hasRoute(LocalRoute::class) ||
            dest.hasRoute(LocalVideoRoute::class) ||
            dest.hasRoute(NetworkRoute::class) ||
            dest.hasRoute(NetworkShareRoute::class) ||
            dest.hasRoute(NetworkVideoRoute::class) ||
            dest.hasRoute(UniversalPluginsRoute::class) ||
            dest.hasRoute(PluginBrowseRoute::class) ||
            dest.hasRoute(MovieRoute::class) ||
            dest.hasRoute(ShowRoute::class) ||
            dest.hasRoute(SeasonRoute::class) ||
            dest.hasRoute(EpisodeRoute::class) -> RouteRole.OFFLINE_OR_LOCAL_CONTENT
        dest.hasRoute(SettingsRoute::class) ||
            dest.hasRoute(AboutRoute::class) -> RouteRole.SETTINGS
        dest.hasRoute(HomeRoute::class) ||
            dest.hasRoute(MediaRoute::class) ||
            dest.hasRoute(LibraryRoute::class) ||
            dest.hasRoute(AudioDetailRoute::class) ||
            dest.hasRoute(CollectionRoute::class) ||
            dest.hasRoute(FavoritesRoute::class) ||
            dest.hasRoute(PersonRoute::class) ||
            dest.hasRoute(PhotoRoute::class) -> RouteRole.SERVER_CONTENT
        else -> null
    }
}

internal fun xrDefaultHomeRoute(
    sessionMode: SessionMode,
    offlineBrowseEntered: Boolean,
): Any? =
    when (sessionMode) {
        SessionMode.SERVER_AUTHENTICATED -> HomeRoute
        SessionMode.STANDALONE_SOURCES -> LocalRoute
        SessionMode.SERVER_NEEDS_AUTH -> if (offlineBrowseEntered) DownloadsRoute else null
        SessionMode.UNCONFIGURED -> null
    }

internal fun xrReauthGateRoute(state: MainState): Any {
    val sid = state.activeServerId
    val addr = state.currentServerAddress
    return if (!sid.isNullOrBlank() && !addr.isNullOrBlank()) {
        UsersRoute(
            serverId = sid,
            serverName = state.activeServerName.orEmpty(),
            serverAddress = addr,
        )
    } else {
        ServersRoute
    }
}

internal fun xrInitialStartRoute(
    state: MainState,
    offlineBrowseEntered: Boolean = false,
): Any =
    when (state.sessionMode) {
        SessionMode.SERVER_AUTHENTICATED -> HomeRoute
        SessionMode.STANDALONE_SOURCES -> LocalRoute
        SessionMode.SERVER_NEEDS_AUTH ->
            if (offlineBrowseEntered) DownloadsRoute else xrReauthGateRoute(state)
        SessionMode.UNCONFIGURED -> WelcomeRoute
    }

internal fun xrNavigationItems(
    sessionMode: SessionMode,
    isOfflineMode: Boolean,
    offlineBrowseEntered: Boolean,
): List<TabBarItem> =
    when (sessionMode) {
        SessionMode.SERVER_AUTHENTICATED ->
            if (isOfflineMode) {
                listOf(homeTab, downloadsTab, sourcesTab)
            } else {
                listOf(homeTab, mediaTab, downloadsTab, sourcesTab)
            }
        SessionMode.STANDALONE_SOURCES ->
            listOf(localTab, networkTab, sourcesTab, settingsTab)
        SessionMode.SERVER_NEEDS_AUTH ->
            if (offlineBrowseEntered) {
                listOf(localTab, networkTab, downloadsTab, sourcesTab, settingsTab)
            } else {
                emptyList()
            }
        SessionMode.UNCONFIGURED -> emptyList()
    }

internal data class XrSessionObserverSnapshot(
    val offlineBrowseEntered: Boolean = false,
    val savedCoordinatorInstanceId: String = "",
    val lastObservedActivationEpoch: Long = -1L,
    val lastObservedSessionMode: SessionMode? = null,
)

internal sealed interface XrTransitionAction {
    data object None : XrTransitionAction

    /** Mutates `navGraph.setStartDestination(target)` and clears backstack up to previous start. */
    data class ResetRoot(val target: Any) : XrTransitionAction

    /** Pops a deleted persisted server setup/reauth route back to [target] (`ServersRoute` or `WelcomeRoute`). */
    data class PopMissingServer(val target: Any) : XrTransitionAction
}

internal data class XrSessionTransitionResult(
    val action: XrTransitionAction,
    val nextObserver: XrSessionObserverSnapshot,
)

internal fun evaluateXrSessionTransition(
    state: MainState,
    currentRole: RouteRole,
    targetServerId: String?,
    isPersistedServer: Boolean,
    observer: XrSessionObserverSnapshot,
): XrSessionTransitionResult {
    // Frame 1 safety: never reset offlineBrowseEntered or overwrite lastObservedSessionMode while loading.
    if (state.isLoading) {
        return XrSessionTransitionResult(action = XrTransitionAction.None, nextObserver = observer)
    }

    val mode = state.sessionMode
    var offlineBrowse =
        if (mode != SessionMode.SERVER_NEEDS_AUTH) false else observer.offlineBrowseEntered

    val coordinatorChanged =
        observer.savedCoordinatorInstanceId.isNotEmpty() &&
            state.coordinatorInstanceId.isNotEmpty() &&
            observer.savedCoordinatorInstanceId != state.coordinatorInstanceId
    val effectivePrevEpoch =
        if (coordinatorChanged) 0L else observer.lastObservedActivationEpoch
    val effectivePrevMode = observer.lastObservedSessionMode

    val activationEpochChanged =
        state.activationEpoch > 0L && state.activationEpoch != effectivePrevEpoch
    val wasAuthenticated = effectivePrevMode == SessionMode.SERVER_AUTHENTICATED

    val action: XrTransitionAction =
        when {
            SessionNavigationPolicy.shouldResetToSourcePickerOnUnconfiguredTransition(
                previousMode = effectivePrevMode,
                currentMode = mode,
                role = currentRole,
            ) -> XrTransitionAction.ResetRoot(WelcomeRoute)

            SessionNavigationPolicy.shouldNavigateHomeOnActivation(
                mode = mode,
                role = currentRole,
                activationEpochChanged = activationEpochChanged,
                wasAuthenticated = wasAuthenticated,
            ) -> {
                offlineBrowse = false
                val target = if (mode == SessionMode.STANDALONE_SOURCES) LocalRoute else HomeRoute
                XrTransitionAction.ResetRoot(target)
            }

            SessionNavigationPolicy.shouldRedirectToSetup(mode, currentRole) ->
                XrTransitionAction.ResetRoot(WelcomeRoute)

            SessionNavigationPolicy.shouldPopMissingServerRoute(
                role = currentRole,
                targetServerId = targetServerId,
                isPersistedServer = isPersistedServer,
                savedServerIds = state.savedServerIds,
            ) -> {
                val fallback: Any = if (state.hasServers) ServersRoute else WelcomeRoute
                XrTransitionAction.PopMissingServer(fallback)
            }

            SessionNavigationPolicy.shouldRedirectToReauthGate(mode, currentRole, offlineBrowse) ->
                XrTransitionAction.ResetRoot(xrReauthGateRoute(state))

            SessionNavigationPolicy.shouldRedirectFromStandalone(mode, currentRole) ->
                XrTransitionAction.ResetRoot(LocalRoute)

            SessionNavigationPolicy.shouldExitSourcePicker(mode, currentRole) -> {
                val target = if (mode == SessionMode.STANDALONE_SOURCES) LocalRoute else HomeRoute
                XrTransitionAction.ResetRoot(target)
            }

            else -> XrTransitionAction.None
        }

    val nextObserver =
        XrSessionObserverSnapshot(
            offlineBrowseEntered = offlineBrowse,
            savedCoordinatorInstanceId = state.coordinatorInstanceId,
            lastObservedActivationEpoch = state.activationEpoch,
            lastObservedSessionMode = mode,
        )

    return XrSessionTransitionResult(action = action, nextObserver = nextObserver)
}

/**
 * Hoisted observer state surviving `LOCKED <-> UNLOCKED`, `HOME <-> FULL` space swaps, and
 * configuration changes in `UnifiedMainActivity`.
 */
class XrNavigationObserverState(
    initialOfflineBrowseEntered: Boolean = false,
    initialSavedCoordinatorInstanceId: String = "",
    initialLastObservedActivationEpoch: Long = -1L,
    initialLastObservedSessionMode: SessionMode? = null,
) {
    var offlineBrowseEntered by mutableStateOf(initialOfflineBrowseEntered)
    var savedCoordinatorInstanceId by mutableStateOf(initialSavedCoordinatorInstanceId)
    var lastObservedActivationEpoch by mutableLongStateOf(initialLastObservedActivationEpoch)
    var lastObservedSessionMode by mutableStateOf(initialLastObservedSessionMode)

    internal fun toSnapshot(): XrSessionObserverSnapshot =
        XrSessionObserverSnapshot(
            offlineBrowseEntered = offlineBrowseEntered,
            savedCoordinatorInstanceId = savedCoordinatorInstanceId,
            lastObservedActivationEpoch = lastObservedActivationEpoch,
            lastObservedSessionMode = lastObservedSessionMode,
        )

    internal fun applySnapshot(snapshot: XrSessionObserverSnapshot) {
        offlineBrowseEntered = snapshot.offlineBrowseEntered
        savedCoordinatorInstanceId = snapshot.savedCoordinatorInstanceId
        lastObservedActivationEpoch = snapshot.lastObservedActivationEpoch
        lastObservedSessionMode = snapshot.lastObservedSessionMode
    }

    companion object {
        val Saver: Saver<XrNavigationObserverState, Any> =
            listSaver(
                save = {
                    listOf(
                        it.offlineBrowseEntered,
                        it.savedCoordinatorInstanceId,
                        it.lastObservedActivationEpoch,
                        it.lastObservedSessionMode?.name ?: "",
                    )
                },
                restore = { saved ->
                    val offline = saved.getOrNull(0) as? Boolean ?: false
                    val coordId = saved.getOrNull(1) as? String ?: ""
                    val epoch = saved.getOrNull(2) as? Long ?: -1L
                    val modeName = (saved.getOrNull(3) as? String).orEmpty()
                    val mode = modeName.takeIf { it.isNotEmpty() }?.let {
                        runCatching { SessionMode.valueOf(it) }.getOrNull()
                    }
                    XrNavigationObserverState(
                        initialOfflineBrowseEntered = offline,
                        initialSavedCoordinatorInstanceId = coordId,
                        initialLastObservedActivationEpoch = epoch,
                        initialLastObservedSessionMode = mode,
                    )
                },
            )
    }
}

@Composable
fun rememberXrNavigationObserverState(): XrNavigationObserverState =
    rememberSaveable(saver = XrNavigationObserverState.Saver) {
        XrNavigationObserverState()
    }

data class XrNavHostBridge(
    val state: MainState,
    val offlineBrowseEntered: Boolean,
    val xrSpaceMode: XrSpaceMode?,
    val searchExpanded: Boolean,
    val onSearchExpand: (Boolean) -> Unit,
    val pendingInitialSearchQuery: String?,
    val onInitialSearchConsumed: () -> Unit,
    val lanSetupController: LanSetupController,
    val onEnterFullSpace: (() -> Unit)?,
    val onEnterHomeSpace: (() -> Unit)?,
    val onReconnect: () -> Unit,
    val onBrowseOfflineMedia: () -> Unit,
    val onMaItemTap: ((SpatialFinItem) -> Boolean)?,
    val onVoiceClick: (() -> Unit)?,
    val playbackLauncher: PlaybackLauncher,
    val onLogout: () -> Unit,
    val onActivateStandalone: () -> Unit,
)

val LocalXrNavHostBridge =
    compositionLocalOf<XrNavHostBridge> {
        error("LocalXrNavHostBridge must be provided by NavigationRoot")
    }

fun buildXrNavGraph(
    navController: NavHostController,
    startDestination: Any,
    appPreferences: AppPreferences,
): NavGraph =
    navController.createGraph(startDestination = startDestination) {
        composable<WelcomeRoute> {
            val bridge = LocalXrNavHostBridge.current
            WelcomeScreen(
                onConnectToServer = {
                    val target: Any =
                        if (bridge.state.hasServers) ServersRoute else AddServerRoute
                    navController.safeNavigate(target)
                },
                onSetUpFromDevice = {
                    navController.safeNavigate(SetUpFromDeviceRoute(clearCompletedDrain = true))
                },
                onActivateStandalone = bridge.onActivateStandalone,
            )
        }
        composable<SetUpFromDeviceRoute> { backStackEntry ->
            val route: SetUpFromDeviceRoute = backStackEntry.toRoute()
            SetUpFromDeviceScreen(
                entryId = backStackEntry.id,
                clearCompletedDrain = route.clearCompletedDrain,
                onNavigateToLogin = { serverId, serverName, serverAddress, prefilledUsername, failureCode ->
                    navController.navigate(
                        LoginRoute(
                            serverId = serverId,
                            serverName = serverName,
                            serverAddress = serverAddress,
                            prefilledUsername = prefilledUsername,
                            lanSetupSessionId = null,
                            initialFailureCodeWire = failureCode.wireCode,
                            isPersistedServer = true,
                        )
                    ) {
                        popUpTo<LoginRoute> { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onBackClick = { navController.safePopBackStack() },
            )
        }
        composable<ServersRoute> {
            ServersScreen(
                navigateToUsers = { serverId, serverName, serverAddress ->
                    navController.safeNavigate(
                        UsersRoute(
                            serverId = serverId,
                            serverName = serverName,
                            serverAddress = serverAddress,
                        )
                    )
                },
                navigateToAddresses = { serverId ->
                    navController.safeNavigate(ServerAddressesRoute(serverId))
                },
                onAddClick = { navController.safeNavigate(AddServerRoute) },
                onBackClick = { navController.safePopBackStack() },
                showBack = navController.previousBackStackEntry != null,
            )
        }
        composable<AddServerRoute> { backStackEntry ->
            AddServerScreen(
                entryId = backStackEntry.id,
                onSuccess = { serverId, serverName, serverAddress ->
                    navController.safeNavigate(
                        UsersRoute(
                            serverId = serverId,
                            serverName = serverName,
                            serverAddress = serverAddress,
                        )
                    )
                },
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
        composable<UsersRoute> { backStackEntry ->
            val route: UsersRoute = backStackEntry.toRoute()
            val bridge = LocalXrNavHostBridge.current
            UsersScreen(
                serverId = route.serverId,
                serverName = route.serverName,
                serverAddress = route.serverAddress,
                onNeedsLogin = { _, username ->
                    navController.safeNavigate(
                        LoginRoute(
                            serverId = route.serverId,
                            serverName = route.serverName,
                            serverAddress = route.serverAddress,
                            prefilledUsername = username,
                            isPersistedServer = true,
                        )
                    )
                },
                onChangeServerClick = {
                    navController.safeNavigate(ServersRoute) {
                        popUpTo(ServersRoute) { inclusive = false }
                        launchSingleTop = true
                    }
                },
                onAddClick = {
                    navController.safeNavigate(
                        LoginRoute(
                            serverId = route.serverId,
                            serverName = route.serverName,
                            serverAddress = route.serverAddress,
                            isPersistedServer = true,
                        )
                    )
                },
                onBackClick = { navController.safePopBackStack() },
                onBrowseOfflineMedia =
                    bridge.onBrowseOfflineMedia.takeIf {
                        bridge.state.sessionMode == SessionMode.SERVER_NEEDS_AUTH
                    },
                onLogout =
                    bridge.onLogout.takeIf {
                        bridge.state.sessionMode == SessionMode.SERVER_AUTHENTICATED
                    },
                showBack = navController.previousBackStackEntry != null,
            )
        }
        composable<LoginRoute> { backStackEntry ->
            val route: LoginRoute = backStackEntry.toRoute()
            val bridge = LocalXrNavHostBridge.current
            val isBrokerFallback =
                route.initialFailureCodeWire != null && route.lanSetupSessionId == null
            if (isBrokerFallback) {
                BackHandler {
                    bridge.lanSetupController.clearCompletedDrainOnFallbackBack()
                    navController.popBackStack()
                }
            }
            LoginScreen(
                serverId = route.serverId,
                serverName = route.serverName,
                serverAddress = route.serverAddress,
                onChangeServerClick = {
                    if (isBrokerFallback) {
                        bridge.lanSetupController.clearCompletedDrainOnFallbackBack()
                    }
                    navController.safeNavigate(ServersRoute) {
                        popUpTo(ServersRoute) { inclusive = false }
                        launchSingleTop = true
                    }
                },
                onBackClick = {
                    if (isBrokerFallback) {
                        bridge.lanSetupController.clearCompletedDrainOnFallbackBack()
                    }
                    navController.safePopBackStack()
                },
                prefilledUsername = route.prefilledUsername,
                lanSetupSessionId = route.lanSetupSessionId,
                initialFailureCode =
                    route.initialFailureCodeWire?.let(OnboardingFailureCode::fromWireCode),
                onBrowseOfflineMedia =
                    bridge.onBrowseOfflineMedia.takeIf {
                        bridge.state.sessionMode == SessionMode.SERVER_NEEDS_AUTH
                    },
            )
        }

        composable<UniversalPluginsRoute> {
            val bridge = LocalXrNavHostBridge.current
            var showMusicAssistantSettings by remember { mutableStateOf(false) }

            val sendspinState by SendspinReceiverSession.state.collectAsStateWithLifecycle()
            val musicAssistantSubtitle =
                when (sendspinState.musicAssistantAuthState) {
                    SendspinMusicAssistantAuthState.AUTHENTICATED ->
                        sendspinState.musicAssistantServerUrl ?: "Configured server"
                    SendspinMusicAssistantAuthState.AUTHENTICATING -> "Authenticating..."
                    SendspinMusicAssistantAuthState.INVALID -> "Invalid credentials"
                    SendspinMusicAssistantAuthState.ERROR -> "Connection error"
                    else -> "Not configured"
                }

            PluginSettingsScreen(
                onPluginClick = { pluginId ->
                    navController.safeNavigate(PluginBrowseRoute(pluginId))
                },
                onJellyfinClick = {
                    navController.safeNavigate(
                        if (bridge.state.hasServers) ServersRoute else AddServerRoute
                    )
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
                },
            )

            if (showMusicAssistantSettings) {
                SpatialDialog(onDismissRequest = { showMusicAssistantSettings = false }) {
                    MusicAssistantAuthDialog(onDismiss = { showMusicAssistantSettings = false })
                }
            }
        }
        composable<PluginBrowseRoute> { backStackEntry ->
            val route: PluginBrowseRoute = backStackEntry.toRoute()
            PluginBrowseScreen(
                pluginId = route.pluginId,
                rowId = route.rowId,
                onBack = { navController.safePopBackStack() },
                onItemClick = { item ->
                    navigateToItem(navController = navController, item = item)
                },
            )
        }
        composable<HomeRoute> { entry ->
            val bridge = LocalXrNavHostBridge.current
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
                        bridge.onSearchExpand(true)
                        navController.safeNavigate(MediaRoute) {
                            popUpTo(navController.graph.startDestinationId) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onSettingsClick = {
                        navController.safeNavigate(SettingsRoute())
                    },
                    onManageServers = { navController.safeNavigate(ServersRoute) },
                    onNavigateToUsers = { serverId, serverName, serverAddress ->
                        navController.safeNavigate(
                            UsersRoute(
                                serverId = serverId,
                                serverName = serverName,
                                serverAddress = serverAddress,
                            )
                        )
                    },
                    onReconnectClick = bridge.onReconnect,
                    onLanguageSettingsClick = {
                        navController.safeNavigate(
                            SettingsRoute(
                                indexes = intArrayOf(SettingsR.string.settings_category_language)
                            )
                        )
                    },
                    onItemClick = { item ->
                        if (bridge.onMaItemTap?.invoke(item) != true) {
                            navigateToItem(navController = navController, item = item)
                        }
                    },
                    onPluginBrowse = { pluginId, rowId ->
                        navController.safeNavigate(PluginBrowseRoute(pluginId = pluginId, rowId = rowId))
                    },
                    onNetworkShareSeeAll = { shareId ->
                        navController.safeNavigate(NetworkShareRoute(shareId = shareId))
                    },
                    onPlay = bridge.playbackLauncher,
                )
            }
        }
        composable<MediaRoute> {
            val bridge = LocalXrNavHostBridge.current
            MediaScreen(
                onItemClick = { item ->
                    navigateToItem(navController = navController, item = item)
                },
                onFavoritesClick = { navController.safeNavigate(FavoritesRoute) },
                searchExpanded = bridge.searchExpanded,
                onSearchExpand = bridge.onSearchExpand,
                initialSearchQuery = bridge.pendingInitialSearchQuery,
                onInitialSearchConsumed = bridge.onInitialSearchConsumed,
            )
        }
        composable<LocalRoute> {
            val bridge = LocalXrNavHostBridge.current
            LocalMediaScreen(
                appPreferences = appPreferences,
                hasServers = bridge.state.hasServers,
                onItemClick = { item ->
                    navController.safeNavigate(LocalVideoRoute(item.mediaStoreId))
                },
                onManageServersClick = {
                    navController.safeNavigate(
                        if (bridge.state.hasServers) ServersRoute else AddServerRoute
                    )
                },
                onSettingsClick = {
                    navController.safeNavigate(SettingsRoute())
                },
                onLanguageSettingsClick = {
                    navController.safeNavigate(
                        SettingsRoute(
                            indexes = intArrayOf(SettingsR.string.settings_category_language)
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
            val bridge = LocalXrNavHostBridge.current
            MovieScreen(
                movieId = UUID.fromString(route.movieId),
                navigateBack = { navController.safePopBackStack() },
                navigateHome = {
                    navigateHome(
                        navController = navController,
                        sessionMode = bridge.state.sessionMode,
                        offlineBrowseEntered = bridge.offlineBrowseEntered,
                    )
                },
                navigateToPerson = { personId ->
                    navController.safeNavigate(PersonRoute(personId.toString()))
                },
                navigateToItem = { item ->
                    navigateToItem(navController = navController, item = item)
                },
                onPlay = bridge.playbackLauncher,
            )
        }
        composable<ShowRoute> { backStackEntry ->
            val route: ShowRoute = backStackEntry.toRoute()
            val bridge = LocalXrNavHostBridge.current
            ShowScreen(
                showId = UUID.fromString(route.showId),
                navigateBack = { navController.safePopBackStack() },
                navigateHome = {
                    navigateHome(
                        navController = navController,
                        sessionMode = bridge.state.sessionMode,
                        offlineBrowseEntered = bridge.offlineBrowseEntered,
                    )
                },
                navigateToItem = { item ->
                    navigateToItem(navController = navController, item = item)
                },
                navigateToPerson = { personId ->
                    navController.safeNavigate(PersonRoute(personId.toString()))
                },
                onPlay = bridge.playbackLauncher,
            )
        }
        composable<SeasonRoute> { backStackEntry ->
            val route: SeasonRoute = backStackEntry.toRoute()
            val bridge = LocalXrNavHostBridge.current
            SeasonScreen(
                seasonId = UUID.fromString(route.seasonId),
                navigateBack = { navController.safePopBackStack() },
                navigateHome = {
                    navigateHome(
                        navController = navController,
                        sessionMode = bridge.state.sessionMode,
                        offlineBrowseEntered = bridge.offlineBrowseEntered,
                    )
                },
                navigateToItem = { item ->
                    navigateToItem(navController = navController, item = item)
                },
                navigateToSeries = { seriesId ->
                    navController.safeNavigate(ShowRoute(showId = seriesId.toString())) {
                        popUpTo(ShowRoute(showId = seriesId.toString()))
                        launchSingleTop = true
                    }
                },
                onPlay = bridge.playbackLauncher,
            )
        }
        composable<EpisodeRoute> { backStackEntry ->
            val route: EpisodeRoute = backStackEntry.toRoute()
            val bridge = LocalXrNavHostBridge.current
            EpisodeScreen(
                episodeId = UUID.fromString(route.episodeId),
                navigateBack = { navController.safePopBackStack() },
                navigateHome = {
                    navigateHome(
                        navController = navController,
                        sessionMode = bridge.state.sessionMode,
                        offlineBrowseEntered = bridge.offlineBrowseEntered,
                    )
                },
                navigateToPerson = { personId ->
                    navController.safeNavigate(PersonRoute(personId.toString()))
                },
                navigateToSeason = { seasonId ->
                    navController.safeNavigate(SeasonRoute(seasonId = seasonId.toString())) {
                        popUpTo(SeasonRoute(seasonId = seasonId.toString()))
                        launchSingleTop = true
                    }
                },
                navigateToEpisode = { episodeId ->
                    navController.safeNavigate(EpisodeRoute(episodeId = episodeId.toString())) {
                        popUpTo(route) { inclusive = true }
                    }
                },
                onPlay = bridge.playbackLauncher,
            )
        }
        composable<LocalVideoRoute> { backStackEntry ->
            val route: LocalVideoRoute = backStackEntry.toRoute()
            val bridge = LocalXrNavHostBridge.current
            LocalVideoScreen(
                mediaStoreId = route.mediaStoreId,
                navigateBack = { navController.safePopBackStack() },
                onPlay = bridge.playbackLauncher,
            )
        }
        composable<NetworkRoute> {
            val bridge = LocalXrNavHostBridge.current
            NetworkScreen(
                onShareClick = { share ->
                    navController.safeNavigate(NetworkShareRoute(shareId = share.id))
                },
                onAddShareClick = { navController.safeNavigate(NetworkAddShareRoute) },
                onItemClick = { item ->
                    navController.safeNavigate(NetworkVideoRoute(videoId = item.networkVideoId))
                },
                onSettingsClick = {
                    navController.safeNavigate(SettingsRoute())
                },
                onConnectToServerClick =
                    if (!bridge.state.hasServers) {
                        {
                            navController.safeNavigate(
                                if (bridge.state.hasServers) ServersRoute else AddServerRoute
                            )
                        }
                    } else {
                        null
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
            val bridge = LocalXrNavHostBridge.current
            NetworkVideoScreen(
                videoId = route.videoId,
                navigateBack = { navController.safePopBackStack() },
                onPlay = bridge.playbackLauncher,
            )
        }
        composable<PersonRoute> { backStackEntry ->
            val route: PersonRoute = backStackEntry.toRoute()
            val bridge = LocalXrNavHostBridge.current
            PersonScreen(
                personId = UUID.fromString(route.personId),
                navigateBack = { navController.safePopBackStack() },
                navigateHome = {
                    navigateHome(
                        navController = navController,
                        sessionMode = bridge.state.sessionMode,
                        offlineBrowseEntered = bridge.offlineBrowseEntered,
                    )
                },
                navigateToItem = { item ->
                    navigateToItem(navController = navController, item = item)
                },
            )
        }
        composable<SettingsRoute> { backStackEntry ->
            val route: SettingsRoute = backStackEntry.toRoute()
            val bridge = LocalXrNavHostBridge.current
            SettingsScreen(
                indexes = route.indexes,
                isServerAuthenticated = bridge.state.sessionMode == SessionMode.SERVER_AUTHENTICATED,
                navigateToSettings = { indexes ->
                    navController.safeNavigate(SettingsRoute(indexes = indexes))
                },
                navigateToServers = { navController.safeNavigate(ServersRoute) },
                navigateToUsers = {
                    val sid = bridge.state.activeServerId
                    val addr = bridge.state.currentServerAddress
                    if (!sid.isNullOrBlank() && !addr.isNullOrBlank()) {
                        navController.safeNavigate(
                            UsersRoute(
                                serverId = sid,
                                serverName = bridge.state.activeServerName.orEmpty(),
                                serverAddress = addr,
                            )
                        )
                    } else {
                        navController.safeNavigate(ServersRoute)
                    }
                },
                navigateToAbout = { navController.safeNavigate(AboutRoute) },
                navigateToSetUpFromDevice = {
                    navController.safeNavigate(SetUpFromDeviceRoute(clearCompletedDrain = true))
                },
                navigateToConnectServer = {
                    navController.safeNavigate(
                        if (bridge.state.hasServers) ServersRoute else AddServerRoute
                    )
                },
                onLogout = bridge.onLogout,
                navigateBack = { navController.safePopBackStack() },
                appPreferences = appPreferences,
            )
        }
        composable<AboutRoute> {
            AboutScreen(navigateBack = { navController.safePopBackStack() })
        }
    }

@Composable
fun NavigationRoot(
    navController: NavHostController,
    navGraph: NavGraph,
    shellStateHolder: SaveableStateHolder,
    observerState: XrNavigationObserverState,
    state: MainState,
    lanSetupController: LanSetupController,
    appPreferences: AppPreferences,
    onActivateStandalone: () -> Unit,
    onLogout: () -> Unit,
    initialSearchQuery: String? = null,
    onInitialSearchConsumed: () -> Unit = {},
    onReconnect: () -> Unit = {},
    /** Non-null on XR devices; null on non-XR fallback. */
    xrSpaceMode: XrSpaceMode? = null,
    /** Called to enter Full Space (Immersive) mode. Only relevant in Home Space. */
    onEnterFullSpace: (() -> Unit)? = null,
    /** Called to enter Home Space (Multitask) mode. Only relevant in Full Space. */
    onEnterHomeSpace: (() -> Unit)? = null,
    /** Process-singleton FCast session manager. */
    fcastSession: CastSessionManager? = null,
    /** Intercepts home-row taps that are Music Assistant items. */
    onMaItemTap: ((SpatialFinItem) -> Boolean)? = null,
    onVoiceClick: (() -> Unit)? = null,
) {
    val isOfflineMode = LocalOfflineMode.current
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    var searchExpanded by remember { mutableStateOf(false) }
    var pendingInitialSearchQuery by remember(initialSearchQuery) { mutableStateOf(initialSearchQuery) }
    var showAudioNowPlaying by remember { mutableStateOf(false) }

    val navigationItems =
        xrNavigationItems(
            sessionMode = state.sessionMode,
            isOfflineMode = isOfflineMode,
            offlineBrowseEntered = observerState.offlineBrowseEntered,
        )

    val showNavigationRail =
        navigationItems.isNotEmpty() &&
            currentDestination.shouldShowNavigationRail(navigationItems, searchExpanded)

    LaunchedEffect(state.sessionMode) {
        if (state.sessionMode != SessionMode.SERVER_AUTHENTICATED) {
            showAudioNowPlaying = false
        }
    }

    // Reactive SessionNavigationPolicy observer (sole post-activation navigator).
    LaunchedEffect(
        state.isLoading,
        state.coordinatorInstanceId,
        state.sessionMode,
        state.activationEpoch,
        state.savedServerIds,
        state.activeServerId,
        state.activeServerName,
        state.currentServerAddress,
        state.hasServers,
        navBackStackEntry?.id,
        observerState.offlineBrowseEntered,
    ) {
        if (state.isLoading) return@LaunchedEffect
        val currentRole = currentDestination.toXrRouteRole() ?: return@LaunchedEffect

        val (targetServerId, isPersistedServer) =
            when {
                currentDestination?.hasRoute(UsersRoute::class) == true -> {
                    val route = runCatching { navBackStackEntry?.toRoute<UsersRoute>() }.getOrNull()
                    route?.serverId to true
                }
                currentDestination?.hasRoute(ServerAddressesRoute::class) == true -> {
                    val route =
                        runCatching { navBackStackEntry?.toRoute<ServerAddressesRoute>() }.getOrNull()
                    route?.serverId to true
                }
                currentDestination?.hasRoute(LoginRoute::class) == true -> {
                    val route = runCatching { navBackStackEntry?.toRoute<LoginRoute>() }.getOrNull()
                    route?.serverId to (route?.isPersistedServer ?: true)
                }
                else -> null to true
            }

        val result =
            evaluateXrSessionTransition(
                state = state,
                currentRole = currentRole,
                targetServerId = targetServerId,
                isPersistedServer = isPersistedServer,
                observer = observerState.toSnapshot(),
            )
        observerState.applySnapshot(result.nextObserver)

        when (val action = result.action) {
            XrTransitionAction.None -> Unit
            is XrTransitionAction.ResetRoot -> {
                val oldStartId = navGraph.startDestinationId
                navGraph.setStartDestination(action.target)
                navController.navigate(action.target) {
                    popUpTo(oldStartId) { inclusive = true }
                    launchSingleTop = true
                }
            }
            is XrTransitionAction.PopMissingServer -> {
                navController.navigate(action.target) {
                    popUpTo(navGraph.startDestinationId) { inclusive = false }
                    launchSingleTop = true
                }
            }
        }
    }

    // Observe LAN /setup navigation events directly (no safeNavigate).
    LaunchedEffect(lanSetupController, navController) {
        lanSetupController.navigationEvents.collect { event ->
            if (!lanSetupController.consumeNavigationEventIfCurrent(event)) return@collect
            when (event) {
                is LanSetupNavigationEvent.NavigateToLogin -> {
                    navController.navigate(
                        LoginRoute(
                            serverId = event.serverId,
                            serverName = event.serverName,
                            serverAddress = event.serverAddress,
                            prefilledUsername = event.prefilledUsername,
                            lanSetupSessionId = event.lanSetupSessionId,
                            initialFailureCodeWire = event.initialFailureCode?.wireCode,
                            isPersistedServer = event.lanSetupSessionId == null,
                        )
                    ) {
                        popUpTo<LoginRoute> { inclusive = true }
                        launchSingleTop = true
                    }
                }
                is LanSetupNavigationEvent.NavigateToSetUpFromDevice -> {
                    navController.navigate(SetUpFromDeviceRoute(clearCompletedDrain = false)) {
                        launchSingleTop = true
                    }
                }
                is LanSetupNavigationEvent.ReturnToAddServer -> {
                    if (!navController.popBackStack<AddServerRoute>(inclusive = false)) {
                        navController.navigate(AddServerRoute) {
                            popUpTo<LoginRoute> { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(pendingInitialSearchQuery, state.isLoading, state.sessionMode) {
        val query = pendingInitialSearchQuery
        if (query.isNullOrBlank() || state.isLoading) return@LaunchedEffect
        if (SessionNavigationPolicy.isSearchAllowed(state.sessionMode)) {
            searchExpanded = true
            if (currentDestination?.hasRoute(MediaRoute::class) != true) {
                navController.navigate(MediaRoute) {
                    popUpTo(navGraph.startDestinationId) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            }
        } else {
            pendingInitialSearchQuery = null
            onInitialSearchConsumed()
        }
    }

    val onBrowseOfflineMedia: () -> Unit = {
        observerState.offlineBrowseEntered = true
        val oldStartId = navGraph.startDestinationId
        navGraph.setStartDestination(DownloadsRoute)
        navController.navigate(DownloadsRoute) {
            popUpTo(oldStartId) { inclusive = true }
            launchSingleTop = true
        }
    }

    val voiceAllowedInCurrentMode =
        state.sessionMode == SessionMode.SERVER_AUTHENTICATED ||
            state.sessionMode == SessionMode.STANDALONE_SOURCES ||
            (state.sessionMode == SessionMode.SERVER_NEEDS_AUTH && observerState.offlineBrowseEntered)

    CompositionLocalProvider(
        LocalFCastSession provides fcastSession,
        LocalCastButtonController provides
            remember(fcastSession) {
                fcastSession?.let { CastButtonControllerAdapter(it) }
            },
        LocalMaCardActionsRenderer provides { item, onDismiss ->
            MaCardActionsMenu(
                item = item,
                dispatcher = LocalMaPlayDispatcher.current,
                onDismiss = onDismiss,
            )
        },
        LocalVoicePickerDialog provides { initialVoiceName, onSave, onDismissRequest ->
            dev.jdtech.jellyfin.presentation.settings.components.VoicePickerDialog(
                initialVoiceName = initialVoiceName,
                onSave = onSave,
                onDismissRequest = onDismissRequest,
            )
        },
        LocalSubtitlePreviewCard provides { modifier ->
            dev.spatialfin.presentation.settings.components.SubtitlePreviewCard(
                appPreferences = appPreferences,
                modifier = modifier,
            )
        },
    ) {
        val playbackLauncher = rememberPlaybackLauncher()
        val bridge =
            XrNavHostBridge(
                state = state,
                offlineBrowseEntered = observerState.offlineBrowseEntered,
                xrSpaceMode = xrSpaceMode,
                searchExpanded = searchExpanded,
                onSearchExpand = { searchExpanded = it },
                pendingInitialSearchQuery = pendingInitialSearchQuery,
                onInitialSearchConsumed = {
                    pendingInitialSearchQuery = null
                    onInitialSearchConsumed()
                },
                lanSetupController = lanSetupController,
                onEnterFullSpace = onEnterFullSpace,
                onEnterHomeSpace = onEnterHomeSpace,
                onReconnect = onReconnect,
                onBrowseOfflineMedia = onBrowseOfflineMedia,
                onMaItemTap = onMaItemTap,
                onVoiceClick = onVoiceClick?.takeIf { voiceAllowedInCurrentMode },
                playbackLauncher = playbackLauncher,
                onLogout = {
                    observerState.offlineBrowseEntered = false
                    onLogout()
                },
                onActivateStandalone = onActivateStandalone,
            )

        val panelModifier =
            if (xrSpaceMode == XrSpaceMode.FULL) {
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(32.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.62f))
                    .border(
                        1.dp,
                        Color.White.copy(alpha = 0.15f),
                        RoundedCornerShape(32.dp),
                    )
            } else {
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.65f))
            }

        val railContent = @Composable {
            NavigationRail(containerColor = Color.Transparent) {
                navigationItems.forEach { item ->
                    val isSelected =
                        currentDestination?.hierarchy?.any { it.hasRoute(item.route::class) } == true
                    NavigationRailItem(
                        selected = isSelected,
                        onClick = {
                            if (item.route is MediaRoute && isSelected) {
                                searchExpanded = true
                            }
                            navController.navigate(item.route) {
                                popUpTo(navGraph.startDestinationId) {
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

                if (state.sessionMode == SessionMode.SERVER_NEEDS_AUTH && observerState.offlineBrowseEntered) {
                    NavigationRailItem(
                        selected = false,
                        onClick = {
                            observerState.offlineBrowseEntered = false
                        },
                        icon = {
                            Icon(
                                painter = painterResource(CoreR.drawable.ic_user),
                                contentDescription = stringResource(SetupR.string.login_btn_login),
                            )
                        },
                        label = { Text(text = stringResource(SetupR.string.login_btn_login)) },
                    )
                }

                if (isOfflineMode && state.sessionMode == SessionMode.SERVER_AUTHENTICATED) {
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

                if (xrSpaceMode != null) {
                    if (!(isOfflineMode && state.sessionMode == SessionMode.SERVER_AUTHENTICATED)) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                    if (onVoiceClick != null && voiceAllowedInCurrentMode) {
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
                    val activeUser = state.currentUser
                    val activeServerId = state.activeServerId
                    if (
                        state.sessionMode == SessionMode.SERVER_AUTHENTICATED &&
                            activeUser != null &&
                            !activeServerId.isNullOrBlank()
                    ) {
                        XrProfileRailButton(
                            user = activeUser,
                            serverAddress = state.currentServerAddress,
                            onClick = {
                                val addr = state.currentServerAddress
                                if (!addr.isNullOrBlank()) {
                                    navController.safeNavigate(
                                        UsersRoute(
                                            serverId = activeServerId,
                                            serverName = state.activeServerName.orEmpty(),
                                            serverAddress = addr,
                                        )
                                    )
                                } else {
                                    navController.safeNavigate(ServersRoute)
                                }
                            },
                        )
                    }
                    val (toggleLabel, toggleAction) =
                        when (xrSpaceMode) {
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
            if (xrSpaceMode == XrSpaceMode.FULL && showNavigationRail) {
                Orbiter(
                    alignment = OrbiterAlignment.CenterEnd(
                        offset = DpVolumeOffset(x = 24.dp),
                    )
                ) {
                    Surface(
                        shape = RoundedCornerShape(32.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
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
                    AnimatedVisibility(visible = showNavigationRail) {
                        railContent()
                    }
                }
                CompositionLocalProvider(LocalXrNavHostBridge provides bridge) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    ) {
                        shellStateHolder.SaveableStateProvider("xr_nav_host") {
                            NavHost(
                                modifier = Modifier.fillMaxSize(),
                                navController = navController,
                                graph = navGraph,
                                enterTransition = { fadeIn(tween(300)) },
                                exitTransition = { fadeOut(tween(300)) },
                            )
                        }
                    }
                }
            }
            if (fcastSession != null) {
                FCastGlobalPickerHost(sessionManager = fcastSession)
            }
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 16.dp, end = 16.dp)
                    .widthIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (fcastSession != null) {
                    FCastMiniController(sessionManager = fcastSession)
                }
                if (state.sessionMode == SessionMode.SERVER_AUTHENTICATED) {
                    LocalAudioPlaybackDispatcher.current?.let { dispatcher ->
                        JellyfinAudioMiniPlayer(
                            dispatcher = dispatcher,
                            onExpand = { showAudioNowPlaying = true },
                        )
                    }
                }
            }
            if (state.sessionMode == SessionMode.SERVER_AUTHENTICATED) {
                LocalAudioPlaybackDispatcher.current?.let { dispatcher ->
                    if (showAudioNowPlaying) {
                        BackHandler(onBack = { showAudioNowPlaying = false })
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
}

internal fun navigateHome(
    navController: NavHostController,
    sessionMode: SessionMode,
    offlineBrowseEntered: Boolean,
) {
    val target = xrDefaultHomeRoute(sessionMode, offlineBrowseEntered) ?: return
    navController.safeNavigate(target) {
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
        is NetworkVideoItem ->
            navController.safeNavigate(NetworkVideoRoute(videoId = item.networkVideoId))
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
 * disc when the URL is null or the request fails.
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
        Text(
            text = initial,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
