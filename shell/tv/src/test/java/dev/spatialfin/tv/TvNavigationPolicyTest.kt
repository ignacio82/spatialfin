package dev.spatialfin.tv

import dev.jdtech.jellyfin.models.User
import dev.jdtech.jellyfin.session.RouteRole
import dev.jdtech.jellyfin.session.SessionMode
import dev.jdtech.jellyfin.viewmodels.MainState
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TvNavigationPolicyTest {

    @Test
    fun `every TvRoute maps to its expected RouteRole`() {
        assertEquals(RouteRole.SOURCE_PICKER, TvRoute.SourcePicker.toRouteRole())
        assertEquals(RouteRole.SETUP_FLOW, TvRoute.ConnectServer.toRouteRole())
        assertEquals(RouteRole.SETUP_FLOW, TvRoute.SetUpFromDevice.toRouteRole())
        assertEquals(RouteRole.SETUP_FLOW, TvRoute.AddShare.toRouteRole())
        assertEquals(RouteRole.REAUTH_GATE, TvRoute.Servers.toRouteRole())
        assertEquals(RouteRole.REAUTH_GATE, TvRoute.Users.toRouteRole())
        assertEquals(RouteRole.REAUTH_GATE, TvRoute.Login.toRouteRole())
        assertEquals(RouteRole.SERVER_CONTENT, TvRoute.Search.toRouteRole())
        assertEquals(RouteRole.SERVER_CONTENT, TvRoute.Library.toRouteRole())
        assertEquals(RouteRole.SERVER_CONTENT, TvRoute.Person.toRouteRole())
        assertEquals(RouteRole.SERVER_CONTENT, TvRoute.AudioDetail.toRouteRole())
        assertEquals(RouteRole.SERVER_CONTENT, TvRoute.Photo.toRouteRole())
        assertEquals(RouteRole.OFFLINE_OR_LOCAL_CONTENT, TvRoute.Home.toRouteRole())
        assertEquals(RouteRole.OFFLINE_OR_LOCAL_CONTENT, TvRoute.Detail.toRouteRole())
        assertEquals(RouteRole.OFFLINE_OR_LOCAL_CONTENT, TvRoute.Show.toRouteRole())
        assertEquals(RouteRole.OFFLINE_OR_LOCAL_CONTENT, TvRoute.Season.toRouteRole())
        assertEquals(RouteRole.OFFLINE_OR_LOCAL_CONTENT, TvRoute.Plugins.toRouteRole())
        assertEquals(RouteRole.OFFLINE_OR_LOCAL_CONTENT, TvRoute.PluginBrowse.toRouteRole())
        assertEquals(RouteRole.OFFLINE_OR_LOCAL_CONTENT, TvRoute.NetworkShare.toRouteRole())
        assertEquals(RouteRole.MA_CONTENT, TvRoute.MaSearch.toRouteRole())
        assertEquals(RouteRole.MA_CONTENT, TvRoute.MaDetail.toRouteRole())
        assertEquals(RouteRole.SETTINGS, TvRoute.Settings.toRouteRole())
    }

    @Test
    fun `parseTvRoute maps legacy Companion and invalid names safely`() {
        assertEquals(TvRoute.SetUpFromDevice, parseTvRoute("Companion"))
        assertEquals(TvRoute.Home, parseTvRoute("NonExistentLegacyRoute"))
        assertEquals(TvRoute.Home, parseTvRoute(null))
    }

    @Test
    fun `loginIsPersistedServer false retains setup Login route while persisted true pops deleted server`() {
        val observer = TvSessionObserverState(
            routeInitialized = true,
            savedCoordinatorInstanceId = "coord-1",
            lastObservedActivationEpoch = 1L,
            lastObservedSessionMode = SessionMode.SERVER_NEEDS_AUTH,
        )
        val state = MainState(
            isLoading = false,
            coordinatorInstanceId = "coord-1",
            sessionMode = SessionMode.SERVER_NEEDS_AUTH,
            activationEpoch = 1L,
            hasServers = true,
            savedServerIds = setOf("srv-other"),
        )
        val unpersisted = evaluateTvSessionTransition(
            currentRoute = TvRoute.Login,
            state = state,
            targetServerId = "srv-ephemeral",
            loginIsPersistedServer = false,
            observer = observer,
        )
        assertNull(unpersisted.replaceRootRoute)

        val persisted = evaluateTvSessionTransition(
            currentRoute = TvRoute.Login,
            state = state,
            targetServerId = "srv-deleted",
            loginIsPersistedServer = true,
            observer = observer,
        )
        assertEquals(TvRoute.Servers, persisted.replaceRootRoute)
    }

    @Test
    fun `authenticated profile switch resets to Home and clears profile content`() {
        val userA = User(id = UUID.randomUUID(), name = "Alice", serverId = "srv-1", accessToken = "tokA")
        val userB = User(id = UUID.randomUUID(), name = "Bob", serverId = "srv-1", accessToken = "tokB")
        val observer = TvSessionObserverState(
            routeInitialized = true,
            savedCoordinatorInstanceId = "coord-1",
            lastObservedActivationEpoch = 2L,
            lastObservedSessionMode = SessionMode.SERVER_AUTHENTICATED,
            lastAuthServerId = "srv-1",
            lastAuthUserId = userA.id.toString(),
        )
        val switchedState = MainState(
            isLoading = false,
            coordinatorInstanceId = "coord-1",
            sessionMode = SessionMode.SERVER_AUTHENTICATED,
            activationEpoch = 3L,
            activeServerId = "srv-1",
            currentUser = userB,
            hasServers = true,
            savedServerIds = setOf("srv-1"),
        )
        val result = evaluateTvSessionTransition(
            currentRoute = TvRoute.Library,
            state = switchedState,
            targetServerId = "srv-1",
            loginIsPersistedServer = true,
            observer = observer,
        )
        assertEquals(TvRoute.Home, result.replaceRootRoute)
        assertTrue(result.resetProfileContent)
    }

    @Test
    fun `standalone activation routes AddShare to Home and filters visible nav items`() {
        val observer = TvSessionObserverState(
            routeInitialized = true,
            savedCoordinatorInstanceId = "coord-1",
            lastObservedActivationEpoch = 0L,
            lastObservedSessionMode = SessionMode.UNCONFIGURED,
        )
        val standaloneState = MainState(
            isLoading = false,
            coordinatorInstanceId = "coord-1",
            sessionMode = SessionMode.STANDALONE_SOURCES,
            activationEpoch = 1L,
        )
        val result = evaluateTvSessionTransition(
            currentRoute = TvRoute.AddShare,
            state = standaloneState,
            targetServerId = null,
            loginIsPersistedServer = true,
            observer = observer,
        )
        assertEquals(TvRoute.Home, result.replaceRootRoute)
        assertEquals(
            listOf(TvRoute.Home, TvRoute.MaSearch, TvRoute.Plugins, TvRoute.Settings),
            tvVisibleNavRoutes(SessionMode.STANDALONE_SOURCES, isOfflineMode = false, offlineBrowseEntered = false),
        )
        assertEquals(
            listOf(TvRoute.Home, TvRoute.Plugins, TvRoute.Settings),
            tvVisibleNavRoutes(SessionMode.SERVER_NEEDS_AUTH, isOfflineMode = false, offlineBrowseEntered = true),
        )
    }

    @Test
    fun `process-death coordinatorInstanceId resync prevents false jump`() {
        val user = User(id = UUID.randomUUID(), name = "Alice", serverId = "srv-1", accessToken = "tokA")
        val observer = TvSessionObserverState(
            routeInitialized = true,
            savedCoordinatorInstanceId = "coord-old",
            lastObservedActivationEpoch = 10L,
            lastObservedSessionMode = SessionMode.SERVER_AUTHENTICATED,
            lastAuthServerId = "srv-1",
            lastAuthUserId = user.id.toString(),
        )
        val restoredState = MainState(
            isLoading = false,
            coordinatorInstanceId = "coord-new",
            sessionMode = SessionMode.SERVER_AUTHENTICATED,
            activationEpoch = 1L,
            activeServerId = "srv-1",
            currentUser = user,
            hasServers = true,
            savedServerIds = setOf("srv-1"),
        )
        val result = evaluateTvSessionTransition(
            currentRoute = TvRoute.Settings,
            state = restoredState,
            targetServerId = "srv-1",
            loginIsPersistedServer = true,
            observer = observer,
        )
        assertNull(result.replaceRootRoute)
        assertFalse(result.resetProfileContent)
        assertEquals("coord-new", result.nextObserverState.savedCoordinatorInstanceId)
    }

    @Test
    fun `frame 1 loading to SERVER_NEEDS_AUTH preserves restored offlineBrowseEntered`() {
        val restoredObserver = TvSessionObserverState(
            routeInitialized = false,
            offlineBrowseEntered = true,
        )
        val loadingState = MainState(isLoading = true)
        val loadingResult = evaluateTvSessionTransition(
            currentRoute = TvRoute.Home,
            state = loadingState,
            targetServerId = null,
            loginIsPersistedServer = true,
            observer = restoredObserver,
        )
        assertNull(loadingResult.replaceRootRoute)
        assertFalse(loadingResult.nextObserverState.routeInitialized)

        val needsAuthState = MainState(
            isLoading = false,
            coordinatorInstanceId = "coord-1",
            sessionMode = SessionMode.SERVER_NEEDS_AUTH,
            activeServerId = "srv-1",
            hasServers = true,
            savedServerIds = setOf("srv-1"),
        )
        val frame1 = evaluateTvSessionTransition(
            currentRoute = TvRoute.Home,
            state = needsAuthState,
            targetServerId = "srv-1",
            loginIsPersistedServer = true,
            observer = loadingResult.nextObserverState,
        )
        assertNull(frame1.replaceRootRoute)
        assertTrue(frame1.nextObserverState.offlineBrowseEntered)

        val maPop = evaluateTvSessionTransition(
            currentRoute = TvRoute.MaSearch,
            state = needsAuthState,
            targetServerId = "srv-1",
            loginIsPersistedServer = true,
            observer = frame1.nextObserverState,
        )
        assertEquals(TvRoute.Home, maPop.replaceRootRoute)
    }
}
