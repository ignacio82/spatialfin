package dev.spatialfin.unified

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM unit test — validates the capability facade so callers don't have to test their own
 * `when (deviceClass)` branches. If a new capability is added, the expected values for each form
 * factor should land here too.
 */
class DeviceClassCapabilitiesTest {
    @Test fun `XR has voice, persisted panel pose, image crossfades, and eager LLM init`() {
        val caps = DeviceClassCapabilities(DeviceClass.XR)
        assertTrue(caps.isXr)
        assertFalse(caps.isTv)
        assertFalse(caps.isPhone)
        assertTrue(caps.hasVoiceUi)
        assertFalse(caps.hasLeanback)
        assertTrue(caps.hasPersistedPanelPose)
        assertTrue(caps.useImageCrossfades)
        assertTrue(caps.eagerInitLlm)
        assertFalse(caps.usesBeamCompanion)
    }

    @Test fun `TV has Leanback, no voice UI, no crossfades, no eager LLM`() {
        val caps = DeviceClassCapabilities(DeviceClass.TV)
        assertTrue(caps.isTv)
        assertTrue(caps.hasLeanback)
        assertFalse(caps.hasVoiceUi)
        assertFalse(caps.useImageCrossfades)
        assertFalse(caps.eagerInitLlm)
        assertFalse(caps.hasPersistedPanelPose)
        assertFalse(caps.usesBeamCompanion)
    }

    @Test fun `Phone uses the Beam companion pipeline`() {
        val caps = DeviceClassCapabilities(DeviceClass.PHONE)
        assertTrue(caps.isPhone)
        assertTrue(caps.hasVoiceUi)
        assertTrue(caps.useImageCrossfades)
        assertTrue(caps.eagerInitLlm)
        assertTrue(caps.usesBeamCompanion)
        assertFalse(caps.hasLeanback)
        assertFalse(caps.hasPersistedPanelPose)
    }
}
