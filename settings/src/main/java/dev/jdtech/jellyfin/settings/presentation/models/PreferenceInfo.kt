package dev.jdtech.jellyfin.settings.presentation.models

import androidx.annotation.DrawableRes
import dev.jdtech.jellyfin.settings.domain.models.Preference as PreferenceBackend
import dev.jdtech.jellyfin.settings.presentation.enums.DeviceType

data class PreferenceInfo(
    val title: String,
    override val description: String,
    @param:DrawableRes override val iconDrawableId: Int? = null,
    override val enabled: Boolean = true,
    override val dependencies: List<PreferenceBackend<Boolean>> = emptyList(),
    override val supportedDeviceTypes: List<DeviceType> =
        listOf(DeviceType.PHONE, DeviceType.TV, DeviceType.XR),
) : Preference {
    override val nameStringResource: Int = 0
    override val descriptionStringRes: Int? = null
}
