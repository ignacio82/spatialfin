package dev.jdtech.jellyfin.companion

import android.os.SystemClock
import dev.jdtech.jellyfin.models.companion.CompanionEndpoint
import dev.jdtech.jellyfin.models.normalizeJellyfinId
import dev.jdtech.jellyfin.network.SafeUrlBlockedException
import dev.jdtech.jellyfin.network.UrlTargetPolicy
import dev.jdtech.jellyfin.network.newBuilderWithSsrfGuard
import dev.jdtech.jellyfin.onboarding.OnboardingFailureCode
import dev.jdtech.jellyfin.onboarding.OnboardingFailureException
import dev.jdtech.jellyfin.onboarding.toOnboardingFailureCode
import dev.jdtech.jellyfin.quickconnect.PollResult
import dev.jdtech.jellyfin.quickconnect.QuickConnectClient
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.DurationUnit
import kotlin.time.toDuration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.jellyfin.sdk.api.client.HttpClientOptions
import org.jellyfin.sdk.model.api.AuthenticationResult
import org.json.JSONObject

sealed interface BrokerAuthResult {
    data class Authenticated(val result: AuthenticationResult) : BrokerAuthResult

    data class FallbackToLogin(val code: OnboardingFailureCode) : BrokerAuthResult

    data class Failed(val code: OnboardingFailureCode) : BrokerAuthResult
}

@Singleton
class CompanionQuickConnectBroker
@Inject
constructor(
    private val baseHttpClient: OkHttpClient,
    private val quickConnectClient: QuickConnectClient,
) {
    @Volatile
    internal var allowLoopbackForTests: Boolean = false

    suspend fun authorizeAndMint(
        endpoint: CompanionEndpoint,
        policy: UrlTargetPolicy,
        companionServerId: String,
        expectedServerId: String,
        jellyfinBaseUrl: String,
        selectedUserId: UUID,
        deadlineElapsedMs: Long? = null,
        maxBrokerBudgetMs: Long,
    ): BrokerAuthResult =
        withContext(Dispatchers.IO) {
            val nowStart = SystemClock.elapsedRealtime()
            val effectiveDeadline = deadlineElapsedMs ?: (nowStart + maxBrokerBudgetMs + RESERVED_TAIL_MS)
            val remainingAtStart = effectiveDeadline - nowStart
            val budgetMs = minOf(maxBrokerBudgetMs, remainingAtStart - RESERVED_TAIL_MS)
            if (budgetMs < MIN_BROKER_BUDGET_MS) {
                return@withContext BrokerAuthResult.Failed(OnboardingFailureCode.URL_TIMEOUT)
            }
            val b1ToB3Deadline = nowStart + budgetMs

            // Step B1: Initiate Quick Connect on Jellyfin
            val initiated = try {
                val b1Remaining = b1ToB3Deadline - SystemClock.elapsedRealtime()
                if (b1Remaining <= 0L) {
                    return@withContext BrokerAuthResult.Failed(OnboardingFailureCode.URL_TIMEOUT)
                }
                withTimeout(b1Remaining) {
                    quickConnectClient.initiate(
                        baseUrl = jellyfinBaseUrl,
                        httpClientOptions = clampedHttpOptions(b1Remaining),
                    )
                }
            } catch (e: TimeoutCancellationException) {
                return@withContext BrokerAuthResult.Failed(OnboardingFailureCode.URL_TIMEOUT)
            } catch (e: CancellationException) {
                throw e
            } catch (e: OnboardingFailureException) {
                return@withContext when (e.code) {
                    OnboardingFailureCode.QC_DISABLED_ON_SERVER ->
                        BrokerAuthResult.FallbackToLogin(OnboardingFailureCode.QC_DISABLED_ON_SERVER)
                    OnboardingFailureCode.URL_TIMEOUT ->
                        BrokerAuthResult.Failed(OnboardingFailureCode.URL_TIMEOUT)
                    else -> BrokerAuthResult.Failed(e.code)
                }
            } catch (e: Throwable) {
                val code = e.toOnboardingFailureCode()
                return@withContext BrokerAuthResult.Failed(code)
            }

            // Step B2: Ask Companion broker to authorize the Quick Connect code
            val b2Remaining = b1ToB3Deadline - SystemClock.elapsedRealtime()
            if (b2Remaining <= 0L) {
                return@withContext BrokerAuthResult.Failed(OnboardingFailureCode.URL_TIMEOUT)
            }
            val brokerError = postBrokerAuthorize(
                endpoint = endpoint,
                policy = policy,
                companionServerId = companionServerId,
                selectedUserId = selectedUserId,
                code = initiated.code,
                timeoutMs = b2Remaining,
            )
            if (brokerError != null) {
                return@withContext brokerError
            }

            // Step B3: Poll Jellyfin QuickConnect/Connect (0 ms initial poll, then 1 s cadence)
            var authenticated = false
            var firstPoll = true
            while (true) {
                if (!firstPoll) {
                    val leftBeforeDelay = b1ToB3Deadline - SystemClock.elapsedRealtime()
                    if (leftBeforeDelay <= 0L) {
                        return@withContext BrokerAuthResult.Failed(OnboardingFailureCode.URL_TIMEOUT)
                    }
                    delay(minOf(POLL_INTERVAL_MS, leftBeforeDelay))
                }
                firstPoll = false
                val pollRemaining = b1ToB3Deadline - SystemClock.elapsedRealtime()
                if (pollRemaining <= 0L) {
                    return@withContext BrokerAuthResult.Failed(OnboardingFailureCode.URL_TIMEOUT)
                }
                val pollResult = try {
                    withTimeout(pollRemaining) {
                        quickConnectClient.pollOnce(
                            baseUrl = jellyfinBaseUrl,
                            secret = initiated.secret,
                            httpClientOptions = clampedHttpOptions(pollRemaining),
                        )
                    }
                } catch (e: TimeoutCancellationException) {
                    return@withContext BrokerAuthResult.Failed(OnboardingFailureCode.URL_TIMEOUT)
                } catch (e: CancellationException) {
                    throw e
                }
                when (pollResult) {
                    PollResult.Authenticated -> {
                        authenticated = true
                        break
                    }
                    PollResult.DisabledOnServer ->
                        return@withContext BrokerAuthResult.FallbackToLogin(OnboardingFailureCode.QC_DISABLED_ON_SERVER)
                    PollResult.NotFound ->
                        return@withContext BrokerAuthResult.FallbackToLogin(OnboardingFailureCode.QC_BROKER_REJECTED)
                    PollResult.Pending,
                    is PollResult.TransientFailure,
                    -> {
                        if (SystemClock.elapsedRealtime() >= b1ToB3Deadline) {
                            return@withContext BrokerAuthResult.Failed(OnboardingFailureCode.URL_TIMEOUT)
                        }
                    }
                }
            }

            if (!authenticated) {
                return@withContext BrokerAuthResult.Failed(OnboardingFailureCode.URL_TIMEOUT)
            }

            // Step B4: Exchange Quick Connect secret for a device-bound Jellyfin AccessToken
            val b4Remaining = effectiveDeadline - SystemClock.elapsedRealtime()
            if (b4Remaining < MIN_B4_REMAINING_MS) {
                return@withContext BrokerAuthResult.Failed(OnboardingFailureCode.URL_TIMEOUT)
            }
            val b4RequestTimeoutMs = maxOf(1_000L, b4Remaining - 500L)
            val authResult = try {
                withTimeout(b4Remaining) {
                    quickConnectClient.authenticate(
                        baseUrl = jellyfinBaseUrl,
                        secret = initiated.secret,
                        httpClientOptions = clampedHttpOptions(b4RequestTimeoutMs),
                    )
                }
            } catch (e: TimeoutCancellationException) {
                return@withContext BrokerAuthResult.Failed(OnboardingFailureCode.URL_TIMEOUT)
            } catch (e: CancellationException) {
                throw e
            } catch (e: OnboardingFailureException) {
                return@withContext when (e.code) {
                    OnboardingFailureCode.URL_TIMEOUT ->
                        BrokerAuthResult.Failed(OnboardingFailureCode.URL_TIMEOUT)
                    OnboardingFailureCode.SERVER_HTTP_ERROR ->
                        BrokerAuthResult.FallbackToLogin(OnboardingFailureCode.SERVER_HTTP_ERROR)
                    else -> BrokerAuthResult.Failed(e.code)
                }
            } catch (e: Throwable) {
                return@withContext BrokerAuthResult.Failed(e.toOnboardingFailureCode())
            }

            val authedUserId = authResult.user?.id
            if (authedUserId == null || authedUserId != selectedUserId) {
                return@withContext BrokerAuthResult.Failed(OnboardingFailureCode.AUTH_INVALID_CREDENTIALS)
            }
            val authedServerId = authResult.serverId?.trim().orEmpty()
            if (authedServerId.isEmpty() ||
                normalizeJellyfinId(authedServerId) != normalizeJellyfinId(expectedServerId)
            ) {
                return@withContext BrokerAuthResult.Failed(OnboardingFailureCode.SERVER_IDENTITY_MISMATCH)
            }
            if (authResult.accessToken.isNullOrBlank()) {
                return@withContext BrokerAuthResult.Failed(OnboardingFailureCode.AUTH_INVALID_CREDENTIALS)
            }
            BrokerAuthResult.Authenticated(authResult)
        }

    private fun postBrokerAuthorize(
        endpoint: CompanionEndpoint,
        policy: UrlTargetPolicy,
        companionServerId: String,
        selectedUserId: UUID,
        code: String,
        timeoutMs: Long,
    ): BrokerAuthResult? {
        val guardedClient = baseHttpClient
            .newBuilderWithSsrfGuard(
                policy = policy,
                allowLoopbackForTests = allowLoopbackForTests,
                maxRedirects = 0,
            )
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(minOf(timeoutMs, 3_000L), TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()

        val payload = JSONObject()
            .put("serverId", companionServerId)
            .put("userId", selectedUserId.toString())
            .put("code", code)
            .toString()

        val request = endpoint
            .authorizedRequest(endpoint.authorizeQuickConnectUrl)
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        return try {
            guardedClient.newCall(request).execute().use { response ->
                val body = runCatching { response.body.string() }.getOrDefault("")
                if (response.isSuccessful) {
                    return null
                }
                val errorCode = runCatching { JSONObject(body).optString("error_code", "") }
                    .getOrDefault("")
                    .trim()
                    .lowercase()
                when (response.code) {
                    401 -> BrokerAuthResult.Failed(OnboardingFailureCode.AUTH_INVALID_CREDENTIALS)
                    403 -> BrokerAuthResult.FallbackToLogin(OnboardingFailureCode.QC_BROKER_REJECTED)
                    429 -> BrokerAuthResult.Failed(OnboardingFailureCode.PAIRING_RATE_LIMITED)
                    404 -> BrokerAuthResult.FallbackToLogin(OnboardingFailureCode.QC_BROKER_REJECTED)
                    409 -> when (errorCode) {
                        "qc_disabled" ->
                            BrokerAuthResult.FallbackToLogin(OnboardingFailureCode.QC_DISABLED_ON_SERVER)
                        "no_verified_user" ->
                            BrokerAuthResult.FallbackToLogin(OnboardingFailureCode.PAIRING_NO_VERIFIED_USER)
                        "qc_authorize_false" ->
                            BrokerAuthResult.FallbackToLogin(OnboardingFailureCode.QC_BROKER_REJECTED)
                        else ->
                            BrokerAuthResult.FallbackToLogin(OnboardingFailureCode.QC_BROKER_REJECTED)
                    }
                    503 -> BrokerAuthResult.FallbackToLogin(OnboardingFailureCode.QC_BROKER_UNREACHABLE)
                    else -> BrokerAuthResult.FallbackToLogin(OnboardingFailureCode.SERVER_HTTP_ERROR)
                }
            }
        } catch (e: SafeUrlBlockedException) {
            BrokerAuthResult.Failed(OnboardingFailureCode.URL_SSRF_BLOCKED)
        } catch (e: SocketTimeoutException) {
            BrokerAuthResult.Failed(OnboardingFailureCode.URL_TIMEOUT)
        } catch (e: IOException) {
            BrokerAuthResult.FallbackToLogin(OnboardingFailureCode.QC_BROKER_UNREACHABLE)
        }
    }

    private fun clampedHttpOptions(timeoutMs: Long): HttpClientOptions {
        val clamped = timeoutMs.coerceAtLeast(1_000L)
        return HttpClientOptions(
            followRedirects = false,
            connectTimeout = minOf(clamped, 3_000L).toDuration(DurationUnit.MILLISECONDS),
            requestTimeout = clamped.toDuration(DurationUnit.MILLISECONDS),
            socketTimeout = clamped.toDuration(DurationUnit.MILLISECONDS),
        )
    }

    companion object {
        const val LAN_HANDOFF_BROKER_BUDGET_MS = 8_000L
        const val INTERACTIVE_BROKER_BUDGET_MS = 25_000L

        private const val RESERVED_TAIL_MS = 3_000L
        private const val MIN_BROKER_BUDGET_MS = 1_000L
        private const val MIN_B4_REMAINING_MS = 2_500L
        private const val POLL_INTERVAL_MS = 1_000L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
