package dev.jdtech.jellyfin.player.local.presentation

import android.net.Uri
import android.util.Base64
import androidx.lifecycle.SavedStateHandle
import dev.jdtech.jellyfin.network.SafeUrlBlockedException
import dev.jdtech.jellyfin.network.UrlTargetPolicy
import dev.jdtech.jellyfin.network.withSsrfGuard
import dev.jdtech.jellyfin.player.core.domain.models.PlayerContentSource
import dev.jdtech.jellyfin.player.core.domain.models.PlayerItem
import dev.jdtech.jellyfin.player.local.domain.PlaylistManager
import dev.jdtech.jellyfin.player.local.subtitles.SubtitleCacheManager
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.repository.LocalMediaRepository
import dev.jdtech.jellyfin.repository.NetworkMediaRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import io.mockk.every
import io.mockk.mockk
import java.util.UUID
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UniversalMediaSourceTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun createViewModel(): PlayerViewModel {
        val app = RuntimeEnvironment.getApplication()
        val prefs = AppPreferences(app.getSharedPreferences("universal_media_source_test", android.content.Context.MODE_PRIVATE))
        return PlayerViewModel(
            application = app,
            playlistManager = mockk<PlaylistManager>(relaxed = true),
            repository = mockk<JellyfinRepository>(relaxed = true),
            localMediaRepository = mockk<LocalMediaRepository>(relaxed = true),
            networkMediaRepository = mockk<NetworkMediaRepository>(relaxed = true),
            appPreferences = prefs,
            savedStateHandle = SavedStateHandle(),
        )
    }

    @Test
    fun buildUniversalMediaSourceBlocksLocalSchemesLoopbackAndDashFileBaseUrl() {
        val vm = createViewModel()

        // Local schemes and loopback blocked
        for (badUri in listOf(
            "file:///data/data/dev.spatialfin/databases/servers.db",
            "content://media/external/video/1",
            "http://127.0.0.1:1982/api/v1/config",
            "http://169.254.169.254/latest/meta-data/",
        )) {
            val item = PlayerItem(
                name = "Bad",
                itemId = UUID.randomUUID(),
                mediaSourceUri = badUri,
                mediaSourceId = "src",
                contentSource = PlayerContentSource.UNIVERSAL,
                playbackPosition = 0L,
            )
            try {
                vm.buildUniversalMediaSource(item)
                fail("Expected SafeUrlBlockedException for $badUri")
            } catch (_: SafeUrlBlockedException) {
            }
        }

        // Inline DASH manifest containing <BaseURL>file:///etc/passwd</BaseURL> is blocked
        val maliciousMpd = """
            <?xml version="1.0" encoding="UTF-8"?>
            <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" mediaPresentationDuration="PT10S" minBufferTime="PT1S">
              <Period>
                <AdaptationSet mimeType="video/mp4">
                  <Representation id="1" bandwidth="1000000">
                    <BaseURL>file:///etc/passwd</BaseURL>
                  </Representation>
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent()
        val maliciousDataUri =
            "data:application/dash+xml;base64," + Base64.encodeToString(maliciousMpd.toByteArray(), Base64.NO_WRAP)
        val dashBadItem = PlayerItem(
            name = "EvilDash",
            itemId = UUID.randomUUID(),
            mediaSourceUri = maliciousDataUri,
            mediaSourceId = "src",
            contentSource = PlayerContentSource.UNIVERSAL,
            playbackPosition = 0L,
            mimeType = "application/dash+xml",
        )
        try {
            vm.buildUniversalMediaSource(dashBadItem)
            fail("Expected DASH <BaseURL>file:///etc/passwd</BaseURL> to be blocked")
        } catch (_: SafeUrlBlockedException) {
        }

        // Valid inline DASH manifest with HTTPS BaseURL parses in memory cleanly
        val safeMpd = maliciousMpd.replace("file:///etc/passwd", "https://cdn.example.com/video.mp4")
        val safeDataUri =
            "data:application/dash+xml;base64," + Base64.encodeToString(safeMpd.toByteArray(), Base64.NO_WRAP)
        val dashSafeItem = dashBadItem.copy(mediaSourceUri = safeDataUri)
        val source = vm.buildUniversalMediaSource(dashSafeItem)
        assertNotNull(source)

        // Separate videoUrl/audioUrl blocks loopback on audioUrl
        val mergeBadItem = PlayerItem(
            name = "MergeBad",
            itemId = UUID.randomUUID(),
            mediaSourceUri = "https://cdn.example.com/v.mp4",
            mediaSourceId = "src",
            contentSource = PlayerContentSource.UNIVERSAL,
            playbackPosition = 0L,
            videoUrl = "https://cdn.example.com/v.mp4",
            audioUrl = "http://127.0.0.1:8096/a.m4a",
        )
        try {
            vm.buildUniversalMediaSource(mergeBadItem)
            fail("Expected loopback audioUrl in MergingMediaSource to be blocked")
        } catch (_: SafeUrlBlockedException) {
        }
    }

    @Test
    fun subtitleCacheManagerPinsEmbyTokenToJellyfinOriginBlocksCrossOriginRedirectsAndCapsSize() {
        val app = RuntimeEnvironment.getApplication()
        val testClient = OkHttpClient.Builder()
            .withSsrfGuard(UrlTargetPolicy.PUBLIC_OR_LAN, allowLoopbackForTests = true)
            .build()
        val cache = SubtitleCacheManager(app, testClient)

        val baseUrl = server.url("/jellyfin").toString()
        val matchingSubUrl = server.url("/jellyfin/Videos/1/Subtitles/0/Stream.srt").toString()
        val nonMatchingPathUrl = server.url("/jellyfin2/Subtitles/0/Stream.srt").toString()

        // 1. Matching origin + basePath attaches X-Emby-Token
        server.enqueue(MockResponse(code = 200, body = "1\n00:00:01,000 --> 00:00:02,000\nHello\n"))
        val text = cache.fetchText(matchingSubUrl, accessToken = "secret-jf-token", allowedBaseUrl = baseUrl)
        assertNotNull(text)
        val req1 = server.takeRequest()
        assertEquals("secret-jf-token", req1.headers["X-Emby-Token"])

        // 2. Non-matching path segment boundary does NOT attach X-Emby-Token
        server.enqueue(MockResponse(code = 200, body = "1\n00:00:01,000 --> 00:00:02,000\nWorld\n"))
        cache.fetchText(nonMatchingPathUrl, accessToken = "secret-jf-token", allowedBaseUrl = baseUrl)
        val req2 = server.takeRequest()
        assertNull(req2.headers["X-Emby-Token"])

        // 3. Cross-origin redirect is blocked (allowCrossOriginRedirects = false)
        val server2 = MockWebServer()
        server2.start()
        try {
            server.enqueue(
                MockResponse(
                    code = 302,
                    headers = okhttp3.Headers.headersOf("Location", server2.url("/evil.srt").toString()),
                ),
            )
            server2.enqueue(MockResponse(code = 200, body = "evil"))
            val redirected = cache.fetchText(matchingSubUrl, accessToken = "secret-jf-token", allowedBaseUrl = baseUrl)
            assertNull(redirected)
            assertEquals(0, server2.requestCount)
        } finally {
            server2.close()
        }
    }
}
