package dev.spatialfin.companion.host

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.spatialfin.companion.protocol.WearVitalsState
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WearVitalsCollector @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun collectVitals(): WearVitalsState {
        var batteryPct = -1
        var isCharging = false

        try {
            val batteryFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus: Intent? = context.registerReceiver(null, batteryFilter)
            batteryStatus?.let { intent ->
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (level >= 0 && scale > 0) {
                    batteryPct = (level * 100 / scale.toFloat()).toInt()
                }
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
            }
        } catch (e: Exception) {
            Timber.w(e, "WearVitalsCollector: failed to read battery status")
        }

        var thermalStatus = 0
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                thermalStatus = powerManager?.currentThermalStatus ?: 0
            }
        } catch (e: Exception) {
            Timber.w(e, "WearVitalsCollector: failed to read thermal status")
        }

        var wifiSpeedMbps = -1
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val info = wifiManager?.connectionInfo
            if (info != null) {
                wifiSpeedMbps = info.linkSpeed
            }
        } catch (e: Exception) {
            Timber.w(e, "WearVitalsCollector: failed to read wifi speed")
        }

        val deviceModel = Build.MODEL ?: "SpatialFin Host"
        val isHeadset = deviceModel.contains("XR", ignoreCase = true) ||
            Build.MANUFACTURER.contains("Samsung", ignoreCase = true) && deviceModel.contains("SM-I", ignoreCase = true)

        return WearVitalsState(
            batteryPercent = batteryPct,
            isCharging = isCharging,
            thermalStatus = thermalStatus,
            wifiSpeedMbps = wifiSpeedMbps,
            deviceName = deviceModel,
            isHeadset = isHeadset,
        )
    }
}
