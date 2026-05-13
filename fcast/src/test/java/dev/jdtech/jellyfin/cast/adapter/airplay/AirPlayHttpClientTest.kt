package dev.jdtech.jellyfin.cast.adapter.airplay

import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AirPlayHttpClientTest {

    private val client = AirPlayHttpClient(host = "10.0.0.42", port = 7000)

    private fun bodyAsString(request: okhttp3.Request): String {
        val buf = Buffer()
        request.body!!.writeTo(buf)
        return buf.readUtf8()
    }

    @Test
    fun `play request uses text-parameters body with content-location and start-position`() {
        val req = client.buildPlayRequest("http://srv/movie.mp4?api_key=abc", 0.25)
        assertEquals("POST", req.method)
        assertEquals("http://10.0.0.42:7000/play", req.url.toString())
        val contentType = req.body!!.contentType().toString()
        assertTrue("content-type was $contentType", contentType.startsWith("text/parameters"))
        val body = bodyAsString(req)
        assertTrue("body must carry Content-Location", body.contains("Content-Location: http://srv/movie.mp4?api_key=abc"))
        assertTrue("body must carry Start-Position", body.contains("Start-Position: 0.25"))
    }

    @Test
    fun `play request clamps start-position to 0 1 range`() {
        val low = bodyAsString(client.buildPlayRequest("http://x", -2.5))
        val high = bodyAsString(client.buildPlayRequest("http://x", 99.0))
        assertTrue(low.contains("Start-Position: 0.0"))
        assertTrue(high.contains("Start-Position: 1.0"))
    }

    @Test
    fun `rate request encodes resume as 1 and pause as 0`() {
        assertTrue(client.buildRateRequest(1.0).url.toString().endsWith("/rate?value=1.000000"))
        assertTrue(client.buildRateRequest(0.0).url.toString().endsWith("/rate?value=0.000000"))
        assertTrue(client.buildRateRequest(-0.5).url.toString().endsWith("/rate?value=0.000000"))
    }

    @Test
    fun `scrub request encodes seconds with six fractional digits`() {
        val url = client.buildScrubRequest(123.456).url.toString()
        assertTrue("got $url", url.endsWith("/scrub?position=123.456000"))
    }

    @Test
    fun `scrub clamps negative positions to zero`() {
        val url = client.buildScrubRequest(-10.0).url.toString()
        assertTrue(url.endsWith("/scrub?position=0.000000"))
    }

    @Test
    fun `playback-info is a plain GET`() {
        val req = client.buildPlaybackInfoRequest()
        assertEquals("GET", req.method)
        assertEquals("http://10.0.0.42:7000/playback-info", req.url.toString())
    }

    @Test
    fun `stop is POST with empty body`() {
        val req = client.buildStopRequest()
        assertEquals("POST", req.method)
        assertEquals("http://10.0.0.42:7000/stop", req.url.toString())
        assertEquals("", bodyAsString(req))
    }

    @Test
    fun `linear-to-dB conversion maps 0 to absolute mute and 1 to zero dB`() {
        assertEquals(-144f, client.dbFromLinear(0f), 0f)
        assertEquals(0f, client.dbFromLinear(1f), 0.0001f)
        assertEquals(-15f, client.dbFromLinear(0.5f), 0.0001f)
        // Clamp: values above 1 collapse to 0 dB rather than overflowing.
        assertEquals(0f, client.dbFromLinear(2.0f), 0.0001f)
    }

    @Test
    fun `volume request URL carries the dB value`() {
        val url = client.buildVolumeRequest(0.5f).url.toString()
        assertTrue("got $url", url.contains("/volume?volume=-15.0"))
    }

    @Test
    fun `common headers include User-Agent, session, and monotonic CSeq`() {
        val req1 = client.buildPlaybackInfoRequest()
        val req2 = client.buildPlaybackInfoRequest()
        assertEquals("MediaControl/1.0", req1.header("User-Agent"))
        assertEquals(client.sessionId, req1.header("X-Apple-Session-ID"))
        // CSeq is per-call and monotonic so the receiver can correlate
        val seq1 = req1.header("CSeq")!!.toInt()
        val seq2 = req2.header("CSeq")!!.toInt()
        assertTrue("CSeq must increase: $seq1 → $seq2", seq2 > seq1)
    }
}
