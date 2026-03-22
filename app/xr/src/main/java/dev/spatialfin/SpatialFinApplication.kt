package dev.spatialfin

import android.app.Application
import android.content.SharedPreferences
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.disk.DiskCache
import coil3.network.cachecontrol.CacheControlCacheStrategy
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.CachePolicy
import coil3.request.crossfade
import coil3.svg.SvgDecoder
import dagger.hilt.android.HiltAndroidApp
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import javax.inject.Inject
import kotlin.time.ExperimentalTime
import okio.Path.Companion.toOkioPath
import timber.log.Timber

@HiltAndroidApp
class SpatialFinApplication : Application(), Configuration.Provider, SingletonImageLoader.Factory {
    @Inject lateinit var appPreferences: AppPreferences

    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    private var logFileTree: LogFileTree? = null
    private val preferenceListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == appPreferences.loggingEnabled.backendName) {
                val enabled = appPreferences.getValue(appPreferences.loggingEnabled)
                updateLogFileTree(enabled = enabled)
                if (!enabled) {
                    CompanionLogUploader.flushNow()
                }
            }
        }

    override fun onCreate() {
        super.onCreate()

        installCrashLogger()

        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        CompanionLogUploader.initialize(this, appPreferences)
        updateLogFileTree(enabled = appPreferences.getValue(appPreferences.loggingEnabled))
        appPreferences.sharedPreferences.registerOnSharedPreferenceChangeListener(preferenceListener)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            val mode =
                when (appPreferences.getValue(appPreferences.theme)) {
                    "system" -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                    "light" -> AppCompatDelegate.MODE_NIGHT_NO
                    "dark" -> AppCompatDelegate.MODE_NIGHT_YES
                    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
            AppCompatDelegate.setDefaultNightMode(mode)
        }
    }

    private fun updateLogFileTree(enabled: Boolean) {
        if (enabled) {
            if (logFileTree == null) {
                installLogFileTree()
            }
        } else {
            uninstallLogFileTree()
        }
    }

    private fun installLogFileTree() {
        try {
            val target = DiagnosticsExport.openLogTarget(this)
            val tree =
                LogFileTree(
                    writer = target.writer,
                    destination = target.destination,
                    onClose = { target.closeable.close() },
                    onLog = { priority, tag, message, throwable ->
                        CompanionLogUploader.enqueue(priority, tag, message, throwable)
                    },
                )
            Timber.plant(tree)
            logFileTree = tree
            Timber.i("LogFileTree: writing to %s", target.destination)
        } catch (e: Exception) {
            Timber.e(e, "LogFileTree: failed to open log file")
        }
    }

    private fun uninstallLogFileTree() {
        logFileTree?.let { tree ->
            Timber.uproot(tree)
            tree.close()
            logFileTree = null
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        appPreferences.sharedPreferences.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        uninstallLogFileTree()
    }

    private fun installCrashLogger() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                DiagnosticsExport.writeCrashReport(this, thread, throwable)
            } catch (_: Exception) {}
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    @OptIn(ExperimentalCoilApi::class, ExperimentalTime::class)
    override fun newImageLoader(context: PlatformContext): ImageLoader {
        return ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(cacheStrategy = { CacheControlCacheStrategy() }))
                add(SvgDecoder.Factory())
            }
            .diskCachePolicy(
                if (appPreferences.getValue(appPreferences.imageCache)) CachePolicy.ENABLED
                else CachePolicy.DISABLED
            )
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("image_cache").toOkioPath())
                    .maxSizeBytes(
                        appPreferences.getValue(appPreferences.imageCacheSize) * 1024L * 1024
                    )
                    .build()
            }
            .crossfade(true)
            .build()
    }
}
