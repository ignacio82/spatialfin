package dev.jdtech.jellyfin.cast.adapter.airplay

import android.content.Context
import dev.jdtech.jellyfin.cast.CastCapability
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AirPlayDiscoveryTest {

    private val discovery = AirPlayDiscovery(mockk<Context>(relaxed = true))

    @Test
    fun `RAOP-only entry yields Audio capability without Video`() {
        val caps = discovery.capabilitiesFromFeatures(featuresHex = null, fromRaop = true)
        assertTrue(CastCapability.Audio in caps)
        assertFalse(CastCapability.Video in caps)
        assertTrue(CastCapability.Volume in caps)
    }

    @Test
    fun `missing features on airplay service yields permissive Video set`() {
        val caps = discovery.capabilitiesFromFeatures(featuresHex = null, fromRaop = false)
        assertTrue(CastCapability.Video in caps)
        assertTrue(CastCapability.Seek in caps)
        assertTrue(CastCapability.Subtitles in caps)
    }

    @Test
    fun `features bit 9 grants Video and Audio`() {
        // 0x200 = bit 9 (video). AirPlay v1 video carries audio in the same stream so we
        // grant Audio automatically when Video is present.
        val caps = discovery.capabilitiesFromFeatures(featuresHex = "0x200", fromRaop = false)
        assertTrue(CastCapability.Video in caps)
        assertTrue(CastCapability.Audio in caps)
    }

    @Test
    fun `features bit 26 without bit 9 reports AirPlay 2-only`() {
        // 0x04000000 = bit 26 alone. AirPlay 2 audio without v1 video = HomePod-style device.
        // Capabilities won't include Video and isV2Only flips true.
        val caps = discovery.capabilitiesFromFeatures(featuresHex = "0x4000000", fromRaop = false)
        assertFalse(CastCapability.Video in caps)
        assertTrue(CastCapability.Audio in caps)
        assertTrue(discovery.isV2Only("0x4000000"))
    }

    @Test
    fun `features carrying both v1 and v2 bits is not flagged v2-only`() {
        // 0x4000200 = bit 9 (v1 video) + bit 26 (AirPlay 2 audio). An Apple TV running tvOS
        // 10+ — still drivable via v1 verbs, so we don't disable it.
        val caps = discovery.capabilitiesFromFeatures(featuresHex = "0x4000200", fromRaop = false)
        assertTrue(CastCapability.Video in caps)
        assertFalse(
            "Device that exposes v1 video must NOT be flagged v2-only",
            discovery.isV2Only("0x4000200"),
        )
    }

    @Test
    fun `comma-separated features halves are merged with OR`() {
        // AirPlay 2 receivers sometimes split the 64-bit features bitmask into two 32-bit
        // hex halves. We OR them together so bit lookups still find the right values.
        val caps = discovery.capabilitiesFromFeatures(
            featuresHex = "0x200,0x4000000",
            fromRaop = false,
        )
        // 0x200 = video, 0x4000000 = AP2 audio
        assertTrue(CastCapability.Video in caps)
        assertTrue(CastCapability.Audio in caps)
    }

    @Test
    fun `garbage features falls back to permissive set`() {
        val caps = discovery.capabilitiesFromFeatures(featuresHex = "not-hex", fromRaop = false)
        // parseFeaturesBitmask returns 0 for garbage; the fallback path grants Video.
        assertTrue(CastCapability.Video in caps)
    }

    @Test
    fun `NativeAss and SplitAv are never granted by AirPlay discovery`() {
        for (features in listOf(null, "", "0x200", "0x4000000", "0xFFFFFFFF", "garbage", "0x200,0x4000000")) {
            for (fromRaop in listOf(true, false)) {
                val caps = discovery.capabilitiesFromFeatures(features, fromRaop)
                assertFalse(
                    "features=$features fromRaop=$fromRaop: NativeAss must be FCast-only",
                    CastCapability.NativeAss in caps,
                )
                assertFalse(
                    "features=$features fromRaop=$fromRaop: SplitAv must be FCast-only",
                    CastCapability.SplitAv in caps,
                )
            }
        }
    }
}
