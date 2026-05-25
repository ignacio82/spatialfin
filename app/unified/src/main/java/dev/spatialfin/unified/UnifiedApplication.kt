package dev.spatialfin.unified

import android.app.Application
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatDelegate
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.WorkManager
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
import com.skydoves.compose.stability.runtime.ComposeStabilityAnalyzer
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import dev.jdtech.jellyfin.core.llm.LlmModelManager
import dev.jdtech.jellyfin.core.diagnostics.PlayerLaunchBreadcrumbs
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.watchnext.WatchNextScheduler
import dev.jdtech.jellyfin.work.DownloadIntegrityWorker
import dev.spatialfin.BuildConfig
import dev.spatialfin.CompanionLiveSyncClient
import dev.spatialfin.CompanionLogUploader
import dev.spatialfin.DiagnosticsExport
import dev.spatialfin.LogFileTree
import dev.spatialfin.beam.BeamCompanionLogUploader
import dev.spatialfin.fcast.FCastReceiverWiring
import dev.spatialfin.fcast.debug.SplitAvDebugBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.ExperimentalTime
import okio.Path.Companion.toOkioPath
import timber.log.Timber

@HiltAndroidApp
class UnifiedApplication : Application(), Configuration.Provider, SingletonImageLoader.Factory {
    @Inject lateinit var appPreferences: AppPreferences
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var llmModelManager: Lazy<LlmModelManager>
    @Inject lateinit var watchNextScheduler: WatchNextScheduler
    @Inject lateinit var splitAvDebugBridge: SplitAvDebugBridge
    @Inject lateinit var rememberedReceiversStore: dev.spatialfin.fcast.session.RememberedReceiversStore

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    private val deviceClass by lazy { detectDeviceClass() }
    private val capabilities by lazy { DeviceClassCapabilities(deviceClass) }
    private val deferredStartupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val deferredStartupStarted = AtomicBoolean(false)
    private val deferredStartupFallback = Runnable { startDeferredInitialization() }
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
            } else if (key == appPreferences.companionUrl.backendName ||
                key == appPreferences.companionToken.backendName
            ) {
                CompanionLiveSyncClient.from(this).refreshConnection()
            }
        }

    override fun onCreate() {
        super.onCreate()

        installCrashLogger()

        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        ComposeStabilityAnalyzer.setEnabled(BuildConfig.DEBUG)

        appPreferences.sharedPreferences.registerOnSharedPreferenceChangeListener(preferenceListener)
        applyNightMode()

        // FCast: install the receiver router + start the foreground service if the user enabled
        // it. The router is registered even when the service is off so that flipping the pref on
        // later (without an app restart) wires up correctly the moment the service starts. Keep
        // this synchronous: a process started for inbound receiver traffic needs the router
        // before any Play frame arrives.
        FCastReceiverWiring.installOnAppStart(
            context = this,
            prefs = appPreferences,
            deviceClass = deviceClass,
        )

        // Normally the first rendered activity frame starts deferred work. This fallback keeps
        // worker/service-only process starts functional when no activity composes a frame.
        Handler(Looper.getMainLooper()).postDelayed(
            deferredStartupFallback,
            DEFERRED_STARTUP_FALLBACK_MS,
        )
    }

    /**
     * Starts housekeeping and optional services that do not contribute to first paint.
     *
     * [UnifiedMainActivity] invokes this after its first frame; the fallback in [onCreate]
     * handles process starts without UI. The atomic gate makes both paths idempotent.
     */
    fun startDeferredInitialization() {
        if (!deferredStartupStarted.compareAndSet(false, true)) return
        Handler(Looper.getMainLooper()).removeCallbacks(deferredStartupFallback)
        deferredStartupScope.launch {
            initializeCompanionLogging()
            updateLogFileTree(enabled = appPreferences.getValue(appPreferences.loggingEnabled))
            reportPendingPlayerLaunch()
            eagerInitializeLlmIfNeeded()
            CompanionLiveSyncClient.from(this@UnifiedApplication).start()
            // Google TV launcher's Watch Next row — Leanback-only surface.
            if (capabilities.hasLeanback) {
                watchNextScheduler.schedulePeriodic(this@UnifiedApplication)
            }
            DownloadIntegrityWorker.enqueue(WorkManager.getInstance(this@UnifiedApplication))

            // Debug-only adb-broadcast backdoor for split-A/V iteration. No-op in release.
            splitAvDebugBridge.installIfDebug(this@UnifiedApplication)

            // Drop remembered receivers we haven't seen in 90 days. Calibrated entries are
            // retained because the user invested in measuring them.
            runCatching { rememberedReceiversStore.pruneStale() }
                .onFailure { Timber.tag("UnifiedApp").w(it, "remembered receiver prune failed") }
        }
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
        if (capabilities.usesBeamCompanion) {
            BeamCompanionLogUploader.initialize(this, appPreferences)
        } else {
            CompanionLogUploader.initialize(this, appPreferences)
        }
    }

    private fun enqueueCompanionLog(priority: Int, tag: String?, message: String, throwable: Throwable?) {
        if (capabilities.usesBeamCompanion) {
            BeamCompanionLogUploader.enqueue(priority, tag, message, throwable)
        } else {
            CompanionLogUploader.enqueue(priority, tag, message, throwable)
        }
    }

    private fun flushCompanionLogs() {
        if (capabilities.usesBeamCompanion) {
            BeamCompanionLogUploader.flushNow()
        } else {
            CompanionLogUploader.flushNow()
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

    private fun reportPendingPlayerLaunch() {
        PlayerLaunchBreadcrumbs.consumePending(this)?.let { breadcrumb ->
            Timber.w("Previous immersive player launch ended unexpectedly: %s", breadcrumb)
        }
    }

    private fun eagerInitializeLlmIfNeeded() {
        // TV has no active voice surface. XR does, but SceneCore/Compose need to present the
        // first visible panel before LiteRT spends several seconds compiling GPU delegates.
        if (!capabilities.eagerInitLlm) return
        if (!appPreferences.getValue(appPreferences.voiceAssistantGemmaEnabled)) return
        llmModelManager.get()
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
        Handler(Looper.getMainLooper()).removeCallbacks(deferredStartupFallback)
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
        // TV runs on weak GPUs (Amlogic Mali-G31 etc.) where every compositor blend is
        // expensive. Disable Coil's crossfade on TV — focus-based navigation already
        // rapid-fires image loads and the fades stack into visible choppiness.
        val enableCrossfade = capabilities.useImageCrossfades
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
            .crossfade(enableCrossfade)
            .build()
    }

    private companion object {
        const val DEFERRED_STARTUP_FALLBACK_MS = 2_000L
    }
}
