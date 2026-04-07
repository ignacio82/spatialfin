package dev.spatialfin.unified

import android.app.Application
import android.content.SharedPreferences
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
import dev.jdtech.jellyfin.core.llm.LlmModelManager
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.spatialfin.BuildConfig
import dev.spatialfin.CompanionLogUploader
import dev.spatialfin.DiagnosticsExport
import dev.spatialfin.LogFileTree
import dev.spatialfin.beam.BeamCompanionLogUploader
import javax.inject.Inject
import kotlin.time.ExperimentalTime
import okio.Path.Companion.toOkioPath
import timber.log.Timber

@HiltAndroidApp
class UnifiedApplication : Application(), Configuration.Provider, SingletonImageLoader.Factory {
    @Inject lateinit var appPreferences: AppPreferences
    @Inject lateinit var workerFactory: HiltWorkerFactory
    // Injected to trigger eager singleton creation so the LLM engine starts
    // initializing as soon as the app launches (not only when the player opens).
    @Inject lateinit var llmModelManager: LlmModelManager

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    private val deviceClass by lazy { detectDeviceClass() }
    private var logFileTree: LogFileTree? = null
    private val preferenceListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == appPreferences.loggingEnabled.backendName) {
                val enabled = appPreferences.getValue(appPreferences.loggingEnabled)
                updateLogFileTree(enabled = enabled)
                if (!enabled) {
                    flushCompanionLogs()
                }
            } else if (key == appPreferences.theme.backendName) {
                applyNightMode()
            }
        }

    override fun onCreate() {
        super.onCreate()

        installCrashLogger()

        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        initializeCompanionLogging()
        updateLogFileTree(enabled = appPreferences.getValue(appPreferences.loggingEnabled))
        appPreferences.sharedPreferences.registerOnSharedPreferenceChangeListener(preferenceListener)
        applyNightMode()
    }

    private fun applyNightMode() {
        val mode =
            when (appPreferences.getValue(appPreferences.theme)) {
                "system" -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                "light" -> AppCompatDelegate.MODE_NIGHT_NO
                "dark" -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    private fun updateLogFileTree(enabled: Boolean) {
        if (enabled) {
            if (logFileTree == null) installLogFileTree()
        } else {
            uninstallLogFileTree()
        }
    }

    private fun initializeCompanionLogging() {
        when (deviceClass) {
            DeviceClass.PHONE -> BeamCompanionLogUploader.initialize(this, appPreferences)
            DeviceClass.TV, DeviceClass.XR -> CompanionLogUploader.initialize(this, appPreferences)
        }
    }

    private fun enqueueCompanionLog(priority: Int, tag: String?, message: String, throwable: Throwable?) {
        when (deviceClass) {
            DeviceClass.PHONE -> BeamCompanionLogUploader.enqueue(priority, tag, message, throwable)
            DeviceClass.TV, DeviceClass.XR -> CompanionLogUploader.enqueue(priority, tag, message, throwable)
        }
    }

    private fun flushCompanionLogs() {
        when (deviceClass) {
            DeviceClass.PHONE -> BeamCompanionLogUploader.flushNow()
            DeviceClass.TV, DeviceClass.XR -> CompanionLogUploader.flushNow()
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
                        enqueueCompanionLog(priority, tag, message, throwable)
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
            runCatching { flushCompanionLogs() }
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
