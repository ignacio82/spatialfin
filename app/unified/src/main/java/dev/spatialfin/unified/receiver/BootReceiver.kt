package dev.spatialfin.unified.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.jdtech.jellyfin.fcast.receiver.FCastReceiverService
import dev.jdtech.jellyfin.sendspin.receiver.SendspinReceiverService
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject
    lateinit var appPreferences: AppPreferences

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        Timber.d("BootReceiver received action: \$action")

        val autoStart = appPreferences.getValue(appPreferences.castAutoStart)
        if (!autoStart) {
            Timber.d("BootReceiver: auto-start is disabled in settings.")
            return
        }

        Timber.i("BootReceiver: Auto-starting cast receivers...")

        try {
            val fcastIntent = Intent(context, FCastReceiverService::class.java)
            ContextCompat.startForegroundService(context, fcastIntent)
            
            val sendspinIntent = Intent(context, SendspinReceiverService::class.java)
            ContextCompat.startForegroundService(context, sendspinIntent)
        } catch (e: Exception) {
            Timber.e(e, "BootReceiver: Failed to start receiver services")
        }
    }
}
