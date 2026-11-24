package dev.spatialfin.unified

import android.app.Activity
import android.app.Application
import android.content.SharedPreferences
import android.os.Bundle
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
import dev.jdtech.jellyfin.companion.CompanionLiveSyncClient
import dev.jdtech.jellyfin.core.diagnostics.PlayerLaunchBreadcrumbs
import dev.jdtech.jellyfin.core.llm.LlmModelManager
import dev.jdtech.jellyfin.core.util.CompanionLogUploader
import dev.jdtech.jellyfin.core.util.XrPlayerSessionClient
import dev.jdtech.jellyfin.core.util.XrPlayerSessionCoordinator
import dev.jdtech.jellyfin.player.core.audio.FfmpegAudioDecoders
import dev.jdtech.jellyfin.player.core.external.PluginHttpDataSourceFactory
import dev.jdtech.jellyfin.plugins.bridge.HttpBridge
import dev.jdtech.jellyfin.session.SessionActivationCoordinator
import dev.jdtech.jellyfin.session.SessionMode
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.watchnext.WatchNextScheduler
import dev.jdtech.jellyfin.work.DownloadIntegrityWorker
import dev.spatialfin.BuildConfig
import dev.spatialfin.DiagnosticsExport
import dev.spatialfin.LogFileTree
import dev.spatialfin.fcast.FCastReceiverWiring
import dev.spatialfin.fcast.debug.SplitAvDebugBridge
import dev.spatialfin.sendspin.SendspinReceiverWiring
import dev.spatialfin.unified.applock.AppLockManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlin.time.ExperimentalTime

@HiltAndroidApp
class UnifiedApplication : Application(), Configuration.Provider, SingletonImageLoader.Factory {
    @Inject lateinit var appPreferences: AppPreferences
    @Inject lateinit var okHttpClient: OkHttpClient
    @Inject lateinit var workerFactory: Lazy<HiltWorkerFactory>
    @Inject lateinit var llmModelManager: Lazy<LlmModelManager>
    @Inject lateinit var watchNextScheduler: Lazy<WatchNextScheduler>
    @Inject lateinit var splitAvDebugBridge: Lazy<SplitAvDebugBridge>
    @Inject lateinit var rememberedReceiversStore: Lazy<dev.spatialfin.fcast.session.RememberedReceiversStore>
    @Inject lateinit var wearStatePublisher: Lazy<dev.spatialfin.companion.host.WearStatePublisher>
    @Inject lateinit var wearCredentialPusher: Lazy<dev.spatialfin.companion.host.WearCredentialPusher>
    @Inject lateinit var wearTvPairingBroker: Lazy<dev.spatialfin.companion.host.WearTvPairingBroker>
    @Inject lateinit var appLockManager: Lazy<AppLockManager>
    @Inject lateinit var sessionActivationCoordinator: Lazy<SessionActivationCoordinator>
    @Inject lateinit var httpBridge: Lazy<HttpBridge>
    @Inject lateinit var xrPlayerSessionCoordinator: Lazy<XrPlayerSessionCoordinator>

    private val isMainProcess: Boolean by lazy {
        runCatching { Application.getProcessName() == packageName }.getOrDefault(true)
    }

    override val workManagerConfiguration: Configuration
        get() =
            Configuration.Builder()
                .setWorkerFactory(workerFactory.get())
                .setDefaultProcessName(packageName)
                .build()

    private val deviceClass by lazy { detectDeviceClass() }
    private val capabilities by lazy { DeviceClassCapabilities(deviceClass) }
    private val deferredStartupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val deferredStartupStarted = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val deferredStartupFallback = Runnable { startDeferredInitialization() }
    private var logFileTree: LogFileTree? = null
    private val preferenceListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == appPreferences.loggingEnabled.backendName) {
                val enabled = appPreferences.getValue(appPreferences.loggingEnabled)
                updateLogFileTree(enabled = enabled)
                if (!enabled) {
                    CompanionLogUploader.flushAsync()
                }
            } else if (key == appPreferences.theme.backendName) {
                applyNightMode()
            } else if (key == appPreferences.companionUrl.backendName ||
                key == appPreferences.companionToken.backendName
            ) {
                CompanionLiveSyncClient.from(this).refreshConnection()
                FCastReceiverWiring.onCompanionConfigChanged()
                CompanionLogUploader.onCompanionConfigChanged()
            }
        }

    private val mainProcessActivityLifecycleCallbacks =
        object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) {
                xrPlayerSessionCoordinator.get().onMainActivityStarted(activity)
            }
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) {
                xrPlayerSessionCoordinator.get().onMainActivityStopped(activity)
            }
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    override fun onCreate() {
        super.onCreate()

        installCrashLogger()

        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        ComposeStabilityAnalyzer.setEnabled(BuildConfig.DEBUG)

        applyNightMode()
        FfmpegAudioDecoders.install()
        PluginHttpDataSourceFactory.configure(okHttpClient)

        if (!isMainProcess) {
            return
        }

        appPreferences.sharedPreferences.registerOnSharedPreferenceChangeListener(preferenceListener)

        httpBridge.get().setAppLockLockedProvider {
            appLockManager.get().lockState.value == AppLockManager.LockState.LOCKED
        }
        XrPlayerSessionClient.installMainProcessCoordinator(xrPlayerSessionCoordinator.get())
        registerActivityLifecycleCallbacks(mainProcessActivityLifecycleCallbacks)

        FCastReceiverWiring.installOnAppStart(
            context = this,
            prefs = appPreferences,
            deviceClass = deviceClass,
            baseHttpClient = okHttpClient,
            appLockObserver = appLockManager.get(),
        )

        SendspinReceiverWiring.installOnAppStart(
            context = this,
            prefs = appPreferences,
            baseHttpClient = okHttpClient,
            appLockObserver = appLockManager.get(),
        )

        mainHandler.postDelayed(
            deferredStartupFallback,
            DEFERRED_STARTUP_FALLBACK_MS,
        )
    }

    fun startDeferredInitialization() {
        if (!isMainProcess) return
        if (!deferredStartupStarted.compareAndSet(false, true)) return
        mainHandler.removeCallbacks(deferredStartupFallback)
        deferredStartupScope.launch {
            val sessionState = sessionActivationCoordinator.get().ensureInitialized()
            CompanionLogUploader.initialize(
                this@UnifiedApplication,
                appPreferences,
                okHttpClient,
                appLockManager.get(),
            )
            updateLogFileTree(enabled = appPreferences.getValue(appPreferences.loggingEnabled))
            reportPendingPlayerLaunch()
            eagerInitializeLlmIfNeeded(sessionState.sessionMode)
            CompanionLiveSyncClient.from(this@UnifiedApplication).start()
            if (capabilities.hasWearCompanionHost) {
                wearCredentialPusher.get().startObserving()
                wearStatePublisher.get().startObserving()
                wearTvPairingBroker.get().startObserving()
                wearCredentialPusher.get().pushCredentials()
            }
            if (capabilities.hasLeanback) {
                watchNextScheduler.get().schedulePeriodic(this@UnifiedApplication)
            }
            DownloadIntegrityWorker.enqueue(WorkManager.getInstance(this@UnifiedApplication))

            splitAvDebugBridge.get().installIfDebug(this@UnifiedApplication)

            runCatching { rememberedReceiversStore.get().pruneStale() }
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
        if (Looper.myLooper() == Looper.getMainLooper()) {
            AppCompatDelegate.setDefaultNightMode(mode)
        } else {
            mainHandler.post { AppCompatDelegate.setDefaultNightMode(mode) }
        }
    }

    private fun updateLogFileTree(enabled: Boolean) {
        if (enabled) {
            if (logFileTree == null) installLogFileTree()
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

    private fun reportPendingPlayerLaunch() {
        PlayerLaunchBreadcrumbs.consumePending(this)?.let { breadcrumb ->
            Timber.w("Previous immersive player launch ended unexpectedly: %s", breadcrumb)
        }
    }

    private fun eagerInitializeLlmIfNeeded(sessionMode: SessionMode) {
        if (!capabilities.eagerInitLlm) return
        if (sessionMode != SessionMode.SERVER_AUTHENTICATED && sessionMode != SessionMode.STANDALONE_SOURCES) return
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
        mainHandler.removeCallbacks(deferredStartupFallback)
        if (isMainProcess) {
            appPreferences.sharedPreferences.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        }
        uninstallLogFileTree()
    }

    private fun installCrashLogger() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                DiagnosticsExport.writeCrashReport(this, thread, throwable)
            } catch (_: Exception) {}
            if (isMainProcess) {
                runCatching { CompanionLogUploader.flushNow() }
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    @OptIn(ExperimentalCoilApi::class, ExperimentalTime::class)
    override fun newImageLoader(context: PlatformContext): ImageLoader {
        val enableCrossfade = capabilities.useImageCrossfades
        dev.jdtech.jellyfin.data.musicassistant.utils.MaImageProxyOverride.resolver = { path, provider ->
            if (dev.jdtech.jellyfin.sendspin.receiver.MusicAssistantRemoteBridge.remoteImageFetcher != null) {
                val encodedPath = android.net.Uri.encode(path)
                "mawebrtc:///imageproxy?path=$encodedPath&provider=$provider"
            } else {
                null
            }
        }
        val builder =
            ImageLoader.Builder(context)
                .components {
                    add(
                        OkHttpNetworkFetcherFactory(
                            callFactory = { okHttpClient },
                            cacheStrategy = { CacheControlCacheStrategy() },
                        ),
                    )
                    add(SvgDecoder.Factory())
                    add(dev.spatialfin.unified.imageloader.WebRtcImageFetcher.Factory())
                }
                .crossfade(enableCrossfade)

        if (!isMainProcess) {
            // Coil's DiskCache uses a single-process DiskLruCache journal; disable it in :xrplayer.
            builder.diskCachePolicy(CachePolicy.DISABLED)
        } else {
            builder
                .diskCachePolicy(
                    if (appPreferences.getValue(appPreferences.imageCache)) CachePolicy.ENABLED
                    else CachePolicy.DISABLED,
                )
                .diskCache {
                    DiskCache.Builder()
                        .directory(context.cacheDir.resolve("image_cache").toOkioPath())
                        .maxSizeBytes(
                            appPreferences.getValue(appPreferences.imageCacheSize) * 1024L * 1024,
                        )
                        .build()
                }
        }
        return builder.build()
    }

    private companion object {
        const val DEFERRED_STARTUP_FALLBACK_MS = 2_000L
    }
}
