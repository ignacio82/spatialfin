package dev.spatialfin.beam

import dev.jdtech.jellyfin.session.RouteRole
import dev.jdtech.jellyfin.session.SessionMode
import dev.jdtech.jellyfin.viewmodels.MainState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BeamNavigationPolicyTest {

    @Test
    fun `every BeamRoute maps to its expected RouteRole`() {
        assertEquals(RouteRole.SOURCE_PICKER, BeamRoute.Welcome.toRouteRole())
        assertEquals(RouteRole.SETUP_FLOW, BeamRoute.SetUpFromDevice.toRouteRole())
        assertEquals(RouteRole.SETUP_FLOW, BeamRoute.AddServer.toRouteRole())
        assertEquals(RouteRole.REAUTH_GATE, BeamRoute.Servers.toRouteRole())
        assertEquals(RouteRole.REAUTH_GATE, BeamRoute.Users.toRouteRole())
        assertEquals(RouteRole.REAUTH_GATE, BeamRoute.Login.toRouteRole())
        assertEquals(RouteRole.SERVER_CONTENT, BeamRoute.Home.toRouteRole())
        assertEquals(RouteRole.SERVER_CONTENT, BeamRoute.Search.toRouteRole())
        assertEquals(RouteRole.SERVER_CONTENT, BeamRoute.Library.toRouteRole())
        assertEquals(RouteRole.SERVER_CONTENT, BeamRoute.Person.toRouteRole())
        assertEquals(RouteRole.SERVER_CONTENT, BeamRoute.AudioDetail.toRouteRole())
        assertEquals(RouteRole.OFFLINE_OR_LOCAL_CONTENT, BeamRoute.Detail.toRouteRole())
        assertEquals(RouteRole.OFFLINE_OR_LOCAL_CONTENT, BeamRoute.Show.toRouteRole())
        assertEquals(RouteRole.OFFLINE_OR_LOCAL_CONTENT, BeamRoute.Season.toRouteRole())
        assertEquals(RouteRole.OFFLINE_OR_LOCAL_CONTENT, BeamRoute.Local.toRouteRole())
        assertEquals(RouteRole.OFFLINE_OR_LOCAL_CONTENT, BeamRoute.Network.toRouteRole())
        assertEquals(RouteRole.OFFLINE_OR_LOCAL_CONTENT, BeamRoute.NetworkShare.toRouteRole())
        assertEquals(RouteRole.OFFLINE_OR_LOCAL_CONTENT, BeamRoute.NetworkAddShare.toRouteRole())
        assertEquals(RouteRole.OFFLINE_OR_LOCAL_CONTENT, BeamRoute.Downloads.toRouteRole())
        assertEquals(RouteRole.OFFLINE_OR_LOCAL_CONTENT, BeamRoute.Plugins.toRouteRole())
        assertEquals(RouteRole.OFFLINE_OR_LOCAL_CONTENT, BeamRoute.PluginBrowse.toRouteRole())
        assertEquals(RouteRole.MA_CONTENT, BeamRoute.MaSearch.toRouteRole())
        assertEquals(RouteRole.MA_CONTENT, BeamRoute.MaDetail.toRouteRole())
        assertEquals(RouteRole.SETTINGS, BeamRoute.Settings.toRouteRole())
    }

    @Test
    fun `legacy Companion saved route restores as SetUpFromDevice`() {
        assertEquals(BeamRoute.SetUpFromDevice, parseBeamRoute("Companion"))
        assertEquals(BeamRoute.SetUpFromDevice, parseNullableBeamRoute("Companion"))
        assertEquals(BeamRoute.Welcome, parseBeamRoute("UnknownLegacyRoute"))
    }

    @Test
    fun `beamDefaultRootRoute covers all session modes`() {
        assertEquals(BeamRoute.Home, beamDefaultRootRoute(SessionMode.SERVER_AUTHENTICATED, false))
        assertEquals(BeamRoute.Local, beamDefaultRootRoute(SessionMode.STANDALONE_SOURCES, false))
        assertEquals(BeamRoute.Downloads, beamDefaultRootRoute(SessionMode.SERVER_NEEDS_AUTH, true))
        assertNull(beamDefaultRootRoute(SessionMode.SERVER_NEEDS_AUTH, false))
        assertNull(beamDefaultRootRoute(SessionMode.UNCONFIGURED, false))
    }

    @Test
    fun `caller-origin Back routes return to origin and exclude Servers and Users from root exit`() {
        assertFalse(isBeamRootRoute(BeamRoute.Servers, serversBackRoute = BeamRoute.Settings, usersBackRoute = null))
        assertTrue(isBeamRootRoute(BeamRoute.Servers, serversBackRoute = null, usersBackRoute = null))
        assertFalse(isBeamRootRoute(BeamRoute.Users, serversBackRoute = null, usersBackRoute = BeamRoute.Home))
        assertTrue(isBeamRootRoute(BeamRoute.Users, serversBackRoute = null, usersBackRoute = null))

        assertEquals(
            BeamRoute.Settings,
            resolveBeamBackRoute(
                currentRoute = BeamRoute.SetUpFromDevice,
                hasServers = true,
                sessionMode = SessionMode.SERVER_AUTHENTICATED,
                offlineBrowseEntered = false,
                detailBackRoute = BeamRoute.Home,
                seasonBackRoute = BeamRoute.Home,
                showBackRoute = BeamRoute.Home,
                personBackRoute = BeamRoute.Home,
                audioBackRoute = BeamRoute.Home,
                maDetailBackRoute = BeamRoute.MaSearch,
                setUpFromDeviceBackRoute = BeamRoute.Settings,
                addServerBackRoute = BeamRoute.Servers,
                networkBackRoute = BeamRoute.Local,
                serversBackRoute = BeamRoute.Settings,
                usersBackRoute = BeamRoute.Home,
            ),
        )
        assertEquals(
            BeamRoute.Welcome,
            resolveBeamBackRoute(
                currentRoute = BeamRoute.AddServer,
                hasServers = false,
                sessionMode = SessionMode.UNCONFIGURED,
                offlineBrowseEntered = false,
                detailBackRoute = BeamRoute.Home,
                seasonBackRoute = BeamRoute.Home,
                showBackRoute = BeamRoute.Home,
                personBackRoute = BeamRoute.Home,
                audioBackRoute = BeamRoute.Home,
                maDetailBackRoute = BeamRoute.MaSearch,
                setUpFromDeviceBackRoute = BeamRoute.Welcome,
                addServerBackRoute = BeamRoute.Settings,
                networkBackRoute = BeamRoute.Network,
                serversBackRoute = null,
                usersBackRoute = null,
            ),
        )
        assertEquals(
            BeamRoute.Local,
            resolveBeamBackRoute(
                currentRoute = BeamRoute.NetworkShare,
                hasServers = false,
                sessionMode = SessionMode.STANDALONE_SOURCES,
                offlineBrowseEntered = false,
                detailBackRoute = BeamRoute.Local,
                seasonBackRoute = BeamRoute.Local,
                showBackRoute = BeamRoute.Local,
                personBackRoute = BeamRoute.Local,
                audioBackRoute = BeamRoute.Local,
                maDetailBackRoute = BeamRoute.MaSearch,
                setUpFromDeviceBackRoute = BeamRoute.Settings,
                addServerBackRoute = BeamRoute.Local,
                networkBackRoute = BeamRoute.Local,
                serversBackRoute = null,
                usersBackRoute = null,
            ),
        )
        assertEquals(
            BeamRoute.Plugins,
            resolveBeamBackRoute(
                currentRoute = BeamRoute.PluginBrowse,
                hasServers = false,
                sessionMode = SessionMode.STANDALONE_SOURCES,
                offlineBrowseEntered = false,
                detailBackRoute = BeamRoute.Local,
                seasonBackRoute = BeamRoute.Local,
                showBackRoute = BeamRoute.Local,
                personBackRoute = BeamRoute.Local,
                audioBackRoute = BeamRoute.Local,
                maDetailBackRoute = BeamRoute.MaSearch,
                setUpFromDeviceBackRoute = BeamRoute.Settings,
                addServerBackRoute = BeamRoute.Local,
                networkBackRoute = BeamRoute.Local,
                serversBackRoute = null,
                usersBackRoute = null,
            ),
        )
    }

    @Test
    fun `missing persisted server pops Users and Login to Servers or Welcome`() {
        val observer = BeamSessionObserverState(
            routeInitialized = true,
            savedCoordinatorInstanceId = "coord-1",
            lastObservedActivationEpoch = 1L,
            lastObservedSessionMode = SessionMode.SERVER_NEEDS_AUTH,
        )
        val stateWithOtherServer = MainState(
            isLoading = false,
            coordinatorInstanceId = "coord-1",
            sessionMode = SessionMode.SERVER_NEEDS_AUTH,
            activationEpoch = 1L,
            hasServers = true,
            savedServerIds = setOf("srv-other"),
        )
        val resUsers = evaluateBeamSessionTransition(
            currentRoute = BeamRoute.Users,
            state = stateWithOtherServer,
            effectiveOffline = false,
            targetServerId = "srv-deleted",
            observer = observer,
        )
        assertEquals(BeamRoute.Servers, resUsers.nextRoute)

        val stateNoServers = stateWithOtherServer.copy(
            sessionMode = SessionMode.UNCONFIGURED,
            hasServers = false,
            savedServerIds = emptySet(),
        )
        val resLogin = evaluateBeamSessionTransition(
            currentRoute = BeamRoute.Login,
            state = stateNoServers,
            effectiveOffline = false,
            targetServerId = "srv-deleted",
            observer = observer,
        )
        assertEquals(BeamRoute.Welcome, resLogin.nextRoute)
    }

    @Test
    fun `standalone sources redirects Welcome and Home to Local`() {
        val standaloneState = MainState(
            isLoading = false,
            coordinatorInstanceId = "coord-1",
            sessionMode = SessionMode.STANDALONE_SOURCES,
            activationEpoch = 2L,
        )
        val initRes = evaluateBeamSessionTransition(
            currentRoute = BeamRoute.Welcome,
            state = standaloneState,
            effectiveOffline = false,
            targetServerId = null,
            observer = BeamSessionObserverState(),
        )
        assertEquals(BeamRoute.Local, initRes.nextRoute)

        val fromHome = evaluateBeamSessionTransition(
            currentRoute = BeamRoute.Home,
            state = standaloneState,
            effectiveOffline = false,
            targetServerId = null,
            observer = initRes.nextObserverState,
        )
        assertEquals(BeamRoute.Local, fromHome.nextRoute)
    }

    @Test
    fun `process-death coordinatorInstanceId change suppresses false activation jump`() {
        val savedObserver = BeamSessionObserverState(
            routeInitialized = true,
            savedCoordinatorInstanceId = "coord-old",
            lastObservedActivationEpoch = 5L,
            lastObservedSessionMode = SessionMode.SERVER_AUTHENTICATED,
        )
        val newProcessState = MainState(
            isLoading = false,
            coordinatorInstanceId = "coord-new",
            sessionMode = SessionMode.SERVER_AUTHENTICATED,
            activationEpoch = 1L,
            activeServerId = "srv-1",
            hasServers = true,
            savedServerIds = setOf("srv-1"),
        )
        val res = evaluateBeamSessionTransition(
            currentRoute = BeamRoute.Settings,
            state = newProcessState,
            effectiveOffline = false,
            targetServerId = "srv-1",
            observer = savedObserver,
        )
        assertEquals(BeamRoute.Settings, res.nextRoute)
        assertEquals("coord-new", res.nextObserverState.savedCoordinatorInstanceId)
    }

    @Test
    fun `frame 1 preserves restored offlineBrowseEntered and pops MA_CONTENT to Downloads`() {
        val restoredObserver = BeamSessionObserverState(
            routeInitialized = false,
            offlineBrowseEntered = true,
        )
        val needsAuthState = MainState(
            isLoading = false,
            coordinatorInstanceId = "coord-1",
            sessionMode = SessionMode.SERVER_NEEDS_AUTH,
            activeServerId = "srv-1",
            hasServers = true,
            savedServerIds = setOf("srv-1"),
        )
        val frame1 = evaluateBeamSessionTransition(
            currentRoute = BeamRoute.Downloads,
            state = needsAuthState,
            effectiveOffline = false,
            targetServerId = "srv-1",
            observer = restoredObserver,
        )
        assertEquals(BeamRoute.Downloads, frame1.nextRoute)
        assertTrue(frame1.nextObserverState.offlineBrowseEntered)

        val maPop = evaluateBeamSessionTransition(
            currentRoute = BeamRoute.MaSearch,
            state = needsAuthState,
            effectiveOffline = false,
            targetServerId = "srv-1",
            observer = frame1.nextObserverState,
        )
        assertEquals(BeamRoute.Downloads, maPop.nextRoute)
    }
}
