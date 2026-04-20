package dev.jdtech.jellyfin.settings.presentation.models

import androidx.annotation.ArrayRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import dev.jdtech.jellyfin.settings.domain.models.Preference as PreferenceBackend
import dev.jdtech.jellyfin.settings.presentation.enums.DeviceType

data class PreferenceSelect(
    @param:StringRes override val nameStringResource: Int,
    @param:StringRes override val descriptionStringRes: Int? = null,
    override val description: String? = null,
    @param:DrawableRes override val iconDrawableId: Int? = null,
    override val enabled: Boolean = true,
    override val dependencies: List<PreferenceBackend<Boolean>> = emptyList(),
    override val supportedDeviceTypes: List<DeviceType> =
        listOf(DeviceType.PHONE, DeviceType.TV, DeviceType.XR),
    val onUpdate: (String?) -> Unit = {},
    val backendPreference: PreferenceBackend<String?>,
    val options: Int,
    val optionValues: Int,
    val optionsIncludeNull: Boolean = false,
    /**
     * Optional compact labels (same length as [options]) used by space-
     * constrained renderers — e.g. Beam's Row-of-buttons style, where the
     * full option labels would overflow. Not consumed by the XR dialog
     * renderer, which still uses [options] directly.
     */
    @param:ArrayRes val shortOptionsRes: Int? = null,
    /**
     * When `false`, the renderer must not write to [backendPreference] on
     * click — [onUpdate] is expected to own persistence (and any side
     * effects, like biometric enrollment or a PIN dialog). Defaults to
     * `true` for the simple "click = save" flow most rows want.
     */
    val autoPersist: Boolean = true,
    val value: String? = null,
) : Preference
