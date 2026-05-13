package dev.spatialfin.fcast

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import dev.jdtech.jellyfin.fcast.protocol.AudioFormatInfo

/**
 * Pure helpers that read the audio track ExoPlayer is *actually* decoding and shape it into
 * the [AudioFormatInfo] beacon payload the sender renders.
 *
 * The mapping intentionally lives on the receiver: ExoPlayer is the only piece of code that
 * knows whether the negotiated decoder is `Dolby Atmos` (eac3-joc), plain `Dolby Digital
 * Plus` (eac3), `TrueHD`, etc. A sender-side guess from Jellyfin's `MediaStream.codec` is
 * close but can lie if the receiver downmixes (e.g. Chromecast Default Media Receiver
 * collapses anything past stereo unless the user specifically enabled passthrough).
 */
@OptIn(UnstableApi::class)
internal object AudioFormatProbe {

    /**
     * Read [player]'s currently-selected audio track and convert to [AudioFormatInfo]. Returns
     * null when no audio track is selected yet — the receiver should omit the field from its
     * beacons rather than send a zero-filled payload that the sender would format as
     * "unknown".
     */
    fun probe(player: Player): AudioFormatInfo? {
        val tracks = player.currentTracks
        val format = selectedAudioFormat(player) ?: run {
            timber.log.Timber.tag("AudioFormatProbe").d(
                "no audio track at all — groups=%d (selected video=%b)",
                tracks.groups.size,
                tracks.groups.any { it.type == C.TRACK_TYPE_VIDEO && it.isSelected },
            )
            return null
        }
        val mime = format.sampleMimeType
        val channels = format.channelCount.takeIf { it != Format.NO_VALUE }
        val sampleRate = format.sampleRate.takeIf { it != Format.NO_VALUE }
        val bitrateKbps = listOfNotNull(
            format.bitrate.takeIf { it != Format.NO_VALUE },
            format.peakBitrate.takeIf { it != Format.NO_VALUE },
            format.averageBitrate.takeIf { it != Format.NO_VALUE },
        ).firstOrNull()?.let { it / 1000 }
        // Distinguish "track is selected and will play" from "track exists but the renderer
        // can't decode it" so the mini-controller can flag the unsupported case. Without this
        // signal users see "Audio · Dolby Digital · 5.1" while hearing silence and have no
        // hint that the codec is the problem.
        val isSelected = tracks.groups.any { group ->
            group.type == C.TRACK_TYPE_AUDIO &&
                (0 until group.length).any { group.isTrackSelected(it) }
        }
        val baseLabel = composeLabel(mime, channels)
        val label = when {
            baseLabel == null -> null
            isSelected -> baseLabel
            else -> "$baseLabel · not supported"
        }
        if (!isSelected) {
            timber.log.Timber.tag("AudioFormatProbe").w(
                "audio codec unsupported on this receiver: mime=%s channels=%s rate=%s",
                mime, channels, sampleRate,
            )
        }
        return AudioFormatInfo(
            mimeType = mime,
            channelCount = channels,
            sampleRateHz = sampleRate,
            bitrateKbps = bitrateKbps,
            label = label,
        )
    }

    private fun selectedAudioFormat(player: Player): Format? {
        val tracks = player.currentTracks
        // 1) Try the proper "selected by track selector" path.
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_AUDIO) continue
            for (i in 0 until group.length) {
                if (group.isTrackSelected(i)) {
                    return group.mediaTrackGroup.getFormat(i)
                }
            }
        }
        // 2) Fallback: NO audio track is selected — common when the codec can't be decoded by
        // any registered MediaCodec (e.g. AC-3 on some MediaTek ARMv7 builds, TrueHD anywhere
        // without a Dolby license). The user perceives this as "video plays, no audio". We
        // still want to *show* the codec name so the bug is diagnosable from the mini-
        // controller. Report the first non-selected track and flag it via the label.
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_AUDIO) continue
            if (group.length == 0) continue
            return group.mediaTrackGroup.getFormat(0)
        }
        return null
    }

    /**
     * Pretty-print a codec MIME + channel count into the kind of label a user actually wants
     * to see in the mini-controller — "Dolby Atmos · 7.1", not "audio/eac3-joc 8ch".
     *
     * Visible canonical mapping:
     *   audio/eac3-joc → Dolby Atmos  (E-AC-3 with Joint Object Coding)
     *   audio/eac3     → Dolby Digital Plus
     *   audio/ac3      → Dolby Digital
     *   audio/true-hd  → Dolby TrueHD
     *   audio/dts-hd   → DTS-HD
     *   audio/dts      → DTS
     *   audio/aac      → AAC
     *   audio/mp4a-latm → AAC LATM
     *   audio/opus     → Opus
     *   audio/flac     → FLAC
     *   audio/mpeg     → MP3
     *
     * Channel count → human label: 1 = Mono, 2 = Stereo, 6 = 5.1, 8 = 7.1, else "Nch".
     */
    internal fun composeLabel(mimeType: String?, channelCount: Int?): String? {
        val codec = codecLabel(mimeType) ?: return null
        val channels = channelLabel(channelCount) ?: return codec
        return "$codec · $channels"
    }

    private fun codecLabel(mimeType: String?): String? = when (mimeType) {
        MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Atmos"
        MimeTypes.AUDIO_E_AC3 -> "Dolby Digital Plus"
        MimeTypes.AUDIO_AC3 -> "Dolby Digital"
        MimeTypes.AUDIO_TRUEHD -> "Dolby TrueHD"
        MimeTypes.AUDIO_DTS_HD -> "DTS-HD"
        MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_EXPRESS -> "DTS"
        MimeTypes.AUDIO_AAC -> "AAC"
        MimeTypes.AUDIO_OPUS -> "Opus"
        MimeTypes.AUDIO_FLAC -> "FLAC"
        MimeTypes.AUDIO_MPEG -> "MP3"
        MimeTypes.AUDIO_VORBIS -> "Vorbis"
        MimeTypes.AUDIO_RAW -> "PCM"
        MimeTypes.AUDIO_ALAC -> "ALAC"
        null -> null
        else -> mimeType.substringAfter('/').uppercase()
    }

    private fun channelLabel(channelCount: Int?): String? = when (channelCount) {
        null, 0 -> null
        1 -> "Mono"
        2 -> "Stereo"
        6 -> "5.1"
        7 -> "6.1"
        8 -> "7.1"
        else -> "${channelCount}ch"
    }
}
