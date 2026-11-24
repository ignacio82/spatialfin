package dev.spatialfin.unified.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.jdtech.jellyfin.fcast.receiver.FCastReceiverService
import dev.jdtech.jellyfin.sendspin.receiver.SendspinReceiverService
import dev.jdtech.jellyfin.sendspin.receiver.SendspinReceiverSession
import dev.jdtech.jellyfin.sendspin.receiver.SendspinUserScope
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.spatialfin.unified.applock.AppLockManager
import dev.spatialfin.unified.applock.AppLockObserver
import javax.inject.Inject
import timber.log.Timber

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject
    lateinit var appPreferences: AppPreferences

    @Inject
    lateinit var appLockObserver: AppLockObserver

    override fun onReceive(context: Context, intent: Intent) {
        val autoStartPreference = appPreferences.getValue(appPreferences.castAutoStart)
        if (!BootStartPolicy.shouldAutoStartReceivers(intent.action, autoStartPreference)) {
            Timber.d("BootReceiver: Skipping auto-start (preference disabled, wrong intent action, or Android 14+ background FGS restriction).")
            return
        }

        if (appLockObserver.lockState.value == AppLockManager.LockState.LOCKED) {
            Timber.i("BootReceiver: App Lock is LOCKED; skipping FCast and Sendspin auto-start.")
            return
        }

        Timber.i("BootReceiver: Auto-starting cast receivers...")

        try {
            val fcastIntent = Intent(context, FCastReceiverService::class.java)
            ContextCompat.startForegroundService(context, fcastIntent)

            if (SendspinReceiverSession.state.value.activeUserScope !is SendspinUserScope.Disabled) {
                val sendspinIntent = Intent(context, SendspinReceiverService::class.java)
                ContextCompat.startForegroundService(context, sendspinIntent)
            } else {
                Timber.d("BootReceiver: Sendspin user scope is Disabled; deferring startup to SessionActivationCoordinator.")
            }
        } catch (e: Exception) {
            Timber.e(e, "BootReceiver: Failed to start receiver services")
        }
    }
}
