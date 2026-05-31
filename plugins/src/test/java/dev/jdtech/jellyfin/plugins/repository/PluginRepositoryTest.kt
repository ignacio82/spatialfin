package dev.jdtech.jellyfin.plugins.repository

import android.content.Context
import dev.jdtech.jellyfin.plugins.bridge.RealDOMParserBridge
import dev.jdtech.jellyfin.plugins.bridge.RealHttpBridge
import dev.jdtech.jellyfin.plugins.bridge.RealUtilitiesBridge
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
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
        repository = PluginRepository(context, okHttpClient)
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
}
