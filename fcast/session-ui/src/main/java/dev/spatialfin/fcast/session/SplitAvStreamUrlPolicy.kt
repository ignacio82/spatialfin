package dev.spatialfin.fcast.session

import dev.jdtech.jellyfin.cast.adapter.sendspinavr.SendspinAvrRequestValidator
import java.net.URI

/**
 * Small, testable rules for split-A/V receiver stream timelines and Spec 0 §3 URL hygiene.
 *
 * Jellyfin HLS transcodes are generated from the requested PlaybackInfo start offset, so the
 * receiver's stream timeline starts at 0 while the user-visible media timeline starts at the
 * requested resume position. Raw direct streams keep the original media timeline.
 */
internal object SplitAvStreamUrlPolicy {
    private val CREDENTIAL_QUERY_KEYS = setOf(
        "api_key",
        "apikey",
        "token",
        "x-emby-token",
        "x-mediabrowser-token",
        "authorization",
    )

    fun isJellyfinHlsUrl(url: String): Boolean =
        url.contains(".m3u8", ignoreCase = true) || url.contains("/hls", ignoreCase = true)

    fun receiverMediaStartOffsetMs(streamUrl: String, requestedStartMs: Long): Long =
        if (isJellyfinHlsUrl(streamUrl)) requestedStartMs.coerceAtLeast(0L) else 0L

    /**
     * Strips forbidden credential query parameters (`api_key`, `ApiKey`, `token`,
     * `X-Emby-Token`, `Authorization`) and validates the resulting URL with
     * [SendspinAvrRequestValidator.validateStreamUrl] before sending it to `spatialfin-avr`.
     * Rejects URLs containing userinfo (`user:pass@host`) or non-Jellyfin paths.
     */
    fun stripAndValidateTheaterStreamUrl(
        rawUrl: String,
        allowedHost: String? = null,
    ): Result<String> {
        if (rawUrl.isBlank()) {
            return Result.failure(IllegalArgumentException("Blank stream URL"))
        }
        val uri = runCatching { URI(rawUrl.trim()) }
            .getOrElse { return Result.failure(IllegalArgumentException("Malformed stream URL", it)) }
        if (!uri.rawUserInfo.isNullOrEmpty()) {
            return Result.failure(IllegalArgumentException("Stream URL userinfo credentials rejected"))
        }
        val keptQuery = uri.rawQuery
            ?.split('&')
            ?.filter { part ->
                part.isNotBlank() && part.substringBefore('=').lowercase() !in CREDENTIAL_QUERY_KEYS
            }
            ?.joinToString("&")
            ?.takeIf { it.isNotEmpty() }

        val cleanFragment = uri.rawFragment
            ?.takeUnless { frag ->
                val lower = frag.lowercase()
                CREDENTIAL_QUERY_KEYS.any { key -> lower.contains("$key=") }
            }

        val rebuilt = buildString {
            append(uri.scheme).append("://")
            val host = uri.host ?: return Result.failure(IllegalArgumentException("Missing stream URL host"))
            if (host.contains(':') && !host.startsWith('[')) {
                append('[').append(host).append(']')
            } else {
                append(host)
            }
            if (uri.port != -1) {
                append(':').append(uri.port)
            }
            append(uri.rawPath.orEmpty())
            if (keptQuery != null) {
                append('?').append(keptQuery)
            }
            if (!cleanFragment.isNullOrEmpty()) {
                append('#').append(cleanFragment)
            }
        }
        return when (val check = SendspinAvrRequestValidator.validateStreamUrl(rebuilt, allowedHost)) {
            is SendspinAvrRequestValidator.ValidationResult.Valid -> Result.success(rebuilt)
            is SendspinAvrRequestValidator.ValidationResult.Invalid ->
                Result.failure(IllegalArgumentException("${check.errorCode}: ${check.reason}"))
        }
    }

    /**
     * Unconditionally scrubs userinfo, query strings, and fragments from a URL before logging so
     * Jellyfin `api_key=` / `token=` parameters can never enter Timber or diagnostic exports.
     */
    fun scrubUrlForLog(rawUrl: String): String {
        val uri = runCatching { URI(rawUrl.trim()) }.getOrNull() ?: return "<redacted-url>"
        val scheme = uri.scheme ?: return "<redacted-url>"
        val host = uri.host ?: return "<redacted-url>"
        val portSuffix = if (uri.port != -1) ":${uri.port}" else ""
        val path = uri.rawPath.orEmpty()
        val hasQuery = !uri.rawQuery.isNullOrEmpty()
        return if (hasQuery) "$scheme://$host$portSuffix$path?<redacted>" else "$scheme://$host$portSuffix$path"
    }
}
