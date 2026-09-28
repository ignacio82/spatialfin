package dev.spatialfin.companion.wear.transport

import dev.spatialfin.companion.protocol.WearCredentials
import dev.spatialfin.companion.protocol.WearPlayerAction
import dev.spatialfin.companion.protocol.WearProtocolCodec
import dev.spatialfin.companion.protocol.WearProtocolPaths
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WearJellyfinRelayClientTest {
    private val credentials =
        WearCredentials(
            serverUrl = "http://jellyfin.test/base/",
            accessToken = "test-token",
            userId = "test-user",
            deviceId = "phone-device",
            serverId = "server",
        )
    private val store =
        mockk<WearCredentialsStore> {
            every { credentials } returns
                MutableStateFlow(this@WearJellyfinRelayClientTest.credentials)
            every { watchDeviceId } returns "watch-device"
        }
    private val requests = CopyOnWriteArrayList<Request>()
    private var responseBody = "[]"
    private var responseCode = 200
    private val relay =
        WearJellyfinRelayClient(
            store,
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    requests += chain.request()
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(responseCode)
                        .message("OK")
                        .body(responseBody.toResponseBody())
                        .build()
                }
                .build(),
        )

    @Test
    fun `idle phone and non-controllable sessions do not hide the playing headset`() = runTest {
        responseBody =
            """
            [
              {"Id":"phone-session","DeviceId":"phone-device","Client":"SpatialFin","SupportsRemoteControl":true,"NowPlayingItem":null},
              {"Id":"blocked","Client":"SpatialFin","SupportsRemoteControl":false,"NowPlayingItem":{"Name":"Other"}},
              {"Id":"other-app","Client":"Jellyfin Web","SupportsRemoteControl":true,"NowPlayingItem":{"Name":"Unrelated playback"}},
              {"Id":"","Client":"SpatialFin","SupportsRemoteControl":true,"NowPlayingItem":{"Name":"Missing session ID"}},
              $XR_SESSION
            ]
        """
                .trimIndent()

        val sessions = relay.listControllableSessions()
        assertEquals(listOf("xr-session", "phone-session"), sessions.map { it.sessionId })
        val state = relay.nowPlayingFrom(sessions.first())
        assertEquals("Galaxy XR", state.targetDeviceName)
        assertEquals("Movie", state.title)
        assertEquals("movie-id", state.itemId)
        assertEquals(30L, state.positionSeconds)
        assertEquals(600L, state.durationSeconds)
        assertEquals(0.4f, state.volume)
        assertTrue(state.isPlaying)
    }

    @Test
    fun `paused sessions and nullable Jellyfin fields remain discoverable`() = runTest {
        responseBody =
            """
            [{"Id":"paused","Client":"SpatialFin","SupportsRemoteControl":true,
              "NowPlayingItem":{"Name":"Paused movie","RunTimeTicks":null},
              "PlayState":{"IsPaused":true,"PositionTicks":null,"VolumeLevel":null}}]
            """
                .trimIndent()
        val state = relay.nowPlayingFrom(relay.listControllableSessions().single())
        assertEquals("Paused movie", state.title)
        assertFalse(state.isPlaying)
        assertEquals(0L, state.positionSeconds)
    }

    @Test
    fun `watch authenticates with its own device ID and preserves server base path`() = runTest {
        relay.listControllableSessions()
        val request = requests.single()
        assertEquals("/base/Sessions", request.url.encodedPath)
        assertEquals("test-user", request.url.queryParameter("ControllableByUserId"))
        val authorization = request.header("Authorization").orEmpty()
        assertTrue(authorization.contains("DeviceId=\"watch-device\""))
        assertFalse(authorization.contains("phone-device"))
        assertTrue(authorization.contains("Token=\"test-token\""))
    }

    @Test
    fun `play pause is posted to the selected headset session`() = runTest {
        assertTrue(relay.dispatch("xr-session", WearPlayerAction.TogglePlayPause).isSuccess)
        assertEquals("POST", requests.single().method)
        assertEquals(
            "/base/Sessions/xr-session/Playing/PlayPause",
            requests.single().url.encodedPath,
        )
    }

    @Test
    fun `skip sends a supported seek with the requested interval`() = runTest {
        responseBody = "[$XR_SESSION]"
        assertTrue(relay.dispatch("xr-session", WearPlayerAction.SeekForward(15)).isSuccess)
        val seek = requests.last()
        assertEquals("/base/Sessions/xr-session/Playing/Seek", seek.url.encodedPath)
        assertEquals("450000000", seek.url.queryParameter("seekPositionTicks"))
    }

    @Test
    fun `rewind clamps to the beginning and skip clamps to the end`() = runTest {
        responseBody = "[$XR_SESSION]"
        assertTrue(relay.dispatch("xr-session", WearPlayerAction.SeekBackward(60)).isSuccess)
        assertEquals("0", requests.last().url.queryParameter("seekPositionTicks"))
        assertTrue(relay.dispatch("xr-session", WearPlayerAction.SeekForward(900)).isSuccess)
        assertEquals("6000000000", requests.last().url.queryParameter("seekPositionTicks"))
    }

    @Test
    fun `missing target does not receive a guessed seek`() = runTest {
        assertTrue(relay.dispatch("gone-session", WearPlayerAction.SeekForward()).isFailure)
        assertTrue(requests.all { it.method == "GET" })
    }

    @Test
    fun `volume command carries Jellyfin percentage and selected session`() = runTest {
        assertTrue(
            relay.dispatch("xr-session", WearPlayerAction.AdjustVolume(percentage = 0.4f)).isSuccess
        )
        val request = requests.single()
        assertEquals("/base/Sessions/xr-session/Command", request.url.encodedPath)
        val buffer = Buffer()
        request.body!!.writeTo(buffer)
        val body = Json.parseToJsonElement(buffer.readUtf8()).jsonObject
        assertEquals("SetVolume", body["Name"]?.jsonPrimitive?.content)
        assertEquals("40", body["Arguments"]?.jsonObject?.get("Volume")?.jsonPrimitive?.content)
    }

    @Test
    fun `unsupported spatial command fails without issuing HTTP request`() = runTest {
        assertTrue(relay.dispatch("xr-session", WearPlayerAction.ResetScreenPlacement).isFailure)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `Continue Watching posts item and resume position to chosen session`() = runTest {
        assertTrue(
            relay
                .dispatch(
                    "xr-session",
                    WearPlayerAction.PlayMediaItem("movie-id", "Movie", 123_000),
                )
                .isSuccess
        )
        assertEquals("/base/Sessions/xr-session/Command", requests.single().url.encodedPath)
        val body = requestBody()
        assertEquals("PlayMediaSource", body["Name"]!!.jsonPrimitive.content)
        assertEquals("movie-id", body["Arguments"]!!.jsonObject["ItemId"]!!.jsonPrimitive.content)
        assertEquals(
            "1230000000",
            body["Arguments"]!!.jsonObject["StartPositionTicks"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `authenticated extension preserves serialized action and escaped voice text`() = runTest {
        val action = WearPlayerAction.AdjustScale(delta = 0.1f)
        assertTrue(relay.dispatch("xr-session", action, true).isSuccess)
        assertEquals("PlayState", requestBody()["Name"]!!.jsonPrimitive.content)
        val encoded =
            requestBody()["Arguments"]!!
                .jsonObject[WearProtocolPaths.RELAY_ACTION_ARGUMENT]!!
                .jsonPrimitive
                .content
        assertEquals(action, WearProtocolCodec.decodeAction(encoded.encodeToByteArray()))
        requests.clear()
        val transcript = "play \"Arrival\"\nplease"
        assertTrue(relay.dispatchVoice("xr-session", transcript).isSuccess)
        assertEquals(
            transcript,
            requestBody()["Arguments"]!!
                .jsonObject[WearProtocolPaths.RELAY_VOICE_ARGUMENT]!!
                .jsonPrimitive
                .content,
        )
    }

    @Test
    fun `expired auth and denied access are discovery errors instead of no devices`() = runTest {
        for ((status, explanation) in
            listOf(401 to "expired", 403 to "cannot control", 503 to "503")) {
            responseCode = status
            val error = runCatching { relay.listControllableSessions() }.exceptionOrNull()
            assertTrue(error is RelayDiscoveryException)
            assertTrue(error!!.message.orEmpty().contains(explanation))
        }
    }

    @Test
    fun `server metadata retains sparse stream indexes and real chapter times`() = runTest {
        responseBody =
            """{"MediaStreams":[{"Type":"Audio","Index":3,"DisplayTitle":"English"},
          {"Type":"Subtitle","Index":9,"DisplayTitle":"Spanish"}],
          "Chapters":[{"Name":"Opening","StartPositionTicks":250000000}]}"""
        val session =
            RelaySession(
                "xr-session",
                "xr",
                "XR",
                "Movie",
                0,
                600,
                true,
                itemId = "movie-id",
                audioStreamIndex = 3,
            )
        val state = relay.nowPlayingFrom(relay.loadMetadata(session))
        assertEquals(3, state.audioTracks.single().index)
        assertTrue(state.audioTracks.single().isSelected)
        assertEquals(9, state.subtitleTracks.single().index)
        assertEquals(25L, state.chapters.single().startPositionSeconds)
        assertTrue(
            relay.dispatch("xr-session", WearPlayerAction.SelectAudioTrack(index = 3)).isSuccess
        )
        assertEquals("3", requestBody()["Arguments"]!!.jsonObject["Index"]!!.jsonPrimitive.content)
    }

    @Test
    fun `continue list loads resumable movies and episodes`() = runTest {
        responseBody =
            """{"Items":[{"Id":"movie","Name":"Film","Type":"Movie","RunTimeTicks":6000000000,
          "UserData":{"PlaybackPositionTicks":1200000000}},{"Id":"episode","Name":"Episode","Type":"Episode"}]}"""
        val items = relay.continueWatching().items
        assertEquals(listOf("Movie", "Episode"), items.map { it.mediaType })
        assertEquals(120L, items.first().playbackPositionSeconds)
        assertEquals("/base/Users/test-user/Items/Resume", requests.single().url.encodedPath)
    }

    @Test
    fun `extension compatibility fails closed for unknown or older builds`() {
        for (version in listOf(null, "", "unknown", "2.7.61", "2.7")) assertFalse(
            supportsWearExtensions(version)
        )
        for (version in listOf("2.7.62", "2.7.62-debug", "2.8.0", "3.0.0")) assertTrue(
            supportsWearExtensions(version)
        )
    }

    private fun requestBody() =
        Buffer().let { buffer ->
            requests.last().body!!.writeTo(buffer)
            Json.parseToJsonElement(buffer.readUtf8()).jsonObject
        }

    private companion object {
        const val XR_SESSION =
            """
            {"Id":"xr-session","DeviceId":"xr-device","DeviceName":"Galaxy XR",
             "Client":"SpatialFin","SupportsRemoteControl":true,
             "NowPlayingItem":{"Id":"movie-id","Name":"Movie","RunTimeTicks":6000000000},
             "PlayState":{"IsPaused":false,"PositionTicks":300000000,"VolumeLevel":40}}
        """
    }
}
