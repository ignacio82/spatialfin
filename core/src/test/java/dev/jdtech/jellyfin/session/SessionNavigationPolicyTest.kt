package dev.jdtech.jellyfin.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionNavigationPolicyTest {

    @Test
    fun `shouldRedirectToSetup permits SOURCE_PICKER, SETUP_FLOW, and REAUTH_GATE in UNCONFIGURED`() {
        assertFalse(SessionNavigationPolicy.shouldRedirectToSetup(SessionMode.UNCONFIGURED, RouteRole.SOURCE_PICKER))
        assertFalse(SessionNavigationPolicy.shouldRedirectToSetup(SessionMode.UNCONFIGURED, RouteRole.SETUP_FLOW))
        assertFalse(SessionNavigationPolicy.shouldRedirectToSetup(SessionMode.UNCONFIGURED, RouteRole.REAUTH_GATE))

        assertTrue(SessionNavigationPolicy.shouldRedirectToSetup(SessionMode.UNCONFIGURED, RouteRole.SERVER_CONTENT))
        assertTrue(SessionNavigationPolicy.shouldRedirectToSetup(SessionMode.UNCONFIGURED, RouteRole.MA_CONTENT))
        assertTrue(SessionNavigationPolicy.shouldRedirectToSetup(SessionMode.UNCONFIGURED, RouteRole.OFFLINE_OR_LOCAL_CONTENT))
        assertTrue(SessionNavigationPolicy.shouldRedirectToSetup(SessionMode.UNCONFIGURED, RouteRole.SETTINGS))

        for (mode in listOf(SessionMode.SERVER_AUTHENTICATED, SessionMode.SERVER_NEEDS_AUTH, SessionMode.STANDALONE_SOURCES)) {
            for (role in RouteRole.entries) {
                assertFalse(SessionNavigationPolicy.shouldRedirectToSetup(mode, role))
            }
        }
    }

    @Test
    fun `shouldResetToSourcePickerOnUnconfiguredTransition fires only on transition into UNCONFIGURED outside SOURCE_PICKER`() {
        assertTrue(
            SessionNavigationPolicy.shouldResetToSourcePickerOnUnconfiguredTransition(
                previousMode = SessionMode.SERVER_AUTHENTICATED,
                currentMode = SessionMode.UNCONFIGURED,
                role = RouteRole.SETTINGS,
            ),
        )
        assertTrue(
            SessionNavigationPolicy.shouldResetToSourcePickerOnUnconfiguredTransition(
                previousMode = SessionMode.SERVER_NEEDS_AUTH,
                currentMode = SessionMode.UNCONFIGURED,
                role = RouteRole.SETUP_FLOW,
            ),
        )
        assertFalse(
            SessionNavigationPolicy.shouldResetToSourcePickerOnUnconfiguredTransition(
                previousMode = null,
                currentMode = SessionMode.UNCONFIGURED,
                role = RouteRole.SETUP_FLOW,
            ),
        )
        assertFalse(
            SessionNavigationPolicy.shouldResetToSourcePickerOnUnconfiguredTransition(
                previousMode = SessionMode.UNCONFIGURED,
                currentMode = SessionMode.UNCONFIGURED,
                role = RouteRole.SETUP_FLOW,
            ),
        )
        assertFalse(
            SessionNavigationPolicy.shouldResetToSourcePickerOnUnconfiguredTransition(
                previousMode = SessionMode.SERVER_AUTHENTICATED,
                currentMode = SessionMode.UNCONFIGURED,
                role = RouteRole.SOURCE_PICKER,
            ),
        )
    }

    @Test
    fun `shouldRedirectToReauthGate respects offlineBrowseEntered`() {
        assertTrue(SessionNavigationPolicy.shouldRedirectToReauthGate(SessionMode.SERVER_NEEDS_AUTH, RouteRole.SERVER_CONTENT, false))
        assertTrue(SessionNavigationPolicy.shouldRedirectToReauthGate(SessionMode.SERVER_NEEDS_AUTH, RouteRole.SERVER_CONTENT, true))
        assertTrue(SessionNavigationPolicy.shouldRedirectToReauthGate(SessionMode.SERVER_NEEDS_AUTH, RouteRole.SOURCE_PICKER, true))

        assertTrue(SessionNavigationPolicy.shouldRedirectToReauthGate(SessionMode.SERVER_NEEDS_AUTH, RouteRole.OFFLINE_OR_LOCAL_CONTENT, false))
        assertFalse(SessionNavigationPolicy.shouldRedirectToReauthGate(SessionMode.SERVER_NEEDS_AUTH, RouteRole.OFFLINE_OR_LOCAL_CONTENT, true))
        assertTrue(SessionNavigationPolicy.shouldRedirectToReauthGate(SessionMode.SERVER_NEEDS_AUTH, RouteRole.SETTINGS, false))
        assertFalse(SessionNavigationPolicy.shouldRedirectToReauthGate(SessionMode.SERVER_NEEDS_AUTH, RouteRole.SETTINGS, true))
        assertTrue(SessionNavigationPolicy.shouldRedirectToReauthGate(SessionMode.SERVER_NEEDS_AUTH, RouteRole.MA_CONTENT, false))
        assertFalse(SessionNavigationPolicy.shouldRedirectToReauthGate(SessionMode.SERVER_NEEDS_AUTH, RouteRole.MA_CONTENT, true))

        assertFalse(SessionNavigationPolicy.shouldRedirectToReauthGate(SessionMode.SERVER_NEEDS_AUTH, RouteRole.SETUP_FLOW, false))
        assertFalse(SessionNavigationPolicy.shouldRedirectToReauthGate(SessionMode.SERVER_NEEDS_AUTH, RouteRole.REAUTH_GATE, false))
    }

    @Test
    fun `shouldRedirectFromStandalone and shouldExitSourcePicker enforce standalone and authenticated rules`() {
        assertTrue(SessionNavigationPolicy.shouldRedirectFromStandalone(SessionMode.STANDALONE_SOURCES, RouteRole.SERVER_CONTENT))
        assertTrue(SessionNavigationPolicy.shouldRedirectFromStandalone(SessionMode.STANDALONE_SOURCES, RouteRole.SOURCE_PICKER))
        assertFalse(SessionNavigationPolicy.shouldRedirectFromStandalone(SessionMode.STANDALONE_SOURCES, RouteRole.OFFLINE_OR_LOCAL_CONTENT))
        assertFalse(SessionNavigationPolicy.shouldRedirectFromStandalone(SessionMode.STANDALONE_SOURCES, RouteRole.MA_CONTENT))

        assertTrue(SessionNavigationPolicy.shouldExitSourcePicker(SessionMode.SERVER_AUTHENTICATED, RouteRole.SOURCE_PICKER))
        assertTrue(SessionNavigationPolicy.shouldExitSourcePicker(SessionMode.STANDALONE_SOURCES, RouteRole.SOURCE_PICKER))
        assertFalse(SessionNavigationPolicy.shouldExitSourcePicker(SessionMode.UNCONFIGURED, RouteRole.SOURCE_PICKER))
        assertFalse(SessionNavigationPolicy.shouldExitSourcePicker(SessionMode.SERVER_NEEDS_AUTH, RouteRole.SOURCE_PICKER))
    }

    @Test
    fun `shouldNavigateHomeOnActivation handles setup, reauth, and offline-to-auth transitions`() {
        assertTrue(
            SessionNavigationPolicy.shouldNavigateHomeOnActivation(
                mode = SessionMode.SERVER_AUTHENTICATED,
                role = RouteRole.SETUP_FLOW,
                activationEpochChanged = true,
                wasAuthenticated = false,
            ),
        )
        assertTrue(
            SessionNavigationPolicy.shouldNavigateHomeOnActivation(
                mode = SessionMode.SERVER_AUTHENTICATED,
                role = RouteRole.OFFLINE_OR_LOCAL_CONTENT,
                activationEpochChanged = true,
                wasAuthenticated = false,
            ),
        )
        // Switching server/user while already authenticated on Settings stays put
        assertFalse(
            SessionNavigationPolicy.shouldNavigateHomeOnActivation(
                mode = SessionMode.SERVER_AUTHENTICATED,
                role = RouteRole.SETTINGS,
                activationEpochChanged = true,
                wasAuthenticated = true,
            ),
        )
        // No epoch change -> never navigates
        assertFalse(
            SessionNavigationPolicy.shouldNavigateHomeOnActivation(
                mode = SessionMode.SERVER_AUTHENTICATED,
                role = RouteRole.SETUP_FLOW,
                activationEpochChanged = false,
                wasAuthenticated = false,
            ),
        )
    }

    @Test
    fun `shouldPopMissingServerRoute guards persisted servers without popping stateless setup login`() {
        val saved = setOf("srv-1", "srv-2")
        assertTrue(SessionNavigationPolicy.shouldPopMissingServerRoute(RouteRole.REAUTH_GATE, "srv-deleted", true, saved))
        assertTrue(SessionNavigationPolicy.shouldPopMissingServerRoute(RouteRole.SETUP_FLOW, "srv-deleted", true, saved))
        assertFalse(SessionNavigationPolicy.shouldPopMissingServerRoute(RouteRole.REAUTH_GATE, "srv-1", true, saved))
        // Stateless /setup login has isPersistedServer == false -> must NOT be popped
        assertFalse(SessionNavigationPolicy.shouldPopMissingServerRoute(RouteRole.REAUTH_GATE, "srv-new", false, saved))
        assertFalse(SessionNavigationPolicy.shouldPopMissingServerRoute(RouteRole.SERVER_CONTENT, "srv-deleted", true, saved))
    }

    @Test
    fun `search and MA overlay helpers match spec`() {
        assertTrue(SessionNavigationPolicy.isSearchAllowed(SessionMode.SERVER_AUTHENTICATED))
        assertFalse(SessionNavigationPolicy.isSearchAllowed(SessionMode.SERVER_NEEDS_AUTH))
        assertFalse(SessionNavigationPolicy.isSearchAllowed(SessionMode.STANDALONE_SOURCES))
        assertFalse(SessionNavigationPolicy.isSearchAllowed(SessionMode.UNCONFIGURED))

        assertTrue(SessionNavigationPolicy.isMaOverlayAllowed(SessionMode.SERVER_AUTHENTICATED))
        assertTrue(SessionNavigationPolicy.isMaOverlayAllowed(SessionMode.STANDALONE_SOURCES))
        assertFalse(SessionNavigationPolicy.isMaOverlayAllowed(SessionMode.SERVER_NEEDS_AUTH))
        assertFalse(SessionNavigationPolicy.isMaOverlayAllowed(SessionMode.UNCONFIGURED))
    }
}
