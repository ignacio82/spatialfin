package dev.jdtech.jellyfin.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.jdtech.jellyfin.network.SafeUrlBlockedException
import dev.jdtech.jellyfin.network.UrlTargetPolicy
import dev.jdtech.jellyfin.network.newBuilderWithSsrfGuard
import dev.jdtech.jellyfin.session.SessionActivationCoordinator
import dev.jdtech.jellyfin.session.SessionMode
import dev.spatialfin.unified.applock.AppLockManager
import dev.spatialfin.unified.applock.AppLockObserver
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber

@HiltWorker
class ImagesDownloaderWorker
@AssistedInject
constructor(
    @Assisted private val appContext: Context,
    @Assisted private val params: WorkerParameters,
    private val okHttpClient: OkHttpClient,
    private val sessionActivationCoordinator: SessionActivationCoordinator,
    private val appLockObserver: AppLockObserver,
) : CoroutineWorker(appContext, params) {

    private fun isFencedValid(expectedServerId: String?, expectedEpoch: Long): Boolean {
        if (isStopped) return false
        if (appLockObserver.lockState.value == AppLockManager.LockState.LOCKED) return false
        if (sessionActivationCoordinator.isActivating.get()) return false
        val state = sessionActivationCoordinator.sessionState.value
        if (state.sessionMode != SessionMode.SERVER_AUTHENTICATED) return false
        if (sessionActivationCoordinator.sessionEpoch.get() != expectedEpoch) return false
        if (expectedServerId != null && state.activeServerId != expectedServerId) return false
        return true
    }

    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            if (isStopped || appLockObserver.lockState.value == AppLockManager.LockState.LOCKED) {
                return@withContext Result.failure()
            }
            val session = sessionActivationCoordinator.ensureInitialized()
            val expectedEpoch = session.sessionEpoch
            val requiredServerId = params.inputData.getString(KEY_SERVER_ID)
            if (!isFencedValid(requiredServerId, expectedEpoch)) {
                return@withContext Result.failure()
            }

            val itemId = params.inputData.getString(KEY_ITEM_ID) ?: return@withContext Result.failure()

            val urlsByName = listOf(
                KEY_URL_PRIMARY to "primary",
                KEY_URL_BACKDROP to "backdrop",
                KEY_URL_LOGO to "logo",
            ).mapNotNull { (key, name) ->
                params.inputData.getString(key)?.let { name to it }
            }

            if (urlsByName.isEmpty()) {
                Timber.d("ImagesDownloaderWorker: no URLs for itemId=%s, skipping", itemId)
                return@withContext Result.success()
            }

            val basePath = "images/$itemId"
            val baseDir = File(appContext.filesDir, basePath)
            try {
                baseDir.mkdirs()
            } catch (e: IOException) {
                Timber.e(e, "ImagesDownloaderWorker: failed to create dir %s", basePath)
                return@withContext Result.retry()
            }

            val client = okHttpClient.newBuilderWithSsrfGuard(UrlTargetPolicy.PUBLIC_OR_LAN).build()
            var anyTransientFailed = false
            var anyPermanentFailed = false

            for ((name, urlString) in urlsByName) {
                if (!isFencedValid(requiredServerId, expectedEpoch)) {
                    return@withContext Result.failure()
                }
                val file = File(baseDir, name)
                if (file.exists()) continue

                try {
                    val request = Request.Builder().url(urlString).build()
                    val outcome = client.newCall(request).execute().use { response ->
                        when {
                            response.isSuccessful -> response.body.bytes() to 200
                            else -> {
                                Timber.w("ImagesDownloaderWorker: HTTP %d for %s/%s", response.code, itemId, name)
                                null to response.code
                            }
                        }
                    }
                    if (!isFencedValid(requiredServerId, expectedEpoch)) {
                        return@withContext Result.failure()
                    }
                    val (bytes, statusCode) = outcome
                    if (bytes != null) {
                        file.writeBytes(bytes)
                        Timber.d("ImagesDownloaderWorker: saved %s/%s (%d bytes)", itemId, name, bytes.size)
                    } else if (statusCode == 401 || statusCode == 403) {
                        anyPermanentFailed = true
                    } else {
                        anyTransientFailed = true
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: SafeUrlBlockedException) {
                    Timber.w(e, "ImagesDownloaderWorker: blocked URL for %s/%s", itemId, name)
                    anyPermanentFailed = true
                } catch (e: IOException) {
                    Timber.e(e, "ImagesDownloaderWorker: failed to download %s/%s", itemId, name)
                    anyTransientFailed = true
                }
            }

            when {
                !isFencedValid(requiredServerId, expectedEpoch) -> Result.failure()
                anyPermanentFailed -> Result.failure()
                anyTransientFailed -> Result.retry()
                else -> Result.success()
            }
        }

    companion object {
        const val KEY_ITEM_ID = "KEY_ITEM_ID"
        const val KEY_SERVER_ID = "KEY_SERVER_ID"
        const val KEY_URL_PRIMARY = "url_primary"
        const val KEY_URL_BACKDROP = "url_backdrop"
        const val KEY_URL_LOGO = "url_logo"

        fun uniqueWorkName(itemId: UUID): String = "download-images:$itemId"
    }
}
