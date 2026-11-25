package dev.jdtech.jellyfin.setup.onboarding

import dev.jdtech.jellyfin.onboarding.OnboardingFailureCode
import dev.jdtech.jellyfin.setup.R as SetupR
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CompanionRecoveryActionTest {

    @Test
    fun `resolves servers deep link with relink params for no_verified_user`() {
        val action =
            resolveCompanionRecoveryAction(
                code = OnboardingFailureCode.PAIRING_NO_VERIFIED_USER,
                companionBaseUrl = "http://192.168.1.90:1982/",
                serverId = "fd6ec3df44f74b4cb2d7cd721991de1b",
                username = "ignacio smith",
            )
        assertNotNull(action)
        assertEquals(SetupR.string.companion_recovery_open_servers, action!!.labelRes)
        assertEquals(
            "http://192.168.1.90:1982/#servers?action=relink&serverId=fd6ec3df44f74b4cb2d7cd721991de1b&user=ignacio%20smith",
            action.targetUrl,
        )
    }

    @Test
    fun `resolves overrides deep link for qc_broker_rejected`() {
        val action =
            resolveCompanionRecoveryAction(
                code = OnboardingFailureCode.QC_BROKER_REJECTED,
                companionBaseUrl = "https://companion.example.com:1982/#old?x=1",
                serverId = "srv-1",
                username = "alice",
            )
        assertNotNull(action)
        assertEquals(SetupR.string.companion_recovery_open_overrides, action!!.labelRes)
        assertEquals(
            "https://companion.example.com:1982/#servers?action=overrides&serverId=srv-1&user=alice",
            action.targetUrl,
        )
    }

    @Test
    fun `resolves server screen deep links for unreachable, qc_disabled, and mismatch`() {
        val unreachable =
            resolveCompanionRecoveryAction(
                code = OnboardingFailureCode.QC_BROKER_UNREACHABLE,
                companionBaseUrl = "http://192.168.1.90:1982",
            )
        val disabled =
            resolveCompanionRecoveryAction(
                code = OnboardingFailureCode.QC_DISABLED_ON_SERVER,
                companionBaseUrl = "http://192.168.1.90:1982",
            )
        val mismatch =
            resolveCompanionRecoveryAction(
                code = OnboardingFailureCode.SERVER_IDENTITY_MISMATCH,
                companionBaseUrl = "http://192.168.1.90:1982",
            )
        assertEquals("http://192.168.1.90:1982/#servers", unreachable?.targetUrl)
        assertEquals("http://192.168.1.90:1982/#servers", disabled?.targetUrl)
        assertEquals("http://192.168.1.90:1982/#servers", mismatch?.targetUrl)
    }

    @Test
    fun `gates auth_invalid_credentials dashboard link on companion context`() {
        assertNull(
            resolveCompanionRecoveryAction(
                code = OnboardingFailureCode.AUTH_INVALID_CREDENTIALS,
                companionBaseUrl = "http://192.168.1.90:1982",
                isCompanionContext = false,
            ),
        )
        val action =
            resolveCompanionRecoveryAction(
                code = OnboardingFailureCode.AUTH_INVALID_CREDENTIALS,
                companionBaseUrl = "http://192.168.1.90:1982",
                isCompanionContext = true,
            )
        assertEquals(SetupR.string.companion_recovery_open_dashboard, action?.labelRes)
        assertEquals("http://192.168.1.90:1982/#dashboard", action?.targetUrl)
    }

    @Test
    fun `rejects blank and non-http URLs`() {
        assertNull(resolveCompanionRecoveryAction(OnboardingFailureCode.PAIRING_NO_VERIFIED_USER, null))
        assertNull(resolveCompanionRecoveryAction(OnboardingFailureCode.PAIRING_NO_VERIFIED_USER, "  "))
        assertNull(resolveCompanionRecoveryAction(OnboardingFailureCode.PAIRING_NO_VERIFIED_USER, "javascript:alert(1)"))
        assertNull(resolveCompanionRecoveryAction(OnboardingFailureCode.PAIRING_NO_VERIFIED_USER, "http://"))
    }
}
