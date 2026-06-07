package dev.spatialfin.sendspin

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import dev.jdtech.jellyfin.sendspin.receiver.SendspinReceiverService
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.spatialfin.BuildConfig
import dev.spatialfin.fcast.FCastReceiverWiring
import java.util.UUID
import timber.log.Timber

/**
 * Glue between the `:sendspin` receiver service and the rest of the app.
 */
object SendspinReceiverWiring {

    fun installOnAppStart(
        context: Context,
        prefs: AppPreferences,
    ) {
        if (FCastReceiverWiring.isReceiverEnabled(prefs)) {
            val started = tryStartReceiver(context.applicationContext, prefs)
            if (!started) {
                installForegroundRetry(context.applicationContext, prefs)
            }
        }
    }

    fun applyReceiverConfig(context: Context, prefs: AppPreferences) {
        SendspinReceiverService.stop(context.applicationContext)
        if (FCastReceiverWiring.isReceiverEnabled(prefs)) {
            tryStartReceiver(context.applicationContext, prefs)
        }
    }

    fun stop(context: Context) {
        SendspinReceiverService.stop(context.applicationContext)
        Timber.i("Sendspin receiver service stopped")
    }

    private val receiverPort: Int
        get() = if (BuildConfig.DEBUG) SendspinReceiverService.DEFAULT_PORT + 1 else SendspinReceiverService.DEFAULT_PORT

    private fun tryStartReceiver(context: Context, prefs: AppPreferences): Boolean =
        try {
            SendspinReceiverService.start(
                context = context,
                displayName = FCastReceiverWiring.resolveDisplayName(prefs),
                clientId = resolveClientId(prefs),
                port = receiverPort,
                softwareVersion = BuildConfig.VERSION_NAME,
            )
            Timber.i("Sendspin receiver service started")
            true
        } catch (e: Exception) {
            Timber.w(e, "Sendspin receiver start deferred (will retry on next Activity resume)")
            false
        }

    private fun installForegroundRetry(context: Context, prefs: AppPreferences) {
        val app = context.applicationContext as? Application ?: return
        app.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) {
                    if (tryStartReceiver(app, prefs)) {
                        app.unregisterActivityLifecycleCallbacks(this)
                    }
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            },
        )
    }

    private fun resolveClientId(prefs: AppPreferences): String {
        prefs.getValue(prefs.sendspinClientId)?.trim()?.takeIf { it.isNotEmpty() }?.let {
            return it
        }
        val generated = UUID.randomUUID().toString()
        prefs.setValue(prefs.sendspinClientId, generated)
        return generated
    }
}
