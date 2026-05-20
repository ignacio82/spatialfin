package dev.jdtech.jellyfin.fcast.sender

import dev.jdtech.jellyfin.fcast.protocol.MetadataObject
import dev.jdtech.jellyfin.fcast.protocol.PlayMessage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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
        /**
         * SpatialFin extension: the source file's audio codec (Jellyfin `MediaStream.codec`,
         * e.g. `eac3`, `aac`, `truehd`). The receiver echoes it back in its audio-only
         * overlay so the user sees the original audio format even when the URL is being
         * transcoded.
         */
        sourceAudioCodec: String? = null,
        /**
         * SpatialFin extension: true if the URL points at a server-side audio transcode.
         * Lets the receiver show "Source · transcoded" vs "Source · direct play" below the
         * title. Independent of [sourceAudioCodec] so a non-SpatialFin receiver can still
         * decode something useful.
         */
        audioTranscoded: Boolean? = null,
    ): PlayMessage = PlayMessage(
        container = container,
        url = url,
        time = positionSeconds.takeIf { it > 0.0 },
        volume = volume?.coerceIn(0.0, 1.0),
        speed = speed,
        headers = headers?.takeIf { it.isNotEmpty() },
        metadata = if (
            title != null || thumbnailUrl != null ||
            sourceAudioCodec != null || audioTranscoded != null
        ) {
            MetadataObject(
                type = if (thumbnailUrl != null) METADATA_TYPE_GENERIC else null,
                title = title,
                thumbnailUrl = thumbnailUrl,
                custom = buildAudioCustom(sourceAudioCodec, audioTranscoded),
            )
        } else {
            null
        },
    )

    /**
     * Pack the SpatialFin audio extension into `metadata.custom` as
     * `{"audio":{"sourceCodec":"eac3","transcoded":true}}`. Non-SpatialFin receivers ignore
     * the field per the v3 spec's `ignoreUnknownKeys` semantics; SpatialFin receivers read it
     * and render under the title.
     */
    private fun buildAudioCustom(
        sourceAudioCodec: String?,
        audioTranscoded: Boolean?,
    ): JsonObject? {
        if (sourceAudioCodec == null && audioTranscoded == null) return null
        return buildJsonObject {
            put(
                "audio",
                buildJsonObject {
                    sourceAudioCodec?.let { put("sourceCodec", it) }
                    audioTranscoded?.let { put("transcoded", it) }
                },
            )
        }
    }

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
