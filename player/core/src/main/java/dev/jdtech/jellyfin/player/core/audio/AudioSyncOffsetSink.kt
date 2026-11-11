package dev.jdtech.jellyfin.player.core.audio

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import dev.jdtech.jellyfin.player.core.sync.AudioSubtitleSyncController

/**
 * Media3 [ForwardingAudioSink] that implements negative audio synchronization offsets
 * (`audioOffsetMs < 0L`, advancing audio relative to video by holding video frame release to match
 * lagging wireless headphones or HDMI AV receivers) and tracks whether compressed HDMI bitstream
 * passthrough is active.
 *
 * - When `audioOffsetMs < 0L`, [getCurrentPositionUs] shifts the reported media clock position by
 *   `audioOffsetMs * 1_000L` (clamped at `0L`). Because ExoPlayer's `MediaCodecVideoRenderer`
 *   releases video frames against this master audio clock, subtracting `|audioOffsetUs|` holds
 *   decoded video frames for `|audioOffsetMs|` across both linear PCM and HDMI bitstream
 *   passthrough.
 * - When `audioOffsetMs >= 0L`, [getCurrentPositionUs] returns the delegate clock position
 *   unchanged because [PcmAudioDelayProcessor] delays the PCM sample stream directly while
 *   preserving 1-to-1 frame throughput.
 */
@UnstableApi
class AudioSyncOffsetSink(
    delegate: AudioSink,
    private val syncController: AudioSubtitleSyncController,
) : ForwardingAudioSink(delegate) {

    @Volatile
    var isCurrentFormatPcm: Boolean = true
        private set

    override fun configure(audioSinkConfig: AudioSink.AudioSinkConfig) {
        val inputFormat: Format = audioSinkConfig.format
        isCurrentFormatPcm =
            inputFormat.sampleMimeType == MimeTypes.AUDIO_RAW ||
                (inputFormat.pcmEncoding != Format.NO_VALUE &&
                    inputFormat.pcmEncoding != C.ENCODING_INVALID &&
                    Util.isEncodingLinearPcm(inputFormat.pcmEncoding))
        super.configure(audioSinkConfig)
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        val basePosUs = super.getCurrentPositionUs(sourceEnded)
        if (basePosUs == AudioSink.CURRENT_POSITION_NOT_SET) {
            return AudioSink.CURRENT_POSITION_NOT_SET
        }
        val offsetMs = syncController.currentAudioOffsetMs()
        if (offsetMs < 0L) {
            return (basePosUs + offsetMs * 1_000L).coerceAtLeast(0L)
        }
        return basePosUs
    }

    /**
     * Returns `true` when the user has requested a positive audio delay (`audioOffsetMs > 0L`)
     * while the sink is configured for compressed HDMI bitstream passthrough (where Media3
     * bypasses linear PCM [PcmAudioDelayProcessor]).
     */
    fun isPositiveDelayBypassedByPassthrough(): Boolean =
        syncController.currentAudioOffsetMs() > 0L && !isCurrentFormatPcm
}
