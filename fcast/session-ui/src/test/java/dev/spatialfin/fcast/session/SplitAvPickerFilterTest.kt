package dev.spatialfin.fcast.session

import dev.jdtech.jellyfin.fcast.sender.FCastReceiver
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitAvPickerFilterTest {

    private fun fcast(appName: String?, source: FCastReceiver.Source = FCastReceiver.Source.Mdns) =
        FCastReceiver(
            host = "192.168.1.171",
            port = 46900,
            name = "Pixel 11 Pro Fold",
            appName = appName,
            source = source,
        )

    @Test
    fun `allows explicit SpatialFin appName`() {
        assertTrue(SplitAvPolicy.isSplitAvPickerCandidate(fcast("SpatialFin")))
        assertTrue(SplitAvPolicy.isSplitAvPickerCandidate(fcast("spatialfin-tv")))
    }

    @Test
    fun `allows unknown null or blank appName so remembered and manual receivers stay visible`() {
        assertTrue(SplitAvPolicy.isSplitAvPickerCandidate(fcast(null, FCastReceiver.Source.Manual)))
        assertTrue(SplitAvPolicy.isSplitAvPickerCandidate(fcast("", FCastReceiver.Source.Manual)))
        assertTrue(SplitAvPolicy.isSplitAvPickerCandidate(fcast("   ", FCastReceiver.Source.Mdns)))
    }

    @Test
    fun `hides confirmed non-SpatialFin FCast receivers`() {
        assertFalse(SplitAvPolicy.isSplitAvPickerCandidate(fcast("FCast Receiver")))
        assertFalse(SplitAvPolicy.isSplitAvPickerCandidate(fcast("VLC")))
    }
}
