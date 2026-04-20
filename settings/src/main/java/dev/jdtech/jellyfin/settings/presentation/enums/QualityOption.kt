package dev.jdtech.jellyfin.settings.presentation.enums

import androidx.annotation.StringRes
import dev.jdtech.jellyfin.settings.R

/**
 * Streaming quality presets surfaced in Settings and the in-player quality
 * dialog. Labelled by target resolution with the bitrate cap as a suffix, so
 * users pick what they know ("1080p") while the Jellyfin server still receives
 * a concrete `maxStreamingBitrate` value to enforce.
 *
 * Stored pref remains a `Long` bps value so upgrading from the legacy 19-entry
 * Mbps list is migration-free — [fromBps] snaps any pre-existing value to the
 * closest preset.
 */
enum class QualityOption(
    val bps: Long,
    @StringRes val labelRes: Int,
) {
    AUTO(0L, R.string.quality_auto),
    UHD(40_000_000L, R.string.quality_2160p),
    FHD(10_000_000L, R.string.quality_1080p),
    HD(4_000_000L, R.string.quality_720p),
    SD(2_000_000L, R.string.quality_480p),
    LOW(1_000_000L, R.string.quality_360p);

    companion object {
        fun fromBps(bps: Long): QualityOption {
            if (bps <= 0L) return AUTO
            return entries
                .filter { it != AUTO }
                .minBy { kotlin.math.abs(it.bps - bps) }
        }
    }
}
