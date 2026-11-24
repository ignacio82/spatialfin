package dev.jdtech.jellyfin.film.repository

import android.content.Context
import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.network.UrlTargetPolicy
import dev.jdtech.jellyfin.network.withSsrfGuard
import dev.jdtech.jellyfin.sendspin.receiver.MusicAssistantRemoteBridge
import dev.jdtech.jellyfin.sendspin.receiver.SendspinReceiverSession
import dev.jdtech.jellyfin.sendspin.receiver.SendspinUserScope
import io.mockk.mockk
import java.util.UUID
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MusicAssistantRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var server2: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        server2 = MockWebServer()
        server2.start()
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("sendspin_music_assistant", Context.MODE_PRIVATE).edit().clear().commit()
        SendspinReceiverSession.clearMusicAssistantState(SendspinUserScope.Disabled)
        MusicAssistantRemoteBridge.remoteExecutor = null
    }

    @After
    fun tearDown() {
        MusicAssistantRemoteBridge.remoteExecutor = null
        SendspinReceiverSession.clearMusicAssistantState(SendspinUserScope.Disabled)
        server.close()
        server2.close()
    }

    @Test
    fun getCredentialsEnforcesThreeStateSendspinUserScopeIsolation() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("sendspin_music_assistant", Context.MODE_PRIVATE)
        val userA = UUID.randomUUID().toString()
        val userB = UUID.randomUUID().toString()

        prefs.edit()
            .putString("u:default/last_url", "http://192.168.1.50:8095")
            .putString("u:default/token_url:http://192.168.1.50:8095", "default-token")
            .putString("u:$userA/last_url", "http://192.168.1.51:8095")
            .putString("u:$userA/token_url:http://192.168.1.51:8095", "token-a")
            .commit()

        val client = OkHttpClient.Builder().withSsrfGuard(UrlTargetPolicy.PUBLIC_OR_LAN).build()
        val repo = MusicAssistantRepository(context, mockk<JellyfinApi>(relaxed = true), client)

        // Authenticated(userA) sees ONLY userA's credentials
        SendspinReceiverSession.clearMusicAssistantState(SendspinUserScope.Authenticated(userA))
        assertEquals("http://192.168.1.51:8095" to "token-a", repo.getCredentials())

        // Authenticated(userB) with no stored MA creds does NOT fall back to u:default/ or userA
        SendspinReceiverSession.clearMusicAssistantState(SendspinUserScope.Authenticated(userB))
        assertNull(repo.getCredentials())

        // Standalone sees u:default/ and legacy ""
        SendspinReceiverSession.clearMusicAssistantState(SendspinUserScope.Standalone)
        assertEquals("http://192.168.1.50:8095" to "default-token", repo.getCredentials())

        // Disabled fails closed for BOTH getCredentials() and executeCommand(), even with remoteExecutor
        var remoteCalled = false
        MusicAssistantRemoteBridge.remoteExecutor = { _, _ ->
            remoteCalled = true
            """[{"name":"Leak"}]"""
        }
        SendspinReceiverSession.clearMusicAssistantState(SendspinUserScope.Disabled)
        assertNull(repo.getCredentials())
        assertNull(repo.executeCommand("music/tracks/library_items"))
        assertEquals(false, remoteCalled)
    }

    @Test
    fun executeCommandEnforcesSsrfAndBlocksCrossOriginRedirects() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("sendspin_music_assistant", Context.MODE_PRIVATE)
        val userA = UUID.randomUUID().toString()
        val baseUrl = server.url("/").toString().trimEnd('/')

        prefs.edit()
            .putString("u:$userA/last_url", baseUrl)
            .putString("u:$userA/token_url:$baseUrl", "ma-bearer-token")
            .commit()

        SendspinReceiverSession.clearMusicAssistantState(SendspinUserScope.Authenticated(userA))
        val apiA = mockk<JellyfinApi>(relaxed = true)

        // 1. Production client (allowLoopbackForTests = false) blocks loopback MA URL
        val strictClient = OkHttpClient.Builder().withSsrfGuard(UrlTargetPolicy.PUBLIC_OR_LAN).build()
        val strictRepo = MusicAssistantRepository(context, apiA, strictClient)
        assertNull(strictRepo.executeCommand("music/tracks/library_items"))
        assertEquals(0, server.requestCount)

        // 2. Test client with allowLoopbackForTests = true succeeds on direct call and blocks cross-origin redirect
        val testClient = OkHttpClient.Builder()
            .withSsrfGuard(UrlTargetPolicy.PUBLIC_OR_LAN, allowLoopbackForTests = true)
            .build()
        val repo = MusicAssistantRepository(context, apiA, testClient)

        server.enqueue(MockResponse(code = 200, body = """[{"name":"Track 1"}]"""))
        val res = repo.executeCommand("music/tracks/library_items")
        assertNotNull(res)
        assertEquals(1, res!!.length())
        val req = server.takeRequest()
        assertEquals("Bearer ma-bearer-token", req.headers["Authorization"])

        server.enqueue(
            MockResponse(
                code = 307,
                headers = okhttp3.Headers.headersOf("Location", server2.url("/api").toString()),
            ),
        )
        server2.enqueue(MockResponse(code = 200, body = """[{"name":"Stolen"}]"""))
        assertNull(repo.executeCommand("music/tracks/library_items"))
        assertEquals(0, server2.requestCount)
    }
}
