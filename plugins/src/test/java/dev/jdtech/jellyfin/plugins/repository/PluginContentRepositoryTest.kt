package dev.jdtech.jellyfin.plugins.repository

import android.content.Context
import dev.jdtech.jellyfin.plugins.bridge.RealDOMParserBridge
import dev.jdtech.jellyfin.plugins.bridge.RealHttpBridge
import dev.jdtech.jellyfin.plugins.bridge.RealUtilitiesBridge
import dev.jdtech.jellyfin.plugins.engine.PluginClient
import dev.jdtech.jellyfin.plugins.engine.PluginEngine
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PluginContentRepositoryTest {

    private lateinit var contentRepository: PluginContentRepository
    private lateinit var pluginRepository: PluginRepository
    private val okHttpClient = mockk<OkHttpClient>()
    private lateinit var context: Context

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        val httpBridge = RealHttpBridge(okHttpClient)
        val domParserBridge = RealDOMParserBridge()
        val utilitiesBridge = RealUtilitiesBridge()
        val engine = PluginEngine(httpBridge, domParserBridge, utilitiesBridge)
        pluginRepository = PluginRepository(context, okHttpClient)
        val client = PluginClient(engine, pluginRepository)
        contentRepository = PluginContentRepository(client, pluginRepository)
    }

    @Test
    fun `test getHome content from plugin`() = runBlocking {
        val pluginId = "test-plugin"
        val scriptContent = """
            function getHome() {
                return [
                    {
                        "id": "vid1",
                        "pluginId": "$pluginId",
                        "title": "Universal Video",
                        "videoUrl": "https://test.com/video.mp4"
                    }
                ];
            }
        """.trimIndent()
        
        val manifestJson = """
            {
                "id": "$pluginId",
                "name": "Test Plugin",
                "author": "Author",
                "version": 1,
                "scriptUrl": "..."
            }
        """.trimIndent()

        val pluginsDir = File(context.filesDir, "universal_plugins")
        val pluginDir = File(pluginsDir, pluginId)
        pluginDir.mkdirs()
        File(pluginDir, "manifest.json").writeText(manifestJson)
        File(pluginDir, "script.js").writeText(scriptContent)

        val items = contentRepository.getHome()
        
        assertEquals(1, items.size)
        assertEquals("Universal Video", items[0].title)
    }

    @Test
    fun `test resolve video url from installed plugin`() = runBlocking {
        val pluginId = "resolver-plugin"
        val scriptContent = """
            source.resolveVideoUrl = async function(url) {
                return {
                    url: "https://cdn.example.com/video.mp4",
                    mimeType: "video/mp4"
                };
            };
        """.trimIndent()

        val manifestJson = """
            {
                "id": "$pluginId",
                "name": "Resolver Plugin",
                "author": "Author",
                "version": 1,
                "scriptUrl": "..."
            }
        """.trimIndent()

        val pluginsDir = File(context.filesDir, "universal_plugins")
        val pluginDir = File(pluginsDir, pluginId)
        pluginDir.mkdirs()
        File(pluginDir, "manifest.json").writeText(manifestJson)
        File(pluginDir, "script.js").writeText(scriptContent)

        val resolved = contentRepository.getVideoUrl(pluginId, "https://example.com/watch/1")

        assertNotNull(resolved)
        assertEquals("https://cdn.example.com/video.mp4", resolved?.url)
        assertEquals("video/mp4", resolved?.mimeType)
    }
}
