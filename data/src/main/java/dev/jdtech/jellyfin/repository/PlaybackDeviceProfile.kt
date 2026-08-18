package dev.jdtech.jellyfin.repository

import android.media.MediaCodecList
import org.jellyfin.sdk.model.api.DeviceProfile
import org.jellyfin.sdk.model.api.DirectPlayProfile
import org.jellyfin.sdk.model.api.DlnaProfileType
import org.jellyfin.sdk.model.api.EncodingContext
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.TranscodeSeekInfo
import org.jellyfin.sdk.model.api.TranscodingProfile
import timber.log.Timber

/**
 * Detects supported hardware and software codecs on the Android device
 * via [MediaCodecList] combined with Media3 (ExoPlayer) built-in software decoders.
 */
object AndroidCodecDetector {

    // Standard video containers supported by ExoPlayer's extractors.
    const val DIRECT_PLAY_VIDEO_CONTAINERS =
        "mkv,mp4,m4v,mov,webm,ts,wmv,asf,avi,flv,3gp,3g2,ogv,ogg"

    // Standard audio containers supported by ExoPlayer's extractors.
    const val DIRECT_PLAY_AUDIO_CONTAINERS =
        "mp3,aac,m4a,flac,ogg,oga,opus,wav,wma,alac,aiff,pcm,ac3,eac3,dts"

    // Software audio codecs that Media3 (ExoPlayer) decodes natively on all Android versions.
    internal val BASELINE_AUDIO_CODECS = setOf(
        "aac", "mp3", "opus", "flac", "vorbis", "pcm", "wav",
        "pcm_s16le", "pcm_s24le", "pcm_s32le", "alac", "ac3",
    )

    // Baseline video codecs supported on virtually all modern Android devices.
    internal val BASELINE_VIDEO_CODECS = setOf(
        "h264", "avc", "hevc", "h265", "vp9", "av1", "vp8",
    )

    fun getSupportedVideoCodecs(): Set<String> {
        val detected = mutableSetOf<String>()
        runCatching {
            val codecInfos = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            for (info in codecInfos) {
                if (info.isEncoder) continue
                for (type in info.supportedTypes) {
                    when (type.lowercase()) {
                        "video/avc" -> detected.addAll(listOf("h264", "avc"))
                        "video/hevc" -> detected.addAll(listOf("hevc", "h265"))
                        "video/x-vnd.on2.vp9" -> detected.add("vp9")
                        "video/av01" -> detected.add("av1")
                        "video/x-vnd.on2.vp8" -> detected.add("vp8")
                        "video/mp4v-es" -> detected.addAll(listOf("mpeg4", "mp4v-es"))
                        "video/mpeg2" -> detected.addAll(listOf("mpeg2video", "mpeg2"))
                        "video/3gpp" -> detected.add("h263")
                        "video/wvc1" -> detected.addAll(listOf("vc1", "wvc1", "wmv3"))
                        "video/wmv3" -> detected.addAll(listOf("wmv3", "vc1"))
                        "video/dolby-vision" -> detected.addAll(listOf("dvhe", "dvh1", "dva1", "dvav"))
                    }
                }
            }
        }.onFailure { e ->
            Timber.w(e, "AndroidCodecDetector: failed to query MediaCodecList for video codecs; falling back to baseline")
        }
        return if (detected.isNotEmpty()) detected else BASELINE_VIDEO_CODECS
    }

    fun getSupportedAudioCodecs(): Set<String> {
        val detected = BASELINE_AUDIO_CODECS.toMutableSet()
        runCatching {
            val codecInfos = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            for (info in codecInfos) {
                if (info.isEncoder) continue
                for (type in info.supportedTypes) {
                    when (type.lowercase()) {
                        "audio/mp4a-latm" -> detected.add("aac")
                        "audio/mpeg" -> detected.add("mp3")
                        "audio/opus" -> detected.add("opus")
                        "audio/flac" -> detected.add("flac")
                        "audio/vorbis" -> detected.add("vorbis")
                        "audio/raw" -> detected.addAll(listOf("pcm", "wav", "pcm_s16le", "pcm_s24le", "pcm_s32le"))
                        "audio/ac3" -> detected.add("ac3")
                        "audio/vnd.dts" -> detected.addAll(listOf("dts", "dca"))
                        "audio/vnd.dts.hd" -> detected.addAll(listOf("dts-hd", "dtshd"))
                        "audio/true-hd" -> detected.add("truehd")
                        "audio/alac" -> detected.add("alac")
                    }
                }
            }
        }.onFailure { e ->
            Timber.w(e, "AndroidCodecDetector: failed to query MediaCodecList for audio codecs; using baseline")
        }
        return detected
    }
}

/**
 * Builds a capability-aware [DeviceProfile] for Jellyfin playback.
 *
 * Advertises the specific video and audio codecs supported by the device's hardware/software
 * decoders so that Jellyfin can direct-play compatible media while automatically transcoding
 * unsupported codecs (e.g. VC-1, WMV, ProRes, EAC3-JOC Atmos, or audio formats without decoders).
 */
internal fun createPlaybackDeviceProfile(
    bitrate: Long,
    forceDirectPlay: Boolean = false,
): DeviceProfile {
    val maxBitrateInt = bitrate.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    val directPlayProfiles = if (forceDirectPlay) {
        listOf(
            DirectPlayProfile(
                container = "",
                type = DlnaProfileType.VIDEO,
            ),
            DirectPlayProfile(
                container = "",
                type = DlnaProfileType.AUDIO,
            ),
        )
    } else {
        val videoCodecs = AndroidCodecDetector.getSupportedVideoCodecs().joinToString(",")
        val audioCodecs = AndroidCodecDetector.getSupportedAudioCodecs().joinToString(",")

        listOf(
            DirectPlayProfile(
                container = AndroidCodecDetector.DIRECT_PLAY_VIDEO_CONTAINERS,
                type = DlnaProfileType.VIDEO,
                videoCodec = videoCodecs,
                audioCodec = audioCodecs,
            ),
            DirectPlayProfile(
                container = "",
                type = DlnaProfileType.VIDEO,
                videoCodec = videoCodecs,
                audioCodec = audioCodecs,
            ),
            DirectPlayProfile(
                container = AndroidCodecDetector.DIRECT_PLAY_AUDIO_CONTAINERS,
                type = DlnaProfileType.AUDIO,
                audioCodec = audioCodecs,
            ),
            DirectPlayProfile(
                container = "",
                type = DlnaProfileType.AUDIO,
                audioCodec = audioCodecs,
            ),
        )
    }

    val transcodingProfiles = listOf(
        // HLS video transcoding profile: used when Jellyfin decides the source cannot be direct-played
        // (unsupported video/audio codec or bitrate exceeds cap)
        TranscodingProfile(
            container = "ts",
            type = DlnaProfileType.VIDEO,
            videoCodec = "h264,hevc",
            audioCodec = "aac,mp3,opus",
            protocol = MediaStreamProtocol.HLS,
            estimateContentLength = false,
            enableMpegtsM2TsMode = false,
            transcodeSeekInfo = TranscodeSeekInfo.AUTO,
            copyTimestamps = false,
            context = EncodingContext.STREAMING,
            enableSubtitlesInManifest = false,
            maxAudioChannels = null,
            minSegments = 0,
            segmentLength = 0,
            breakOnNonKeyFrames = false,
            conditions = emptyList(),
            enableAudioVbrEncoding = true,
        ),
        // HTTP audio transcoding profile
        TranscodingProfile(
            container = "mp3",
            type = DlnaProfileType.AUDIO,
            videoCodec = "",
            audioCodec = "mp3",
            protocol = MediaStreamProtocol.HTTP,
            estimateContentLength = false,
            enableMpegtsM2TsMode = false,
            transcodeSeekInfo = TranscodeSeekInfo.AUTO,
            copyTimestamps = false,
            context = EncodingContext.STREAMING,
            enableSubtitlesInManifest = false,
            maxAudioChannels = null,
            minSegments = 0,
            segmentLength = 0,
            breakOnNonKeyFrames = false,
            conditions = emptyList(),
            enableAudioVbrEncoding = true,
        ),
        // HLS audio transcoding profile
        TranscodingProfile(
            container = "aac",
            type = DlnaProfileType.AUDIO,
            videoCodec = "",
            audioCodec = "aac",
            protocol = MediaStreamProtocol.HLS,
            estimateContentLength = false,
            enableMpegtsM2TsMode = false,
            transcodeSeekInfo = TranscodeSeekInfo.AUTO,
            copyTimestamps = false,
            context = EncodingContext.STREAMING,
            enableSubtitlesInManifest = false,
            maxAudioChannels = null,
            minSegments = 0,
            segmentLength = 0,
            breakOnNonKeyFrames = false,
            conditions = emptyList(),
            enableAudioVbrEncoding = true,
        ),
    )

    return DeviceProfile(
        name = "SpatialFin Android",
        maxStaticBitrate = maxBitrateInt,
        maxStreamingBitrate = maxBitrateInt,
        codecProfiles = emptyList(),
        containerProfiles = emptyList(),
        directPlayProfiles = directPlayProfiles,
        transcodingProfiles = transcodingProfiles,
        subtitleProfiles = createPlaybackSubtitleProfiles(),
    )
}
