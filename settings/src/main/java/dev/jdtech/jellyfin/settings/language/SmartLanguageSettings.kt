package dev.jdtech.jellyfin.settings.language

data class SmartLanguageSettings(
    val preferOriginalAudio: Boolean = true,
    /**
     * When the selected audio is already in a spoken language, auto-enable a forced /
     * signs-only subtitle track in that same language so the foreign-language portions of
     * the show are translated without subtitling the whole dialogue.
     */
    val forcedSubtitlesInUnderstoodAudio: Boolean = true,
    val spokenLanguageCodes: List<String> = emptyList(),
)
