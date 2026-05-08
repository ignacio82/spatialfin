package dev.jdtech.jellyfin.fcast.sender

import dev.jdtech.jellyfin.fcast.protocol.MetadataObject
import dev.jdtech.jellyfin.fcast.protocol.PlayMessage

/**
 * Pure helper that assembles an FCast [PlayMessage] from the inputs the player layer already has:
 * the stream URL, MIME container, current position, optional auth/Cookie/Referer headers, and
 * cosmetic metadata. Kept independent of [PlayerStateSnapshot] so the helper is testable and
 * reusable from non-XR call sites (Beam, TV, voice action).
 */
object PlayMessageBuilder {

    fun build(
        url: String,
        container: String,
        positionSeconds: Double = 0.0,
        volume: Double? = null,
        speed: Double? = null,
        headers: Map<String, String>? = null,
        title: String? = null,
        thumbnailUrl: String? = null,
    ): PlayMessage = PlayMessage(
        container = container,
        url = url,
        time = positionSeconds.takeIf { it > 0.0 },
        volume = volume?.coerceIn(0.0, 1.0),
        speed = speed,
        headers = headers?.takeIf { it.isNotEmpty() },
        metadata = if (title != null || thumbnailUrl != null) {
            MetadataObject(
                type = if (thumbnailUrl != null) METADATA_TYPE_GENERIC else null,
                title = title,
                thumbnailUrl = thumbnailUrl,
            )
        } else {
            null
        },
    )

    /**
     * Best-effort MIME inference for cases where the streaming layer doesn't carry one.
     * The FCast receiver matches on this string to pick its decoder, so leave it blank rather
     * than guessing wrong.
     */
    fun guessContainer(url: String): String? {
        val lower = url.substringBefore('?').lowercase()
        return when {
            lower.endsWith(".m3u8") -> "application/vnd.apple.mpegurl"
            lower.endsWith(".mpd") -> "application/dash+xml"
            lower.endsWith(".mp4") || lower.endsWith(".m4v") -> "video/mp4"
            lower.endsWith(".mkv") -> "video/x-matroska"
            lower.endsWith(".webm") -> "video/webm"
            lower.endsWith(".mp3") -> "audio/mpeg"
            lower.endsWith(".flac") -> "audio/flac"
            else -> null
        }
    }

    private const val METADATA_TYPE_GENERIC = 1
}
