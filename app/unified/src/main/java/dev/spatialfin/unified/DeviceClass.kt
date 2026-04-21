package dev.spatialfin.unified

import android.content.Context
import android.content.pm.PackageManager

enum class DeviceClass { XR, TV, PHONE }

fun Context.detectDeviceClass(): DeviceClass = when {
    packageManager.hasSystemFeature("android.software.xr.api.spatial") -> DeviceClass.XR
    packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) -> DeviceClass.TV
    else -> DeviceClass.PHONE
}

/**
 * Capability facade over [DeviceClass]. Call sites asking *"what can this device do?"* should
 * branch on these booleans instead of `when (deviceClass)`, so form-factor-aware behaviour stays
 * documented in one file. Add new capabilities here when a new feature needs to differ per form
 * factor — keeps the rest of the codebase from sprinkling `deviceClass == DeviceClass.TV` checks.
 */
data class DeviceClassCapabilities(val deviceClass: DeviceClass) {
    val isXr: Boolean = deviceClass == DeviceClass.XR
    val isTv: Boolean = deviceClass == DeviceClass.TV
    val isPhone: Boolean = deviceClass == DeviceClass.PHONE

    /** Voice assistant UI ships on phone + XR. TV has no voice surface today. */
    val hasVoiceUi: Boolean = deviceClass != DeviceClass.TV

    /** Leanback platform features (Watch Next channel, system-wide recommendations). */
    val hasLeanback: Boolean = deviceClass == DeviceClass.TV

    /** Only XR persists a user-placed immersive panel pose. */
    val hasPersistedPanelPose: Boolean = deviceClass == DeviceClass.XR

    /**
     * TV deliberately disables Coil crossfade; stacked fades during rapid D-pad nav stall on
     * low-end Google TV GPUs (Chromecast with Google TV / Sabrina).
     */
    val useImageCrossfades: Boolean = deviceClass != DeviceClass.TV

    /**
     * Eager LLM (Gemma) init is skipped on TV — no voice surface to pay for, and the ~100 MB
     * RAM + GPU lock-in is harmful on a 2 GB device.
     */
    val eagerInitLlm: Boolean = deviceClass != DeviceClass.TV

    /** Beam Pro uses its own companion uploader pipeline; XR and TV share the standard one. */
    val usesBeamCompanion: Boolean = deviceClass == DeviceClass.PHONE
}

/** Convenience accessor so callers don't have to re-detect the class. */
fun Context.deviceCapabilities(): DeviceClassCapabilities =
    DeviceClassCapabilities(detectDeviceClass())
