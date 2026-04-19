package dev.jdtech.jellyfin.models.companion

import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * Stable per-device identifier used across all companion traffic (log upload,
 * config sync, per-device preference overrides). Settings.Secure.ANDROID_ID
 * survives app reinstall but resets on factory reset, which is appropriate
 * here — after a factory reset we should be a "new device" to the companion.
 */
object DeviceIdentity {
    fun deviceId(context: Context): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?.takeIf { it.isNotBlank() }
            ?: "spatialfin-${Build.MODEL}-${Build.VERSION.SDK_INT}"
}
