package dev.jdtech.jellyfin.session

import dev.jdtech.jellyfin.onboarding.OnboardingFailureCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionModeTest {

    @Test
    fun `deriveSessionMode covers full truth table`() {
        assertEquals(
            SessionMode.SERVER_AUTHENTICATED,
            deriveSessionMode(
                hasCurrentServer = true,
                hasCurrentServerAddress = true,
                hasCurrentUserWithToken = true,
                hasNetworkShares = false,
                standaloneModeChosen = false,
            ),
        )
        assertEquals(
            SessionMode.SERVER_NEEDS_AUTH,
            deriveSessionMode(
                hasCurrentServer = true,
                hasCurrentServerAddress = true,
                hasCurrentUserWithToken = false,
                hasNetworkShares = true,
                standaloneModeChosen = true,
            ),
        )
        assertEquals(
            SessionMode.SERVER_NEEDS_AUTH,
            deriveSessionMode(
                hasCurrentServer = true,
                hasCurrentServerAddress = false,
                hasCurrentUserWithToken = true,
                hasNetworkShares = false,
                standaloneModeChosen = false,
            ),
        )
        assertEquals(
            SessionMode.STANDALONE_SOURCES,
            deriveSessionMode(
                hasCurrentServer = false,
                hasCurrentServerAddress = false,
                hasCurrentUserWithToken = false,
                hasNetworkShares = false,
                standaloneModeChosen = true,
            ),
        )
        assertEquals(
            SessionMode.STANDALONE_SOURCES,
            deriveSessionMode(
                hasCurrentServer = false,
                hasCurrentServerAddress = false,
                hasCurrentUserWithToken = false,
                hasNetworkShares = true,
                standaloneModeChosen = false,
            ),
        )
        assertEquals(
            SessionMode.UNCONFIGURED,
            deriveSessionMode(
                hasCurrentServer = false,
                hasCurrentServerAddress = false,
                hasCurrentUserWithToken = false,
                hasNetworkShares = false,
                standaloneModeChosen = false,
            ),
        )
    }

    @Test
    fun `OnboardingFailureCode fromWireCode parses canonical and alias codes`() {
        for (code in OnboardingFailureCode.entries) {
            assertEquals(code, OnboardingFailureCode.fromWireCode(code.wireCode))
        }
        assertEquals(OnboardingFailureCode.URL_SSRF_BLOCKED, OnboardingFailureCode.fromWireCode("ssrf_blocked"))
        assertEquals(OnboardingFailureCode.AUTH_INVALID_CREDENTIALS, OnboardingFailureCode.fromWireCode("invalid_credentials"))
        assertEquals(OnboardingFailureCode.QC_DISABLED_ON_SERVER, OnboardingFailureCode.fromWireCode("qc_disabled"))
        assertEquals(OnboardingFailureCode.PAIRING_NO_VERIFIED_USER, OnboardingFailureCode.fromWireCode("no_verified_user"))
        assertEquals(OnboardingFailureCode.SERVER_HTTP_ERROR, OnboardingFailureCode.fromWireCode("unknown_nonsense"))
    }
}
