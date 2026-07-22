package dev.spatialfin.unified.receiver

import android.content.Intent
import android.os.Build
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootStartPolicyTest {

    @Test
    fun `auto-start returns true on pre-Android 14 when preference is enabled and boot completed`() {
        val result = BootStartPolicy.shouldAutoStartReceivers(
            action = Intent.ACTION_BOOT_COMPLETED,
            autoStartPreference = true,
            sdkInt = Build.VERSION_CODES.TIRAMISU,
        )
        assertTrue(result)
    }

    @Test
    fun `auto-start returns true on pre-Android 14 when preference is enabled and package replaced`() {
        val result = BootStartPolicy.shouldAutoStartReceivers(
            action = Intent.ACTION_MY_PACKAGE_REPLACED,
            autoStartPreference = true,
            sdkInt = Build.VERSION_CODES.TIRAMISU,
        )
        assertTrue(result)
    }

    @Test
    fun `auto-start returns false on Android 14 plus even when preference is enabled`() {
        val resultApi34 = BootStartPolicy.shouldAutoStartReceivers(
            action = Intent.ACTION_BOOT_COMPLETED,
            autoStartPreference = true,
            sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
        )
        assertFalse(resultApi34)

        val resultApi35 = BootStartPolicy.shouldAutoStartReceivers(
            action = Intent.ACTION_BOOT_COMPLETED,
            autoStartPreference = true,
            sdkInt = 35,
        )
        assertFalse(resultApi35)
    }

    @Test
    fun `auto-start returns false when preference is disabled regardless of SDK`() {
        val result = BootStartPolicy.shouldAutoStartReceivers(
            action = Intent.ACTION_BOOT_COMPLETED,
            autoStartPreference = false,
            sdkInt = Build.VERSION_CODES.TIRAMISU,
        )
        assertFalse(result)
    }

    @Test
    fun `auto-start returns false for unexpected intent action`() {
        val result = BootStartPolicy.shouldAutoStartReceivers(
            action = Intent.ACTION_MAIN,
            autoStartPreference = true,
            sdkInt = Build.VERSION_CODES.TIRAMISU,
        )
        assertFalse(result)
    }
}
