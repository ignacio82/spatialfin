package dev.spatialfin.fcast.session

/**
 * User-visible summary of the route selected for split-A/V audio.
 *
 * This intentionally describes the sender-side decision (direct source bitstream vs Jellyfin
 * audio transcode). The receiver's decoded [AudioFormatInfo] is reported separately once
 * ExoPlayer resolves tracks.
 */
data class SplitAvAudioRouteInfo(
    val route: Route,
    val sourceAudioCodec: String?,
    val targetAudioCodec: String? = null,
) {
    enum class Route {
        Direct,
        Transcoded,
        UpgradedToDirect,
        DowngradedToTranscode,
    }

    val label: String
        get() = when (route) {
            Route.Direct -> "Direct source audio"
            Route.Transcoded -> "Transcoded${targetSuffix()}"
            Route.UpgradedToDirect -> "Upgraded to direct source audio"
            Route.DowngradedToTranscode -> "Transcoded${targetSuffix()} (receiver limit)"
        }

    private fun targetSuffix(): String =
        targetAudioCodec?.takeIf { it.isNotBlank() }?.let { " to ${prettyCodec(it)}" }.orEmpty()

    private fun prettyCodec(codec: String): String = when (codec.lowercase().trim()) {
        "eac3", "ec3" -> "E-AC-3"
        "eac3-joc" -> "Dolby Atmos"
        "ac3" -> "AC-3"
        "truehd", "mlp" -> "TrueHD"
        "dts-hd", "dts-hd-ma" -> "DTS-HD"
        "dts", "dca" -> "DTS"
        "aac" -> "AAC"
        "mp3" -> "MP3"
        "flac" -> "FLAC"
        "opus" -> "Opus"
        "vorbis" -> "Vorbis"
        "pcm", "pcm_s16le", "pcm_s24le", "pcm_s32le", "pcm_f32le" -> "PCM"
        else -> codec.uppercase()
    }
}
