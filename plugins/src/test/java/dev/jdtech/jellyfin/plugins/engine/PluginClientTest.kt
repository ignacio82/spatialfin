package dev.jdtech.jellyfin.plugins.engine

import android.content.Context
import dev.jdtech.jellyfin.plugins.bridge.RealDOMParserBridge
import dev.jdtech.jellyfin.plugins.bridge.RealHttpBridge
import dev.jdtech.jellyfin.plugins.bridge.RealUtilitiesBridge
import dev.jdtech.jellyfin.plugins.repository.PluginRepository
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
class PluginClientTest {

    private lateinit var client: PluginClient
    private lateinit var repository: PluginRepository
    private val okHttpClient = mockk<OkHttpClient>()
    private lateinit var context: Context

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        val httpBridge = RealHttpBridge(okHttpClient)
        val domParserBridge = RealDOMParserBridge()
        val utilitiesBridge = RealUtilitiesBridge()
        val engine = PluginEngine(httpBridge, domParserBridge, utilitiesBridge)
        repository = PluginRepository(context, okHttpClient)
        client = PluginClient(engine, repository)
    }

    @Test
    fun `test runPlugin`() = runBlocking {
        val pluginId = "test-plugin"
        val scriptContent = "globalThis.executed = true;"
        
        // Mock plugin installation manually
        val pluginsDir = File(context.filesDir, "universal_plugins")
        val pluginDir = File(pluginsDir, pluginId)
        pluginDir.mkdirs()
        File(pluginDir, "script.js").writeText(scriptContent)

        val result = client.runPlugin(pluginId)
        
        assert(result.isSuccess)
        val runtime = result.getOrNull()!!
        val executed = runtime.evaluate("globalThis.executed") as Boolean
        assertEquals(true, executed)
        
        runtime.close()
    }
}
