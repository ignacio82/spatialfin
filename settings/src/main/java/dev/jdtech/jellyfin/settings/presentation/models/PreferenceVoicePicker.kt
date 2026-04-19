package dev.jdtech.jellyfin.settings.presentation.models

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import dev.jdtech.jellyfin.settings.domain.models.Preference as PreferenceBackend
import dev.jdtech.jellyfin.settings.presentation.enums.DeviceType

data class PreferenceVoicePicker(
    @param:StringRes override val nameStringResource: Int,
    @param:StringRes override val descriptionStringRes: Int? = null,
    override val description: String? = null,
    @param:DrawableRes override val iconDrawableId: Int? = null,
    override val enabled: Boolean = true,
    override val dependencies: List<PreferenceBackend<Boolean>> = emptyList(),
    override val supportedDeviceTypes: List<DeviceType> =
        listOf(DeviceType.PHONE, DeviceType.TV, DeviceType.XR),
    val backendPreference: PreferenceBackend<String?>,
    override val summary: String,
    val onClick: (Preference) -> Unit = {},
) : SummaryPreference
