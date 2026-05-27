package dev.jdtech.jellyfin.fcast.receiver

import dev.jdtech.jellyfin.fcast.protocol.MetadataObject
import dev.jdtech.jellyfin.fcast.protocol.PlayMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalStreamTest {

    @Test fun `fromPlayMessage maps every field`() {
        val msg = PlayMessage(
            container = "video/mp4",
            url = "https://e.org/m.mp4",
            time = 30.0,
            volume = 0.5,
            speed = 1.5,
            headers = mapOf("Cookie" to "k=v"),
            metadata = MetadataObject(title = "Movie", thumbnailUrl = "https://e.org/poster.jpg"),
        )
        val req = ExternalStreamRequest.fromPlayMessage(msg)!!
        assertEquals("video/mp4", req.container)
        assertEquals(ExternalStreamSource.Url("https://e.org/m.mp4"), req.source)
        assertEquals(30.0, req.startPositionSeconds, 0.0)
        assertEquals(0.5, req.initialVolume!!, 0.0)
        assertEquals(1.5, req.initialSpeed!!, 0.0)
        assertEquals("k=v", req.headers["Cookie"])
        assertEquals("Movie", req.title)
        assertEquals("https://e.org/poster.jpg", req.thumbnailUrl)
    }

    @Test fun `inline DASH manifests parse successfully`() {
        val msg = PlayMessage(container = "application/dash+xml", content = "<MPD></MPD>")
        val parsed = ExternalStreamRequest.parsePlayMessage(msg) as ExternalStreamRequest.ParseResult.Valid
        assertEquals(ExternalStreamSource.Inline("<MPD></MPD>"), parsed.request.source)
    }

    @Test fun `neither source is rejected with explicit reason`() {
        val parsed = ExternalStreamRequest.parsePlayMessage(PlayMessage(container = "video/mp4"))
        assertTrue(parsed is ExternalStreamRequest.ParseResult.Invalid)
        assertTrue((parsed as ExternalStreamRequest.ParseResult.Invalid).reason.contains("url or content"))
    }

    @Test fun `both sources are rejected with explicit reason`() {
        val parsed = ExternalStreamRequest.parsePlayMessage(
            PlayMessage(container = "video/mp4", url = "u", content = "bytes"),
        )
        assertTrue(parsed is ExternalStreamRequest.ParseResult.Invalid)
        assertTrue((parsed as ExternalStreamRequest.ParseResult.Invalid).reason.contains("exactly one"))
    }

    @Test fun `inline HLS rejects relative segments`() {
        val parsed = ExternalStreamRequest.parsePlayMessage(
            PlayMessage(container = "application/vnd.apple.mpegurl", content = "#EXTM3U\nsegment.ts"),
        )
        assertTrue(parsed is ExternalStreamRequest.ParseResult.Invalid)
        assertTrue((parsed as ExternalStreamRequest.ParseResult.Invalid).reason.contains("absolute"))
    }

    @Test fun `Rejecting is the safe default`() {
        val result = ExternalStreamPlayer.Rejecting.play(
            ExternalStreamRequest(source = ExternalStreamSource.Url("u"), container = "v"),
        )
        assertTrue(result is ExternalStreamPlayer.PlayResult.Rejected)
    }

    @Test fun `ExternalStreamIngressRouter routes Accepted through`() {
        val player = object : ExternalStreamPlayer {
            override fun play(request: ExternalStreamRequest) =
                ExternalStreamPlayer.PlayResult.Accepted
            override fun pause() {}
            override fun resume() {}
            override fun stop() {}
            override fun seek(seconds: Double) {}
            override fun setVolume(volume: Double) {}
            override fun setSpeed(speed: Double) {}
            override fun setTrack(type: Int, trackId: String) {}
        }
        val router = ExternalStreamIngressRouter(player)
        val res = router.onPlay(PlayMessage(container = "video/mp4", url = "u"))
        assertTrue(res is FCastIngressRouter.IngressResult.Accepted)
    }

    @Test fun `ExternalStreamIngressRouter rejects when player rejects`() {
        val player = object : ExternalStreamPlayer {
            override fun play(request: ExternalStreamRequest) =
                ExternalStreamPlayer.PlayResult.Rejected("DRM required")
            override fun pause() {}
            override fun resume() {}
            override fun stop() {}
            override fun seek(seconds: Double) {}
            override fun setVolume(volume: Double) {}
            override fun setSpeed(speed: Double) {}
            override fun setTrack(type: Int, trackId: String) {}
        }
        val router = ExternalStreamIngressRouter(player)
        val res = router.onPlay(PlayMessage(container = "video/mp4", url = "u"))
        assertTrue(res is FCastIngressRouter.IngressResult.Rejected)
        assertEquals("DRM required", (res as FCastIngressRouter.IngressResult.Rejected).reason)
    }

    @Test fun `ExternalStreamIngressRouter accepts valid inline DASH before player decision`() {
        val player = object : ExternalStreamPlayer {
            override fun play(request: ExternalStreamRequest) = ExternalStreamPlayer.PlayResult.Accepted
            override fun pause() {}
            override fun resume() {}
            override fun stop() {}
            override fun seek(seconds: Double) {}
            override fun setVolume(volume: Double) {}
            override fun setSpeed(speed: Double) {}
            override fun setTrack(type: Int, trackId: String) {}
        }
        val router = ExternalStreamIngressRouter(player)
        val res = router.onPlay(PlayMessage(container = "application/dash+xml", content = "<MPD/>"))
        assertTrue(res is FCastIngressRouter.IngressResult.Accepted)
    }

    @Test fun `ExternalStreamIngressRouter rejects PlayMessage with neither source`() {
        val router = ExternalStreamIngressRouter(ExternalStreamPlayer.Rejecting)
        val res = router.onPlay(PlayMessage(container = "video/mp4"))
        assertTrue(res is FCastIngressRouter.IngressResult.Rejected)
    }
}
