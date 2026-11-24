package dev.jdtech.jellyfin.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.jdtech.jellyfin.companion.CompanionConfigImporter
import dev.jdtech.jellyfin.companion.CompanionImportOutcome
import dev.jdtech.jellyfin.companion.ImportMode
import dev.jdtech.jellyfin.models.companion.CompanionEndpoint
import dev.jdtech.jellyfin.onboarding.OnboardingFailureCode
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.spatialfin.unified.applock.AppLockManager
import dev.spatialfin.unified.applock.AppLockObserver
import timber.log.Timber

@HiltWorker
class CompanionSyncWorker
@AssistedInject
constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val appPreferences: AppPreferences,
    private val importer: CompanionConfigImporter,
    private val appLockObserver: AppLockObserver,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (appLockObserver.lockState.value == AppLockManager.LockState.LOCKED) {
            Timber.d("COMPANION SYNC: App Lock active — deferring until unlock")
            return Result.success()
        }

        val url = appPreferences.getValue(appPreferences.companionUrl)
        val token = appPreferences.getValue(appPreferences.companionToken)

        if (url.isEmpty() || token.isEmpty()) {
            return Result.success()
        }

        val endpoint =
            runCatching { CompanionEndpoint.parse(url, token) }
                .getOrElse { error ->
                    Timber.w(error, "COMPANION SYNC: Ignoring invalid companion endpoint")
                    return Result.failure()
                }

        Timber.d("COMPANION SYNC: Background sync started from %s", endpoint.normalizedBaseUrl)

        return when (val outcome = importer.importFromEndpoint(endpoint, ImportMode.BACKGROUND_SYNC)) {
            is CompanionImportOutcome.Activated -> {
                Timber.i("COMPANION SYNC: Background sync completed successfully")
                Result.success()
            }
            is CompanionImportOutcome.Failed -> {
                when (outcome.code) {
                    OnboardingFailureCode.AUTH_INVALID_CREDENTIALS,
                    OnboardingFailureCode.URL_SSRF_BLOCKED,
                    -> {
                        Timber.w(
                            "COMPANION SYNC: Permanent failure (%s: %s); not retrying",
                            outcome.code.wireCode,
                            outcome.message,
                        )
                        Result.failure()
                    }
                    else -> {
                        Timber.w(
                            "COMPANION SYNC: Transient failure (%s: %s); scheduling retry",
                            outcome.code.wireCode,
                            outcome.message,
                        )
                        Result.retry()
                    }
                }
            }
            is CompanionImportOutcome.NeedsUserSelection,
            is CompanionImportOutcome.BrokerFallbackToLogin,
            -> {
                Timber.w("COMPANION SYNC: Unexpected interactive outcome in background sync: %s", outcome)
                Result.success()
            }
        }
    }
}
