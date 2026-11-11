package dev.jdtech.jellyfin.player.local.presentation

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import dev.jdtech.jellyfin.player.core.domain.models.PlayerContentSource
import dev.jdtech.jellyfin.player.core.video.ColorMetadataFormatter
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/**
 * Immutable snapshot of real-time playback diagnostics ("Stats for Nerds") across XR, Beam, and TV.
 */
data class PlaybackStatsSnapshot(
    val deliveryMethod: String,
    val transcodeReasons: List<String>,
    val containerFormat: String,
    val videoCodec: String,
    val videoDecoderName: String,
    val isHardwareVideoDecoder: Boolean,
    val videoResolution: String,
    val videoFrameRate: Float,
    val displayRefreshRateHz: Float,
    val colorTransferSummary: String,
    val audioCodec: String,
    val audioDecoderName: String,
    val audioModeSummary: String,
    val subtitleEngineSummary: String,
    val renderedVideoFrames: Long,
    val droppedVideoFrames: Long,
    val audioUnderruns: Long,
    val bufferedDurationMs: Long,
    val estimatedBandwidthMbps: Float,
    val subtitleOffsetMs: Long,
    val audioOffsetMs: Long,
)

/**
 * Lightweight Media3 [AnalyticsListener] that accumulates decoder, frame-drop, underrun, and
 * bandwidth telemetry into volatile/atomic fields without allocating objects or triggering UI
 * recompositions while the diagnostics HUD is hidden.
 */
@UnstableApi
class PlaybackStatsCollector : AnalyticsListener {

    @Volatile
    var videoDecoderName: String = "None"
        private set

    @Volatile
    var isHardwareVideoDecoder: Boolean = false
        private set

    @Volatile
    var audioDecoderName: String = "None"
        private set

    @Volatile
    var latestVideoFormat: Format? = null
        private set

    @Volatile
    var latestAudioFormat: Format? = null
        private set

    @Volatile
    var estimatedBitrateBps: Long = 0L
        private set

    private val droppedFramesAccum = AtomicLong(0L)
    private val renderedFramesAccum = AtomicLong(0L)
    private val audioUnderrunsAccum = AtomicLong(0L)

    override fun onVideoDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long,
    ) {
        videoDecoderName = decoderName.ifBlank { "None" }
        isHardwareVideoDecoder = isHardwareAcceleratedDecoder(videoDecoderName)
    }

    override fun onAudioDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long,
    ) {
        audioDecoderName = decoderName.ifBlank { "None" }
    }

    override fun onVideoInputFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        format: Format,
        decoderReuseEvaluation: DecoderReuseEvaluation?,
    ) {
        latestVideoFormat = format
    }

    override fun onAudioInputFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        format: Format,
        decoderReuseEvaluation: DecoderReuseEvaluation?,
    ) {
        latestAudioFormat = format
    }

    override fun onDroppedVideoFrames(
        eventTime: AnalyticsListener.EventTime,
        droppedFrames: Int,
        elapsedMs: Long,
    ) {
        if (droppedFrames > 0) {
            droppedFramesAccum.addAndGet(droppedFrames.toLong())
        }
    }

    override fun onAudioUnderrun(
        eventTime: AnalyticsListener.EventTime,
        bufferSize: Int,
        bufferSizeMs: Long,
        elapsedSinceLastFeedMs: Long,
    ) {
        audioUnderrunsAccum.incrementAndGet()
    }

    override fun onBandwidthEstimate(
        eventTime: AnalyticsListener.EventTime,
        totalLoadTimeMs: Int,
        totalBytesLoaded: Long,
        bitrateEstimate: Long,
    ) {
        if (bitrateEstimate >= 0L) {
            estimatedBitrateBps = bitrateEstimate
        }
    }

    fun recordRenderedFramesForTesting(count: Long) {
        if (count > 0L) {
            renderedFramesAccum.addAndGet(count)
        }
    }

    fun reset() {
        videoDecoderName = "None"
        isHardwareVideoDecoder = false
        audioDecoderName = "None"
        latestVideoFormat = null
        latestAudioFormat = null
        estimatedBitrateBps = 0L
        droppedFramesAccum.set(0L)
        renderedFramesAccum.set(0L)
        audioUnderrunsAccum.set(0L)
    }

    /**
     * Builds a [PlaybackStatsSnapshot] on demand from the active [player] and [viewModel].
     */
    fun buildSnapshot(
        player: Player,
        viewModel: PlayerViewModel,
        displayRefreshRateHz: Float = 0f,
        audioModeSummaryOverride: String? = null,
        subtitleEngineSummaryOverride: String? = null,
    ): PlaybackStatsSnapshot {
        val exoPlayer = player as? ExoPlayer
        val videoFormat = exoPlayer?.videoFormat ?: latestVideoFormat ?: findSelectedTrackFormat(player, C.TRACK_TYPE_VIDEO)
        val audioFormat = exoPlayer?.audioFormat ?: latestAudioFormat ?: findSelectedTrackFormat(player, C.TRACK_TYPE_AUDIO)
        val subtitleFormat = findSelectedTrackFormat(player, C.TRACK_TYPE_TEXT)

        val uiState = viewModel.uiState.value
        val currentItem = viewModel.currentPlayerItem()
        val deliveryMethod = resolveDeliveryMethod(
            playbackInfoLabel = uiState.currentPlaybackInfoLabel,
            transcodeReason = uiState.currentTranscodeReason,
            contentSource = currentItem?.contentSource,
            mediaUri = currentItem?.mediaSourceUri ?: viewModel.currentMediaUri?.toString(),
        )
        val transcodeReasons = uiState.currentTranscodeReason
            ?.split(",", ";")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

        val containerFormat = resolveContainerFormat(
            videoFormat = videoFormat,
            mediaUri = currentItem?.mediaSourceUri ?: viewModel.currentMediaUri?.toString(),
        )

        val audioModeSummary = audioModeSummaryOverride ?: resolveDefaultAudioModeSummary(audioFormat)
        val subtitleEngineSummary = subtitleEngineSummaryOverride ?: resolveDefaultSubtitleSummary(
            subtitleFormat = subtitleFormat,
            visualSubtitlesEnabled = uiState.visualSubtitlesEnabled,
        )

        val videoCounters = exoPlayer?.videoDecoderCounters
        val exoRendered = videoCounters?.renderedOutputBufferCount?.toLong() ?: 0L
        val exoDropped = videoCounters?.droppedBufferCount?.toLong() ?: 0L

        return buildSnapshotFromInputs(
            videoFormat = videoFormat,
            audioFormat = audioFormat,
            bufferedDurationMs = player.totalBufferedDuration.coerceAtLeast(0L),
            deliveryMethod = deliveryMethod,
            transcodeReasons = transcodeReasons,
            containerFormat = containerFormat,
            displayRefreshRateHz = displayRefreshRateHz,
            audioModeSummary = audioModeSummary,
            subtitleEngineSummary = subtitleEngineSummary,
            subtitleOffsetMs = viewModel.subtitleOffsetMs.value,
            audioOffsetMs = viewModel.audioOffsetMs.value,
            exoRenderedFrames = exoRendered,
            exoDroppedFrames = exoDropped,
        )
    }

    /**
     * Pure snapshot builder used by [buildSnapshot] and unit tests without requiring a live
     * [PlayerViewModel].
     */
    fun buildSnapshotFromInputs(
        videoFormat: Format?,
        audioFormat: Format?,
        bufferedDurationMs: Long,
        deliveryMethod: String,
        transcodeReasons: List<String>,
        containerFormat: String,
        displayRefreshRateHz: Float,
        audioModeSummary: String,
        subtitleEngineSummary: String,
        subtitleOffsetMs: Long,
        audioOffsetMs: Long,
        exoRenderedFrames: Long = 0L,
        exoDroppedFrames: Long = 0L,
    ): PlaybackStatsSnapshot {
        val videoCodecLabel = formatVideoCodec(videoFormat)
        val resolutionLabel = if (videoFormat != null && videoFormat.width > 0 && videoFormat.height > 0) {
            "${videoFormat.width}x${videoFormat.height}"
        } else {
            "Unknown"
        }
        val frameRate = videoFormat?.frameRate?.takeIf { it > 0f } ?: 0f
        val colorSummary = ColorMetadataFormatter.formatColorMetadataSummary(videoFormat)
        val audioCodecLabel = formatAudioCodec(audioFormat)

        val renderedFrames = max(renderedFramesAccum.get(), exoRenderedFrames)
        val droppedFrames = max(droppedFramesAccum.get(), exoDroppedFrames)
        val underruns = audioUnderrunsAccum.get()
        val bandwidthMbps = (estimatedBitrateBps.coerceAtLeast(0L).toFloat()) / 1_000_000f

        return PlaybackStatsSnapshot(
            deliveryMethod = deliveryMethod,
            transcodeReasons = transcodeReasons,
            containerFormat = containerFormat,
            videoCodec = videoCodecLabel,
            videoDecoderName = videoDecoderName,
            isHardwareVideoDecoder = isHardwareVideoDecoder,
            videoResolution = resolutionLabel,
            videoFrameRate = frameRate,
            displayRefreshRateHz = displayRefreshRateHz.coerceAtLeast(0f),
            colorTransferSummary = colorSummary,
            audioCodec = audioCodecLabel,
            audioDecoderName = audioDecoderName,
            audioModeSummary = audioModeSummary,
            subtitleEngineSummary = subtitleEngineSummary,
            renderedVideoFrames = renderedFrames,
            droppedVideoFrames = droppedFrames,
            audioUnderruns = underruns,
            bufferedDurationMs = bufferedDurationMs.coerceAtLeast(0L),
            estimatedBandwidthMbps = bandwidthMbps,
            subtitleOffsetMs = subtitleOffsetMs,
            audioOffsetMs = audioOffsetMs,
        )
    }

    companion object {
        fun isHardwareAcceleratedDecoder(decoderName: String): Boolean {
            val normalized = decoderName.trim()
            if (normalized.isEmpty() || normalized.equals("None", ignoreCase = true)) {
                return false
            }
            val lower = normalized.lowercase()
            if (
                lower.startsWith("c2.android.") ||
                lower.startsWith("omx.google.") ||
                lower.startsWith("c2.google.") ||
                lower.startsWith("c2.dolby.") ||
                lower.contains("ffmpeg") ||
                lower.contains("libdav1d") ||
                lower.contains("libvpx") ||
                lower.endsWith(".sw") ||
                lower.contains(".sw.")
            ) {
                return false
            }
            return true
        }

        private fun findSelectedTrackFormat(player: Player, trackType: @C.TrackType Int): Format? {
            val group = player.currentTracks.groups.firstOrNull { it.type == trackType && it.isSelected }
                ?: return null
            val selectedIndex = (0 until group.length).firstOrNull { group.isTrackSelected(it) } ?: 0
            return if (selectedIndex in 0 until group.length) group.getTrackFormat(selectedIndex) else null
        }

        private fun resolveDeliveryMethod(
            playbackInfoLabel: String?,
            transcodeReason: String?,
            contentSource: PlayerContentSource?,
            mediaUri: String?,
        ): String {
            if (contentSource == PlayerContentSource.LOCAL ||
                mediaUri?.startsWith("file://") == true ||
                mediaUri?.startsWith("content://") == true
            ) {
                return "Local"
            }
            if (!transcodeReason.isNullOrBlank() ||
                playbackInfoLabel?.contains("Transcod", ignoreCase = true) == true
            ) {
                return "Transcode"
            }
            if (playbackInfoLabel?.contains("Direct Stream", ignoreCase = true) == true) {
                return "Direct Stream"
            }
            if (playbackInfoLabel?.contains("Direct Play", ignoreCase = true) == true) {
                return "Direct Play"
            }
            if (mediaUri?.contains(".m3u8", ignoreCase = true) == true) {
                return "Transcode"
            }
            return "Direct Play"
        }

        private fun resolveContainerFormat(
            videoFormat: Format?,
            mediaUri: String?,
        ): String {
            val containerMime = videoFormat?.containerMimeType
            if (!containerMime.isNullOrBlank()) {
                return containerMime.substringAfter('/').uppercase()
            }
            val uriLower = mediaUri?.substringBefore('?')?.lowercase() ?: return "Unknown"
            return when {
                uriLower.endsWith(".mkv") -> "MKV"
                uriLower.endsWith(".mp4") || uriLower.endsWith(".m4v") -> "MP4"
                uriLower.endsWith(".m3u8") -> "HLS"
                uriLower.endsWith(".mpd") -> "DASH"
                uriLower.endsWith(".webm") -> "WEBM"
                uriLower.endsWith(".ts") -> "MPEG-TS"
                else -> "STREAM"
            }
        }

        private fun formatVideoCodec(format: Format?): String {
            if (format == null) return "Unknown"
            val mime = format.sampleMimeType?.substringAfter('/')?.uppercase() ?: "VIDEO"
            val codecs = format.codecs?.takeIf { it.isNotBlank() }
            return if (codecs != null) "$mime ($codecs)" else mime
        }

        private fun formatAudioCodec(format: Format?): String {
            if (format == null) return "None"
            val mime = format.sampleMimeType?.substringAfter('/')?.uppercase() ?: "AUDIO"
            val channels = format.channelCount.takeIf { it > 0 }?.let { "${it}ch" }
            val sampleRate = format.sampleRate.takeIf { it > 0 }?.let { "${it / 1000}kHz" }
            return listOfNotNull(mime, channels, sampleRate).joinToString(" ")
        }

        private fun resolveDefaultAudioModeSummary(audioFormat: Format?): String {
            if (audioFormat == null) return "None"
            return "Stereo/Multichannel PCM"
        }

        private fun resolveDefaultSubtitleSummary(
            subtitleFormat: Format?,
            visualSubtitlesEnabled: Boolean,
        ): String {
            if (!visualSubtitlesEnabled || subtitleFormat == null) return "Off"
            val mime = subtitleFormat.sampleMimeType.orEmpty()
            return if (mime.contains("ssa", ignoreCase = true) || mime.contains("ass", ignoreCase = true)) {
                "libass JNI (ASS/SSA)"
            } else {
                "Media3 Cue Overlay"
            }
        }
    }
}
