package dev.spatialfin.fcast.session

import org.junit.Assert.assertEquals
import org.junit.Test

class SplitAvAudioRouteInfoTest {

    @Test
    fun `direct route label is explicit`() {
        assertEquals(
            "Direct source audio",
            SplitAvAudioRouteInfo(
                route = SplitAvAudioRouteInfo.Route.Direct,
                sourceAudioCodec = "truehd",
            ).label,
        )
    }

    @Test
    fun `transcode route label includes target codec`() {
        assertEquals(
            "Transcoded to E-AC-3",
            SplitAvAudioRouteInfo(
                route = SplitAvAudioRouteInfo.Route.Transcoded,
                sourceAudioCodec = "truehd",
                targetAudioCodec = "eac3",
            ).label,
        )
    }

    @Test
    fun `quality recast labels show direction`() {
        assertEquals(
            "Upgraded to direct source audio",
            SplitAvAudioRouteInfo(
                route = SplitAvAudioRouteInfo.Route.UpgradedToDirect,
                sourceAudioCodec = "truehd",
            ).label,
        )
        assertEquals(
            "Transcoded to AAC (receiver limit)",
            SplitAvAudioRouteInfo(
                route = SplitAvAudioRouteInfo.Route.DowngradedToTranscode,
                sourceAudioCodec = "truehd",
                targetAudioCodec = "aac",
            ).label,
        )
    }
}
