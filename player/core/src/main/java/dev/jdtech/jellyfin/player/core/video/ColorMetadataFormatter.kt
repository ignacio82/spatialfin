package dev.jdtech.jellyfin.player.core.video

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi

/**
 * Shared CTA-861.3 static HDR metadata parser (`MaxCLL`) and human-readable color space / transfer
 * formatter used by both `XrSurfaceColorMetadata` (`:player:xr`) and `PlaybackStatsCollector`
 * (`:player:local`).
 */
@OptIn(UnstableApi::class)
object ColorMetadataFormatter {
    private const val CTA861_STATIC_METADATA_TYPE_1 = 0
    private const val CTA861_PAYLOAD_SIZE = 25
    private const val CTA861_PREFIXED_MIN_SIZE = 27
    private const val MAX_VALID_NITS = 10_000
    const val MAX_CONTENT_LIGHT_LEVEL_UNKNOWN = 0

    /**
     * Extracts CTA-861.3 static metadata Type 1 Maximum Content Light Level (`MaxCLL`) in nits.
     *
     * Standard 25-byte CTA-861.3 descriptors store `descriptor_id = 0` at byte 0, `MaxCLL`
     * (little-endian uint16) at bytes `21..22`, and `MaxFALL` at bytes `23..24`. Containers that
     * prepend a 2-byte header (`size >= 27` with `byte[2] == 0`) shift `MaxCLL` to bytes `23..24`.
     * Returns [MAX_CONTENT_LIGHT_LEVEL_UNKNOWN] (`0`) when absent or `0`.
     */
    fun extractMaxContentLightLevel(hdrStaticInfo: ByteArray?): Int {
        if (hdrStaticInfo == null || hdrStaticInfo.size < CTA861_PAYLOAD_SIZE) {
            return MAX_CONTENT_LIGHT_LEVEL_UNKNOWN
        }
        val maxCllOffset = when {
            hdrStaticInfo.size == CTA861_PAYLOAD_SIZE &&
                (hdrStaticInfo[0].toInt() and 0xFF) == CTA861_STATIC_METADATA_TYPE_1 -> 21
            hdrStaticInfo.size >= CTA861_PREFIXED_MIN_SIZE &&
                (hdrStaticInfo[2].toInt() and 0xFF) == CTA861_STATIC_METADATA_TYPE_1 -> 23
            (hdrStaticInfo[0].toInt() and 0xFF) == CTA861_STATIC_METADATA_TYPE_1 -> 21
            else -> return MAX_CONTENT_LIGHT_LEVEL_UNKNOWN
        }
        val low = hdrStaticInfo[maxCllOffset].toInt() and 0xFF
        val high = hdrStaticInfo[maxCllOffset + 1].toInt() and 0xFF
        val rawMaxCll = low or (high shl 8)
        if (rawMaxCll <= 0) {
            return MAX_CONTENT_LIGHT_LEVEL_UNKNOWN
        }
        return rawMaxCll.coerceAtMost(MAX_VALID_NITS)
    }

    /**
     * Formats a concise diagnostic summary of the video [Format]'s color space, transfer function,
     * and optional `MaxCLL` nits (for example `"BT.2020 / PQ (HDR10) / MaxCLL 1000 nits"`,
     * `"BT.2020 / Dolby Vision"`, `"BT.2020 / HLG"`, or `"BT.709 / SDR"`).
     */
    fun formatColorMetadataSummary(format: Format?): String {
        if (format == null) return "SDR"
        val colorInfo: ColorInfo? = format.colorInfo
        val isDolbyVision =
            format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION ||
                format.codecs?.startsWith("dvh", ignoreCase = true) == true ||
                format.codecs?.startsWith("dvhe", ignoreCase = true) == true

        val spaceLabel = when (colorInfo?.colorSpace) {
            C.COLOR_SPACE_BT2020 -> "BT.2020"
            C.COLOR_SPACE_BT601 -> "BT.601"
            C.COLOR_SPACE_BT709 -> "BT.709"
            else -> if (isDolbyVision) "BT.2020" else "BT.709"
        }

        val transferLabel = when {
            isDolbyVision -> "Dolby Vision"
            colorInfo?.colorTransfer == C.COLOR_TRANSFER_ST2084 -> "PQ (HDR10)"
            colorInfo?.colorTransfer == C.COLOR_TRANSFER_HLG -> "HLG"
            colorInfo?.colorTransfer == C.COLOR_TRANSFER_LINEAR -> "Linear"
            else -> "SDR"
        }

        val maxCll = extractMaxContentLightLevel(colorInfo?.hdrStaticInfo)
        return if (maxCll > 0) {
            "$spaceLabel / $transferLabel / MaxCLL $maxCll nits"
        } else {
            "$spaceLabel / $transferLabel"
        }
    }
}
