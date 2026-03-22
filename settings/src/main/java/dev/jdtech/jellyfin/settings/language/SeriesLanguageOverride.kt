package dev.jdtech.jellyfin.settings.language

data class SeriesLanguageOverride(
    val audioLanguageCode: String? = null,
    val audioTrackSignature: String? = null,
    val subtitleLanguageCode: String? = null,
    val subtitleTrackSignature: String? = null,
    val subtitlesEnabled: Boolean? = null,
)
