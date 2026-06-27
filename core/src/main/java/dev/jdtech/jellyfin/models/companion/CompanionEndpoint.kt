package dev.jdtech.jellyfin.models.companion

import java.util.Locale
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request

class CompanionEndpoint private constructor(
    val baseUrl: HttpUrl,
    val setupToken: String,
) {
    val normalizedBaseUrl: String = baseUrl.toString().removeSuffix("/")
    val configUrl: HttpUrl = buildUrl("api/v1/config")
    val deviceLogsUrl: HttpUrl = buildUrl("api/v1/device-logs")

    fun searchUrl(query: String): HttpUrl =
        buildUrl("api/v1/search")
            .newBuilder()
            .setQueryParameter("q", query)
            .build()

    fun authorizedRequest(url: HttpUrl): Request.Builder =
        Request.Builder()
            .url(url)
            .addHeader("X-Setup-Token", setupToken)

    fun webSocketUrlString(): String =
        normalizedBaseUrl
            .replaceFirst("http://", "ws://", ignoreCase = true)
            .replaceFirst("https://", "wss://", ignoreCase = true)

    private fun buildUrl(path: String): HttpUrl {
        val builder = baseUrl.newBuilder()
        path.split('/')
            .filter { it.isNotBlank() }
            .forEach(builder::addPathSegment)
        return builder.build()
    }

    companion object {
        fun from(payload: CompanionDiscoveryPayload): CompanionEndpoint =
            parse(payload.companion_url, payload.setup_token)

        fun from(envelope: CompanionTvPairingEnvelope): CompanionEndpoint =
            parse(envelope.companion_url, envelope.setup_token)

        fun parse(rawBaseUrl: String, rawSetupToken: String): CompanionEndpoint {
            val setupToken = rawSetupToken.trim()
            require(setupToken.isNotEmpty()) { "Missing companion setup token" }

            val baseUrl = rawBaseUrl.trim().toHttpUrlOrNull()
                ?: throw IllegalArgumentException("Invalid companion URL")

            require(baseUrl.encodedUsername.isEmpty() && baseUrl.encodedPassword.isEmpty()) {
                "Companion URL must not include credentials"
            }
            require(baseUrl.query == null && baseUrl.fragment == null) {
                "Companion URL must not include query or fragment"
            }
            require(baseUrl.isHttps || isLocalNetworkHost(baseUrl.host)) {
                "Companion URL must use HTTPS unless it points to a local network host"
            }

            return CompanionEndpoint(baseUrl, setupToken)
        }

        fun isLocalNetworkHost(host: String): Boolean {
            val normalized = host
                .trim()
                .trim('[', ']')
                .trimEnd('.')
                .lowercase(Locale.US)
            if (normalized.isEmpty()) return false
            if (normalized == "localhost" || normalized == "::1") return true
            if (normalized.endsWith(".local")) return true

            if (normalized.contains(':')) {
                return normalized.startsWith("fe80:") ||
                    normalized.startsWith("fc") ||
                    normalized.startsWith("fd")
            }

            val octets = normalized.split('.')
            if (octets.size != 4) return false
            val bytes = octets.map { it.toIntOrNull() ?: return false }
            if (bytes.any { it !in 0..255 }) return false
            val a = bytes[0]
            val b = bytes[1]
            return a == 10 ||
                (a == 172 && b in 16..31) ||
                (a == 192 && b == 168) ||
                a == 127 ||
                (a == 169 && b == 254)
        }
    }
}
