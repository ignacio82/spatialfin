package dev.jdtech.jellyfin.cast.adapter.googlecast

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * JSON payloads carried inside the `payload_utf8` field of [CastMessage]s on each Cast V2
 * namespace. Receivers tolerate unknown fields per the Chromium spec so we deserialize with
 * `ignoreUnknownKeys = true`. Encoder skips defaults to keep frames small.
 */
internal val CastJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    isLenient = false
}

// --- connection namespace ---

@Serializable
internal data class ConnectionMessage(val type: String)

// --- heartbeat namespace ---

@Serializable
internal data class HeartbeatMessage(val type: String)

// --- receiver namespace ---

@Serializable
internal data class LaunchRequest(
    val type: String = "LAUNCH",
    val requestId: Int,
    val appId: String,
)

@Serializable
internal data class StopRequest(
    val type: String = "STOP",
    val requestId: Int,
    val sessionId: String? = null,
)

@Serializable
internal data class GetStatusRequest(
    val type: String = "GET_STATUS",
    val requestId: Int,
)

/**
 * Subset of `RECEIVER_STATUS` we actually read. The receiver sends a much larger blob; the
 * `ignoreUnknownKeys` setting handles the rest.
 */
@Serializable
internal data class ReceiverStatus(
    val type: String,
    val requestId: Int = 0,
    val status: ReceiverStatusBody = ReceiverStatusBody(),
)

@Serializable
internal data class ReceiverStatusBody(
    val applications: List<ReceiverApplication> = emptyList(),
    val volume: ReceiverVolume = ReceiverVolume(),
)

@Serializable
internal data class ReceiverApplication(
    val appId: String,
    val sessionId: String,
    /** Address of the running app — destination for subsequent media-namespace messages. */
    val transportId: String,
    val displayName: String? = null,
    val statusText: String? = null,
)

@Serializable
internal data class ReceiverVolume(
    val level: Float = 1f,
    val muted: Boolean = false,
)

// --- media namespace ---

@Serializable
internal data class MediaInfo(
    val contentId: String,
    val contentType: String,
    /** `BUFFERED` for VOD, `LIVE` for streams. Receiver-defaults to BUFFERED. */
    val streamType: String = "BUFFERED",
    val metadata: MediaMetadata? = null,
    val duration: Double? = null,
)

@Serializable
internal data class MediaMetadata(
    /** 0=generic, 1=movie, 2=tv show, 3=music, 4=photo. We send 0 (generic) for everything. */
    val metadataType: Int = 0,
    val title: String? = null,
    val subtitle: String? = null,
    val images: List<MediaImage> = emptyList(),
)

@Serializable
internal data class MediaImage(val url: String)

/**
 * `LOAD` on the media namespace. `autoplay = true` makes the receiver start as soon as it has
 * enough buffer.
 */
@Serializable
internal data class LoadRequest(
    val type: String = "LOAD",
    val requestId: Int,
    val media: MediaInfo,
    val autoplay: Boolean = true,
    /** Seconds; receiver seeks here before starting playback. */
    val currentTime: Double = 0.0,
)

@Serializable
internal data class MediaCommand(
    val type: String,
    val requestId: Int,
    val mediaSessionId: Int,
)

@Serializable
internal data class SeekRequest(
    val type: String = "SEEK",
    val requestId: Int,
    val mediaSessionId: Int,
    val currentTime: Double,
    val resumeState: String = "PLAYBACK_START",
)

@Serializable
internal data class SetReceiverVolumeRequest(
    val type: String = "SET_VOLUME",
    val requestId: Int,
    val volume: ReceiverVolume,
)

/**
 * Subset of `MEDIA_STATUS`. The receiver sends an envelope with a `status` array; in normal
 * playback that array has one entry. We deserialize only the fields we react to.
 */
@Serializable
internal data class MediaStatusEnvelope(
    val type: String,
    val requestId: Int = 0,
    val status: List<MediaStatusItem> = emptyList(),
)

@Serializable
internal data class MediaStatusItem(
    val mediaSessionId: Int,
    val playerState: String = "IDLE",
    val currentTime: Double = 0.0,
    val playbackRate: Float = 1f,
    val media: MediaInfo? = null,
    /** Only present on the first status after LOAD; receiver omits on subsequent updates. */
    val idleReason: String? = null,
)

/**
 * Some Cast frames have a `type` field but live outside the typed wrappers above (e.g. error
 * envelopes). Decoding via [JsonObject] preserves the rest for logging.
 */
internal fun JsonElement.typeField(): String? =
    (this as? JsonObject)?.get("type")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }

/**
 * Build the bare-bones `{"type":"PONG"}` heartbeat reply. Inlined here to avoid allocating
 * a `HeartbeatMessage` instance on every PING — heartbeats fire every ~5s for the lifetime of
 * the session and the receiver doesn't care about extra fields.
 */
internal fun buildPong(): String = buildJsonObject { put("type", "PONG") }.toString()
