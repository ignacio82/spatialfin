package dev.jdtech.jellyfin.settings.language

data class SmartLanguageSettings(
    val preferOriginalAudio: Boolean = true,
    val spokenLanguageCodes: List<String> = emptyList(),
)
