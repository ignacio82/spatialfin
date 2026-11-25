package dev.jdtech.jellyfin.companion

import dev.jdtech.jellyfin.models.normalizeJellyfinId
import dev.jdtech.jellyfin.network.SafeUrlBlockedException
import dev.jdtech.jellyfin.network.SafeUrlPolicy
import dev.jdtech.jellyfin.network.UrlTargetPolicy
import dev.jdtech.jellyfin.network.newBuilderWithSsrfGuard
import dev.jdtech.jellyfin.onboarding.OnboardingFailureCode
import dev.jdtech.jellyfin.quickconnect.ApproveResult
import dev.jdtech.jellyfin.quickconnect.QuickConnectClient
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

sealed interface CompanionRelinkResult {
    data class Success(val username: String?) : CompanionRelinkResult

    data class InvalidAdminPassword(val message: String) : CompanionRelinkResult

    data class Failed(
        val message: String,
        val errorCode: String? = null,
    ) : CompanionRelinkResult
}

/**
 * Ephemeral admin client for updating/re-linking the signed-in Jellyfin user directly in
 * SpatialFin Companion without persisting the Companion admin password or admin session cookie.
 *
 * Security invariants:
 * - Authenticates via `POST /api/admin/login` with `remember = false` and holds the session cookie
 *   in a local variable only for the duration of this call, always calling `/api/admin/logout`.
 * - Never sends the device's `jellyfinAccessToken` to Companion; Companion mints its own token
 *   via Quick Connect and this client approves Companion's 6-digit code locally against Jellyfin.
 * - Never logs passwords, session cookies, or Jellyfin tokens.
 */
@Singleton
class CompanionAdminRelinkClient
@Inject
constructor(
    private val okHttpClient: OkHttpClient,
    private val quickConnectClient: QuickConnectClient,
) {
    @Volatile
    internal var allowLoopbackForTests: Boolean = false

    suspend fun relinkUser(
        companionBaseUrl: String,
        adminPassword: String?,
        serverId: String,
        jellyfinBaseUrl: String,
        jellyfinAccessToken: String,
        jellyfinUsername: String? = null,
        jellyfinPassword: String? = null,
        enableQuickConnectBroker: Boolean = false,
    ): CompanionRelinkResult =
        withContext(Dispatchers.IO) {
            val validatedBase =
                try {
                    SafeUrlPolicy.validateInitialUrl(
                        rawUrl = companionBaseUrl,
                        policy = UrlTargetPolicy.PUBLIC_OR_LAN,
                        allowLoopbackForTests = allowLoopbackForTests,
                    )
                } catch (e: SafeUrlBlockedException) {
                    return@withContext CompanionRelinkResult.Failed(
                        message = e.message ?: "Companion URL is not allowed.",
                        errorCode = OnboardingFailureCode.URL_SSRF_BLOCKED.wireCode,
                    )
                } catch (_: Throwable) {
                    return@withContext CompanionRelinkResult.Failed(
                        message = "Invalid Companion URL.",
                        errorCode = OnboardingFailureCode.HOST_REJECTED.wireCode,
                    )
                }
            val base = validatedBase.toString().removeSuffix("/")
            val client =
                okHttpClient
                    .newBuilderWithSsrfGuard(
                        policy = UrlTargetPolicy.PUBLIC_OR_LAN,
                        allowLoopbackForTests = allowLoopbackForTests,
                        maxRedirects = 0,
                    )
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .connectTimeout(3_000L, TimeUnit.MILLISECONDS)
                    .readTimeout(10_000L, TimeUnit.MILLISECONDS)
                    .callTimeout(10_000L, TimeUnit.MILLISECONDS)
                    .build()

            var sessionCookie: String? = null
            try {
                // Phase 1: Ephemeral Companion admin login
                val loginPayload =
                    JSONObject()
                        .put("password", adminPassword.orEmpty())
                        .put("remember", false)
                        .toString()
                val loginReq =
                    adminPostRequest(
                        url = "$base/api/admin/login",
                        sessionCookie = null,
                        jsonBody = loginPayload,
                    )
                val loginOutcome =
                    client.newCall(loginReq).execute().use { resp ->
                        val body = runCatching { resp.body.string() }.getOrDefault("")
                        when (resp.code) {
                            in 200..299 -> {
                                sessionCookie = extractSessionCookie(resp.headers("Set-Cookie"))
                                null
                            }
                            401 -> {
                                val msg =
                                    if (adminPassword.isNullOrEmpty()) {
                                        "Enter your Companion admin password to update Companion."
                                    } else {
                                        "Companion admin password was rejected."
                                    }
                                CompanionRelinkResult.InvalidAdminPassword(msg)
                            }
                            403 -> {
                                val errObj = runCatching { JSONObject(body) }.getOrNull()
                                CompanionRelinkResult.Failed(
                                    message =
                                        errObj?.optString("error")?.takeIf { it.isNotBlank() }
                                            ?: "Companion rejected admin login.",
                                    errorCode = errObj?.optString("error_code")?.takeIf { it.isNotBlank() },
                                )
                            }
                            429 ->
                                CompanionRelinkResult.Failed(
                                    message = "Too many Companion login attempts. Wait a moment and try again.",
                                    errorCode = OnboardingFailureCode.PAIRING_RATE_LIMITED.wireCode,
                                )
                            else ->
                                CompanionRelinkResult.Failed(
                                    message = "Companion admin login failed (HTTP ${resp.code}).",
                                    errorCode = OnboardingFailureCode.SERVER_HTTP_ERROR.wireCode,
                                )
                        }
                    }
                if (loginOutcome != null) {
                    return@withContext loginOutcome
                }

                // Phase 2: Initiate Quick Connect linking inside Companion
                val initiatePayload = JSONObject().put("serverId", serverId).toString()
                val initiateReq =
                    adminPostRequest(
                        url = "$base/api/admin/quick-connect/initiate",
                        sessionCookie = sessionCookie,
                        jsonBody = initiatePayload,
                    )
                val (pendingId, qcCode, earlyFallback) =
                    client.newCall(initiateReq).execute().use { resp ->
                        val body = runCatching { resp.body.string() }.getOrDefault("")
                        val json = runCatching { JSONObject(body) }.getOrNull()
                        val errCode = json?.optString("error_code")?.takeIf { it.isNotBlank() }
                        when {
                            resp.code in 200..299 -> {
                                val pid = json?.optString("pendingId")?.trim().orEmpty()
                                val code = json?.optString("code")?.trim().orEmpty()
                                if (pid.isEmpty() || code.isEmpty()) {
                                    Triple(
                                        "",
                                        "",
                                        CompanionRelinkResult.Failed(
                                            "Companion returned an invalid Quick Connect initiation response.",
                                            OnboardingFailureCode.SERVER_HTTP_ERROR.wireCode,
                                        ),
                                    )
                                } else {
                                    Triple(pid, code, null)
                                }
                            }
                            resp.code == 409 &&
                                errCode == "qc_disabled" &&
                                !jellyfinUsername.isNullOrBlank() &&
                                jellyfinPassword != null -> {
                                val fallbackRes =
                                    fallbackVerifyWithPassword(
                                        client = client,
                                        base = base,
                                        sessionCookie = sessionCookie,
                                        serverId = serverId,
                                        jellyfinUsername = jellyfinUsername,
                                        jellyfinPassword = jellyfinPassword,
                                        enableQuickConnectBroker = enableQuickConnectBroker,
                                    )
                                Triple("", "", fallbackRes)
                            }
                            else -> {
                                val msg =
                                    json?.optString("error")?.takeIf { it.isNotBlank() }
                                        ?: "Could not start Quick Connect on Companion (HTTP ${resp.code})."
                                Triple("", "", CompanionRelinkResult.Failed(msg, errCode))
                            }
                        }
                    }
                if (earlyFallback != null) {
                    return@withContext earlyFallback
                }

                // Phase 3: Locally approve Companion's Quick Connect code against Jellyfin
                when (
                    val approveRes =
                        quickConnectClient.approve(
                            baseUrl = jellyfinBaseUrl,
                            accessToken = jellyfinAccessToken,
                            code = qcCode,
                        )
                ) {
                    ApproveResult.Approved -> Unit
                    is ApproveResult.Failed -> {
                        bestEffortCancelQuickConnect(client, base, sessionCookie, pendingId)
                        if (approveRes.code == OnboardingFailureCode.QC_DISABLED_ON_SERVER &&
                            !jellyfinUsername.isNullOrBlank() &&
                            jellyfinPassword != null
                        ) {
                            return@withContext fallbackVerifyWithPassword(
                                client = client,
                                base = base,
                                sessionCookie = sessionCookie,
                                serverId = serverId,
                                jellyfinUsername = jellyfinUsername,
                                jellyfinPassword = jellyfinPassword,
                                enableQuickConnectBroker = enableQuickConnectBroker,
                            )
                        }
                        return@withContext CompanionRelinkResult.Failed(
                            message = "Could not approve Quick Connect on Jellyfin (${approveRes.code.wireCode}).",
                            errorCode = approveRes.code.wireCode,
                        )
                    }
                }

                // Phase 4: Poll Companion until it exchanges and saves the dedicated user token
                val pollPayload = JSONObject().put("pendingId", pendingId).toString()
                for (attempt in 0 until MAX_POLL_ATTEMPTS) {
                    if (attempt > 0) {
                        delay(POLL_INTERVAL_MS)
                    }
                    val pollReq =
                        adminPostRequest(
                            url = "$base/api/admin/quick-connect/poll",
                            sessionCookie = sessionCookie,
                            jsonBody = pollPayload,
                        )
                    val stepResult =
                        client.newCall(pollReq).execute().use { resp ->
                            val body = runCatching { resp.body.string() }.getOrDefault("")
                            val json = runCatching { JSONObject(body) }.getOrNull()
                            if (resp.code !in 200..299 || json == null) {
                                val errMsg =
                                    json?.optString("error")?.takeIf { it.isNotBlank() }
                                        ?: "Companion Quick Connect failed (HTTP ${resp.code})."
                                val errCode = json?.optString("error_code")?.takeIf { it.isNotBlank() }
                                bestEffortCancelQuickConnect(client, base, sessionCookie, pendingId)
                                return@use CompanionRelinkResult.Failed(errMsg, errCode)
                            }
                            when (json.optString("status")) {
                                "pending" -> null
                                "authenticated" -> {
                                    val linkedServerId =
                                        json.optString("serverId").takeIf { it.isNotBlank() } ?: serverId
                                    val linkedUserId =
                                        json.optString("userId").takeIf { it.isNotBlank() }
                                    val linkedUsername =
                                        json.optString("username").takeIf { it.isNotBlank() }
                                    val configObj = json.optJSONObject("config")
                                    val configVersion =
                                        if (json.has("configVersion")) {
                                            json.opt("configVersion")?.toString()
                                        } else {
                                            resp.header("ETag")?.trim('"')
                                        }
                                    if (enableQuickConnectBroker && configObj != null && configVersion != null) {
                                        val brokerErr =
                                            ensureQuickConnectBrokerEnabled(
                                                client = client,
                                                base = base,
                                                sessionCookie = sessionCookie,
                                                configObj = configObj,
                                                configVersion = configVersion,
                                                serverId = linkedServerId,
                                                userId = linkedUserId,
                                                username = linkedUsername ?: jellyfinUsername,
                                            )
                                        if (brokerErr != null) {
                                            return@use brokerErr
                                        }
                                    }
                                    CompanionRelinkResult.Success(linkedUsername ?: jellyfinUsername)
                                }
                                else -> {
                                    val errMsg =
                                        json.optString("error").takeIf { it.isNotBlank() }
                                            ?: "Quick Connect linking did not complete."
                                    val errCode = json.optString("error_code").takeIf { it.isNotBlank() }
                                    bestEffortCancelQuickConnect(client, base, sessionCookie, pendingId)
                                    CompanionRelinkResult.Failed(errMsg, errCode)
                                }
                            }
                        }
                    if (stepResult != null) {
                        return@withContext stepResult
                    }
                }

                bestEffortCancelQuickConnect(client, base, sessionCookie, pendingId)
                CompanionRelinkResult.Failed(
                    message = "Timed out waiting for Companion to complete linking.",
                    errorCode = OnboardingFailureCode.URL_TIMEOUT.wireCode,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: SafeUrlBlockedException) {
                CompanionRelinkResult.Failed(
                    message = e.message ?: "Companion request was blocked by security policy.",
                    errorCode = OnboardingFailureCode.URL_SSRF_BLOCKED.wireCode,
                )
            } catch (_: IOException) {
                CompanionRelinkResult.Failed(
                    message = "Could not reach Companion server.",
                    errorCode = OnboardingFailureCode.QC_BROKER_UNREACHABLE.wireCode,
                )
            } finally {
                val cookieToClear = sessionCookie
                if (cookieToClear != null) {
                    withContext(NonCancellable) {
                        bestEffortLogout(client, base, cookieToClear)
                    }
                }
            }
        }

    private fun fallbackVerifyWithPassword(
        client: OkHttpClient,
        base: String,
        sessionCookie: String?,
        serverId: String,
        jellyfinUsername: String,
        jellyfinPassword: String,
        enableQuickConnectBroker: Boolean,
    ): CompanionRelinkResult {
        val verifyPayload =
            JSONObject()
                .put("serverId", serverId)
                .put("username", jellyfinUsername)
                .put("password", jellyfinPassword)
                .toString()
        val req =
            adminPostRequest(
                url = "$base/api/admin/verify-jellyfin-user",
                sessionCookie = sessionCookie,
                jsonBody = verifyPayload,
            )
        return client.newCall(req).execute().use { resp ->
            val body = runCatching { resp.body.string() }.getOrDefault("")
            val json = runCatching { JSONObject(body) }.getOrNull()
            if (resp.code !in 200..299 || json == null || !json.optBoolean("success", false)) {
                val msg =
                    json?.optString("error")?.takeIf { it.isNotBlank() }
                        ?: "Companion user verification failed (HTTP ${resp.code})."
                val code = json?.optString("error_code")?.takeIf { it.isNotBlank() }
                return@use CompanionRelinkResult.Failed(msg, code)
            }
            val userJson = json.optJSONObject("user")
            val linkedUserId = userJson?.optString("id")?.takeIf { it.isNotBlank() }
            val linkedUsername =
                userJson?.optString("username")?.takeIf { it.isNotBlank() } ?: jellyfinUsername
            val linkedServerId = json.optString("serverId").takeIf { it.isNotBlank() } ?: serverId
            val configObj = json.optJSONObject("config")
            val configVersion =
                if (json.has("configVersion")) {
                    json.opt("configVersion")?.toString()
                } else {
                    resp.header("ETag")?.trim('"')
                }
            if (enableQuickConnectBroker && configObj != null && configVersion != null) {
                val brokerErr =
                    ensureQuickConnectBrokerEnabled(
                        client = client,
                        base = base,
                        sessionCookie = sessionCookie,
                        configObj = configObj,
                        configVersion = configVersion,
                        serverId = linkedServerId,
                        userId = linkedUserId,
                        username = linkedUsername,
                    )
                if (brokerErr != null) return@use brokerErr
            }
            CompanionRelinkResult.Success(linkedUsername)
        }
    }

    private fun ensureQuickConnectBrokerEnabled(
        client: OkHttpClient,
        base: String,
        sessionCookie: String?,
        configObj: JSONObject,
        configVersion: String,
        serverId: String,
        userId: String?,
        username: String?,
    ): CompanionRelinkResult.Failed? {
        val servers = configObj.optJSONArray("servers") ?: return null
        val normalizedServerTarget = normalizeJellyfinId(serverId)
        val normalizedUserTarget = userId?.let(::normalizeJellyfinId)

        var targetUserObj: JSONObject? = null
        for (i in 0 until servers.length()) {
            val srv = servers.optJSONObject(i) ?: continue
            val srvId = srv.optString("id")
            val matchesServer =
                srvId == serverId ||
                    (srvId.isNotBlank() && normalizeJellyfinId(srvId) == normalizedServerTarget) ||
                    legacyIdsMatch(srv.optJSONArray("legacy_ids"), serverId, normalizedServerTarget)
            if (!matchesServer) continue

            val users = srv.optJSONArray("users") ?: continue
            for (j in 0 until users.length()) {
                val u = users.optJSONObject(j) ?: continue
                val uid = u.optString("id")
                val uname = u.optString("username").ifBlank { u.optString("name") }
                val matchesUser =
                    (normalizedUserTarget != null && uid.isNotBlank() && normalizeJellyfinId(uid) == normalizedUserTarget) ||
                        (!username.isNullOrBlank() && uname.equals(username, ignoreCase = true))
                if (matchesUser) {
                    targetUserObj = u
                    break
                }
            }
            if (targetUserObj != null) break
        }

        if (targetUserObj == null || targetUserObj.optBoolean("quick_connect_broker_enabled", true)) {
            return null
        }

        targetUserObj.put("quick_connect_broker_enabled", true)
        val patchBody = JSONObject().put("servers", servers).toString()
        val patchReq =
            adminPostRequest(
                url = "$base/api/admin/config",
                sessionCookie = sessionCookie,
                jsonBody = patchBody,
                ifMatchVersion = configVersion,
            )
        return client.newCall(patchReq).execute().use { resp ->
            if (resp.code in 200..299) {
                null
            } else {
                val body = runCatching { resp.body.string() }.getOrDefault("")
                val json = runCatching { JSONObject(body) }.getOrNull()
                CompanionRelinkResult.Failed(
                    message = "User was re-linked, but Companion could not re-enable Quick Connect auto-approval (HTTP ${resp.code}).",
                    errorCode = json?.optString("error_code")?.takeIf { it.isNotBlank() },
                )
            }
        }
    }

    private fun legacyIdsMatch(
        legacyIds: JSONArray?,
        rawServerId: String,
        normalizedServerTarget: String,
    ): Boolean {
        if (legacyIds == null) return false
        for (k in 0 until legacyIds.length()) {
            val lid = legacyIds.optString(k)
            if (lid.isNotBlank() && (lid == rawServerId || normalizeJellyfinId(lid) == normalizedServerTarget)) {
                return true
            }
        }
        return false
    }

    private fun adminPostRequest(
        url: String,
        sessionCookie: String?,
        jsonBody: String,
        ifMatchVersion: String? = null,
    ): Request =
        Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("X-Companion-Admin", "1")
            .apply {
                if (!sessionCookie.isNullOrBlank()) {
                    header("Cookie", "session=$sessionCookie")
                }
                if (!ifMatchVersion.isNullOrBlank()) {
                    header("If-Match", "\"${ifMatchVersion.trim('"')}\"")
                }
            }
            .post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
            .build()

    private fun extractSessionCookie(setCookieHeaders: List<String>): String? {
        for (raw in setCookieHeaders) {
            val firstPair = raw.substringBefore(';').trim()
            if (firstPair.startsWith("session=")) {
                val token = firstPair.removePrefix("session=").trim()
                if (token.isNotEmpty()) return token
            }
        }
        return null
    }

    private fun bestEffortCancelQuickConnect(
        client: OkHttpClient,
        base: String,
        sessionCookie: String?,
        pendingId: String,
    ) {
        if (pendingId.isBlank()) return
        runCatching {
            val req =
                adminPostRequest(
                    url = "$base/api/admin/quick-connect/cancel",
                    sessionCookie = sessionCookie,
                    jsonBody = JSONObject().put("pendingId", pendingId).toString(),
                )
            client.newCall(req).execute().close()
        }
    }

    private fun bestEffortLogout(
        client: OkHttpClient,
        base: String,
        sessionCookie: String,
    ) {
        runCatching {
            val req =
                adminPostRequest(
                    url = "$base/api/admin/logout",
                    sessionCookie = sessionCookie,
                    jsonBody = "{}",
                )
            client.newCall(req).execute().close()
        }
    }

    private companion object {
        private const val MAX_POLL_ATTEMPTS = 12
        private const val POLL_INTERVAL_MS = 300L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
