package dev.spatialfin.unified

import android.content.Context
import android.content.pm.PackageManager

enum class DeviceClass { XR, TV, PHONE }

fun Context.detectDeviceClass(): DeviceClass = when {
    packageManager.hasSystemFeature("android.software.xr.api.spatial") -> DeviceClass.XR
    packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) -> DeviceClass.TV
    else -> DeviceClass.PHONE
}
