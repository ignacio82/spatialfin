package dev.jdtech.jellyfin.fcast.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Split-A/V mode metadata, carried inside [MetadataObject.custom] under a `splitAv` key so it
 * coexists with other app-specific custom payloads. Older receivers ignore it (the v3 spec
 * mandates "tolerate unknown fields"), so a sender can opt in without breaking anything.
 *
 * Wire shape:
 * ```
 * "metadata": {
 *   "custom": {
 *     "splitAv": { "role": "audio", "version": 1, "syncCadenceHz": 10 }
 *   }
 * }
 * ```
 */
@Serializable
data class SplitAvMetadata(
    val role: SplitAvRole,
    val version: Int = SCHEMA_VERSION,
    /**
     * Suggested cadence for the receiver's [PlaybackUpdateMessage] emissions. The receiver MAY
     * use this as a hint; the canonical default for split mode is 10 Hz so the sender's drift
     * correction has enough beacons per second.
     */
    val syncCadenceHz: Int? = null,
) {
    companion object {
        const val SCHEMA_VERSION: Int = 1

        /** Default beacon cadence when the receiver enters split mode. */
        const val DEFAULT_SYNC_CADENCE_HZ: Int = 10
    }
}

@Serializable
enum class SplitAvRole {
    @SerialName("audio") AUDIO,
    @SerialName("video") VIDEO,
}

/**
 * Read [SplitAvMetadata] out of a [PlayMessage]'s metadata.custom envelope. Returns null when
 * the message has no metadata, no custom payload, no `splitAv` key, or the payload is malformed.
 * Tolerates extra sibling keys in `custom` (other app features may write their own).
 */
fun PlayMessage.splitAv(): SplitAvMetadata? {
    val custom = metadata?.custom ?: return null
    val obj = custom as? JsonObject ?: return null
    val node = obj["splitAv"] ?: return null
    return runCatching { SplitAvJson.decodeFromJsonElement(SplitAvMetadata.serializer(), node) }
        .getOrNull()
}

/**
 * Return a copy of [PlayMessage] with the given [splitAv] payload merged into metadata.custom.
 * Preserves any other keys already present in `custom` so multiple app features can layer
 * their own metadata on the same message.
 */
fun PlayMessage.withSplitAv(splitAv: SplitAvMetadata): PlayMessage {
    val splitAvNode = SplitAvJson.encodeToJsonElement(SplitAvMetadata.serializer(), splitAv)
    val existingCustom = metadata?.custom as? JsonObject
    val mergedCustom: JsonObject = buildJsonObject {
        existingCustom?.forEach { (k, v) -> if (k != "splitAv") put(k, v) }
        put("splitAv", splitAvNode)
    }
    val mergedMetadata = (metadata ?: MetadataObject()).copy(custom = mergedCustom)
    return copy(metadata = mergedMetadata)
}

/**
 * Local Json instance — the protocol module's [FCastJson] is internal-visible. Mirrors the
 * relevant config (`ignoreUnknownKeys`, `encodeDefaults = false`) so wire output matches.
 */
private val SplitAvJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
}
