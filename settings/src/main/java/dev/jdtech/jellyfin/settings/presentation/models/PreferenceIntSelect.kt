package dev.jdtech.jellyfin.settings.presentation.models

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import dev.jdtech.jellyfin.settings.domain.models.Preference as PreferenceBackend
import dev.jdtech.jellyfin.settings.presentation.enums.DeviceType

/**
 * Select preference whose backend stores an [Int]. Covers the small family
 * of settings whose values are Android color constants or other sentinel
 * integers — converting them to strings for [PreferenceSelect] would work
 * but produces unreadable `optionValues` arrays full of "-16711681"-style
 * entries, so this type keeps the options in code as typed pairs instead.
 */
data class PreferenceIntSelect(
    @param:StringRes override val nameStringResource: Int,
    @param:StringRes override val descriptionStringRes: Int? = null,
    override val description: String? = null,
    @param:DrawableRes override val iconDrawableId: Int? = null,
    override val enabled: Boolean = true,
    override val dependencies: List<PreferenceBackend<Boolean>> = emptyList(),
    override val supportedDeviceTypes: List<DeviceType> =
        listOf(DeviceType.PHONE, DeviceType.TV, DeviceType.XR),
    val onUpdate: (Int) -> Unit = {},
    val backendPreference: PreferenceBackend<Int>,
    val options: List<IntSelectOption>,
    val value: Int? = null,
) : Preference

data class IntSelectOption(
    @param:StringRes val labelRes: Int,
    val value: Int,
)
