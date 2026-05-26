package dev.jdtech.jellyfin.fcast.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * JSON payload schemas for FCast messages. Field optionality follows the v3 spec — peers MUST
 * tolerate unknown fields, so we serialize with `ignoreUnknownKeys = true` (see [FCastJson]).
 *
 * Numeric times are seconds (Double); volumes are 0..1.
 */

@Serializable
data class PlayMessage(
    val container: String,
    val url: String? = null,
    val content: String? = null,
    val time: Double? = null,
    val volume: Double? = null,
    val speed: Double? = null,
    val headers: Map<String, String>? = null,
    val metadata: MetadataObject? = null,
)

@Serializable
data class MetadataObject(
    val type: Int? = null,
    val title: String? = null,
    @SerialName("image") val thumbnailUrl: String? = null,
    @SerialName("custom") val custom: JsonElement? = null,
)

@Serializable
data class SeekMessage(val time: Double)

@Serializable
data class PlaybackUpdateMessage(
    val generationTime: Long,
    val state: Int,
    val time: Double? = null,
    val duration: Double? = null,
    val speed: Double? = null,
    val itemIndex: Int? = null,
    /**
     * SpatialFin extension (not in the FCast v3 spec). Receiver populates with the audio
     * format ExoPlayer is actually decoding. Non-SpatialFin senders/receivers ignore via
     * `ignoreUnknownKeys = true`. Null = receiver hasn't probed yet or non-SpatialFin peer.
     */
    val audioFormat: AudioFormatInfo? = null,
    /**
     * SpatialFin v4 extension. Receiver's monotonic clock (`SystemClock.elapsedRealtime`) at
     * the instant [time] was sampled — the *same* clock used for the Ping/Pong NTP timestamps,
     * so the sender can map this beacon onto its own clock precisely via the estimated offset
     * θ instead of the `RTT/2` symmetry guess. Null for pre-v4 / non-SpatialFin receivers.
     */
    val monotonicSampleMs: Long? = null,
    /**
     * SpatialFin extension. The receiver's *authoritative* set of audio codecs it can
     * actually render — software-decodable codecs plus whatever the attached HDMI / SPDIF
     * chain advertises for passthrough (`AudioCapabilities.supportsEncoding`). Lowercase
     * Jellyfin codec tokens (`ac3`, `eac3`, `truehd`, `dts`, `dts-hd`, `aac`, …). The sender
     * uses this — never a hardcoded codec table — to decide split-A/V direct-stream vs
     * server transcode, so the same build is correct on a TrueHD-capable AVR, a DD+/Atmos
     * soundbar, and a PCM-only TV alike. Null for pre-extension / non-SpatialFin receivers
     * (sender then falls back to a conservative default and self-corrects on the first
     * beacon that carries this).
     */
    val supportedAudioCodecs: List<String>? = null,
) {
    val playbackState: PlaybackState? get() = PlaybackState.fromCode(state)
}

/**
 * SpatialFin v4 extension — optional Ping body. Empty/absent body ⇒ a legacy body-less Ping
 * (byte-identical to v2/v3). Only emitted when the negotiated protocol version ≥ 4.
 */
@Serializable
data class PingMessage(
    /** Sender monotonic clock (`SystemClock.elapsedRealtime`) at Ping send (NTP t1). */
    val t1: Long,
)

/**
 * SpatialFin v4 extension — optional Pong body echoing the NTP four-timestamp set. The
 * receiver copies [t1] back unchanged and adds its own monotonic receive/send stamps so the
 * sender can solve clock offset θ and round-trip delay δ.
 */
@Serializable
data class PongMessage(
    /** Echo of [PingMessage.t1]. */
    val t1: Long,
    /** Receiver monotonic clock when the Ping was read (NTP t2). */
    val t2: Long,
    /** Receiver monotonic clock when this Pong was written (NTP t3). */
    val t3: Long,
)

/**
 * SpatialFin v4 extension — optional Resume body requesting a *synchronized* start. Empty/
 * absent body ⇒ a legacy resume-now. Used to start XR video and remote audio at the same wall
 * instant, killing the multi-second initial split-A/V gap.
 */
@Serializable
data class ResumeMessage(
    /** Receiver monotonic clock instant at which to begin playback. If already past, or
     *  further out than the receiver's sanity cap, the receiver resumes immediately. */
    val atReceiverMonotonicMs: Long,
)

/**
 * SpatialFin-only beacon extension describing what ExoPlayer's actually decoding right now.
 * Atmos detection: `mimeType == "audio/eac3-joc"` is the Joint Object Coding MIME that signals
 * Dolby Atmos in an E-AC-3 bitstream. TrueHD Atmos has no dedicated MIME — sender infers from
 * source metadata as a fallback when it sees `audio/true-hd` here.
 */
@Serializable
data class AudioFormatInfo(
    /** Media3 `Format.sampleMimeType` (e.g. `audio/eac3-joc`, `audio/aac`, `audio/true-hd`). */
    val mimeType: String? = null,
    /** Decoded channel count (1=mono, 2=stereo, 6=5.1, 8=7.1). */
    val channelCount: Int? = null,
    /** Decoded sample rate in Hz (typical: 44_100, 48_000, 96_000). */
    val sampleRateHz: Int? = null,
    /** Average bitrate in kbps if known; null when the source doesn't expose it. */
    val bitrateKbps: Int? = null,
    /** Receiver-side pretty-printed label (e.g. `Dolby Atmos · 7.1`); fallback for the UI. */
    val label: String? = null,
)

enum class PlaybackState(val code: Int) {
    Idle(0),
    Playing(1),
    Paused(2),
    ;

    companion object {
        fun fromCode(code: Int): PlaybackState? = entries.firstOrNull { it.code == code }
    }
}

@Serializable
data class VolumeUpdateMessage(
    val generationTime: Long,
    val volume: Double,
)

@Serializable
data class SetVolumeMessage(val volume: Double)

@Serializable
data class SetSpeedMessage(val speed: Double)

@Serializable
data class PlaybackErrorMessage(val message: String)

@Serializable
data class VersionMessage(val version: Int)

@Serializable
data class InitialSenderMessage(
    @SerialName("friendlyName") val displayName: String? = null,
    val appName: String? = null,
    val appVersion: String? = null,
)

@Serializable
data class InitialReceiverMessage(
    @SerialName("friendlyName") val displayName: String? = null,
    val appName: String? = null,
    val appVersion: String? = null,
    val playData: PlayMessage? = null,
)

@Serializable
data class PlayUpdateMessage(
    val generationTime: Long,
    val playData: PlayMessage? = null,
)

@Serializable
data class SetPlaylistItemMessage(val itemIndex: Int)

@Serializable
data class SubscribeEventMessage(val event: EventSubscribeObject)

@Serializable
data class UnsubscribeEventMessage(val event: EventSubscribeObject)

@Serializable
data class EventSubscribeObject(
    val type: Int,
    val key: String? = null,
)

@Serializable
data class EventMessage(
    val generationTime: Long,
    val event: EventObject,
)

@Serializable
data class EventObject(
    val type: Int,
    val key: String? = null,
    val itemIndex: Int? = null,
)

enum class FCastEventType(val code: Int) {
    MediaItemStart(0),
    MediaItemEnd(1),
    MediaItemChange(2),
    KeyDown(3),
    KeyUp(4),
    ;

    companion object {
        fun fromCode(code: Int): FCastEventType? = entries.firstOrNull { it.code == code }
    }
}

@Serializable
data class SpatialFinTrack(
    val id: String,
    val name: String,
    val language: String? = null,
    val isSelected: Boolean = false,
)

@Serializable
data class SpatialFinTracksUpdateMessage(
    val audioTracks: List<SpatialFinTrack> = emptyList(),
    val subtitleTracks: List<SpatialFinTrack> = emptyList(),
)

@Serializable
data class SpatialFinSetTrackMessage(
    val type: Int, // e.g. 1 for audio, 3 for text
    val trackId: String,
)
