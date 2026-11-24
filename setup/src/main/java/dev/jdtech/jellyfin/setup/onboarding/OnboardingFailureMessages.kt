package dev.jdtech.jellyfin.setup.onboarding

import android.content.Context
import androidx.annotation.StringRes
import dev.jdtech.jellyfin.onboarding.OnboardingFailureCode
import dev.jdtech.jellyfin.setup.R as SetupR

object OnboardingFailureMessages {

    @StringRes
    fun messageRes(code: OnboardingFailureCode): Int =
        when (code) {
            OnboardingFailureCode.UDP_DISCOVERY_SOCKET_ERR -> SetupR.string.onboarding_err_udp_discovery_socket_err
            OnboardingFailureCode.UDP_DISCOVERY_ZERO_REPLIES -> SetupR.string.onboarding_err_udp_discovery_zero_replies
            OnboardingFailureCode.URL_DNS_UNRESOLVED -> SetupR.string.onboarding_err_url_dns_unresolved
            OnboardingFailureCode.URL_CLEARTEXT_BLOCKED -> SetupR.string.onboarding_err_url_cleartext_blocked
            OnboardingFailureCode.URL_TLS_UNTRUSTED -> SetupR.string.onboarding_err_url_tls_untrusted
            OnboardingFailureCode.URL_REVERSE_PROXY_HTML_RESPONSE -> SetupR.string.onboarding_err_url_reverse_proxy_html_response
            OnboardingFailureCode.URL_SSRF_BLOCKED -> SetupR.string.onboarding_err_url_ssrf_blocked
            OnboardingFailureCode.URL_TIMEOUT -> SetupR.string.onboarding_err_url_timeout
            OnboardingFailureCode.SERVER_HTTP_ERROR -> SetupR.string.onboarding_err_server_http_error
            OnboardingFailureCode.SERVER_IDENTITY_MISMATCH -> SetupR.string.onboarding_err_server_identity_mismatch
            OnboardingFailureCode.AUTH_INVALID_CREDENTIALS -> SetupR.string.onboarding_err_auth_invalid_credentials
            OnboardingFailureCode.QC_DISABLED_ON_SERVER -> SetupR.string.onboarding_err_qc_disabled_on_server
            OnboardingFailureCode.QC_BROKER_UNREACHABLE -> SetupR.string.onboarding_err_qc_broker_unreachable
            OnboardingFailureCode.QC_BROKER_REJECTED -> SetupR.string.onboarding_err_qc_broker_rejected
            OnboardingFailureCode.NSD_REGISTRATION_FAILED -> SetupR.string.onboarding_err_nsd_registration_failed
            OnboardingFailureCode.PAIRING_CODE_MISMATCH -> SetupR.string.onboarding_err_pairing_code_mismatch
            OnboardingFailureCode.PAIRING_CODE_FORMAT -> SetupR.string.onboarding_err_pairing_code_format
            OnboardingFailureCode.PAIRING_CODE_EXPIRED -> SetupR.string.onboarding_err_pairing_code_expired
            OnboardingFailureCode.PAIRING_CANCELLED -> SetupR.string.onboarding_err_pairing_cancelled
            OnboardingFailureCode.PAIRING_NO_VERIFIED_USER -> SetupR.string.onboarding_err_pairing_no_verified_user
            OnboardingFailureCode.PAIRING_RATE_LIMITED -> SetupR.string.onboarding_err_pairing_rate_limited
            OnboardingFailureCode.PAIRING_SESSION_ACTIVE -> SetupR.string.onboarding_err_pairing_session_active
            OnboardingFailureCode.SERVER_BUSY -> SetupR.string.onboarding_err_server_busy
            OnboardingFailureCode.HOST_REJECTED -> SetupR.string.onboarding_err_host_rejected
            OnboardingFailureCode.SESSION_NOT_FOUND -> SetupR.string.onboarding_err_session_not_found
            OnboardingFailureCode.STORAGE_ERROR -> SetupR.string.onboarding_err_storage_error
        }

    fun message(context: Context, code: OnboardingFailureCode): String =
        context.getString(messageRes(code))

    fun messageForWireCode(context: Context, wireCode: String?): String =
        message(context, OnboardingFailureCode.fromWireCode(wireCode))

    fun formatCopyableErrorDetails(context: Context, code: OnboardingFailureCode, detail: String? = null): String {
        val base = message(context, code)
        val suffix = detail?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
        return "$base [${code.wireCode}]$suffix"
    }
}
