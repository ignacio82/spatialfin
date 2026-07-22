package dev.spatialfin.unified.receiver

import android.content.Intent
import android.os.Build

object BootStartPolicy {

    /**
     * Determines whether [BootReceiver] is permitted to launch foreground receiver services
     * from a background boot / package replacement broadcast.
     *
     * On Android 14+ (API 34+), starting foreground services of type `mediaPlayback` directly
     * from a `BOOT_COMPLETED` or `MY_PACKAGE_REPLACED` broadcast receiver is prohibited by the OS
     * and throws `ForegroundServiceStartNotAllowedException`. On API 34+, receiver auto-start
     * is deferred until the app is launched in the foreground.
     */
    fun shouldAutoStartReceivers(
        action: String?,
        autoStartPreference: Boolean,
        sdkInt: Int = Build.VERSION.SDK_INT,
    ): Boolean {
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return false
        }
        if (!autoStartPreference) {
            return false
        }
        if (sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return false
        }
        return true
    }
}
