package dev.jdtech.jellyfin.player.xr

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.xr.scenecore.SurfaceEntity
import dev.jdtech.jellyfin.player.core.video.ColorMetadataFormatter
import timber.log.Timber

/**
 * Maps Media3 [ColorInfo] metadata from the active video track to Jetpack XR SceneCore's
 * [SurfaceEntity.ContentColorMetadata] so wide-gamut (`BT.2020`) and High Dynamic Range
 * (`HDR10 / ST2084`, `HLG`, `LINEAR`) streams are signaled to the XR hardware compositor.
 */
@OptIn(UnstableApi::class)
internal object XrSurfaceColorMetadata {

    fun mapColorSpace(media3ColorSpace: Int): SurfaceEntity.ContentColorMetadata.ColorSpace =
        when (media3ColorSpace) {
            C.COLOR_SPACE_BT2020 -> SurfaceEntity.ContentColorMetadata.ColorSpace.BT2020
            C.COLOR_SPACE_BT601 -> SurfaceEntity.ContentColorMetadata.ColorSpace.BT601_PAL
            C.COLOR_SPACE_BT709, Format.NO_VALUE -> SurfaceEntity.ContentColorMetadata.ColorSpace.BT709
            else -> SurfaceEntity.ContentColorMetadata.ColorSpace.BT709
        }

    fun mapColorTransfer(media3ColorTransfer: Int): SurfaceEntity.ContentColorMetadata.ColorTransfer =
        when (media3ColorTransfer) {
            C.COLOR_TRANSFER_ST2084 -> SurfaceEntity.ContentColorMetadata.ColorTransfer.ST2084
            C.COLOR_TRANSFER_HLG -> SurfaceEntity.ContentColorMetadata.ColorTransfer.HLG
            C.COLOR_TRANSFER_LINEAR -> SurfaceEntity.ContentColorMetadata.ColorTransfer.LINEAR
            C.COLOR_TRANSFER_GAMMA_2_2 -> SurfaceEntity.ContentColorMetadata.ColorTransfer.GAMMA_2_2
            C.COLOR_TRANSFER_SRGB -> SurfaceEntity.ContentColorMetadata.ColorTransfer.SRGB
            C.COLOR_TRANSFER_SDR, Format.NO_VALUE -> SurfaceEntity.ContentColorMetadata.ColorTransfer.SDR
            else -> SurfaceEntity.ContentColorMetadata.ColorTransfer.SDR
        }

    fun mapColorRange(media3ColorRange: Int): SurfaceEntity.ContentColorMetadata.ColorRange =
        when (media3ColorRange) {
            C.COLOR_RANGE_FULL -> SurfaceEntity.ContentColorMetadata.ColorRange.FULL
            C.COLOR_RANGE_LIMITED, Format.NO_VALUE -> SurfaceEntity.ContentColorMetadata.ColorRange.LIMITED
            else -> SurfaceEntity.ContentColorMetadata.ColorRange.LIMITED
        }

    /**
     * Extracts CTA-861.3 static metadata Type 1 Maximum Content Light Level (`MaxCLL`) in nits.
     *
     * Standard 25-byte CTA-861.3 descriptors store `descriptor_id = 0` at byte 0, `MaxCLL`
     * (little-endian uint16) at bytes `21..22`, and `MaxFALL` at bytes `23..24`. Containers that
     * prepend a 2-byte header (`size >= 27` with `byte[2] == 0`) shift `MaxCLL` to bytes `23..24`.
     * Returns [SurfaceEntity.ContentColorMetadata.MAX_CONTENT_LIGHT_LEVEL_UNKNOWN] (`0`) when
     * absent or `0` (per CTA-861.3, `0` denotes "unknown" and must not be clamped to `1 nit`).
     */
    fun extractMaxContentLightLevel(hdrStaticInfo: ByteArray?): Int =
        ColorMetadataFormatter.extractMaxContentLightLevel(hdrStaticInfo)

    /**
     * Converts Media3 [ColorInfo] to [SurfaceEntity.ContentColorMetadata], or returns `null` when
     * [colorInfo] is absent or describes standard SDR / untagged video so the compositor resets to
     * its default SDR pipeline.
     */
    fun fromColorInfo(colorInfo: ColorInfo?): SurfaceEntity.ContentColorMetadata? {
        if (colorInfo == null) return null
        val maxCll = extractMaxContentLightLevel(colorInfo.hdrStaticInfo)
        val isStandardOrUntaggedColorSpace =
            colorInfo.colorSpace == Format.NO_VALUE ||
                colorInfo.colorSpace == C.COLOR_SPACE_BT709 ||
                colorInfo.colorSpace == C.COLOR_SPACE_BT601
        val isStandardOrUntaggedTransfer =
            colorInfo.colorTransfer == Format.NO_VALUE ||
                colorInfo.colorTransfer == C.COLOR_TRANSFER_SDR ||
                colorInfo.colorTransfer == C.COLOR_TRANSFER_GAMMA_2_2
        val hasHdrStaticMetadata = colorInfo.hdrStaticInfo != null && colorInfo.hdrStaticInfo!!.isNotEmpty()

        if (isStandardOrUntaggedColorSpace && isStandardOrUntaggedTransfer && !hasHdrStaticMetadata) {
            return null
        }

        return SurfaceEntity.ContentColorMetadata(
            colorSpace = mapColorSpace(colorInfo.colorSpace),
            colorTransfer = mapColorTransfer(colorInfo.colorTransfer),
            colorRange = mapColorRange(colorInfo.colorRange),
            maxContentLightLevel = maxCll,
        )
    }

    fun extractActiveVideoColorInfo(tracks: Tracks): ColorInfo? {
        val videoGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO && it.length > 0 }
        val activeGroup = videoGroups.firstOrNull { it.isSelected } ?: videoGroups.firstOrNull() ?: return null
        val selectedTrackIndex = (0 until activeGroup.length).firstOrNull { activeGroup.isTrackSelected(it) } ?: 0
        return activeGroup.getTrackFormat(selectedTrackIndex).colorInfo
    }

    fun applyToSurfaceEntity(surfaceEntity: SurfaceEntity?, colorInfo: ColorInfo?) {
        if (surfaceEntity == null) return
        val metadata = fromColorInfo(colorInfo)
        runCatching {
            surfaceEntity.contentColorMetadata = metadata
            if (metadata != null) {
                Timber.i(
                    "XR_VIDEO: applied ContentColorMetadata space=%s transfer=%s range=%s maxCll=%d",
                    metadata.colorSpace,
                    metadata.colorTransfer,
                    metadata.colorRange,
                    metadata.maxContentLightLevel,
                )
            } else {
                Timber.d("XR_VIDEO: reset ContentColorMetadata to default SDR")
            }
        }.onFailure { error ->
            Timber.w(error, "XR_VIDEO: failed to update SurfaceEntity.contentColorMetadata")
        }
    }
}
