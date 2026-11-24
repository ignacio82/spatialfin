package dev.jdtech.jellyfin.settings.domain

import dev.jdtech.jellyfin.settings.language.SeriesLanguageOverride
import dev.jdtech.jellyfin.settings.voice.VoiceTelemetryEntry
import java.util.UUID

/**
 * Cross-process seam for forwarding shared player mutations from `:xrplayer` to the main process
 * (and executing them directly in the main process) so `:xrplayer` never writes default
 * `SharedPreferences` or races main-process session teardown.
 *
 * Bound in `:core` `SessionModule`.
 */
interface XrProcessPreferenceBridge {
    enum class StopSourceType {
        JELLYFIN,
        LOCAL,
        NETWORK,
    }

    fun setPendingSyncPlayLeave(
        serverId: String,
        userId: String,
        expectedSessionEpoch: Long,
    )

    fun clearPendingSyncPlayLeave(
        serverId: String,
        userId: String,
        expectedSessionEpoch: Long,
    )

    fun getSeriesLanguageOverride(seriesId: String): SeriesLanguageOverride?

    fun setSeriesLanguageOverride(
        seriesId: String,
        override: SeriesLanguageOverride?,
    )

    fun getPlayerMaxBitrate(): Long

    fun setPlayerMaxBitrate(bitrate: Long)

    fun recordVoiceTelemetry(entry: VoiceTelemetryEntry)

    fun reportStopAndMaybeRunHygiene(
        sourceType: StopSourceType,
        itemId: UUID? = null,
        localMediaId: Long? = null,
        networkVideoId: String? = null,
        positionMs: Long,
        durationMs: Long,
        playedPercentage: Int = 0,
        markedPlayed: Boolean = false,
        runHygiene: Boolean = false,
        serverId: String? = null,
        userId: UUID? = null,
        expectedSessionEpoch: Long = 0L,
    )

    object NoOp : XrProcessPreferenceBridge {
        override fun setPendingSyncPlayLeave(
            serverId: String,
            userId: String,
            expectedSessionEpoch: Long,
        ) = Unit

        override fun clearPendingSyncPlayLeave(
            serverId: String,
            userId: String,
            expectedSessionEpoch: Long,
        ) = Unit

        override fun getSeriesLanguageOverride(seriesId: String): SeriesLanguageOverride? = null

        override fun setSeriesLanguageOverride(
            seriesId: String,
            override: SeriesLanguageOverride?,
        ) = Unit

        override fun getPlayerMaxBitrate(): Long = 0L

        override fun setPlayerMaxBitrate(bitrate: Long) = Unit

        override fun recordVoiceTelemetry(entry: VoiceTelemetryEntry) = Unit

        override fun reportStopAndMaybeRunHygiene(
            sourceType: StopSourceType,
            itemId: UUID?,
            localMediaId: Long?,
            networkVideoId: String?,
            positionMs: Long,
            durationMs: Long,
            playedPercentage: Int,
            markedPlayed: Boolean,
            runHygiene: Boolean,
            serverId: String?,
            userId: UUID?,
            expectedSessionEpoch: Long,
        ) = Unit
    }
}
