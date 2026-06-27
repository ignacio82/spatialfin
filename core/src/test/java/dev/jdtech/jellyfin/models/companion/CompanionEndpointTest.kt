package dev.jdtech.jellyfin.models.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionEndpointTest {
    @Test
    fun `normalizes base URL and builds authenticated endpoint URLs`() {
        val endpoint = CompanionEndpoint.parse("http://192.168.1.20:3000/", " token ")

        assertEquals("http://192.168.1.20:3000", endpoint.normalizedBaseUrl)
        assertEquals("http://192.168.1.20:3000/api/v1/config", endpoint.configUrl.toString())
        assertEquals("http://192.168.1.20:3000/api/v1/device-logs", endpoint.deviceLogsUrl.toString())
        assertEquals("ws://192.168.1.20:3000", endpoint.webSocketUrlString())

        val request = endpoint.authorizedRequest(endpoint.configUrl).build()
        assertEquals("token", request.header("X-Setup-Token"))
    }

    @Test
    fun `allows cleartext only for local network hosts`() {
        assertTrue(CompanionEndpoint.isLocalNetworkHost("localhost"))
        assertTrue(CompanionEndpoint.isLocalNetworkHost("companion.local"))
        assertTrue(CompanionEndpoint.isLocalNetworkHost("10.0.0.5"))
        assertTrue(CompanionEndpoint.isLocalNetworkHost("172.16.0.5"))
        assertTrue(CompanionEndpoint.isLocalNetworkHost("192.168.1.5"))
        assertTrue(CompanionEndpoint.isLocalNetworkHost("169.254.1.5"))
        assertFalse(CompanionEndpoint.isLocalNetworkHost("8.8.8.8"))
        assertFalse(CompanionEndpoint.isLocalNetworkHost("example.com"))
    }

    @Test
    fun `rejects public cleartext companion URL`() {
        val result = runCatching { CompanionEndpoint.parse("http://example.com", "token") }

        assertTrue(result.isFailure)
    }

    @Test
    fun `allows public HTTPS companion URL`() {
        val endpoint = CompanionEndpoint.parse("https://example.com/companion", "token")

        assertEquals("https://example.com/companion", endpoint.normalizedBaseUrl)
        assertEquals("https://example.com/companion/api/v1/config", endpoint.configUrl.toString())
        assertEquals("wss://example.com/companion", endpoint.webSocketUrlString())
    }

    @Test
    fun `rejects URLs with credentials query or missing token`() {
        assertTrue(runCatching { CompanionEndpoint.parse("https://user@example.com", "token") }.isFailure)
        assertTrue(runCatching { CompanionEndpoint.parse("https://example.com?x=1", "token") }.isFailure)
        assertTrue(runCatching { CompanionEndpoint.parse("https://example.com", " ") }.isFailure)
    }
}
