package dev.jdtech.jellyfin.watchnext

import android.content.Context
import android.content.pm.PackageManager
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dev.jdtech.jellyfin.work.WatchNextWorker
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules `WatchNextWorker` on Google TV devices.
 *
 *  - `schedulePeriodic` — called once from `UnifiedApplication.onCreate` on TV.
 *    30-min cadence (KEEP policy, so app restarts don't thrash the queue).
 *  - `syncNow` — fire-and-forget one-shot triggered after
 *    `HomeViewModel.loadData` succeeds, so the Watch Next row reflects a
 *    just-loaded home screen without waiting up to 30 minutes.
 *
 * Both methods short-circuit on non-TV devices, so it is safe to call them
 * unconditionally from shared code (the libre bundle runs the same home VM).
 */
@Singleton
class WatchNextScheduler @Inject constructor() {

    fun schedulePeriodic(context: Context) {
        if (!isTv(context)) return
        val request =
            PeriodicWorkRequestBuilder<WatchNextWorker>(30, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
    }

    fun syncNow(context: Context) {
        if (!isTv(context)) return
        val request = OneTimeWorkRequestBuilder<WatchNextWorker>().build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                ONE_SHOT_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
    }

    private fun isTv(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

    companion object {
        private const val PERIODIC_WORK_NAME = "watch-next-sync-periodic"
        private const val ONE_SHOT_WORK_NAME = "watch-next-sync-now"
    }
}
