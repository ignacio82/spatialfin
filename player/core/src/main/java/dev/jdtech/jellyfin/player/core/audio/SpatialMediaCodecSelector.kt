package dev.jdtech.jellyfin.player.core.audio

import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import timber.log.Timber

/**
 * Custom [MediaCodecSelector] that wraps [MediaCodecSelector.DEFAULT] and filters out
 * broken platform decoders that fail on common audio streams.
 *
 * Specifically, Google Pixel / Tensor devices include a software Dolby decoder
 * (`c2.dolby.eac3.decoder.eac3` / `c2.dolby.eac3.decoder.ac3`) which advertises support for
 * `audio/eac3`, but lacks Dolby Atmos / JOC (Joint Object Coding) support. When encountering a
 * DDP-JOC frame (present in almost all commercial streaming E-AC3 5.1/7.1 releases), the codec
 * reports `ddpdec_process unsupported DDP-JOC frame`, raises `UNKNOWN_ERROR` (0x80000000), and
 * causes ExoPlayer to crash with a fatal `MediaCodecDecoderException`.
 *
 * By excluding `c2.dolby.eac3.decoder.*` when local decoding is required, [MediaCodecAudioRenderer]
 * reports that no platform decoder is available for E-AC3 decoding. This allows
 * [androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer] (bundled via `libffmpegJNI.so`) to seamlessly
 * decode all E-AC3 / Atmos streams into multichannel PCM without crashing.
 *
 * Crucially, when connected to an external AV receiver, soundbar, or TV that supports direct
 * bitstream passthrough, [MediaCodecAudioRenderer.supportsFormat] handles passthrough directly
 * without querying decoder infos, so full hardware bitstreaming / passthrough (Dolby Atmos, TrueHD,
 * DTS) remains completely unaffected and preserved.
 */
@OptIn(UnstableApi::class)
class SpatialMediaCodecSelector(
    private val delegate: MediaCodecSelector = MediaCodecSelector.DEFAULT,
) : MediaCodecSelector {

    companion object : MediaCodecSelector by SpatialMediaCodecSelector()

    override fun getDecoderInfos(
        mimeType: String,
        requiresSecureDecoder: Boolean,
        requiresTunnelingDecoder: Boolean,
    ): List<MediaCodecInfo> {
        val decoders = delegate.getDecoderInfos(
            mimeType,
            requiresSecureDecoder,
            requiresTunnelingDecoder,
        )

        if (mimeType.equals(MimeTypes.AUDIO_E_AC3, ignoreCase = true) ||
            mimeType.equals(MimeTypes.AUDIO_E_AC3_JOC, ignoreCase = true)
        ) {
            val filtered = decoders.filterNot { decoder ->
                decoder.name.startsWith("c2.dolby.eac3.decoder", ignoreCase = true)
            }
            if (filtered.size != decoders.size) {
                Timber.d(
                    "SpatialMediaCodecSelector: filtered out broken software decoder [%s] for %s; delegating to bundled FFmpeg",
                    decoders.filter { it.name.startsWith("c2.dolby.eac3.decoder", ignoreCase = true) }.joinToString { it.name },
                    mimeType,
                )
            }
            return filtered
        }

        return decoders
    }
}
