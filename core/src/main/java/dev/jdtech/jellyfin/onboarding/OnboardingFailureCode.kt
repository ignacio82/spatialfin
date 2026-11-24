package dev.jdtech.jellyfin.onboarding

import android.database.sqlite.SQLiteException
import dev.jdtech.jellyfin.network.SafeUrlBlockedException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.UnknownServiceException
import java.security.cert.CertPathValidatorException
import javax.net.ssl.SSLException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.SerializationException
import org.jellyfin.sdk.api.client.exception.InvalidContentException
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.exception.TimeoutException

/**
 * Pure UI-free typed failure taxonomy shared across `:core` and `:setup`.
 * Every entry maps 1:1 to a localized message in `:setup` `OnboardingFailureMessages`.
 */
enum class OnboardingFailureCode(val wireCode: String) {
    UDP_DISCOVERY_SOCKET_ERR("udp_discovery_socket_err"),
    UDP_DISCOVERY_ZERO_REPLIES("udp_discovery_zero_replies"),
    URL_DNS_UNRESOLVED("server_unreachable"),
    URL_CLEARTEXT_BLOCKED("cleartext_blocked"),
    URL_TLS_UNTRUSTED("tls_untrusted"),
    URL_REVERSE_PROXY_HTML_RESPONSE("reverse_proxy_html"),
    URL_SSRF_BLOCKED("ssrf_blocked"),
    URL_TIMEOUT("server_timeout"),
    SERVER_HTTP_ERROR("server_http_error"),
    SERVER_IDENTITY_MISMATCH("server_identity_mismatch"),
    AUTH_INVALID_CREDENTIALS("invalid_credentials"),
    QC_DISABLED_ON_SERVER("qc_disabled"),
    QC_BROKER_UNREACHABLE("qc_broker_unreachable"),
    QC_BROKER_REJECTED("qc_broker_rejected"),
    NSD_REGISTRATION_FAILED("nsd_registration_failed"),
    PAIRING_CODE_MISMATCH("pairing_token_invalid"),
    PAIRING_CODE_FORMAT("pairing_code_format"),
    PAIRING_CODE_EXPIRED("pairing_code_expired"),
    PAIRING_CANCELLED("pairing_cancelled"),
    PAIRING_NO_VERIFIED_USER("no_verified_user"),
    PAIRING_RATE_LIMITED("pairing_rate_limited"),
    PAIRING_SESSION_ACTIVE("pairing_session_active"),
    SERVER_BUSY("server_busy"),
    HOST_REJECTED("host_rejected"),
    SESSION_NOT_FOUND("session_not_found"),
    STORAGE_ERROR("storage_error"),
    ;

    companion object {
        private val byWireCode: Map<String, OnboardingFailureCode> =
            entries.associateBy { it.wireCode }

        fun fromWireCode(wireCode: String?): OnboardingFailureCode {
            val normalized = wireCode?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
                ?: return SERVER_HTTP_ERROR
            return when (normalized) {
                "pairing_timeout" -> URL_TIMEOUT
                "server_not_found",
                "user_not_found",
                "qc_authorize_false",
                -> QC_BROKER_REJECTED
                else -> byWireCode[normalized] ?: SERVER_HTTP_ERROR
            }
        }
    }
}

class OnboardingFailureException(
    val code: OnboardingFailureCode,
    override val message: String? = code.wireCode,
    override val cause: Throwable? = null,
) : Exception(message ?: code.wireCode, cause)

fun Throwable.toOnboardingFailureCode(): OnboardingFailureCode {
    var cursor: Throwable? = this
    var depth = 0
    while (cursor != null && depth < 12) {
        when (cursor) {
            is OnboardingFailureException -> return cursor.code
            is SafeUrlBlockedException -> return OnboardingFailureCode.URL_SSRF_BLOCKED
            is TimeoutCancellationException,
            is SocketTimeoutException,
            is TimeoutException,
            -> return OnboardingFailureCode.URL_TIMEOUT
            is SQLiteException -> return OnboardingFailureCode.STORAGE_ERROR
            is UnknownServiceException -> {
                val msg = cursor.message.orEmpty()
                return if (msg.contains("CLEARTEXT", ignoreCase = true)) {
                    OnboardingFailureCode.URL_CLEARTEXT_BLOCKED
                } else {
                    OnboardingFailureCode.URL_TLS_UNTRUSTED
                }
            }
            is SSLException,
            is CertPathValidatorException,
            -> return OnboardingFailureCode.URL_TLS_UNTRUSTED
            is UnknownHostException,
            is ConnectException,
            -> return OnboardingFailureCode.URL_DNS_UNRESOLVED
            is InvalidStatusException -> {
                return when (cursor.status) {
                    401 -> OnboardingFailureCode.AUTH_INVALID_CREDENTIALS
                    429 -> OnboardingFailureCode.PAIRING_RATE_LIMITED
                    503 -> OnboardingFailureCode.SERVER_BUSY
                    504 -> OnboardingFailureCode.URL_TIMEOUT
                    else -> OnboardingFailureCode.SERVER_HTTP_ERROR
                }
            }
            is InvalidContentException,
            is SerializationException,
            -> return OnboardingFailureCode.URL_REVERSE_PROXY_HTML_RESPONSE
        }
        cursor = cursor.cause
        depth++
    }
    return OnboardingFailureCode.SERVER_HTTP_ERROR
}
