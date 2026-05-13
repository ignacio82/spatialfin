package dev.jdtech.jellyfin.cast.adapter.googlecast

import android.content.Context
import dev.jdtech.jellyfin.cast.CastCapability
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleCastDiscoveryTest {

    // The discovery class only touches Context inside acquireMulticastLock(), which is
    // never invoked from capability parsing. A bare mock is plenty for the parser tests.
    private val discovery = GoogleCastDiscovery(mockk<Context>(relaxed = true))

    @Test
    fun `null capability string falls back to a permissive set`() {
        val caps = discovery.capabilitiesFromCaBitmask(null)
        assertTrue("Volume always granted", CastCapability.Volume in caps)
        assertTrue("Seek always granted", CastCapability.Seek in caps)
        assertTrue("Subtitles always granted", CastCapability.Subtitles in caps)
        assertTrue("Video assumed when ca is missing", CastCapability.Video in caps)
        assertTrue("Audio assumed when ca is missing", CastCapability.Audio in caps)
    }

    @Test
    fun `unparseable capability string falls back to a permissive set`() {
        val caps = discovery.capabilitiesFromCaBitmask("not-a-number")
        assertTrue(CastCapability.Video in caps)
        assertTrue(CastCapability.Audio in caps)
        assertTrue(CastCapability.Volume in caps)
    }

    @Test
    fun `video bit set yields Video capability`() {
        // 0x001 = video output bit only.
        val caps = discovery.capabilitiesFromCaBitmask("1")
        assertTrue(CastCapability.Video in caps)
        assertFalse(CastCapability.Audio in caps)
    }

    @Test
    fun `audio bit set yields Audio capability`() {
        // 0x004 = audio output bit only.
        val caps = discovery.capabilitiesFromCaBitmask("4")
        assertFalse(CastCapability.Video in caps)
        assertTrue(CastCapability.Audio in caps)
    }

    @Test
    fun `combined video and audio bits both grant their capabilities`() {
        // 5 = 0x001 | 0x004 = video + audio
        val caps = discovery.capabilitiesFromCaBitmask("5")
        assertTrue(CastCapability.Video in caps)
        assertTrue(CastCapability.Audio in caps)
    }

    @Test
    fun `receiver with no advertised AV output still gets Audio fallback`() {
        // A weird Cast receiver that advertises only multizone (0x008) and on-screen display
        // (0x020) but not video or audio. We don't want the picker to render it as totally
        // incapable; Cast V2 receivers all play audio so at least claim that.
        val caps = discovery.capabilitiesFromCaBitmask("40") // 0x028
        assertFalse(CastCapability.Video in caps)
        assertTrue(CastCapability.Audio in caps)
    }

    @Test
    fun `NativeAss is never set by Cast discovery`() {
        // Critical invariant: only FCast receivers running SpatialFin carry NativeAss /
        // EmbeddedFonts. The sender-side subtitle policy in PR 2 depends on this being
        // false for Cast so styled ASS routes through the burn-in transcode path.
        for (caBits in listOf("0", "1", "4", "5", "31", "511", null, "garbage")) {
            val caps = discovery.capabilitiesFromCaBitmask(caBits)
            assertFalse(
                "ca=$caBits must not advertise NativeAss",
                CastCapability.NativeAss in caps,
            )
            assertFalse(
                "ca=$caBits must not advertise EmbeddedFonts",
                CastCapability.EmbeddedFonts in caps,
            )
            assertFalse(
                "ca=$caBits must not advertise SplitAv — that's FCast-only",
                CastCapability.SplitAv in caps,
            )
        }
    }
}
