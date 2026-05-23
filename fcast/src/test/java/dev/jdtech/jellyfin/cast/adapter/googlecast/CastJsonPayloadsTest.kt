package dev.jdtech.jellyfin.cast.adapter.googlecast

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CastJsonPayloadsTest {

    @Test
    fun `connection payload includes cast connection type`() {
        val json = CastJson.encodeToString(
            ConnectionMessage.serializer(),
            ConnectionMessage(type = CastMessages.CONNECT),
        )

        assertTrue(json.contains("\"connType\":0"))
    }

    @Test
    fun `media load does not carry receiver session id`() {
        val json = CastJson.encodeToString(
            LoadRequest.serializer(),
            LoadRequest(
                requestId = 3,
                media = MediaInfo(
                    contentId = "https://example.test/video.m3u8",
                    contentType = "application/vnd.apple.mpegurl",
                ),
            ),
        )

        assertFalse(json.contains("sessionId"))
    }

    @Test
    fun `media status probe is a media get status request`() {
        val json = CastJson.encodeToString(
            MediaGetStatusRequest.serializer(),
            MediaGetStatusRequest(requestId = 9),
        )

        assertTrue(json.contains("\"type\":\"GET_STATUS\""))
        assertTrue(json.contains("\"requestId\":9"))
    }
}
