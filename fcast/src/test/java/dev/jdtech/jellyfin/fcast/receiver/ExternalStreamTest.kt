package dev.jdtech.jellyfin.fcast.receiver

import dev.jdtech.jellyfin.fcast.protocol.MetadataObject
import dev.jdtech.jellyfin.fcast.protocol.PlayMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        assertEquals("https://e.org/m.mp4", req.url)
        assertEquals(30.0, req.startPositionSeconds, 0.0)
        assertEquals(0.5, req.initialVolume!!, 0.0)
        assertEquals("k=v", req.headers["Cookie"])
        assertEquals("Movie", req.title)
        assertEquals("https://e.org/poster.jpg", req.thumbnailUrl)
    }

    @Test fun `fromPlayMessage returns null when url is missing`() {
        val msg = PlayMessage(container = "application/dash+xml", content = "<MPD></MPD>")
        assertNull(ExternalStreamRequest.fromPlayMessage(msg))
    }

    @Test fun `Rejecting is the safe default`() {
        val result = ExternalStreamPlayer.Rejecting.play(
            ExternalStreamRequest(url = "u", container = "v"),
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

    @Test fun `ExternalStreamIngressRouter rejects when PlayMessage has no url`() {
        val router = ExternalStreamIngressRouter(ExternalStreamPlayer.Rejecting)
        val res = router.onPlay(PlayMessage(container = "application/dash+xml", content = "<MPD/>"))
        assertTrue(res is FCastIngressRouter.IngressResult.Rejected)
    }
}
