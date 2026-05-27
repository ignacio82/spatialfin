package dev.jdtech.jellyfin.fcast.receiver

import android.content.Intent
import dev.jdtech.jellyfin.fcast.protocol.PlayMessage
import dev.jdtech.jellyfin.fcast.protocol.SplitAvMetadata
import dev.jdtech.jellyfin.fcast.protocol.splitAv
import java.net.URI
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * A transient media source supplied by an FCast sender. Inline content is restricted to
 * adaptive manifests because arbitrary inline payloads cannot be handed to Media3 safely.
 */
@Serializable
sealed interface ExternalStreamSource {
    @Serializable
    @SerialName("url")
    data class Url(val url: String) : ExternalStreamSource

    @Serializable
    @SerialName("inline")
    data class Inline(val content: String) : ExternalStreamSource
}

/** Normalized inbound FCast media request, independent of the FCast wire envelope. */
@Serializable
data class ExternalStreamRequest(
    val source: ExternalStreamSource,
    val container: String,
    val startPositionSeconds: Double = 0.0,
    val initialVolume: Double? = null,
    val initialSpeed: Double? = null,
    val headers: Map<String, String> = emptyMap(),
    val title: String? = null,
    val thumbnailUrl: String? = null,
    val splitAv: SplitAvMetadata? = null,
    val sourceAudioCodec: String? = null,
    val audioTranscoded: Boolean? = null,
) {
    companion object {
        /**
         * Parse exactly one source from a Play message. A malformed request is rejected before
         * any Activity launch so the connected sender receives an actionable PlaybackError.
         */
        fun parsePlayMessage(msg: PlayMessage): ParseResult {
            val url = msg.url?.trim()?.takeIf(String::isNotEmpty)
            val content = msg.content?.takeIf { it.isNotBlank() }
            val source = when {
                url != null && content != null ->
                    return ParseResult.Invalid("PlayMessage must provide exactly one of url or content, not both")
                url == null && content == null ->
                    return ParseResult.Invalid("PlayMessage must provide a nonblank url or content source")
                url != null -> ExternalStreamSource.Url(url)
                else -> {
                    val failure = validateInlineManifest(msg.container, content!!)
                    if (failure != null) return ParseResult.Invalid(failure)
                    ExternalStreamSource.Inline(content)
                }
            }
            val audioCustom = (msg.metadata?.custom as? JsonObject)
                ?.get("audio") as? JsonObject
            return ParseResult.Valid(
                ExternalStreamRequest(
                    source = source,
                    container = msg.container,
                    startPositionSeconds = msg.time ?: 0.0,
                    initialVolume = msg.volume,
                    initialSpeed = msg.speed,
                    headers = msg.headers.orEmpty(),
                    title = msg.metadata?.title,
                    thumbnailUrl = msg.metadata?.thumbnailUrl,
                    splitAv = msg.splitAv(),
                    sourceAudioCodec = audioCustom?.get("sourceCodec")
                        ?.jsonPrimitive?.contentOrNull,
                    audioTranscoded = audioCustom?.get("transcoded")
                        ?.jsonPrimitive?.booleanOrNull,
                ),
            )
        }

        /** Compatibility helper for consumers that do not need the parsing failure reason. */
        fun fromPlayMessage(msg: PlayMessage): ExternalStreamRequest? =
            (parsePlayMessage(msg) as? ParseResult.Valid)?.request

        private fun validateInlineManifest(container: String, content: String): String? {
            return when (canonicalManifestContainer(container)) {
                INLINE_DASH -> null
                INLINE_HLS -> {
                    val childUris = content.lineSequence()
                        .map(String::trim)
                        .filter { it.isNotEmpty() && !it.startsWith("#") }
                        .plus(
                            Regex("""URI\s*=\s*"([^"]+)"""")
                                .findAll(content)
                                .map { it.groupValues[1] },
                        )
                    if (childUris.any { uri -> runCatching { !URI(uri).isAbsolute }.getOrDefault(true) }) {
                        "Inline HLS manifests require absolute child media URLs"
                    } else {
                        null
                    }
                }
                else -> "Unsupported inline content type '$container'; only DASH and HLS manifests are accepted"
            }
        }

        private fun canonicalManifestContainer(container: String): String = when (container.trim().lowercase()) {
            "application/dash+xml", "application/mpd", "application/x-mpegdash" -> INLINE_DASH
            "application/vnd.apple.mpegurl", "application/x-mpegurl", "application/mpegurl" -> INLINE_HLS
            else -> container.trim().lowercase()
        }

        private const val INLINE_DASH = "application/dash+xml"
        private const val INLINE_HLS = "application/x-mpegurl"
    }

    sealed interface ParseResult {
        data class Valid(val request: ExternalStreamRequest) : ParseResult
        data class Invalid(val reason: String) : ParseResult
    }
}

/**
 * Shared request intent codec for the flat and immersive inbound players. Requests are held only
 * by the active launch Intent; neither implementation persists them. In particular, authentication
 * header values must never be logged.
 */
object ExternalStreamIntentCodec {
    const val EXTRA_REQUEST_JSON: String = "fcast.in.request_json"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        classDiscriminator = "sourceType"
    }

    fun putRequest(intent: Intent, request: ExternalStreamRequest): Intent =
        intent.putExtra(EXTRA_REQUEST_JSON, json.encodeToString(request))

    fun getRequest(intent: Intent): ExternalStreamRequest? =
        intent.getStringExtra(EXTRA_REQUEST_JSON)?.let { encoded ->
            runCatching { json.decodeFromString<ExternalStreamRequest>(encoded) }.getOrNull()
        }
}

/** Player control surface used by both in-process and cross-process inbound receivers. */
interface ExternalStreamPlayer {
    fun play(request: ExternalStreamRequest): PlayResult
    fun pause()
    fun resume()
    fun resumeAt(atReceiverMonotonicMs: Long) = resume()
    fun stop()
    fun seek(seconds: Double)
    fun setVolume(volume: Double)
    fun setSpeed(speed: Double)
    fun setTrack(type: Int, trackId: String)

    sealed interface PlayResult {
        data object Accepted : PlayResult
        data class Rejected(val reason: String) : PlayResult
    }

    object Rejecting : ExternalStreamPlayer {
        override fun play(request: ExternalStreamRequest): PlayResult =
            PlayResult.Rejected("External streams are not supported on this device")
        override fun pause() = Unit
        override fun resume() = Unit
        override fun stop() = Unit
        override fun seek(seconds: Double) = Unit
        override fun setVolume(volume: Double) = Unit
        override fun setSpeed(speed: Double) = Unit
        override fun setTrack(type: Int, trackId: String) = Unit
    }
}

/** Adapter that turns an [ExternalStreamPlayer] into the receiver server's router. */
class ExternalStreamIngressRouter(
    private val player: ExternalStreamPlayer,
) : FCastIngressRouter {
    override fun onPlay(request: PlayMessage): FCastIngressRouter.IngressResult {
        val external = when (val parsed = ExternalStreamRequest.parsePlayMessage(request)) {
            is ExternalStreamRequest.ParseResult.Invalid ->
                return FCastIngressRouter.IngressResult.Rejected(parsed.reason)
            is ExternalStreamRequest.ParseResult.Valid -> parsed.request
        }
        return when (val result = player.play(external)) {
            ExternalStreamPlayer.PlayResult.Accepted -> FCastIngressRouter.IngressResult.Accepted
            is ExternalStreamPlayer.PlayResult.Rejected -> FCastIngressRouter.IngressResult.Rejected(result.reason)
        }
    }

    override fun onPause() = player.pause()
    override fun onResume() = player.resume()
    override fun onResumeAt(atReceiverMonotonicMs: Long) = player.resumeAt(atReceiverMonotonicMs)
    override fun onStop() = player.stop()
    override fun onSeek(seconds: Double) = player.seek(seconds)
    override fun onSetVolume(volume: Double) = player.setVolume(volume)
    override fun onSetSpeed(speed: Double) = player.setSpeed(speed)
    override fun onSetTrack(type: Int, trackId: String) = player.setTrack(type, trackId)
}
