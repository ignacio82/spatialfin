package dev.spatialfin.beam

import android.app.Application
import android.os.Environment
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
class BeamApplication : Application(), Configuration.Provider, SingletonImageLoader.Factory {
    @Inject lateinit var appPreferences: AppPreferences
    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()

        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Timber.e(
                throwable,
                "FATAL Beam crash thread=%s model=%s sdk=%d",
                thread.name,
                android.os.Build.MODEL,
                android.os.Build.VERSION.SDK_INT,
            )
            writeCrashToDownloads(thread, throwable)
            BeamCompanionLogUploader.flushNow()
            previousHandler?.uncaughtException(thread, throwable)
        }

        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        BeamCompanionLogUploader.initialize(this, appPreferences)
        Timber.plant(
            object : Timber.Tree() {
                override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                    BeamCompanionLogUploader.enqueue(priority, tag, message, t)
                }
            }
        )
    }

    private fun writeCrashToDownloads(thread: Thread, throwable: Throwable) {
        try {
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(downloads, "spatialfin-crash-$timestamp.txt")
            val sw = StringWriter()
            sw.append("SpatialFin Beam Crash Report\n")
            sw.append("Time: ${Date()}\n")
            sw.append("Thread: ${thread.name}\n")
            sw.append("Model: ${android.os.Build.MODEL}\n")
            sw.append("Device: ${android.os.Build.DEVICE}\n")
            sw.append("SDK: ${android.os.Build.VERSION.SDK_INT}\n")
            sw.append("Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n")
            sw.append("\n--- Stack Trace ---\n")
            throwable.printStackTrace(PrintWriter(sw))
            file.writeText(sw.toString())
        } catch (_: Exception) {
            // Best effort - don't let crash logging cause another crash
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
                if (appPreferences.getValue(appPreferences.imageCache)) {
                    CachePolicy.ENABLED
                } else {
                    CachePolicy.DISABLED
                }
            )
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("image_cache").toOkioPath())
                    .maxSizeBytes(
                        appPreferences.getValue(appPreferences.imageCacheSize) * 1024L * 1024L
                    )
                    .build()
            }
            .crossfade(true)
            .build()
    }
}
