package dev.jdtech.jellyfin.plugins.engine

import android.content.Context
import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.plugins.bridge.HttpBridge
import dev.jdtech.jellyfin.plugins.bridge.HttpResponse
import dev.jdtech.jellyfin.plugins.bridge.RealDOMParserBridge
import dev.jdtech.jellyfin.plugins.bridge.RealUtilitiesBridge
import dev.jdtech.jellyfin.plugins.model.PluginConfig
import dev.jdtech.jellyfin.plugins.repository.PluginContentRepository
import dev.jdtech.jellyfin.plugins.repository.PluginRepository
import dev.jdtech.jellyfin.session.ActiveSessionBus
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DocsPeerTubeExampleTest {

    private lateinit var context: Context
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
    }

    private fun findRepoRoot(): File {
        var cur: File? = File(System.getProperty("user.dir") ?: ".").canonicalFile
        while (cur != null) {
            if (File(cur, "site/examples/peertube/manifest.json").isFile) {
                return cur
            }
            cur = cur.parentFile
        }
        error("Could not locate repo root containing site/examples/peertube/manifest.json")
    }

    @Test
    fun `published peertube example deserializes and executes in QuickJS`() = runBlocking {
        val repoRoot = findRepoRoot()
        val manifestText = File(repoRoot, "site/examples/peertube/manifest.json").readText()
        val scriptText = File(repoRoot, "site/examples/peertube/script.js").readText()

        val manifest = json.decodeFromString<PluginConfig>(manifestText)
        assertEquals("peertube-example", manifest.id)
        assertTrue(manifest.capabilities.containsAll(listOf("home", "search", "resolve")))
        assertTrue(manifest.settings.any { it.variable == "instanceUrl" })

        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse(
                    code = 200,
                    body = """
                        {
                          "data": [
                            {
                              "shortUUID": "abc123",
                              "name": "SpatialFin Tour",
                              "description": "Home feed sample",
                              "url": "/w/abc123",
                              "duration": 180,
                              "views": 42,
                              "isLive": false,
                              "thumbnailPath": "/static/thumbnails/abc123.jpg",
                              "channel": { "displayName": "SpatialFin" }
                            }
                          ]
                        }
                    """.trimIndent(),
                ),
            )
            server.enqueue(
                MockResponse(
                    code = 200,
                    body = """
                        {
                          "data": [
                            {
                              "shortUUID": "def456",
                              "name": "SpatialFin Search Hit",
                              "description": "Search sample",
                              "url": "/w/def456",
                              "duration": 95,
                              "views": 12,
                              "isLive": false,
                              "channel": { "displayName": "SpatialFin" }
                            }
                          ]
                        }
                    """.trimIndent(),
                ),
            )
            server.enqueue(
                MockResponse(
                    code = 200,
                    body = """
                        {
                          "shortUUID": "abc123",
                          "streamingPlaylists": [
                            { "playlistUrl": "/static/streaming-playlists/hls/abc123/master.m3u8" }
                          ]
                        }
                    """.trimIndent(),
                ),
            )

            val httpClient = OkHttpClient()
            val bridge = object : HttpBridge {
                override fun request(
                    method: String,
                    url: String,
                    headersJson: String,
                    body: String?,
                    usePlatformAuth: Boolean,
                ): String {
                    assertTrue("Only GET or POST allowed, got $method", method in setOf("GET", "POST"))
                    assertTrue("Plugin example must use public HTTPS origin, got $url", url.startsWith("https://peertube.example/"))

                    val pathAndQuery = url.removePrefix("https://peertube.example")
                    val forwardUrl = server.url(pathAndQuery)
                    val req = Request.Builder().url(forwardUrl).get().build()
                    httpClient.newCall(req).execute().use { resp ->
                        val respBody = resp.body.string()
                        return json.encodeToString(
                            HttpResponse(
                                isOk = resp.isSuccessful,
                                code = resp.code,
                                body = respBody,
                                headers = emptyMap(),
                                base64 = false,
                            ),
                        )
                    }
                }
            }

            val engine = PluginEngine(bridge, RealDOMParserBridge(), RealUtilitiesBridge())
            val jellyfinApi = mockk<JellyfinApi>(relaxed = true)
            every { jellyfinApi.userId } returns null
            val appPreferences = mockk<AppPreferences>(relaxed = true)
            val pluginRepository = PluginRepository(
                context = context,
                okHttpClient = httpClient,
                jellyfinApi = jellyfinApi,
                activeSessionBus = ActiveSessionBus(),
                appPreferences = appPreferences,
                database = mockk(relaxed = true),
            )
            val pluginClient = PluginClient(engine, pluginRepository)
            val contentRepository = PluginContentRepository(pluginClient, pluginRepository)

            val pluginDir = File(File(context.filesDir, "universal_plugins"), "default/peertube-example")
            pluginDir.mkdirs()
            File(pluginDir, "manifest.json").writeText(manifestText)
            File(pluginDir, "script.js").writeText(scriptText)

            val homeItems = contentRepository.getHome()
            assertEquals(1, homeItems.size)
            assertEquals("SpatialFin Tour", homeItems[0].title)
            assertEquals("SpatialFin", homeItems[0].author)
            assertEquals("https://peertube.example/w/abc123", homeItems[0].videoUrl)
            assertEquals("https://peertube.example/static/thumbnails/abc123.jpg", homeItems[0].thumbnailUrl)

            val searchItems = contentRepository.searchPlugin("peertube-example", "spatialfin")
            assertEquals(1, searchItems.size)
            assertEquals("SpatialFin Search Hit", searchItems[0].title)

            val inputWatchUrl = "https://peertube.example/w/abc123"
            val resolved = contentRepository.getVideoUrl("peertube-example", inputWatchUrl)
            assertNotNull(resolved)
            assertTrue(resolved!!.url != inputWatchUrl)
            assertEquals("https://peertube.example/static/streaming-playlists/hls/abc123/master.m3u8", resolved.url)
            assertEquals("application/x-mpegURL", resolved.mimeType)
        } finally {
            server.close()
        }
    }
}
