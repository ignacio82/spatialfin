package dev.jdtech.jellyfin.sendspin.receiver

import android.app.Application
import android.content.Context
import android.content.Intent
import dev.jdtech.jellyfin.sendspin.receiver.remote.SendspinLoopbackRelay
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SendspinReceiverSessionTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("sendspin_music_assistant", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        SendspinReceiverService.clearOutboundHttpClientForTests()
        SendspinReceiverService.clearAppLockLockedProviderForTests()
        SendspinReceiverSession.clearMusicAssistantState(SendspinUserScope.Disabled)
        SendspinReceiverSession.reset()
    }

    @After
    fun tearDown() {
        SendspinReceiverService.clearOutboundHttpClientForTests()
        SendspinReceiverService.clearAppLockLockedProviderForTests()
        SendspinReceiverSession.clearMusicAssistantState(SendspinUserScope.Disabled)
        SendspinReceiverSession.reset()
    }

    @Test
    fun `clearMusicAssistantState wipes playback metadata and advances generation across 3-state scope`() {
        SendspinReceiverSession.update {
            it.copy(
                serviceRunning = true,
                connected = true,
                serverName = "Home MA",
                serverId = "srv-1",
                playbackState = SendspinReceiverPlaybackState.PLAYING,
                playbackStarted = true,
                title = "Song A",
                artist = "Artist A",
                album = "Album A",
                artworkUrl = "http://ma/art.jpg",
                musicAssistantServerUrl = "http://192.168.1.50:8095",
                musicAssistantToken = "secret-token-a",
                musicAssistantAuthState = SendspinMusicAssistantAuthState.AUTHENTICATED,
            )
        }
        val beforeGen = SendspinReceiverSession.state.value.userScopeGeneration

        SendspinReceiverSession.clearMusicAssistantState(SendspinUserScope.Authenticated("user-b"))
        val after = SendspinReceiverSession.state.value

        assertEquals(SendspinUserScope.Authenticated("user-b"), after.activeUserScope)
        assertEquals("user-b", after.activeJellyfinUserId)
        assertEquals(beforeGen + 1L, after.userScopeGeneration)
        assertFalse(after.connected)
        assertNull(after.serverName)
        assertNull(after.serverId)
        assertEquals(SendspinReceiverPlaybackState.STOPPED, after.playbackState)
        assertFalse(after.playbackStarted)
        assertNull(after.title)
        assertNull(after.artist)
        assertNull(after.album)
        assertNull(after.artworkUrl)
        assertNull(after.musicAssistantServerUrl)
        assertNull(after.musicAssistantToken)
        assertEquals(SendspinMusicAssistantAuthState.UNKNOWN, after.musicAssistantAuthState)
    }

    @Test
    fun `activeUserScope and userScopeGeneration stay sticky across reset and onDestroy`() {
        SendspinReceiverSession.clearMusicAssistantState(SendspinUserScope.Authenticated("user-sticky"))
        val gen = SendspinReceiverSession.state.value.userScopeGeneration

        SendspinReceiverSession.reset()
        assertEquals(SendspinUserScope.Authenticated("user-sticky"), SendspinReceiverSession.state.value.activeUserScope)
        assertEquals(gen, SendspinReceiverSession.state.value.userScopeGeneration)

        val controller = Robolectric.buildService(SendspinReceiverService::class.java).create()
        controller.destroy()
        assertEquals(SendspinUserScope.Authenticated("user-sticky"), SendspinReceiverSession.state.value.activeUserScope)
        assertEquals(gen, SendspinReceiverSession.state.value.userScopeGeneration)
    }

    @Test
    fun `hydrateMusicAssistantSessionForScope isolates per-user keys, Standalone fallback, and Disabled or Locked gating`() {
        SendspinReceiverService.storeMusicAssistantConfig(
            context = app,
            jellyfinUserId = "user-a",
            serverUrl = "http://192.168.1.10:8095",
            token = "token-a",
        )
        // Legacy global key must NOT leak into authenticated user-b or Disabled
        app.getSharedPreferences("sendspin_music_assistant", Context.MODE_PRIVATE)
            .edit()
            .putString("last_url", "http://192.168.1.99:8095")
            .putString("token_url:http://192.168.1.99:8095", "legacy-token")
            .commit()

        SendspinReceiverService.setMusicAssistantUser(app, SendspinUserScope.Authenticated("user-b"))
        val stateB = SendspinReceiverSession.state.value
        assertEquals(SendspinUserScope.Authenticated("user-b"), stateB.activeUserScope)
        assertNull(stateB.musicAssistantServerUrl)
        assertNull(stateB.musicAssistantToken)
        assertEquals(SendspinMusicAssistantAuthState.MISSING, stateB.musicAssistantAuthState)
        assertNull(shadowOf(app).nextStartedService)

        SendspinReceiverService.setMusicAssistantUser(app, SendspinUserScope.Authenticated("user-a"))
        val stateA = SendspinReceiverSession.state.value
        assertEquals("http://192.168.1.10:8095", stateA.musicAssistantServerUrl)
        assertEquals("token-a", stateA.musicAssistantToken)
        assertEquals(SendspinMusicAssistantAuthState.AUTHENTICATED, stateA.musicAssistantAuthState)

        // Standalone scope reads legacy global fallback
        SendspinReceiverService.setMusicAssistantUser(app, SendspinUserScope.Standalone)
        val stateStandalone = SendspinReceiverSession.state.value
        assertEquals(SendspinUserScope.Standalone, stateStandalone.activeUserScope)
        assertEquals("http://192.168.1.99:8095", stateStandalone.musicAssistantServerUrl)
        assertEquals("legacy-token", stateStandalone.musicAssistantToken)
        assertEquals(SendspinMusicAssistantAuthState.AUTHENTICATED, stateStandalone.musicAssistantAuthState)

        // Disabled scope never hydrates credentials
        SendspinReceiverService.setMusicAssistantUser(app, SendspinUserScope.Disabled)
        val stateDisabled = SendspinReceiverSession.state.value
        assertEquals(SendspinUserScope.Disabled, stateDisabled.activeUserScope)
        assertNull(stateDisabled.musicAssistantServerUrl)
        assertNull(stateDisabled.musicAssistantToken)
        assertEquals(SendspinMusicAssistantAuthState.UNKNOWN, stateDisabled.musicAssistantAuthState)

        // Locked state blocks hydration even for Authenticated scope
        SendspinReceiverService.setAppLockLockedProvider { true }
        SendspinReceiverService.setMusicAssistantUser(app, SendspinUserScope.Authenticated("user-a"))
        val stateLocked = SendspinReceiverSession.state.value
        assertNull(stateLocked.musicAssistantServerUrl)
        assertNull(stateLocked.musicAssistantToken)
    }

    @Test
    fun `action helpers short-circuit when locked or Disabled and catch startService failures when unlocked`() {
        val throwingContext = mockk<Context>(relaxed = true)
        every { throwingContext.applicationContext } returns app
        val startCount = AtomicInteger(0)
        every { throwingContext.startService(any()) } answers {
            startCount.incrementAndGet()
            throw IllegalStateException("background start blocked")
        }

        // Disabled scope short-circuits non-teardown action helpers without calling startService
        SendspinReceiverSession.clearMusicAssistantState(SendspinUserScope.Disabled)
        SendspinReceiverService.sendControllerCommand(throwingContext, SendspinControllerCommands.PLAY)
        SendspinReceiverService.dismissControls(throwingContext)
        SendspinReceiverService.refreshMusicAssistantGroups(throwingContext, "user-x")
        SendspinReceiverService.loginMusicAssistant(throwingContext, "http://192.168.1.10:8095", "u", "p", "user-x")
        SendspinReceiverService.saveMusicAssistantToken(throwingContext, "http://192.168.1.10:8095", "tok", "user-x")
        SendspinReceiverService.setMusicAssistantServerUrl(throwingContext, "http://192.168.1.10:8095", "user-x")
        SendspinReceiverService.setMusicAssistantRemoteId(throwingContext, "ABCDEFGHIJKLMNOPQRSTUVWXYZ", "user-x")
        SendspinReceiverService.setMusicAssistantGroupMember(throwingContext, "p1", true, "user-x")
        SendspinReceiverService.playMusicAssistantMedia(throwingContext, "library://track/1", "user-x")
        assertEquals(0, startCount.get())

        // Locked state also short-circuits non-teardown action helpers
        SendspinReceiverSession.clearMusicAssistantState(SendspinUserScope.Authenticated("user-x"))
        SendspinReceiverService.setAppLockLockedProvider { true }
        SendspinReceiverService.sendControllerCommand(throwingContext, SendspinControllerCommands.PLAY)
        assertEquals(0, startCount.get())

        // Unlocked + Authenticated invokes startService and swallows exceptions via runCatching
        SendspinReceiverService.setAppLockLockedProvider { false }
        SendspinReceiverSession.update { it.copy(serviceRunning = true) }
        SendspinReceiverService.setMusicAssistantUser(throwingContext, SendspinUserScope.Authenticated("user-x"))
        SendspinReceiverService.sendControllerCommand(throwingContext, SendspinControllerCommands.PLAY)
        assertEquals(2, startCount.get())
    }

    @Test
    fun `onStartCommand fails closed with startForeground before stopSelf when outboundHttp missing, locked, or Disabled`() {
        val http = OkHttpClient()
        SendspinReceiverService.setOutboundHttpClient(http) { http }

        // 1. Disabled scope rejects default FGS start
        SendspinReceiverSession.clearMusicAssistantState(SendspinUserScope.Disabled)
        val c1 = Robolectric.buildService(SendspinReceiverService::class.java).create()
        val r1 = c1.get().onStartCommand(Intent(app, SendspinReceiverService::class.java), 0, 1)
        assertEquals(android.app.Service.START_NOT_STICKY, r1)
        assertFalse(SendspinReceiverSession.state.value.serviceRunning)
        assertTrue(shadowOf(c1.get()).isStoppedBySelf)
        c1.destroy()

        // 2. Locked state rejects default FGS start even when Authenticated
        SendspinReceiverSession.clearMusicAssistantState(SendspinUserScope.Authenticated("user-1"))
        SendspinReceiverService.setAppLockLockedProvider { true }
        val c2 = Robolectric.buildService(SendspinReceiverService::class.java).create()
        val r2 = c2.get().onStartCommand(Intent(app, SendspinReceiverService::class.java), 0, 2)
        assertEquals(android.app.Service.START_NOT_STICKY, r2)
        assertFalse(SendspinReceiverSession.state.value.serviceRunning)
        assertTrue(shadowOf(c2.get()).isStoppedBySelf)
        c2.destroy()

        // 3. Missing outboundHttp rejects default FGS start
        SendspinReceiverService.setAppLockLockedProvider { false }
        SendspinReceiverService.clearOutboundHttpClientForTests()
        val c3 = Robolectric.buildService(SendspinReceiverService::class.java).create()
        val r3 = c3.get().onStartCommand(Intent(app, SendspinReceiverService::class.java), 0, 3)
        assertEquals(android.app.Service.START_NOT_STICKY, r3)
        assertFalse(SendspinReceiverSession.state.value.serviceRunning)
        assertTrue(shadowOf(c3.get()).isStoppedBySelf)
        c3.destroy()
    }

    @Test
    fun `onStartCommand enforces action allowlist when locked or Disabled and tears down on SET_USER Disabled`() {
        val http = OkHttpClient()
        SendspinReceiverService.setOutboundHttpClient(http) { http }
        SendspinReceiverSession.clearMusicAssistantState(SendspinUserScope.Disabled)

        val controller = Robolectric.buildService(SendspinReceiverService::class.java).create()
        val service = controller.get()

        // Disallowed action while Disabled is rejected and stops self when not running
        val disallowed =
            Intent(app, SendspinReceiverService::class.java).apply {
                action = "dev.jdtech.jellyfin.sendspin.action.MUSIC_ASSISTANT_REFRESH"
            }
        val r1 = service.onStartCommand(disallowed, 0, 7)
        assertEquals(android.app.Service.START_NOT_STICKY, r1)
        assertTrue(shadowOf(service).isStoppedBySelf)
        controller.destroy()

        // SET_USER to Disabled while serviceRunning tears down playback metadata and stops self
        val c2 = Robolectric.buildService(SendspinReceiverService::class.java).create()
        val s2 = c2.get()
        SendspinReceiverSession.update {
            it.copy(
                serviceRunning = true,
                connected = true,
                title = "Playing Track",
                playbackState = SendspinReceiverPlaybackState.PLAYING,
                playbackStarted = true,
                activeUserScope = SendspinUserScope.Disabled,
            )
        }
        val setUserIntent =
            Intent(app, SendspinReceiverService::class.java).apply {
                action = "dev.spatialfin.sendspin.action.MUSIC_ASSISTANT_SET_USER"
            }
        val r2 = s2.onStartCommand(setUserIntent, 0, 8)
        assertEquals(android.app.Service.START_NOT_STICKY, r2)
        assertFalse(SendspinReceiverSession.state.value.serviceRunning)
        assertFalse(SendspinReceiverSession.state.value.connected)
        assertNull(SendspinReceiverSession.state.value.title)
        assertEquals(SendspinReceiverPlaybackState.STOPPED, SendspinReceiverSession.state.value.playbackState)
        assertTrue(shadowOf(s2).isStoppedBySelf)
        c2.destroy()
    }

    @Test
    fun `scheduleClientRebuild refuses rebuild when locked or Disabled`() {
        val controller = Robolectric.buildService(SendspinReceiverService::class.java).create()
        val service = controller.get()

        SendspinReceiverSession.clearMusicAssistantState(SendspinUserScope.Disabled)
        assertFalse(service.scheduleClientRebuild("test_disabled"))

        SendspinReceiverSession.clearMusicAssistantState(SendspinUserScope.Authenticated("u1"))
        SendspinReceiverService.setAppLockLockedProvider { true }
        assertFalse(service.scheduleClientRebuild("test_locked"))

        controller.destroy()
    }

    @Test
    fun `migrateUserScope and clearUserScope move and wipe per-user MA preferences`() {
        SendspinReceiverService.storeMusicAssistantConfig(
            context = app,
            jellyfinUserId = "old-user",
            serverUrl = "http://192.168.1.30:8095",
            token = "tok-old",
        )
        SendspinReceiverService.storeMusicAssistantConfig(
            context = app,
            jellyfinUserId = "keep-user",
            serverUrl = "http://192.168.1.40:8095",
            token = "tok-keep",
        )

        SendspinReceiverService.migrateUserScope(app, "old-user", "new-user")
        SendspinReceiverService.setMusicAssistantUser(app, SendspinUserScope.Authenticated("new-user"))
        assertEquals("http://192.168.1.30:8095", SendspinReceiverSession.state.value.musicAssistantServerUrl)
        assertEquals("tok-old", SendspinReceiverSession.state.value.musicAssistantToken)

        SendspinReceiverService.setMusicAssistantUser(app, SendspinUserScope.Authenticated("old-user"))
        assertNull(SendspinReceiverSession.state.value.musicAssistantServerUrl)
        assertNull(SendspinReceiverSession.state.value.musicAssistantToken)

        // clearUserScope wipes active user's keys and resets active scope to Disabled
        SendspinReceiverService.setMusicAssistantUser(app, SendspinUserScope.Authenticated("new-user"))
        SendspinReceiverService.clearUserScope(app, "new-user")
        assertEquals(SendspinUserScope.Disabled, SendspinReceiverSession.state.value.activeUserScope)
        assertNull(SendspinReceiverSession.state.value.musicAssistantToken)

        // Other user's config remains intact
        SendspinReceiverService.setMusicAssistantUser(app, SendspinUserScope.Authenticated("keep-user"))
        assertEquals("http://192.168.1.40:8095", SendspinReceiverSession.state.value.musicAssistantServerUrl)
        assertEquals("tok-keep", SendspinReceiverSession.state.value.musicAssistantToken)
    }

    @Test
    fun `onAppUnlocked dispatches ACTION_APP_UNLOCKED only when unlocked, non-Disabled, and serviceRunning`() {
        val locked = AtomicBoolean(true)
        SendspinReceiverService.setAppLockLockedProvider { locked.get() }
        SendspinReceiverSession.clearMusicAssistantState(SendspinUserScope.Authenticated("u1"))
        SendspinReceiverSession.update { it.copy(serviceRunning = true) }

        SendspinReceiverService.onAppUnlocked(app)
        assertNull(shadowOf(app).nextStartedService)

        locked.set(false)
        SendspinReceiverService.onAppUnlocked(app)
        val started = shadowOf(app).nextStartedService
        assertNotNull(started)
        assertEquals(SendspinReceiverService.ACTION_APP_UNLOCKED, started.action)
    }

    @Test
    fun `loopback relay enforces CSPRNG token at handshake and single-connection exclusivity`() {
        val receivedTexts = mutableListOf<String>()
        val textLatch = CountDownLatch(1)
        val relay =
            SendspinLoopbackRelay(
                onOutboundText = {
                    receivedTexts += it
                    textLatch.countDown()
                },
                onOutboundBinary = {},
            )
        try {
            val port = relay.start()
            assertTrue("Loopback relay should bind on ephemeral port", port > 0)
            val validUrl = requireNotNull(relay.loopbackUrl())
            assertTrue(validUrl.startsWith("ws://127.0.0.1:$port/?token="))
            assertTrue(validUrl.substringAfter("?token=").length == 32)

            val client = OkHttpClient()

            // 1. Missing token rejected BEFORE HTTP 101
            val badNoTokenLatch = CountDownLatch(1)
            val badNoTokenFailed = AtomicInteger(0)
            client.newWebSocket(
                Request.Builder().url("ws://127.0.0.1:$port/").build(),
                object : WebSocketListener() {
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        badNoTokenFailed.incrementAndGet()
                        badNoTokenLatch.countDown()
                    }
                },
            )
            assertTrue(badNoTokenLatch.await(5, TimeUnit.SECONDS))
            assertEquals(1, badNoTokenFailed.get())

            // 2. Wrong token rejected BEFORE HTTP 101
            val badTokenLatch = CountDownLatch(1)
            val badTokenFailed = AtomicInteger(0)
            client.newWebSocket(
                Request.Builder().url("ws://127.0.0.1:$port/?token=00000000000000000000000000000000").build(),
                object : WebSocketListener() {
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        badTokenFailed.incrementAndGet()
                        badTokenLatch.countDown()
                    }
                },
            )
            assertTrue(badTokenLatch.await(5, TimeUnit.SECONDS))
            assertEquals(1, badTokenFailed.get())

            client.connectionPool.evictAll()

            // 3. Valid token connects and relays text bidirectionally
            val openLatch = CountDownLatch(1)
            val inboundMsgLatch = CountDownLatch(1)
            val inboundMsg = AtomicReference<String>()
            val ws1Failure = AtomicReference<String>()
            val ws1 =
                client.newWebSocket(
                    Request.Builder().url(validUrl).build(),
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            openLatch.countDown()
                            webSocket.send("hello-from-client")
                        }

                        override fun onMessage(webSocket: WebSocket, text: String) {
                            inboundMsg.set(text)
                            inboundMsgLatch.countDown()
                        }

                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                            ws1Failure.set("closing:$code:$reason")
                        }

                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                            ws1Failure.set("failure:${response?.code}:${t.javaClass.simpleName}:${t.message}")
                            openLatch.countDown()
                        }
                    },
                )
            assertTrue("ws1 open failed: ${ws1Failure.get()}", openLatch.await(5, TimeUnit.SECONDS))
            assertNull("ws1 failed on open: ${ws1Failure.get()}", ws1Failure.get())
            assertTrue("ws1 text not received; failure=${ws1Failure.get()}", textLatch.await(5, TimeUnit.SECONDS))
            assertEquals(listOf("hello-from-client"), receivedTexts)

            relay.deliverText("hello-from-server")
            assertTrue("inbound not received; failure=${ws1Failure.get()}", inboundMsgLatch.await(5, TimeUnit.SECONDS))
            assertEquals("hello-from-server", inboundMsg.get())

            // 4. Second simultaneous connection with the SAME valid token is rejected (exclusivity)
            val secondFailLatch = CountDownLatch(1)
            val secondOpened = AtomicInteger(0)
            client.newWebSocket(
                Request.Builder().url(validUrl).build(),
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        secondOpened.incrementAndGet()
                        secondFailLatch.countDown()
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        secondFailLatch.countDown()
                    }
                },
            )
            assertTrue(secondFailLatch.await(5, TimeUnit.SECONDS))
            assertEquals(0, secondOpened.get())

            ws1.close(1000, "done")
            client.dispatcher.executorService.shutdown()
        } finally {
            relay.stop()
        }
    }

    @Test
    fun `loginAndStoreMusicAssistantConfig stores scoped token and hydrates active user`() = runBlocking {
        SendspinReceiverSession.clearMusicAssistantState(SendspinUserScope.Authenticated("user-login"))
        val res =
            SendspinReceiverService.loginAndStoreMusicAssistantConfig(
                context = app,
                jellyfinUserId = "user-login",
                serverUrl = "http://192.168.1.44:8095/",
                token = "direct-token-123",
                username = null,
                password = null,
                httpClient = OkHttpClient(),
            )
        assertTrue(res.isSuccess)
        val state = SendspinReceiverSession.state.value
        assertEquals("http://192.168.1.44:8095", state.musicAssistantServerUrl)
        assertEquals("direct-token-123", state.musicAssistantToken)
        assertEquals(SendspinMusicAssistantAuthState.AUTHENTICATED, state.musicAssistantAuthState)

        // Storing for another user does NOT overwrite active user's session state
        val otherRes =
            SendspinReceiverService.loginAndStoreMusicAssistantConfig(
                context = app,
                jellyfinUserId = "user-other",
                serverUrl = "http://192.168.1.88:8095",
                token = "other-token",
                username = null,
                password = null,
                httpClient = OkHttpClient(),
            )
        assertTrue(otherRes.isSuccess)
        assertNotEquals("other-token", SendspinReceiverSession.state.value.musicAssistantToken)
        assertEquals("direct-token-123", SendspinReceiverSession.state.value.musicAssistantToken)
        assertNotNull(SendspinReceiverService.extractLoopbackHostPort("ws://127.0.0.1:45678/?token=abc"))
    }
}
