package dev.jdtech.jellyfin.work

import android.content.Context
import android.content.pm.PackageManager
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.session.SessionActivationCoordinator
import dev.jdtech.jellyfin.session.SessionMode
import dev.jdtech.jellyfin.watchnext.WatchNextSync
import dev.spatialfin.unified.applock.AppLockManager
import dev.spatialfin.unified.applock.AppLockObserver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

@HiltWorker
class WatchNextWorker
@AssistedInject
constructor(
    @Assisted private val context: Context,
    @Assisted private val workerParams: WorkerParameters,
    private val repository: JellyfinRepository,
    private val sessionActivationCoordinator: SessionActivationCoordinator,
    private val appLockObserver: AppLockObserver,
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) {
            return Result.success()
        }
        if (appLockObserver.lockState.value == AppLockManager.LockState.LOCKED) {
            return Result.success()
        }

        val session = sessionActivationCoordinator.ensureInitialized()
        if (appLockObserver.lockState.value == AppLockManager.LockState.LOCKED) {
            return Result.success()
        }
        if (session.sessionMode != SessionMode.SERVER_AUTHENTICATED) {
            runCatching {
                withContext(Dispatchers.IO) {
                    WatchNextSync.sync(context, emptyList(), emptyList())
                }
            }
            return Result.success()
        }
        val expectedEpoch = session.sessionEpoch

        return try {
            withContext(Dispatchers.IO) {
                val resumeItems = runCatching { repository.getResumeItems() }
                    .onFailure { if (it is CancellationException) throw it }
                    .getOrDefault(emptyList())
                val nextUpItems = runCatching { repository.getNextUp() }
                    .onFailure { if (it is CancellationException) throw it }
                    .getOrDefault(emptyList())
                if (appLockObserver.lockState.value == AppLockManager.LockState.LOCKED ||
                    sessionActivationCoordinator.isActivating.get() ||
                    sessionActivationCoordinator.sessionEpoch.get() != expectedEpoch ||
                    sessionActivationCoordinator.sessionMode.value != SessionMode.SERVER_AUTHENTICATED
                ) {
                    return@withContext
                }
                WatchNextSync.sync(context, resumeItems, nextUpItems)
            }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "WatchNextWorker: sync failed")
            Result.success()
        }
    }
}
