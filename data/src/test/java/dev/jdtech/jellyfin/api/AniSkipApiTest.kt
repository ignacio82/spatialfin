package dev.jdtech.jellyfin.api

import dev.jdtech.jellyfin.models.SpatialFinSegmentType
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AniSkipApiTest {

    @Test
    fun `parses opening and ending intervals from valid aniskip response`() = runBlocking {
        val mockJson = """
            {
              "found": true,
              "results": [
                {
                  "interval": {
                    "startTime": 5.2,
                    "endTime": 95.5
                  },
                  "skipType": "op",
                  "skipId": "test-op-1",
                  "episodeLength": 1420.0
                },
                {
                  "interval": {
                    "startTime": 1320.5,
                    "endTime": 1410.0
                  },
                  "skipType": "ed",
                  "skipId": "test-ed-1",
                  "episodeLength": 1420.0
                }
              ]
            }
        """.trimIndent()

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(mockJson.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val api = AniSkipApi(client)
        val segments = api.getSkipTimes(malId = 21L, episodeNumber = 1)

        assertEquals(2, segments.size)

        val intro = segments.first { it.type == SpatialFinSegmentType.INTRO }
        assertEquals(5200L, intro.startTicks)
        assertEquals(95500L, intro.endTicks)

        val outro = segments.first { it.type == SpatialFinSegmentType.OUTRO }
        assertEquals(1320500L, outro.startTicks)
        assertEquals(1410000L, outro.endTicks)
    }

    @Test
    fun `handles 404 not found gracefully`() = runBlocking {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(404)
                    .message("Not Found")
                    .body("{\"message\":\"Not found\"}".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val api = AniSkipApi(client)
        val segments = api.getSkipTimes(malId = 99999L, episodeNumber = 1)

        assertTrue(segments.isEmpty())
    }

    @Test
    fun `handles found false gracefully`() = runBlocking {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("{\"found\":false,\"results\":[]}".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val api = AniSkipApi(client)
        val segments = api.getSkipTimes(malId = 21L, episodeNumber = 999)

        assertTrue(segments.isEmpty())
    }
}
