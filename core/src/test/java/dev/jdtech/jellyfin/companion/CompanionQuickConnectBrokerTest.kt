package dev.jdtech.jellyfin.companion

import android.os.SystemClock
import dev.jdtech.jellyfin.models.companion.CompanionEndpoint
import dev.jdtech.jellyfin.network.UrlTargetPolicy
import dev.jdtech.jellyfin.network.newBuilderWithSsrfGuard
import dev.jdtech.jellyfin.onboarding.OnboardingFailureCode
import dev.jdtech.jellyfin.quickconnect.PollResult
import dev.jdtech.jellyfin.quickconnect.QuickConnectClient
import dev.jdtech.jellyfin.quickconnect.QuickConnectInitiated
import io.mockk.coEvery
import io.mockk.mockk
import java.util.UUID
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.jellyfin.sdk.model.api.AuthenticationResult
import org.jellyfin.sdk.model.api.UserDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CompanionQuickConnectBrokerTest {

    private fun loopbackClient(): OkHttpClient =
        OkHttpClient()
            .newBuilderWithSsrfGuard(
                policy = UrlTargetPolicy.LAN_ONLY,
                allowLoopbackForTests = true,
            )
            .build()

    @Test
    fun `authorizeAndMint succeeds across B1-B4 and verifies serverId and userId`() = runBlocking {
        val companionServer = MockWebServer()
        companionServer.start()
        try {
            companionServer.enqueue(MockResponse(code = 200, body = """{"authorized":true}"""))

            val userId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
            val serverId = "abc123def456"
            val qcClient = mockk<QuickConnectClient>()
            coEvery { qcClient.initiate(any(), any()) } returns QuickConnectInitiated(code = "123456", secret = "sec-1")
            coEvery { qcClient.pollOnce(any(), "sec-1", any()) } returns PollResult.Authenticated

            val userDto = mockk<UserDto>(relaxed = true)
            coEvery { userDto.id } returns userId
            val authResult = mockk<AuthenticationResult>(relaxed = true)
            coEvery { authResult.user } returns userDto
            coEvery { authResult.serverId } returns "abc1-23de-f456"
            coEvery { authResult.accessToken } returns "minted-device-token"
            coEvery { qcClient.authenticate(any(), "sec-1", any()) } returns authResult

            val broker = CompanionQuickConnectBroker(loopbackClient(), qcClient).apply {
                allowLoopbackForTests = true
            }
            val endpoint = CompanionEndpoint.parse(
                rawBaseUrl = companionServer.url("/").toString(),
                rawSetupToken = "sf-setup-0123456789abcdef0123456789abcdef",
                allowLoopbackForTests = true,
            )
            val outcome = broker.authorizeAndMint(
                endpoint = endpoint,
                policy = UrlTargetPolicy.LAN_ONLY,
                companionServerId = "srv-1",
                expectedServerId = serverId,
                jellyfinBaseUrl = "http://192.168.1.50:8096",
                selectedUserId = userId,
                maxBrokerBudgetMs = CompanionQuickConnectBroker.INTERACTIVE_BROKER_BUDGET_MS,
            )
            assertTrue("Expected Authenticated, got $outcome", outcome is BrokerAuthResult.Authenticated)
            assertEquals("minted-device-token", (outcome as BrokerAuthResult.Authenticated).result.accessToken)
        } finally {
            companionServer.close()
        }
    }

    @Test
    fun `authorizeAndMint maps Companion 409 qc_disabled, 404, 401, and exhausted deadline accurately`() = runBlocking {
        val companionServer = MockWebServer()
        companionServer.start()
        try {
            val userId = UUID.randomUUID()
            val qcClient = mockk<QuickConnectClient>()
            coEvery { qcClient.initiate(any(), any()) } returns QuickConnectInitiated(code = "654321", secret = "sec-2")

            val broker = CompanionQuickConnectBroker(loopbackClient(), qcClient).apply {
                allowLoopbackForTests = true
            }
            val endpoint = CompanionEndpoint.parse(
                rawBaseUrl = companionServer.url("/").toString(),
                rawSetupToken = "sf-setup-0123456789abcdef0123456789abcdef",
                allowLoopbackForTests = true,
            )

            // 409 qc_disabled -> FallbackToLogin(QC_DISABLED_ON_SERVER)
            companionServer.enqueue(MockResponse(code = 409, body = """{"error_code":"qc_disabled"}"""))
            val res409 = broker.authorizeAndMint(
                endpoint = endpoint,
                policy = UrlTargetPolicy.LAN_ONLY,
                companionServerId = "srv-1",
                expectedServerId = "srv-1",
                jellyfinBaseUrl = "http://192.168.1.50:8096",
                selectedUserId = userId,
                maxBrokerBudgetMs = 8_000L,
            )
            assertEquals(BrokerAuthResult.FallbackToLogin(OnboardingFailureCode.QC_DISABLED_ON_SERVER), res409)

            // 401 -> Failed(AUTH_INVALID_CREDENTIALS)
            companionServer.enqueue(MockResponse(code = 401, body = """{"error":"Unauthorized"}"""))
            val res401 = broker.authorizeAndMint(
                endpoint = endpoint,
                policy = UrlTargetPolicy.LAN_ONLY,
                companionServerId = "srv-1",
                expectedServerId = "srv-1",
                jellyfinBaseUrl = "http://192.168.1.50:8096",
                selectedUserId = userId,
                maxBrokerBudgetMs = 8_000L,
            )
            assertEquals(BrokerAuthResult.Failed(OnboardingFailureCode.AUTH_INVALID_CREDENTIALS), res401)

            // Deadline with less than 3 s reserved tail + 1 s min budget -> URL_TIMEOUT immediately
            val resExpired = broker.authorizeAndMint(
                endpoint = endpoint,
                policy = UrlTargetPolicy.LAN_ONLY,
                companionServerId = "srv-1",
                expectedServerId = "srv-1",
                jellyfinBaseUrl = "http://192.168.1.50:8096",
                selectedUserId = userId,
                deadlineElapsedMs = SystemClock.elapsedRealtime() + 2_000L,
                maxBrokerBudgetMs = 8_000L,
            )
            assertEquals(BrokerAuthResult.Failed(OnboardingFailureCode.URL_TIMEOUT), resExpired)
        } finally {
            companionServer.close()
        }
    }
}
