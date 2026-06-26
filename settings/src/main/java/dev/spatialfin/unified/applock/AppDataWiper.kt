package dev.spatialfin.unified.applock

import android.app.ActivityManager
import android.content.Context
import android.os.Environment
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Nukes every piece of SpatialFin data on device in response to PIN-wipe
 * policy or an explicit user request.
 *
 * [ActivityManager.clearApplicationUserData] clears internal storage,
 * databases, SharedPreferences, and the Hilt-scoped Keystore aliases we own,
 * then kills the process. But it does not touch shared external storage —
 * our downloads live in `Environment.DIRECTORY_DOWNLOADS/SpatialFin`, so we
 * delete that ourselves first.
 */
@Singleton
class AppDataWiper @Inject constructor(
    @ApplicationContext private val appContext: Context,
) {
    suspend fun wipeEverything() = withContext(Dispatchers.IO) {
        runCatching { deleteExternalDownloads() }
            .onFailure { Timber.w(it, "AppDataWiper: failed to delete external downloads") }
        val activityManager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val cleared = runCatching { activityManager?.clearApplicationUserData() }.getOrElse { false }
        Timber.w("AppDataWiper: clearApplicationUserData=%s, process will be killed", cleared)
    }

    private fun deleteExternalDownloads() {
        val root = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            DOWNLOADS_FOLDER_NAME,
        )
        if (root.exists()) root.deleteRecursively()
    }

    companion object {
        private const val DOWNLOADS_FOLDER_NAME = "SpatialFin"
    }
}
