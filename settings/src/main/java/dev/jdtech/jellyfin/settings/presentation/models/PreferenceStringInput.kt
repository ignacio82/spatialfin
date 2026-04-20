package dev.jdtech.jellyfin.settings.presentation.models

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import dev.jdtech.jellyfin.settings.domain.models.Preference as PreferenceBackend
import dev.jdtech.jellyfin.settings.presentation.enums.DeviceType

/**
 * Free-form text preference backed by a nullable String. `secret = true` hides
 * the value by default and offers a show/hide toggle — used for API keys and
 * other credentials so an over-the-shoulder glance doesn't leak them.
 *
 * Empty input is normalised to `null` at the renderer before writing, so the
 * backing SharedPreferences entry stays absent rather than containing an empty
 * string.
 */
data class PreferenceStringInput(
    @param:StringRes override val nameStringResource: Int,
    @param:StringRes override val descriptionStringRes: Int? = null,
    override val description: String? = null,
    @param:DrawableRes override val iconDrawableId: Int? = null,
    override val enabled: Boolean = true,
    override val dependencies: List<PreferenceBackend<Boolean>> = emptyList(),
    override val supportedDeviceTypes: List<DeviceType> =
        listOf(DeviceType.PHONE, DeviceType.TV, DeviceType.XR),
    val onClick: (Preference) -> Unit = {},
    val backendPreference: PreferenceBackend<String?>,
    @param:StringRes val placeholderRes: Int? = null,
    val placeholder: String? = null,
    val secret: Boolean = false,
    val value: String? = null,
) : Preference
