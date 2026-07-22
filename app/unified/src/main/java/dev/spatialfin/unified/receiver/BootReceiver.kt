package dev.spatialfin.unified.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
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
        val autoStartPreference = appPreferences.getValue(appPreferences.castAutoStart)
        if (!BootStartPolicy.shouldAutoStartReceivers(intent.action, autoStartPreference)) {
            Timber.d("BootReceiver: Skipping auto-start (preference disabled, wrong intent action, or Android 14+ background FGS restriction).")
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
