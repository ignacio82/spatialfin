package dev.jdtech.jellyfin.data.musicassistant.api

import dev.jdtech.jellyfin.data.musicassistant.utils.SessionState
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OkHttpServiceClientTest {
    @Test
    fun `RpcEngine clear drains all pending callbacks with failure so suspended callers unblock`() {
        var authErrorCount = 0
        val engine = RpcEngine(
            onAuthError = { authErrorCount++ },
            onError = {},
        )
        var received: Result<Answer>? = null
        engine.registerCallback("msg-1") { received = it }

        engine.clear(CancellationException("socket closed"))

        assertTrue(received != null && received!!.isFailure)
        assertTrue(received!!.exceptionOrNull() is CancellationException)
        assertEquals(0, authErrorCount)
    }

    @Test
    fun `RpcEngine error_code 20 invokes onAuthError and completes callback with Result success`() {
        var authErrors = 0
        val engine = RpcEngine(
            onAuthError = { authErrors++ },
            onError = {},
        )
        var received: Result<Answer>? = null
        engine.registerCallback("auth-1") { received = it }

        val handled = engine.handleResponse(
            buildJsonObject {
                put("message_id", JsonPrimitive("auth-1"))
                put("error_code", JsonPrimitive(20))
            }
        )

        assertTrue(handled)
        assertEquals(1, authErrors)
        assertTrue(received?.isSuccess == true)
    }

    @Test
    fun `disconnectForRebind transitions to Initial and bumps activeSocketGeneration`() {
        val client = OkHttpServiceClient(OkHttpClient())
        val genBefore = client.activeSocketGeneration.value

        client.disconnectForRebind()

        assertEquals(SessionState.Disconnected.Initial, client.sessionState.value)
        assertEquals(genBefore + 1L, client.activeSocketGeneration.value)
        assertFalse(client.isReadyForCommands.value)
        assertNull(client.serverBaseUrl.value)

        client.disconnectByUser()
        assertEquals(SessionState.Disconnected.ByUser, client.sessionState.value)
        assertEquals(genBefore + 2L, client.activeSocketGeneration.value)
    }
}
