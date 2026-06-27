package dev.spatialfin.fcast.session

import dev.spatialfin.fcast.session.SplitAvAudioRoutePolicy.FallbackMode
import dev.spatialfin.fcast.session.SplitAvAudioRoutePolicy.RecastAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitAvAudioRoutePolicyTest {

    @Test
    fun `auto direct streams passthrough codec only when receiver advertises it`() {
        assertFalse(SplitAvAudioRoutePolicy.canDirect("truehd", null, FallbackMode.Auto))
        assertFalse(SplitAvAudioRoutePolicy.canDirect("truehd", listOf("eac3"), FallbackMode.Auto))
        assertTrue(SplitAvAudioRoutePolicy.canDirect("truehd", listOf("truehd"), FallbackMode.Auto))
    }

    @Test
    fun `auto treats eac3-joc as direct-capable on eac3 chain`() {
        assertTrue(SplitAvAudioRoutePolicy.canDirect("eac3-joc", listOf("eac3"), FallbackMode.Auto))
    }

    @Test
    fun `settings overrides bypass capability decision`() {
        assertTrue(SplitAvAudioRoutePolicy.canDirect("truehd", null, FallbackMode.Passthrough))
        assertFalse(SplitAvAudioRoutePolicy.canDirect("aac", listOf("pcm"), FallbackMode.TranscodeAac))
    }

    @Test
    fun `resolved capabilities downgrade unsupported direct stream`() {
        assertEquals(
            RecastAction.DowngradeToTranscode,
            SplitAvAudioRoutePolicy.recastForResolvedCapabilities(
                wasDirectStream = true,
                sourceAudioCodec = "truehd",
                receiverAudioCodecs = listOf("eac3"),
                fallbackMode = FallbackMode.Auto,
            ),
        )
    }

    @Test
    fun `resolved capabilities upgrade conservative transcode to direct stream`() {
        assertEquals(
            RecastAction.UpgradeToDirect,
            SplitAvAudioRoutePolicy.recastForResolvedCapabilities(
                wasDirectStream = false,
                sourceAudioCodec = "truehd",
                receiverAudioCodecs = listOf("truehd"),
                fallbackMode = FallbackMode.Auto,
            ),
        )
    }

    @Test
    fun `forced transcode is not upgraded by capability beacons`() {
        assertEquals(
            RecastAction.None,
            SplitAvAudioRoutePolicy.recastForResolvedCapabilities(
                wasDirectStream = false,
                sourceAudioCodec = "truehd",
                receiverAudioCodecs = listOf("truehd"),
                fallbackMode = FallbackMode.TranscodeAac,
            ),
        )
    }

    @Test
    fun `transcode target prefers best receiver-supported codec before aac floor`() {
        assertEquals(
            listOf("eac3", "ac3", "aac"),
            SplitAvAudioRoutePolicy.preferredTranscodeCodecs(listOf("ac3", "eac3")),
        )
        assertEquals(listOf("aac"), SplitAvAudioRoutePolicy.preferredTranscodeCodecs(null))
    }
}
