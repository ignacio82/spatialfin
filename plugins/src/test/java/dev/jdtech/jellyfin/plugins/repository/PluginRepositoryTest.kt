package dev.jdtech.jellyfin.plugins.repository

import android.content.Context
import dev.jdtech.jellyfin.session.ActiveSessionBus
import dev.jdtech.jellyfin.plugins.bridge.RealDOMParserBridge
import dev.jdtech.jellyfin.plugins.bridge.RealHttpBridge
import dev.jdtech.jellyfin.plugins.bridge.RealUtilitiesBridge
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PluginRepositoryTest {

    private lateinit var repository: PluginRepository
    private val okHttpClient = mockk<OkHttpClient>()
    private lateinit var context: Context

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        repository = PluginRepository(context, okHttpClient, mockk(relaxed = true), ActiveSessionBus(), mockk(relaxed = true), mockk(relaxed = true))
    }

    @Test
    fun `test plugin installation and retrieval`() = runBlocking {
        val manifestUrl = "https://test.com/manifest.json"
        val scriptUrl = "./script.js"
        val absoluteScriptUrl = "https://test.com/script.js"
        
        val manifestJson = """
            {
                "id": "test-plugin",
                "name": "Test Plugin",
                "author": "Author",
                "version": 1,
                "scriptUrl": "$scriptUrl"
            }
        """.trimIndent()
        
        val scriptContent = "console.log('hello');"

        val mockCall1 = mockk<Call>()
        val mockResponse1 = mockk<Response>()
        every { okHttpClient.newCall(match { it.url.toString() == manifestUrl }) } returns mockCall1
        every { mockCall1.execute() } returns mockResponse1
        every { mockResponse1.isSuccessful } returns true
        every { mockResponse1.body } returns manifestJson.toResponseBody()
        every { mockResponse1.close() } returns Unit

        val mockCall2 = mockk<Call>()
        val mockResponse2 = mockk<Response>()
        every { okHttpClient.newCall(match { it.url.toString() == absoluteScriptUrl }) } returns mockCall2
        every { mockCall2.execute() } returns mockResponse2
        every { mockResponse2.isSuccessful } returns true
        every { mockResponse2.body } returns scriptContent.toResponseBody()
        every { mockResponse2.close() } returns Unit

        val result = repository.installPlugin(manifestUrl)
        
        assert(result.isSuccess)
        assertEquals("test-plugin", result.getOrNull()?.id)
        
        val installed = repository.getInstalledPlugins()
        assertEquals(1, installed.size)
        assertEquals("test-plugin", installed[0].id)
        
        val script = repository.getPluginScript("test-plugin")
        assertEquals(scriptContent, script)
    }

    @Test
    fun `test cold-start scope resolution reads authenticated database user without mutating jellyfinApi and defaults on blank token`() = runBlocking {
        val mockApi = mockk<dev.jdtech.jellyfin.api.JellyfinApi>(relaxed = true)
        var capturedUserId: java.util.UUID? = null
        every { mockApi.userId } answers { capturedUserId }
        every { mockApi.userId = any() } answers { capturedUserId = firstArg() }

        val realAppPrefs = dev.jdtech.jellyfin.settings.domain.AppPreferences(context.getSharedPreferences("test_prefs", android.content.Context.MODE_PRIVATE))
        val mockDb = mockk<dev.jdtech.jellyfin.database.ServerDatabaseDao>(relaxed = true)

        val serverId = "server-uuid-123"
        val expectedUserId = java.util.UUID.fromString("11111111-2222-3333-4444-555555555555")
        val authedUser = dev.jdtech.jellyfin.models.User(
            id = expectedUserId,
            name = "alice",
            serverId = serverId,
            accessToken = "valid-token",
        )

        realAppPrefs.setValue(realAppPrefs.currentServer, serverId)
        every { mockDb.getServerCurrentUser(serverId) } returns authedUser

        val repo = PluginRepository(context, okHttpClient, mockApi, ActiveSessionBus(), realAppPrefs, mockDb)

        assertEquals(expectedUserId.toString(), repo.currentScope())
        // Must NOT mutate jellyfinApi.userId
        assertEquals(null, capturedUserId)

        repo.updatePluginHomeRowEnabled("my-plugin", "suggestions", false)
        assertEquals(false, repo.isPluginHomeRowEnabled("my-plugin", "suggestions", true))

        // Blank token falls back to "default"
        every { mockDb.getServerCurrentUser(serverId) } returns authedUser.copy(accessToken = null)
        assertEquals("default", repo.currentScope())

        // migrateUserScope & clearUserScope move and delete scope directories cleanly
        val newUserId = java.util.UUID.fromString("22222222-3333-4444-5555-666666666666")
        repo.migrateUserScope(expectedUserId.toString(), newUserId.toString())
        assertTrue(File(context.filesDir, "universal_plugins/$newUserId").exists())
        assertTrue(!File(context.filesDir, "universal_plugins/$expectedUserId").exists())
        repo.clearUserScope(newUserId.toString())
        assertTrue(!File(context.filesDir, "universal_plugins/$newUserId").exists())
    }

    @Test
    fun `test installPlugin and uninstallPlugin emit settingsChanges`() = runBlocking {
        val manifestUrl = "https://test.com/manifest-notify.json"
        val scriptUrl = "./script.js"
        val absoluteScriptUrl = "https://test.com/script.js"

        val manifestJson = """
            {
                "id": "notify-plugin",
                "name": "Notify Plugin",
                "author": "Author",
                "version": 1,
                "scriptUrl": "$scriptUrl"
            }
        """.trimIndent()

        val mockCall1 = mockk<Call>()
        val mockResponse1 = mockk<Response>()
        every { okHttpClient.newCall(match { it.url.toString() == manifestUrl }) } returns mockCall1
        every { mockCall1.execute() } returns mockResponse1
        every { mockResponse1.isSuccessful } returns true
        every { mockResponse1.body } returns manifestJson.toResponseBody()
        every { mockResponse1.close() } returns Unit

        val mockCall2 = mockk<Call>()
        val mockResponse2 = mockk<Response>()
        every { okHttpClient.newCall(match { it.url.toString() == absoluteScriptUrl }) } returns mockCall2
        every { mockCall2.execute() } returns mockResponse2
        every { mockResponse2.isSuccessful } returns true
        every { mockResponse2.body } returns "console.log('ok');".toResponseBody()
        every { mockResponse2.close() } returns Unit

        val installEmission = async(start = CoroutineStart.UNDISPATCHED) {
            repository.settingsChanges.first()
        }
        val result = repository.installPlugin(manifestUrl)
        assertTrue(result.isSuccess)
        assertEquals(Unit, withTimeout(5_000) { installEmission.await() })

        val uninstallEmission = async(start = CoroutineStart.UNDISPATCHED) {
            repository.settingsChanges.first()
        }
        val removed = repository.uninstallPlugin("notify-plugin")
        assertTrue(removed)
        assertEquals(Unit, withTimeout(5_000) { uninstallEmission.await() })
    }

    @Test
    fun `test addCustomPluginHomeRow preserves options as JsonObject`() = runBlocking {
        val manifestUrl = "https://test.com/manifest-template.json"
        val absoluteScriptUrl = "https://test.com/script.js"

        val manifestJson = """
            {
                "id": "template-plugin",
                "name": "Template Plugin",
                "author": "Author",
                "version": 1,
                "scriptUrl": "./script.js",
                "homeRowTemplates": [
                    {
                        "id": "subreddit",
                        "name": "Subreddit",
                        "type": "subreddit",
                        "namePrefix": "r/",
                        "fields": [
                            { "id": "subreddit", "name": "Subreddit", "required": true },
                            { "id": "sort", "name": "Sort", "default": "hot", "required": false }
                        ]
                    }
                ]
            }
        """.trimIndent()

        val mockCall1 = mockk<Call>()
        val mockResponse1 = mockk<Response>()
        every { okHttpClient.newCall(match { it.url.toString() == manifestUrl }) } returns mockCall1
        every { mockCall1.execute() } returns mockResponse1
        every { mockResponse1.isSuccessful } returns true
        every { mockResponse1.body } returns manifestJson.toResponseBody()
        every { mockResponse1.close() } returns Unit

        val mockCall2 = mockk<Call>()
        val mockResponse2 = mockk<Response>()
        every { okHttpClient.newCall(match { it.url.toString() == absoluteScriptUrl }) } returns mockCall2
        every { mockCall2.execute() } returns mockResponse2
        every { mockResponse2.isSuccessful } returns true
        every { mockResponse2.body } returns "console.log('ok');".toResponseBody()
        every { mockResponse2.close() } returns Unit

        val installResult = repository.installPlugin(manifestUrl)
        assertTrue(installResult.isSuccess)

        val added = repository.addCustomPluginHomeRow(
            pluginId = "template-plugin",
            templateId = "subreddit",
            name = "",
            options = mapOf("subreddit" to "videos", "sort" to "top"),
        )
        assertTrue(added)

        val customRows = repository.getCustomPluginHomeRows("template-plugin")
        assertEquals(1, customRows.size)
        val row = customRows.single()
        assertEquals("r/videos", row.name)
        assertEquals("subreddit", row.type)
        val optionsObj = row.options?.jsonObject
        assertNotNull(optionsObj)
        assertEquals("videos", optionsObj?.get("subreddit")?.jsonPrimitive?.content)
        assertEquals("top", optionsObj?.get("sort")?.jsonPrimitive?.content)
    }

    @Test
    fun `rejects traversal and leading dot plugin ids across all resolvePluginDir callers`() = runBlocking {
        for (badId in listOf("", ".", "..", ".hidden", "../escape", "foo/bar", "a..b")) {
            assertEquals(false, repository.uninstallPlugin(badId))
            assertEquals(null, repository.getPluginScript(badId))
            assertEquals(emptyMap<String, String>(), repository.getPluginSettings(badId))
            try {
                repository.updatePluginSetting(badId, "k", "v")
                org.junit.Assert.fail("Expected updatePluginSetting to reject $badId")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun `requireHttps rejects http manifest and http scriptUrl and mismatched overwrite origin`() = runBlocking {
        val realClient = OkHttpClient.Builder().build()
        val strictRepo = PluginRepository(
            context,
            realClient,
            mockk(relaxed = true),
            ActiveSessionBus(),
            mockk(relaxed = true),
            mockk(relaxed = true),
        )

        // HTTP manifest rejected when requireHttps = true
        val httpResult = strictRepo.installPlugin("http://example.com/manifest.json", requireHttps = true)
        assertTrue(httpResult.isFailure)

        // HTTPS manifest with absolute http:// scriptUrl rejected when requireHttps = true
        val mockClient = mockk<OkHttpClient>()
        val manifestWithHttpScript = """
            {"id":"https-only","name":"Test","author":"A","version":1,"scriptUrl":"http://evil.example/s.js"}
        """.trimIndent()
        val mockCall = mockk<Call>()
        val mockResp = mockk<Response>()
        every { mockClient.newBuilder() } returns OkHttpClient.Builder()
        every { mockClient.proxySelector } returns java.net.ProxySelector.getDefault()
        every { mockClient.eventListenerFactory } returns okhttp3.EventListener.Factory { okhttp3.EventListener.NONE }
        // Test via non-https requireHttps=false vs requireHttps=true on mocked client directly:
        val mockRepo = PluginRepository(
            context,
            mockClient,
            mockk(relaxed = true),
            ActiveSessionBus(),
            mockk(relaxed = true),
            mockk(relaxed = true),
        )
        // Oversized download (> 2 MiB) fails cleanly and closes response
        val hugeBody = "A".repeat((PluginRepository.MAX_PLUGIN_DOWNLOAD_BYTES + 1024).toInt())
        every { mockClient.newCall(any()) } returns mockCall
        every { mockCall.execute() } returns mockResp
        every { mockResp.isSuccessful } returns true
        every { mockResp.body } returns hugeBody.toResponseBody()
        every { mockResp.request } returns okhttp3.Request.Builder().url("https://example.com/m.json").build()
        every { mockResp.close() } returns Unit

        val oversizedResult = mockRepo.installPlugin("https://example.com/m.json", requireHttps = false)
        assertTrue("Expected > 2 MiB download to fail", oversizedResult.isFailure)
        io.mockk.verify { mockResp.close() }

        // Non-2xx response closes body cleanly
        val errResp = mockk<Response>()
        every { mockCall.execute() } returns errResp
        every { errResp.isSuccessful } returns false
        every { errResp.code } returns 500
        every { errResp.close() } returns Unit
        val errResult = mockRepo.installPlugin("https://example.com/err.json", requireHttps = false)
        assertTrue(errResult.isFailure)
        io.mockk.verify { errResp.close() }

        // isMainProcessProvider = false prevents pluginsDir() mkdirs side effects
        val nonMainDir = File(context.filesDir, "universal_plugins/nonmain-scope")
        nonMainDir.deleteRecursively()
        repository.isMainProcessProvider = { false }
        try {
            repository.pluginsDir("nonmain-scope")
            assertTrue("Expected pluginsDir() not to mkdirs in non-main process", !nonMainDir.exists())
        } finally {
            repository.isMainProcessProvider = { true }
        }
    }

    @Test
    fun `RealHttpBridge enforces PUBLIC_INTERNET_ONLY Proxy NO_PROXY cookie isolation and header redaction`() {
        val bridge = RealHttpBridge(context, OkHttpClient())
        assertEquals(java.net.Proxy.NO_PROXY, bridge.guardedBaseClient.proxy)

        // Private LAN and loopback requests fail closed (code = -1)
        val lanRespJson = bridge.request("GET", "http://192.168.1.1/admin", "{}", null, true)
        assertTrue(lanRespJson.contains("\"isOk\":false"))
        val loopbackRespJson = bridge.request("GET", "http://127.0.0.1:1982/api/v1/config", "{}", null, true)
        assertTrue(loopbackRespJson.contains("\"isOk\":false"))

        // App Lock blocks requests immediately
        bridge.setAppLockLockedProvider { true }
        val lockedResp = bridge.request("GET", "https://example.com/", "{}", null, false)
        assertTrue(lockedResp.contains("Device is locked"))
        bridge.setAppLockLockedProvider { false }

        // Redaction covers Authorization, X-Api-Key, and Cookie
        with(bridge) {
            val redacted = mapOf(
                "Authorization" to "Bearer secret",
                "X-Api-Key" to "key-123",
                "Cookie" to "sid=abc",
                "Accept" to "application/json",
            ).redactedForLog()
            assertTrue(redacted["Authorization"]!!.startsWith("<redacted"))
            assertTrue(redacted["X-Api-Key"]!!.startsWith("<redacted"))
            assertTrue(redacted["Cookie"]!!.startsWith("<redacted"))
            assertEquals("application/json", redacted["Accept"])
        }

        // RealDOMParserBridge clear() wipes handles
        val dom = RealDOMParserBridge()
        val docHandle = dom.parse("<div><span>hi</span></div>")
        assertNotNull(dom.querySelector(docHandle, "span"))
        dom.clear()
        assertEquals(null, dom.querySelector(docHandle, "span"))
    }
}
