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

    data object ShowCompanionDiscoveryDialog : SettingsEvent

    data object RestartActivity : SettingsEvent

    data class ConfigureAppLock(val enable: Boolean) : SettingsEvent
}
