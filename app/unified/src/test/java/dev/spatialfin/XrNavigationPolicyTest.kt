package dev.spatialfin

import dev.jdtech.jellyfin.core.util.XrPlayerSessionCoordinator
import dev.jdtech.jellyfin.models.CollectionType
import dev.jdtech.jellyfin.player.xr.XrFCastInboundPlayerActivity
import dev.jdtech.jellyfin.player.xr.XrPlayerActivity
import dev.jdtech.jellyfin.session.RouteRole
import dev.jdtech.jellyfin.session.SessionMode
import dev.jdtech.jellyfin.session.SessionNavigationPolicy
import dev.jdtech.jellyfin.viewmodels.MainState
import dev.spatialfin.unified.audio.JellyfinAudioDetailType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XrNavigationPolicyTest {

    @Test
    fun allXrRoutesMapToExpectedRouteRoles() {
        assertEquals(RouteRole.SOURCE_PICKER, WelcomeRoute.toXrRouteRole())

        listOf<Any>(
            AddServerRoute,
            ServersRoute,
            SetUpFromDeviceRoute(),
            ServerAddressesRoute("srv-1"),
            NetworkAddShareRoute,
        ).forEach { route ->
            assertEquals("Expected SETUP_FLOW for $route", RouteRole.SETUP_FLOW, route.toXrRouteRole())
        }

        listOf<Any>(
            UsersRoute("srv-1", "Home", "http://192.168.1.10:8096"),
            LoginRoute("srv-1", "Home", "http://192.168.1.10:8096"),
        ).forEach { route ->
            assertEquals("Expected REAUTH_GATE for $route", RouteRole.REAUTH_GATE, route.toXrRouteRole())
        }

        listOf<Any>(
            DownloadsRoute,
            LocalRoute,
            LocalVideoRoute(1L),
            NetworkRoute,
            NetworkShareRoute("share-1"),
            NetworkVideoRoute("vid-1"),
            UniversalPluginsRoute,
            PluginBrowseRoute("plugin-1"),
            MovieRoute("movie-1"),
            ShowRoute("show-1"),
            SeasonRoute("season-1"),
            EpisodeRoute("ep-1"),
        ).forEach { route ->
            assertEquals(
                "Expected OFFLINE_OR_LOCAL_CONTENT for $route",
                RouteRole.OFFLINE_OR_LOCAL_CONTENT,
                route.toXrRouteRole(),
            )
        }

        listOf<Any>(
            SettingsRoute(),
            AboutRoute,
        ).forEach { route ->
            assertEquals("Expected SETTINGS for $route", RouteRole.SETTINGS, route.toXrRouteRole())
        }

        listOf<Any>(
            HomeRoute,
            MediaRoute,
            LibraryRoute("lib-1", "Movies", CollectionType.Movies),
            AudioDetailRoute("a-1", "Album", JellyfinAudioDetailType.Album),
            CollectionRoute("col-1", "Collection"),
            FavoritesRoute,
            PersonRoute("person-1"),
            PhotoRoute("photo-1"),
        ).forEach { route ->
            assertEquals(
                "Expected SERVER_CONTENT for $route",
                RouteRole.SERVER_CONTENT,
                route.toXrRouteRole(),
            )
        }
    }

    @Test
    fun missingServerGuardPopsPersistedRoutesButRetainsEphemeralLanSetupLogin() {
        val stateWithOtherServer =
            MainState(
                isLoading = false,
                coordinatorInstanceId = "coord-1",
                sessionMode = SessionMode.SERVER_NEEDS_AUTH,
                hasServers = true,
                savedServerIds = setOf("srv-other"),
            )
        val observer =
            XrSessionObserverSnapshot(
                savedCoordinatorInstanceId = "coord-1",
                lastObservedActivationEpoch = 0L,
                lastObservedSessionMode = SessionMode.SERVER_NEEDS_AUTH,
            )

        // Persisted UsersRoute targeting deleted server -> PopMissingServer(ServersRoute)
        val usersRes =
            evaluateXrSessionTransition(
                state = stateWithOtherServer,
                currentRole = UsersRoute("srv-deleted", "Old", "http://10.0.0.2:8096").toXrRouteRole(),
                targetServerId = "srv-deleted",
                isPersistedServer = true,
                observer = observer,
            )
        assertEquals(XrTransitionAction.PopMissingServer(ServersRoute), usersRes.action)

        // Persisted ServerAddressesRoute targeting deleted server -> PopMissingServer(ServersRoute)
        val addrRes =
            evaluateXrSessionTransition(
                state = stateWithOtherServer,
                currentRole = ServerAddressesRoute("srv-deleted").toXrRouteRole(),
                targetServerId = "srv-deleted",
                isPersistedServer = true,
                observer = observer,
            )
        assertEquals(XrTransitionAction.PopMissingServer(ServersRoute), addrRes.action)

        // Persisted LoginRoute targeting deleted server -> PopMissingServer(ServersRoute)
        val persistedLoginRes =
            evaluateXrSessionTransition(
                state = stateWithOtherServer,
                currentRole = LoginRoute("srv-deleted", "Old", "http://10.0.0.2:8096", isPersistedServer = true).toXrRouteRole(),
                targetServerId = "srv-deleted",
                isPersistedServer = true,
                observer = observer,
            )
        assertEquals(XrTransitionAction.PopMissingServer(ServersRoute), persistedLoginRes.action)

        // Ephemeral /setup LoginRoute (isPersistedServer = false) -> retained even if not in savedServerIds
        val ephemeralLoginRes =
            evaluateXrSessionTransition(
                state = stateWithOtherServer,
                currentRole = LoginRoute("srv-ephemeral", "New", "http://10.0.0.3:8096", isPersistedServer = false).toXrRouteRole(),
                targetServerId = "srv-ephemeral",
                isPersistedServer = false,
                observer = observer,
            )
        assertEquals(XrTransitionAction.None, ephemeralLoginRes.action)
    }

    @Test
    fun standaloneModeRoutesToLocalAndOmitsDownloadsTab() {
        val state =
            MainState(
                isLoading = false,
                coordinatorInstanceId = "coord-1",
                sessionMode = SessionMode.STANDALONE_SOURCES,
                activationEpoch = 1L,
            )

        assertEquals(LocalRoute, xrInitialStartRoute(state, offlineBrowseEntered = false))
        assertEquals(LocalRoute, xrDefaultHomeRoute(SessionMode.STANDALONE_SOURCES, offlineBrowseEntered = false))

        val tabs =
            xrNavigationItems(
                sessionMode = SessionMode.STANDALONE_SOURCES,
                isOfflineMode = false,
                offlineBrowseEntered = false,
            )
        assertEquals(listOf(localTab, networkTab, sourcesTab, settingsTab), tabs)
        assertFalse(tabs.contains(downloadsTab))

        val observer =
            XrSessionObserverSnapshot(
                savedCoordinatorInstanceId = "coord-1",
                lastObservedActivationEpoch = 1L,
                lastObservedSessionMode = SessionMode.STANDALONE_SOURCES,
            )
        val fromServerContent =
            evaluateXrSessionTransition(
                state = state,
                currentRole = RouteRole.SERVER_CONTENT,
                targetServerId = null,
                isPersistedServer = true,
                observer = observer,
            )
        assertEquals(XrTransitionAction.ResetRoot(LocalRoute), fromServerContent.action)
    }

    @Test
    fun freshProcessCoordinatorInstanceIdResyncsActivationEpoch() {
        // Prior process recorded activationEpoch = 2 under coord-old.
        // Fresh process starts with coord-new and activationEpoch = 2 after background activation.
        val state =
            MainState(
                isLoading = false,
                coordinatorInstanceId = "coord-new",
                sessionMode = SessionMode.SERVER_AUTHENTICATED,
                activationEpoch = 2L,
            )
        val restoredObserver =
            XrSessionObserverSnapshot(
                offlineBrowseEntered = false,
                savedCoordinatorInstanceId = "coord-old",
                lastObservedActivationEpoch = 2L,
                lastObservedSessionMode = SessionMode.SERVER_NEEDS_AUTH,
            )

        val result =
            evaluateXrSessionTransition(
                state = state,
                currentRole = RouteRole.SETUP_FLOW,
                targetServerId = null,
                isPersistedServer = true,
                observer = restoredObserver,
            )
        assertEquals(XrTransitionAction.ResetRoot(HomeRoute), result.action)
        assertEquals("coord-new", result.nextObserver.savedCoordinatorInstanceId)
        assertEquals(2L, result.nextObserver.lastObservedActivationEpoch)
    }

    @Test
    fun frameOneLoadingPreservesOfflineBrowseEnteredAndLastSessionMode() {
        val loadingState =
            MainState(
                isLoading = true,
                sessionMode = SessionMode.UNCONFIGURED,
            )
        val restoredObserver =
            XrSessionObserverSnapshot(
                offlineBrowseEntered = true,
                savedCoordinatorInstanceId = "coord-1",
                lastObservedActivationEpoch = 1L,
                lastObservedSessionMode = SessionMode.SERVER_NEEDS_AUTH,
            )

        val frame1 =
            evaluateXrSessionTransition(
                state = loadingState,
                currentRole = RouteRole.OFFLINE_OR_LOCAL_CONTENT,
                targetServerId = null,
                isPersistedServer = true,
                observer = restoredObserver,
            )
        assertEquals(XrTransitionAction.None, frame1.action)
        assertTrue(frame1.nextObserver.offlineBrowseEntered)
        assertEquals(SessionMode.SERVER_NEEDS_AUTH, frame1.nextObserver.lastObservedSessionMode)

        // Frame 2 completes loading as SERVER_NEEDS_AUTH -> offline browse stays permitted
        val loadedState =
            MainState(
                isLoading = false,
                coordinatorInstanceId = "coord-1",
                sessionMode = SessionMode.SERVER_NEEDS_AUTH,
                activationEpoch = 1L,
                hasServers = true,
                activeServerId = "srv-1",
                currentServerAddress = "http://192.168.1.10:8096",
                savedServerIds = setOf("srv-1"),
            )
        val frame2 =
            evaluateXrSessionTransition(
                state = loadedState,
                currentRole = RouteRole.OFFLINE_OR_LOCAL_CONTENT,
                targetServerId = null,
                isPersistedServer = true,
                observer = frame1.nextObserver,
            )
        assertEquals(XrTransitionAction.None, frame2.action)
        assertTrue(frame2.nextObserver.offlineBrowseEntered)
    }

    @Test
    fun navigateHomeTargetMatchesActiveSessionModeAndOfflineBrowse() {
        assertEquals(HomeRoute, xrDefaultHomeRoute(SessionMode.SERVER_AUTHENTICATED, false))
        assertEquals(HomeRoute, xrDefaultHomeRoute(SessionMode.SERVER_AUTHENTICATED, true))
        assertEquals(LocalRoute, xrDefaultHomeRoute(SessionMode.STANDALONE_SOURCES, false))
        assertEquals(DownloadsRoute, xrDefaultHomeRoute(SessionMode.SERVER_NEEDS_AUTH, true))
        assertNull(xrDefaultHomeRoute(SessionMode.SERVER_NEEDS_AUTH, false))
        assertNull(xrDefaultHomeRoute(SessionMode.UNCONFIGURED, false))
    }

    @Test
    fun serverNeedsAuthOfflineBrowseShowsDownloadsAndSignInTabs() {
        val lockedOut =
            xrNavigationItems(
                sessionMode = SessionMode.SERVER_NEEDS_AUTH,
                isOfflineMode = false,
                offlineBrowseEntered = false,
            )
        assertTrue(lockedOut.isEmpty())

        val offlineAllowed =
            xrNavigationItems(
                sessionMode = SessionMode.SERVER_NEEDS_AUTH,
                isOfflineMode = false,
                offlineBrowseEntered = true,
            )
        assertEquals(
            listOf(localTab, networkTab, downloadsTab, sourcesTab, settingsTab),
            offlineAllowed,
        )
    }

    @Test
    fun xrPlayerActivityClassConstantsMatchActualPlayerActivities() {
        assertEquals(
            setOf(
                XrPlayerActivity::class.java.name,
                XrFCastInboundPlayerActivity::class.java.name,
            ),
            XrPlayerSessionCoordinator.XR_PLAYER_ACTIVITY_CLASSES,
        )
    }

    @Test
    fun searchAndMaPredicatesAlignWithXrModeRules() {
        assertTrue(SessionNavigationPolicy.isSearchAllowed(SessionMode.SERVER_AUTHENTICATED))
        assertFalse(SessionNavigationPolicy.isSearchAllowed(SessionMode.STANDALONE_SOURCES))
        assertFalse(SessionNavigationPolicy.isSearchAllowed(SessionMode.SERVER_NEEDS_AUTH))
        assertFalse(SessionNavigationPolicy.isSearchAllowed(SessionMode.UNCONFIGURED))
    }
}
