package dev.spatialfin.unified.applock

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * Consecutive-failure counter for PIN unlock attempts, persisted outside
 * SharedPreferences so it survives process death and is always readable even
 * if prefs storage is corrupted or locked. The file is wiped along with
 * everything else when [AppDataWiper] runs.
 */
@Singleton
class AppLockFailureCounter @Inject constructor(
    @ApplicationContext private val appContext: Context,
) {
    private val file: File
        get() = File(appContext.filesDir, FILE_NAME)

    fun get(): Int = runCatching {
        val f = file
        if (!f.exists()) 0 else f.readText().trim().toIntOrNull() ?: 0
    }.getOrElse {
        Timber.w(it, "AppLockFailureCounter: read failed")
        0
    }

    /** Increment the counter and return the new value. */
    fun bump(): Int {
        val next = get() + 1
        write(next)
        return next
    }

    fun reset() {
        write(0)
    }

    private fun write(value: Int) {
        runCatching {
            val f = file
            f.parentFile?.mkdirs()
            f.writeText(value.toString())
        }.onFailure { Timber.w(it, "AppLockFailureCounter: write failed") }
    }

    companion object {
        private const val FILE_NAME = "app_lock_failures"
    }
}
