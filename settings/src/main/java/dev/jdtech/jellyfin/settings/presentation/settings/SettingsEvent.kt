package dev.jdtech.jellyfin.settings.presentation.settings

import android.content.Intent
import dev.jdtech.jellyfin.settings.language.SmartLanguageSettings

sealed interface SettingsEvent {
    data object NavigateToUsers : SettingsEvent

    data object NavigateToServers : SettingsEvent

    data object NavigateToAbout : SettingsEvent

    data class NavigateToSettings(val indexes: IntArray) : SettingsEvent

    data class UpdateTheme(val theme: String) : SettingsEvent

    data class LaunchIntent(val intent: Intent) : SettingsEvent

    data class ShowCloudApiKeyDialog(val currentValue: String?) : SettingsEvent

    data class ShowSeerrUrlDialog(val currentValue: String?) : SettingsEvent

    data class ShowSeerrApiKeyDialog(val currentValue: String?) : SettingsEvent

    data class ShowTmdbApiKeyDialog(val currentValue: String?) : SettingsEvent

    data class ShowOmdbApiKeyDialog(val currentValue: String?) : SettingsEvent

    data class ShowSmartLanguageDialog(val settings: SmartLanguageSettings) : SettingsEvent

    data class ShowVoicePickerDialog(val currentVoiceName: String?) : SettingsEvent

    data object ShowCompanionDiscoveryDialog : SettingsEvent

    data object RestartActivity : SettingsEvent

    data class ConfigureAppLock(val enable: Boolean) : SettingsEvent

    /** User picked a new auth mode (off / biometric / pin). */
    data class ChangeAppLockMode(val mode: String) : SettingsEvent

    /** Open the PIN setup flow — either first-time or a change while PIN mode is active. */
    data object ShowAppLockPinSetup : SettingsEvent

    /** User tapped "Delete unencrypted downloads". The UI layer handles counting + delete. */
    data object ShowDeleteUnencryptedDialog : SettingsEvent
}
