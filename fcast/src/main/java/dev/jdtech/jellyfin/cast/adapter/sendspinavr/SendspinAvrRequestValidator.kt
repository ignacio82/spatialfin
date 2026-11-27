package dev.jdtech.jellyfin.cast.adapter.sendspinavr

import java.net.URI
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Pure-Kotlin client-side outbound request & URL safety validator for Spec 0 Theater Audio v1.
 * Mirrors AVR-side SSRF, header-injection, source-eligibility, protocol-version, and gain-safety
 * guards before dispatching traffic over WebSocket or HTTP.
 */
object SendspinAvrRequestValidator {

    sealed interface ValidationResult {
        data object Valid : ValidationResult
        data class Invalid(val errorCode: String, val reason: String) : ValidationResult
    }

    const val MIN_CHIME_GAIN_DBFS: Double = -30.0
    const val MAX_CALIBRATION_GAIN_DBFS: Double = -12.0
    const val MIN_CHIME_DURATION_MS: Int = 200
    const val MAX_CHIME_DURATION_MS: Int = 5_000

    private val FORBIDDEN_QUERY_CREDENTIAL_KEYS: Set<String> = setOf(
        "apikey",
        "api_key",
        "x-emby-token",
        "x-mediabrowser-token",
        "token",
        "authorization",
    )

    private val JELLYFIN_STREAM_PATH_PREFIXES: List<String> = listOf(
        "/audio/",
        "/videos/",
        "/items/",
    )

    /** Validates a raw WebSocket envelope JSON object for `v == 1` and payload boundary hygiene. */
    fun validateWsEnvelopeJson(rawJson: String): ValidationResult {
        val root = runCatching { SendspinAvrJson.codec.parseToJsonElement(rawJson).jsonObject }
            .getOrElse { return ValidationResult.Invalid("invalid_request", "Malformed JSON") }
        val v = root["v"]?.jsonPrimitive?.intOrNull
        if (v != TheaterAudioProtocol.VERSION) {
            return ValidationResult.Invalid("protocol_version_unsupported", "Unsupported envelope v=$v")
        }
        val payloadObj = root["payload"] as? JsonObject
            ?: return ValidationResult.Invalid("invalid_request", "Missing WS payload object")
        if ("session_id" in payloadObj) {
            return ValidationResult.Invalid("invalid_request", "session_id forbidden inside WS payload")
        }
        return ValidationResult.Valid
    }

    fun validateHello(payload: HelloRequestPayload): ValidationResult {
        if (hasControlChars(payload.clientId) || hasControlChars(payload.clientName) ||
            hasControlChars(payload.formFactor) || hasControlChars(payload.appVersion) ||
            payload.clientId.isBlank() || payload.clientName.isBlank()
        ) {
            return ValidationResult.Invalid("invalid_request", "Invalid hello metadata")
        }
        if (payload.minProtocolVersion < 1 ||
            payload.maxProtocolVersion < payload.minProtocolVersion ||
            TheaterAudioProtocol.VERSION !in payload.minProtocolVersion..payload.maxProtocolVersion
        ) {
            return ValidationResult.Invalid(
                "protocol_version_unsupported",
                "Protocol range ${payload.minProtocolVersion}..${payload.maxProtocolVersion} excludes v${TheaterAudioProtocol.VERSION}",
            )
        }
        return ValidationResult.Valid
    }

    fun validateSessionStart(
        payload: SessionStartPayload,
        allowedHost: String? = null,
    ): ValidationResult {
        if (payload.sourceType != TheaterAudioProtocol.VALID_SOURCE_TYPE) {
            return ValidationResult.Invalid(
                "source_ineligible",
                "Only source_type='${TheaterAudioProtocol.VALID_SOURCE_TYPE}' is eligible in v1 (got '${payload.sourceType}')",
            )
        }
        if (payload.streamLayout !in TheaterAudioProtocol.VALID_STREAM_LAYOUTS) {
            return ValidationResult.Invalid("invalid_request", "Invalid stream_layout: ${payload.streamLayout}")
        }
        validateToken(payload.jellyfinToken, "jellyfin_token").let { if (it !is ValidationResult.Valid) return it }
        validateStreamUrl(payload.streamUrl, allowedHost).let { if (it !is ValidationResult.Valid) return it }
        if (payload.audioStreamIndex < 0 || payload.targetPtsUs < 0L || payload.mediaSourceId.isBlank()) {
            return ValidationResult.Invalid("invalid_request", "Invalid stream indices or target_pts_us")
        }
        return ValidationResult.Valid
    }

    fun validatePlaybackLoad(
        payload: PlaybackLoadPayload,
        allowedHost: String? = null,
    ): ValidationResult {
        if (payload.barrierSeq < 0L || payload.audioStreamIndex < 0 || payload.targetPtsUs < 0L) {
            return ValidationResult.Invalid("invalid_request", "Invalid barrier or stream indices")
        }
        payload.streamLayout?.let { layout ->
            if (layout !in TheaterAudioProtocol.VALID_STREAM_LAYOUTS) {
                return ValidationResult.Invalid("invalid_request", "Invalid stream_layout: $layout")
            }
        }
        payload.jellyfinToken?.let { token ->
            validateToken(token, "jellyfin_token").let { if (it !is ValidationResult.Valid) return it }
        }
        payload.streamUrl?.let { url ->
            validateStreamUrl(url, allowedHost).let { if (it !is ValidationResult.Valid) return it }
        }
        return ValidationResult.Valid
    }

    fun validateCalibrationChime(payload: CalibrationChimeRequest): ValidationResult {
        if (payload.role.isBlank() || payload.signal.isBlank()) {
            return ValidationResult.Invalid("invalid_request", "Blank role or signal")
        }
        if (!payload.gainDbfs.isFinite() ||
            payload.gainDbfs !in MIN_CHIME_GAIN_DBFS..MAX_CALIBRATION_GAIN_DBFS
        ) {
            return ValidationResult.Invalid(
                "invalid_request",
                "Chime gain ${payload.gainDbfs} dBFS outside safe bounds [$MIN_CHIME_GAIN_DBFS, $MAX_CALIBRATION_GAIN_DBFS]",
            )
        }
        if (payload.durationMs !in MIN_CHIME_DURATION_MS..MAX_CHIME_DURATION_MS) {
            return ValidationResult.Invalid("invalid_request", "Chime duration out of range")
        }
        return ValidationResult.Valid
    }

    fun validateToken(token: String, fieldName: String): ValidationResult {
        if (token.isBlank() || hasControlChars(token)) {
            return ValidationResult.Invalid("invalid_request", "Invalid or unsafe characters in $fieldName")
        }
        return ValidationResult.Valid
    }

    fun validateStreamUrl(streamUrl: String, allowedHost: String? = null): ValidationResult {
        if (streamUrl.isBlank() || hasControlChars(streamUrl)) {
            return ValidationResult.Invalid("url_rejected", "Blank or control-character stream URL")
        }
        val uri = runCatching { URI(streamUrl) }
            .getOrElse { return ValidationResult.Invalid("url_rejected", "Unparseable URI") }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return ValidationResult.Invalid("url_rejected", "Unsupported URI scheme: $scheme")
        }
        if (!uri.rawUserInfo.isNullOrEmpty()) {
            return ValidationResult.Invalid("url_rejected", "URI userinfo credentials rejected")
        }
        val host = uri.host?.lowercase()?.trim('[', ']')
            ?: return ValidationResult.Invalid("url_rejected", "Missing URI host")
        if (allowedHost != null && !host.equals(allowedHost.trim('[', ']'), ignoreCase = true)) {
            return ValidationResult.Invalid("url_rejected", "Stream host does not match paired Jellyfin host")
        }
        if (isDisallowedHost(host)) {
            return ValidationResult.Invalid("url_rejected", "Disallowed link-local/metadata/unspecified host: $host")
        }
        val pathLower = uri.rawPath.orEmpty().lowercase()
        if (JELLYFIN_STREAM_PATH_PREFIXES.none { prefix -> pathLower.startsWith(prefix) }) {
            return ValidationResult.Invalid("url_rejected", "Non-Jellyfin stream path: ${uri.rawPath}")
        }
        val queryParts = uri.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }
        for (part in queryParts) {
            val key = part.substringBefore('=').lowercase()
            if (key in FORBIDDEN_QUERY_CREDENTIAL_KEYS) {
                return ValidationResult.Invalid("url_rejected", "Query credential parameter '$key' rejected")
            }
        }
        if (!uri.rawFragment.isNullOrEmpty()) {
            val fragmentLower = uri.rawFragment.lowercase()
            if (FORBIDDEN_QUERY_CREDENTIAL_KEYS.any { fragmentLower.contains("$it=") }) {
                return ValidationResult.Invalid("url_rejected", "Fragment credential parameter rejected")
            }
        }
        return ValidationResult.Valid
    }

    private fun isDisallowedHost(host: String): Boolean =
        host == "0.0.0.0" ||
            host == "::" ||
            host == "169.254.169.254" ||
            host.startsWith("169.254.") ||
            host.startsWith("fe80:")

    private fun hasControlChars(value: String): Boolean =
        value.any { ch -> ch == '\r' || ch == '\n' || ch == '\u0000' || ch.code < 0x20 || ch.code == 0x7F }
}
