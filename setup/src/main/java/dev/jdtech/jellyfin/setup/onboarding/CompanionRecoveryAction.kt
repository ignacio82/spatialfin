package dev.jdtech.jellyfin.setup.onboarding

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.annotation.StringRes
import dev.jdtech.jellyfin.companion.CompanionCandidate
import dev.jdtech.jellyfin.onboarding.OnboardingFailureCode
import dev.jdtech.jellyfin.setup.R as SetupR
import java.net.URI
import java.net.URLEncoder

data class CompanionRecoveryAction(
    @param:StringRes val labelRes: Int,
    @param:StringRes val hintRes: Int,
    val targetUrl: String,
)

/**
 * Resolves a Companion web UI deep link and actionable recovery guidance for [code] when a valid
 * `http(s)` [companionBaseUrl] is configured on this device.
 */
fun resolveCompanionRecoveryAction(
    code: OnboardingFailureCode,
    companionBaseUrl: String?,
    serverId: String? = null,
    username: String? = null,
    isCompanionContext: Boolean = false,
): CompanionRecoveryAction? {
    val cleanBase = normalizeHttpBaseUrl(companionBaseUrl) ?: return null
    val targetParams =
        buildList {
            serverId?.trim()?.takeIf { it.isNotEmpty() }?.let { add("serverId" to it) }
            username?.trim()?.takeIf { it.isNotEmpty() }?.let { add("user" to it) }
        }

    return when (code) {
        OnboardingFailureCode.PAIRING_NO_VERIFIED_USER ->
            CompanionRecoveryAction(
                labelRes = SetupR.string.companion_recovery_open_servers,
                hintRes = SetupR.string.companion_recovery_hint_no_verified_user,
                targetUrl =
                    buildHashUrl(
                        baseUrl = cleanBase,
                        screen = "servers",
                        params = listOf("action" to "relink") + targetParams,
                    ),
            )
        OnboardingFailureCode.QC_BROKER_REJECTED ->
            CompanionRecoveryAction(
                labelRes = SetupR.string.companion_recovery_open_overrides,
                hintRes = SetupR.string.companion_recovery_hint_qc_broker_rejected,
                targetUrl =
                    buildHashUrl(
                        baseUrl = cleanBase,
                        screen = "servers",
                        params = listOf("action" to "overrides") + targetParams,
                    ),
            )
        OnboardingFailureCode.QC_BROKER_UNREACHABLE ->
            CompanionRecoveryAction(
                labelRes = SetupR.string.companion_recovery_open_servers,
                hintRes = SetupR.string.companion_recovery_hint_qc_broker_unreachable,
                targetUrl = buildHashUrl(baseUrl = cleanBase, screen = "servers"),
            )
        OnboardingFailureCode.QC_DISABLED_ON_SERVER ->
            CompanionRecoveryAction(
                labelRes = SetupR.string.companion_recovery_open_servers,
                hintRes = SetupR.string.companion_recovery_hint_qc_disabled,
                targetUrl = buildHashUrl(baseUrl = cleanBase, screen = "servers"),
            )
        OnboardingFailureCode.SERVER_IDENTITY_MISMATCH ->
            CompanionRecoveryAction(
                labelRes = SetupR.string.companion_recovery_open_servers,
                hintRes = SetupR.string.companion_recovery_hint_server_mismatch,
                targetUrl = buildHashUrl(baseUrl = cleanBase, screen = "servers"),
            )
        OnboardingFailureCode.AUTH_INVALID_CREDENTIALS ->
            if (isCompanionContext) {
                CompanionRecoveryAction(
                    labelRes = SetupR.string.companion_recovery_open_dashboard,
                    hintRes = SetupR.string.companion_recovery_hint_token_rotated,
                    targetUrl = buildHashUrl(baseUrl = cleanBase, screen = "dashboard"),
                )
            } else {
                null
            }
        else -> null
    }
}

fun openCompanionUrlInBrowser(context: Context, url: String) {
    val cleanUrl = url.trim()
    if (!cleanUrl.startsWith("http://", ignoreCase = true) &&
        !cleanUrl.startsWith("https://", ignoreCase = true)
    ) {
        return
    }
    val launched =
        runCatching {
            val intent =
                Intent(Intent.ACTION_VIEW, Uri.parse(cleanUrl)).apply {
                    if (context !is Activity) {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                }
            context.startActivity(intent)
        }.isSuccess

    if (!launched) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("SpatialFin Companion URL", cleanUrl))
        Toast.makeText(
            context,
            context.getString(SetupR.string.companion_recovery_no_browser_copied, cleanUrl),
            Toast.LENGTH_LONG,
        ).show()
    }
}

private fun normalizeHttpBaseUrl(raw: String?): String? {
    val clean = raw?.trim()?.substringBefore('#')?.substringBefore('?')?.trimEnd('/').orEmpty()
    if (clean.isBlank()) return null
    if (!clean.startsWith("http://", ignoreCase = true) &&
        !clean.startsWith("https://", ignoreCase = true)
    ) {
        return null
    }
    val parsed = runCatching { URI(clean) }.getOrNull() ?: return null
    if (parsed.host.isNullOrBlank()) return null
    return clean
}

private fun buildHashUrl(
    baseUrl: String,
    screen: String,
    params: List<Pair<String, String>> = emptyList(),
): String {
    if (params.isEmpty()) return "$baseUrl/#$screen"
    val encodedQuery =
        params.joinToString("&") { (k, v) ->
            "${urlEncode(k)}=${urlEncode(v)}"
        }
    return "$baseUrl/#$screen?$encodedQuery"
}

@StringRes
fun companionCandidateStatusRes(candidate: CompanionCandidate): Int =
    when {
        !candidate.isVerified && candidate.needsRelink -> SetupR.string.companion_candidate_needs_relink
        !candidate.isVerified -> SetupR.string.companion_candidate_pending
        !candidate.quickConnectBrokerEnabled -> SetupR.string.companion_candidate_qc_disabled
        else -> SetupR.string.companion_candidate_ready
    }

@StringRes
fun usersCompanionStatusRes(candidate: CompanionCandidate): Int =
    when {
        !candidate.isVerified && candidate.needsRelink -> SetupR.string.users_status_companion_needs_relink
        !candidate.isVerified -> SetupR.string.users_status_companion_pending
        !candidate.quickConnectBrokerEnabled -> SetupR.string.users_status_companion_qc_disabled
        else -> SetupR.string.users_status_companion_ready
    }

private fun urlEncode(value: String): String =
    URLEncoder.encode(value, "UTF-8").replace("+", "%20")
