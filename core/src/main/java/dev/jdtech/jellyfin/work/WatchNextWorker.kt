package dev.jdtech.jellyfin.work

import android.content.Context
import android.content.pm.PackageManager
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.watchnext.WatchNextSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Publishes the current user's Continue Watching + Next Up items into the Google
 * TV home screen's Watch Next row.
 *
 * Scheduled by `WatchNextScheduler` — a 30-min periodic cadence plus one-shots
 * triggered after a successful `HomeViewModel.loadData`. Always returns
 * `Result.success()` on failure: the Launcher row is cosmetic, a transient
 * network blip shouldn't fill the WorkManager retry queue.
 */
@HiltWorker
class WatchNextWorker
@AssistedInject
constructor(
    @Assisted private val context: Context,
    @Assisted private val workerParams: WorkerParameters,
    private val repository: JellyfinRepository,
    private val appPreferences: AppPreferences,
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) {
            return Result.success()
        }
        if (appPreferences.getValue(appPreferences.currentServer) == null) {
            return Result.success()
        }

        return try {
            withContext(Dispatchers.IO) {
                val resumeItems = runCatching { repository.getResumeItems() }.getOrDefault(emptyList())
                val nextUpItems = runCatching { repository.getNextUp() }.getOrDefault(emptyList())
                WatchNextSync.sync(context, resumeItems, nextUpItems)
            }
            Result.success()
        } catch (e: Exception) {
            Timber.w(e, "WatchNextWorker: sync failed")
            Result.success()
        }
    }
}
