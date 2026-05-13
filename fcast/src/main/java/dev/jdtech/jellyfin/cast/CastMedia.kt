package dev.jdtech.jellyfin.cast

/**
 * Protocol-agnostic load descriptor. Adapters translate this to their wire format —
 * FCast `PlayMessage`, Google Cast `LOAD` JSON, AirPlay `Content-Location` body.
 *
 * [url] must already be self-authenticating (Jellyfin URLs include `?api_key=…`). Custom
 * request headers are not honored by Google Cast or AirPlay; if a non-self-auth URL is ever
 * needed, route it through `LocalCastProxy` (added in PR 2).
 *
 * @property startPositionMs absolute media-time offset at which playback should begin. Most
 *   protocols accept this directly; FCast carries it inside `PlayMessage.time`.
 */
data class CastMedia(
    val url: String,
    val contentType: String,
    val title: String? = null,
    val subtitle: String? = null,
    val posterUrl: String? = null,
    val durationMs: Long? = null,
    val startPositionMs: Long = 0L,
    val subtitleTracks: List<SubtitleTrack> = emptyList(),
) {
    /**
     * One external subtitle stream. WebVTT URLs work everywhere; Cast/AirPlay can't render
     * libass-style ASS/SSA — the URL must point at a Jellyfin-transcoded WebVTT.
     */
    data class SubtitleTrack(
        val url: String,
        val language: String,
        val label: String? = null,
        val mimeType: String = "text/vtt",
    )
}
