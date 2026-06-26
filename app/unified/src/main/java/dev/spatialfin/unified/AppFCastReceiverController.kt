package dev.spatialfin.unified

import android.content.Context
import dev.jdtech.jellyfin.presentation.shell.FCastReceiverController
import dev.jdtech.jellyfin.settings.domain.AppPreferences

/**
 * App-side implementation of the [FCastReceiverController] seam, supplied to the
 * form-factor shells via `LocalFCastReceiverController`. Delegates to the
 * `:player:xr`-coupled [dev.spatialfin.fcast.FCastReceiverWiring] (which stays
 * in `:app:unified`) and re-applies both the FCast and SendSpin receiver
 * configs together, matching what the shell "Apply" buttons did before
 * extraction.
 */
object AppFCastReceiverController : FCastReceiverController {
    override fun isReceiverEnabled(prefs: AppPreferences): Boolean =
        dev.spatialfin.fcast.FCastReceiverWiring.isReceiverEnabled(prefs)

    override fun resolveDisplayName(prefs: AppPreferences): String =
        dev.spatialfin.fcast.FCastReceiverWiring.resolveDisplayName(prefs)

    override fun applyReceiverConfig(context: Context, prefs: AppPreferences) {
        dev.spatialfin.fcast.FCastReceiverWiring.applyReceiverConfig(context, prefs)
        dev.spatialfin.sendspin.SendspinReceiverWiring.applyReceiverConfig(context, prefs)
    }
}
