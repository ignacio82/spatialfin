package dev.spatialfin.fcast

import dev.jdtech.jellyfin.fcast.protocol.SplitAvMetadata
import dev.jdtech.jellyfin.fcast.protocol.SplitAvRole
import dev.jdtech.jellyfin.fcast.receiver.ExternalStreamRequest
import dev.jdtech.jellyfin.fcast.receiver.ExternalStreamSource
import dev.spatialfin.unified.DeviceClass
import org.junit.Assert.assertEquals
import org.junit.Test

class InboundPlaybackRoutingPolicyTest {
    private val video = ExternalStreamRequest(ExternalStreamSource.Url("https://example/video.mp4"), "video/mp4")

    @Test fun `XR full space enabled routes ordinary video immersive`() {
        assertEquals(
            InboundPlaybackDestination.IMMERSIVE_XR,
            InboundPlaybackRoutingPolicy.select(DeviceClass.XR, true, video),
        )
    }

    @Test fun `disabled preference and non XR route flat`() {
        assertEquals(InboundPlaybackDestination.FLAT, InboundPlaybackRoutingPolicy.select(DeviceClass.XR, false, video))
        assertEquals(InboundPlaybackDestination.FLAT, InboundPlaybackRoutingPolicy.select(DeviceClass.PHONE, true, video))
    }

    @Test fun `split audio is always flat`() {
        val audio = video.copy(splitAv = SplitAvMetadata(SplitAvRole.AUDIO))
        assertEquals(InboundPlaybackDestination.FLAT, InboundPlaybackRoutingPolicy.select(DeviceClass.XR, true, audio))
    }
}
