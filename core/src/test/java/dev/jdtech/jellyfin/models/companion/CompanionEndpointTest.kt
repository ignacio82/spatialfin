package dev.jdtech.jellyfin.models.companion

import android.content.SharedPreferences
import dev.jdtech.jellyfin.network.UrlTargetPolicy
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionEndpointTest {
    @Test
    fun `normalizes base URL and builds authenticated endpoint URLs`() {
        val endpoint = CompanionEndpoint.parse("http://192.168.1.20:3000/", " token ")

        assertEquals("http://192.168.1.20:3000", endpoint.normalizedBaseUrl)
        assertEquals("http://192.168.1.20:3000/api/v1/config", endpoint.configUrl.toString())
        assertEquals("http://192.168.1.20:3000/api/v1/device-logs", endpoint.deviceLogsUrl.toString())
        assertEquals(
            "http://192.168.1.20:3000/api/v1/auth/authorize-quick-connect",
            endpoint.authorizeQuickConnectUrl.toString(),
        )
        assertEquals("ws://192.168.1.20:3000", endpoint.webSocketUrlString())

        val request = endpoint.authorizedRequest(endpoint.configUrl).build()
        assertEquals("token", request.header("X-Setup-Token"))
    }

    @Test
    fun `allows cleartext only for RFC1918 CGNAT ULA and blocks loopback IMDS and public HTTP`() {
        assertNotNull(CompanionEndpoint.parse("http://10.0.0.5:1982", "token"))
        assertNotNull(CompanionEndpoint.parse("http://172.16.0.5:1982", "token"))
        assertNotNull(CompanionEndpoint.parse("http://192.168.1.5:1982", "token"))
        assertNotNull(CompanionEndpoint.parse("http://100.64.0.5:1982", "token"))
        assertNotNull(CompanionEndpoint.parse("http://[fd00::1]:1982", "token"))

        assertTrue(runCatching { CompanionEndpoint.parse("http://localhost:1982", "token") }.isFailure)
        assertTrue(runCatching { CompanionEndpoint.parse("http://127.0.0.1:1982", "token") }.isFailure)
        assertTrue(runCatching { CompanionEndpoint.parse("http://169.254.169.254:1982", "token") }.isFailure)
        assertTrue(runCatching { CompanionEndpoint.parse("http://8.8.8.8:1982", "token") }.isFailure)
        assertTrue(runCatching { CompanionEndpoint.parse("http://127.1:1982", "token") }.isFailure)
    }

    @Test
    fun `allows public HTTPS under LAN_ONLY and blocks public HTTPS under PRIVATE_LAN_ONLY`() {
        val endpoint = CompanionEndpoint.parse("https://example.com/companion", "token")
        assertEquals("https://example.com/companion", endpoint.normalizedBaseUrl)
        assertEquals("https://example.com/companion/api/v1/config", endpoint.configUrl.toString())
        assertEquals(
            "https://example.com/companion/api/v1/auth/authorize-quick-connect",
            endpoint.authorizeQuickConnectUrl.toString(),
        )
        assertEquals("wss://example.com/companion", endpoint.webSocketUrlString())

        assertTrue(
            runCatching {
                CompanionEndpoint.parse(
                    "https://8.8.8.8/companion",
                    "token",
                    policy = UrlTargetPolicy.PRIVATE_LAN_ONLY,
                )
            }.isFailure,
        )
    }

    @Test
    fun `fromPreferences returns endpoint when configured and null when blank or invalid`() {
        val backing = mutableMapOf<String, String?>()
        val sp = mockk<SharedPreferences>(relaxed = true)
        every { sp.getString(any(), any()) } answers {
            val key = firstArg<String>()
            val def = secondArg<String?>()
            backing[key] ?: def
        }
        val prefs = AppPreferences(sp)

        backing[prefs.companionUrl.backendName] = "http://192.168.1.90:1982"
        backing[prefs.companionToken.backendName] = "sf-setup-123"
        assertEquals("http://192.168.1.90:1982", CompanionEndpoint.fromPreferences(prefs)?.normalizedBaseUrl)

        backing[prefs.companionUrl.backendName] = "http://127.0.0.1:1982"
        assertNull(CompanionEndpoint.fromPreferences(prefs))

        backing[prefs.companionUrl.backendName] = ""
        assertNull(CompanionEndpoint.fromPreferences(prefs))
    }

    @Test
    fun `rejects URLs with credentials query or missing token`() {
        assertTrue(runCatching { CompanionEndpoint.parse("https://user@example.com", "token") }.isFailure)
        assertTrue(runCatching { CompanionEndpoint.parse("https://example.com?x=1", "token") }.isFailure)
        assertTrue(runCatching { CompanionEndpoint.parse("https://example.com", " ") }.isFailure)
    }
}
