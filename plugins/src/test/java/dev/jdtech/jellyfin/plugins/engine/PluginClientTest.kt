package dev.jdtech.jellyfin.plugins.engine

import android.content.Context
import dev.jdtech.jellyfin.plugins.bridge.RealDOMParserBridge
import dev.jdtech.jellyfin.plugins.bridge.RealHttpBridge
import dev.jdtech.jellyfin.plugins.bridge.RealUtilitiesBridge
import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.plugins.repository.PluginRepository
import dev.jdtech.jellyfin.session.ActiveSessionBus
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
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
        val httpBridge = RealHttpBridge(context, okHttpClient)
        val domParserBridge = RealDOMParserBridge()
        val utilitiesBridge = RealUtilitiesBridge()
        val engine = PluginEngine(httpBridge, domParserBridge, utilitiesBridge)
        val jellyfinApi = mockk<JellyfinApi>(relaxed = true)
        every { jellyfinApi.userId } returns null
        repository = PluginRepository(context, okHttpClient, jellyfinApi, ActiveSessionBus(), mockk(relaxed = true), mockk(relaxed = true))
        client = PluginClient(engine, repository)
    }

    @Test
    fun `test runPlugin`() = runBlocking {
        val pluginId = "test-plugin"
        val scriptContent = "globalThis.executed = true; source.enable = function() {};"
        
        // Mock plugin installation manually. Plugins are scoped per Jellyfin user
        // under universal_plugins/<userId-or-default>/; the relaxed JellyfinApi
        // mock has a null userId, so this resolves to the "default" scope.
        val pluginsDir = File(File(context.filesDir, "universal_plugins"), "default")
        val pluginDir = File(pluginsDir, pluginId)
        pluginDir.mkdirs()
        File(pluginDir, "script.js").writeText(scriptContent)

        val result = client.runPlugin(pluginId)
        
        assert(result.isSuccess)
        val runtime = result.getOrNull()!!
        val executed = runtime.evaluate("globalThis.executed")?.toString()?.toBoolean() ?: false
        assertEquals(true, executed)
        
        runtime.close()
    }

    @Test
    fun `runPlugin closes runtime when script throws exception`() = runBlocking {
        val pluginId = "broken-plugin"
        val pluginsDir = File(File(context.filesDir, "universal_plugins"), "default")
        val pluginDir = File(pluginsDir, pluginId)
        pluginDir.mkdirs()
        File(pluginDir, "script.js").writeText("throw new Error('boom');")

        val mockRuntime = mockk<PluginRuntime>(relaxed = true) {
            coEvery { evaluate(any()) } throws RuntimeException("boom")
        }
        val mockEngine = mockk<PluginEngine> {
            coEvery { createRuntime(any(), any()) } returns mockRuntime
        }

        val brokenClient = PluginClient(mockEngine, repository)
        val result = brokenClient.runPlugin(pluginId)

        assert(result.isFailure)
        verify(exactly = 1) { mockRuntime.close() }
    }

    @Test
    fun `runPlugin closes runtime and rethrows CancellationException`() = runBlocking {
        val pluginId = "cancelled-plugin"
        val pluginsDir = File(File(context.filesDir, "universal_plugins"), "default")
        val pluginDir = File(pluginsDir, pluginId)
        pluginDir.mkdirs()
        File(pluginDir, "script.js").writeText("globalThis.ok = true;")

        val mockRuntime = mockk<PluginRuntime>(relaxed = true) {
            coEvery { evaluate(any()) } throws CancellationException("cancelled")
        }
        val mockEngine = mockk<PluginEngine> {
            coEvery { createRuntime(any(), any()) } returns mockRuntime
        }

        val cancelClient = PluginClient(mockEngine, repository)
        var rethrown = false
        try {
            cancelClient.runPlugin(pluginId)
        } catch (_: CancellationException) {
            rethrown = true
        }
        assertEquals(true, rethrown)
        verify(exactly = 1) { mockRuntime.close() }
    }
}
