package dev.jdtech.jellyfin.quickconnect

import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.onboarding.OnboardingFailureCode
import dev.jdtech.jellyfin.onboarding.OnboardingFailureException
import dev.jdtech.jellyfin.onboarding.toOnboardingFailureCode
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.HttpClientOptions
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.extensions.quickConnectApi
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.model.api.AuthenticationResult
import org.jellyfin.sdk.model.api.QuickConnectDto
import timber.log.Timber

data class QuickConnectInitiated(
    val code: String,
    val secret: String,
)

sealed interface PollResult {
    data object Pending : PollResult

    data object Authenticated : PollResult

    data object NotFound : PollResult

    data object DisabledOnServer : PollResult

    data class TransientFailure(val code: OnboardingFailureCode) : PollResult
}

sealed interface ApproveResult {
    data object Approved : ApproveResult

    data class Failed(val code: OnboardingFailureCode) : ApproveResult
}

/**
 * Stateless Jellyfin Quick Connect client. Every method uses an isolated
 * [JellyfinApi.createApi] instance so singleton [JellyfinApi] is never mutated,
 * and runs on [Dispatchers.IO] because jellyfin-sdk's OkHttpClient reads
 * `ResponseBody.bytes()` synchronously on the caller's dispatcher after `await`.
 */
@Singleton
class QuickConnectClient @Inject constructor(
    private val jellyfinApi: JellyfinApi,
) {
    private fun normalizeBaseUrl(baseUrl: String): String = baseUrl.trim().trimEnd('/')

    suspend fun checkEnabled(
        baseUrl: String,
        httpClientOptions: HttpClientOptions? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        val normalizedBaseUrl = normalizeBaseUrl(baseUrl)
        try {
            val api = jellyfinApi.createApi(
                baseUrl = normalizedBaseUrl,
                accessToken = null,
                httpClientOptions = httpClientOptions ?: jellyfinApi.httpClientOptions,
                followRedirects = true,
            )
            api.quickConnectApi.getQuickConnectEnabled().content
        } catch (e: TimeoutCancellationException) {
            Timber.w(e, "QuickConnect.checkEnabled timed out")
            throw OnboardingFailureException(OnboardingFailureCode.URL_TIMEOUT, cause = e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: InvalidStatusException) {
            if (e.status == 401) {
                false
            } else {
                Timber.w(e, "QuickConnect.checkEnabled HTTP status %d", e.status)
                throw OnboardingFailureException(e.toOnboardingFailureCode(), cause = e)
            }
        } catch (e: Throwable) {
            Timber.w(e, "QuickConnect.checkEnabled failed: %s", e.javaClass.simpleName)
            throw OnboardingFailureException(e.toOnboardingFailureCode(), cause = e)
        }
    }

    suspend fun initiate(
        baseUrl: String,
        httpClientOptions: HttpClientOptions? = null,
    ): QuickConnectInitiated = withContext(Dispatchers.IO) {
        val normalizedBaseUrl = normalizeBaseUrl(baseUrl)
        try {
            val api = jellyfinApi.createApi(
                baseUrl = normalizedBaseUrl,
                accessToken = null,
                httpClientOptions = httpClientOptions ?: jellyfinApi.httpClientOptions,
                followRedirects = true,
            )
            val state = api.quickConnectApi.initiateQuickConnect().content
            val code = state.code.trim()
            val secret = state.secret.trim()
            if (code.isEmpty() || secret.isEmpty()) {
                Timber.w("QuickConnect.initiate returned blank code or secret")
                throw OnboardingFailureException(
                    OnboardingFailureCode.SERVER_HTTP_ERROR,
                    "Jellyfin Quick Connect initiate returned blank code or secret",
                )
            }
            QuickConnectInitiated(code = code, secret = secret)
        } catch (e: TimeoutCancellationException) {
            Timber.w(e, "QuickConnect.initiate timed out")
            throw OnboardingFailureException(OnboardingFailureCode.URL_TIMEOUT, cause = e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: OnboardingFailureException) {
            throw e
        } catch (e: InvalidStatusException) {
            if (e.status == 401) {
                throw OnboardingFailureException(OnboardingFailureCode.QC_DISABLED_ON_SERVER, cause = e)
            }
            Timber.w(e, "QuickConnect.initiate HTTP status %d", e.status)
            throw OnboardingFailureException(e.toOnboardingFailureCode(), cause = e)
        } catch (e: Throwable) {
            Timber.w(e, "QuickConnect.initiate failed: %s", e.javaClass.simpleName)
            throw OnboardingFailureException(e.toOnboardingFailureCode(), cause = e)
        }
    }

    suspend fun pollOnce(
        baseUrl: String,
        secret: String,
        httpClientOptions: HttpClientOptions? = null,
    ): PollResult = withContext(Dispatchers.IO) {
        val normalizedBaseUrl = normalizeBaseUrl(baseUrl)
        try {
            val api = jellyfinApi.createApi(
                baseUrl = normalizedBaseUrl,
                accessToken = null,
                httpClientOptions = httpClientOptions ?: jellyfinApi.httpClientOptions,
                followRedirects = true,
            )
            val state = api.quickConnectApi.getQuickConnectState(secret = secret).content
            if (state.authenticated) PollResult.Authenticated else PollResult.Pending
        } catch (e: TimeoutCancellationException) {
            Timber.w(e, "QuickConnect.pollOnce timed out")
            PollResult.TransientFailure(OnboardingFailureCode.URL_TIMEOUT)
        } catch (e: CancellationException) {
            throw e
        } catch (e: InvalidStatusException) {
            when (e.status) {
                404 -> PollResult.NotFound
                401 -> PollResult.DisabledOnServer
                else -> {
                    Timber.w(e, "QuickConnect.pollOnce HTTP status %d", e.status)
                    PollResult.TransientFailure(e.toOnboardingFailureCode())
                }
            }
        } catch (e: Throwable) {
            Timber.w(e, "QuickConnect.pollOnce failed: %s", e.javaClass.simpleName)
            PollResult.TransientFailure(e.toOnboardingFailureCode())
        }
    }

    suspend fun authenticate(
        baseUrl: String,
        secret: String,
        httpClientOptions: HttpClientOptions? = null,
    ): AuthenticationResult = withContext(Dispatchers.IO) {
        val normalizedBaseUrl = normalizeBaseUrl(baseUrl)
        try {
            val api = jellyfinApi.createApi(
                baseUrl = normalizedBaseUrl,
                accessToken = null,
                httpClientOptions = httpClientOptions ?: jellyfinApi.httpClientOptions,
                followRedirects = false,
            )
            api.userApi.authenticateWithQuickConnect(
                data = QuickConnectDto(secret = secret),
            ).content
        } catch (e: TimeoutCancellationException) {
            Timber.w(e, "QuickConnect.authenticate timed out")
            throw OnboardingFailureException(OnboardingFailureCode.URL_TIMEOUT, cause = e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: OnboardingFailureException) {
            throw e
        } catch (e: InvalidStatusException) {
            Timber.w(e, "QuickConnect.authenticate HTTP status %d", e.status)
            val code =
                if (e.status in 300..399) {
                    OnboardingFailureCode.SERVER_HTTP_ERROR
                } else {
                    e.toOnboardingFailureCode()
                }
            throw OnboardingFailureException(code, cause = e)
        } catch (e: Throwable) {
            Timber.w(e, "QuickConnect.authenticate failed: %s", e.javaClass.simpleName)
            throw OnboardingFailureException(e.toOnboardingFailureCode(), cause = e)
        }
    }

    suspend fun approve(
        baseUrl: String,
        accessToken: String,
        code: String,
        httpClientOptions: HttpClientOptions? = null,
    ): ApproveResult {
        val cleanedCode = code.replace(WHITESPACE_REGEX, "")
        if (!SIX_DIGIT_CODE_REGEX.matches(cleanedCode)) {
            return ApproveResult.Failed(OnboardingFailureCode.PAIRING_CODE_FORMAT)
        }
        val normalizedBaseUrl = normalizeBaseUrl(baseUrl)
        return withContext(Dispatchers.IO) {
            try {
                val api = jellyfinApi.createApi(
                    baseUrl = normalizedBaseUrl,
                    accessToken = accessToken,
                    httpClientOptions = httpClientOptions ?: jellyfinApi.httpClientOptions,
                    followRedirects = true,
                )
                val approved = api.quickConnectApi.authorizeQuickConnect(code = cleanedCode).content
                if (approved) {
                    ApproveResult.Approved
                } else {
                    ApproveResult.Failed(OnboardingFailureCode.PAIRING_CODE_EXPIRED)
                }
            } catch (e: TimeoutCancellationException) {
                Timber.w(e, "QuickConnect.approve timed out")
                ApproveResult.Failed(OnboardingFailureCode.URL_TIMEOUT)
            } catch (e: CancellationException) {
                throw e
            } catch (e: InvalidStatusException) {
                when (e.status) {
                    404 -> ApproveResult.Failed(OnboardingFailureCode.PAIRING_CODE_EXPIRED)
                    401, 403 -> {
                        val enabled = runCatching { checkEnabled(normalizedBaseUrl, httpClientOptions) }.getOrNull()
                        if (enabled == false) {
                            ApproveResult.Failed(OnboardingFailureCode.QC_DISABLED_ON_SERVER)
                        } else {
                            ApproveResult.Failed(OnboardingFailureCode.AUTH_INVALID_CREDENTIALS)
                        }
                    }
                    else -> {
                        Timber.w(e, "QuickConnect.approve HTTP status %d", e.status)
                        ApproveResult.Failed(e.toOnboardingFailureCode())
                    }
                }
            } catch (e: Throwable) {
                Timber.w(e, "QuickConnect.approve failed: %s", e.javaClass.simpleName)
                ApproveResult.Failed(e.toOnboardingFailureCode())
            }
        }
    }

    private companion object {
        private val WHITESPACE_REGEX = Regex("\\s+")
        private val SIX_DIGIT_CODE_REGEX = Regex("^\\d{6}$")
    }
}
