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
    fun `test cold-start scope resolution falls back to database active user when jellyfinApi userId is null`() = runBlocking {
        val mockApi = mockk<dev.jdtech.jellyfin.api.JellyfinApi>(relaxed = true)
        var capturedUserId: java.util.UUID? = null
        every { mockApi.userId } answers { capturedUserId }
        every { mockApi.userId = any() } answers { capturedUserId = firstArg() }

        val realAppPrefs = dev.jdtech.jellyfin.settings.domain.AppPreferences(context.getSharedPreferences("test_prefs", android.content.Context.MODE_PRIVATE))
        val mockDb = mockk<dev.jdtech.jellyfin.database.ServerDatabaseDao>(relaxed = true)

        val serverId = "server-uuid-123"
        val expectedUserId = java.util.UUID.fromString("11111111-2222-3333-4444-555555555555")
        val mockServer = mockk<dev.jdtech.jellyfin.models.Server>(relaxed = true)

        realAppPrefs.setValue(realAppPrefs.currentServer, serverId)
        every { mockServer.currentUserId } returns expectedUserId
        every { mockDb.get(serverId) } returns mockServer

        val repo = PluginRepository(context, okHttpClient, mockApi, ActiveSessionBus(), realAppPrefs, mockDb)
        
        // Save setting for plugin under resolved user scope
        repo.updatePluginHomeRowEnabled("my-plugin", "suggestions", false)

        // Verify setting is preserved and jellyfinApi.userId was populated
        assertEquals(expectedUserId, capturedUserId)
        assertEquals(false, repo.isPluginHomeRowEnabled("my-plugin", "suggestions", true))
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
}
