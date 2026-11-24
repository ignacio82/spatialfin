package dev.jdtech.jellyfin.models.companion

import dev.jdtech.jellyfin.network.SafeUrlPolicy
import dev.jdtech.jellyfin.network.UrlTargetPolicy
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import okhttp3.HttpUrl
import okhttp3.Request

class CompanionEndpoint private constructor(
    val baseUrl: HttpUrl,
    val setupToken: String,
) {
    val normalizedBaseUrl: String = baseUrl.toString().removeSuffix("/")
    val configUrl: HttpUrl = buildUrl("api/v1/config")
    val deviceLogsUrl: HttpUrl = buildUrl("api/v1/device-logs")
    val fcastRegisterUrl: HttpUrl = buildUrl("api/fcast/register")
    val authorizeQuickConnectUrl: HttpUrl = buildUrl("api/v1/auth/authorize-quick-connect")

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
        fun from(
            payload: CompanionDiscoveryPayload,
            allowLoopbackForTests: Boolean = false,
        ): CompanionEndpoint =
            parse(
                rawBaseUrl = payload.companion_url,
                rawSetupToken = payload.setup_token,
                policy = UrlTargetPolicy.LAN_ONLY,
                allowLoopbackForTests = allowLoopbackForTests,
            )

        fun from(
            envelope: CompanionTvPairingEnvelope,
            allowLoopbackForTests: Boolean = false,
        ): CompanionEndpoint =
            parse(
                rawBaseUrl = envelope.companion_url,
                rawSetupToken = envelope.setup_token,
                policy = UrlTargetPolicy.PRIVATE_LAN_ONLY,
                allowLoopbackForTests = allowLoopbackForTests,
            )

        fun fromPreferences(
            appPreferences: AppPreferences,
            allowLoopbackForTests: Boolean = false,
        ): CompanionEndpoint? {
            val rawUrl = appPreferences.getValue(appPreferences.companionUrl)
            val rawToken = appPreferences.getValue(appPreferences.companionToken)
            if (rawUrl.isBlank() || rawToken.isBlank()) return null
            return runCatching {
                parse(
                    rawBaseUrl = rawUrl,
                    rawSetupToken = rawToken,
                    policy = UrlTargetPolicy.LAN_ONLY,
                    allowLoopbackForTests = allowLoopbackForTests,
                )
            }.getOrNull()
        }

        fun parse(
            rawBaseUrl: String,
            rawSetupToken: String,
            policy: UrlTargetPolicy = UrlTargetPolicy.LAN_ONLY,
            allowLoopbackForTests: Boolean = false,
        ): CompanionEndpoint {
            val setupToken = rawSetupToken.trim()
            require(setupToken.isNotEmpty()) { "Missing companion setup token" }

            val baseUrl = try {
                SafeUrlPolicy.validateInitialUrl(
                    rawUrl = rawBaseUrl,
                    policy = policy,
                    allowLoopbackForTests = allowLoopbackForTests,
                )
            } catch (e: Exception) {
                throw IllegalArgumentException(e.message ?: "Invalid companion URL", e)
            }

            require(baseUrl.query == null && baseUrl.fragment == null) {
                "Companion URL must not include query or fragment"
            }

            return CompanionEndpoint(baseUrl, setupToken)
        }
    }
}
