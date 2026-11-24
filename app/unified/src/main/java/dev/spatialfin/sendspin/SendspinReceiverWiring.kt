package dev.spatialfin.sendspin

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import dev.jdtech.jellyfin.network.UrlTargetPolicy
import dev.jdtech.jellyfin.network.newBuilderWithSsrfGuard
import dev.jdtech.jellyfin.sendspin.receiver.SendspinReceiverService
import dev.jdtech.jellyfin.sendspin.receiver.SendspinReceiverSession
import dev.jdtech.jellyfin.sendspin.receiver.SendspinUserScope
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.spatialfin.BuildConfig
import dev.spatialfin.fcast.FCastReceiverWiring
import dev.spatialfin.unified.applock.AppLockManager
import dev.spatialfin.unified.applock.AppLockObserver
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import timber.log.Timber

/**
 * Glue between the `:sendspin` receiver service and the rest of the app.
 */
object SendspinReceiverWiring {

    @Volatile
    private var appLockObserver: AppLockObserver = AppLockObserver.NoOp

    private val foregroundRetryInstalled = AtomicBoolean(false)
    private val lockObserverInstalled = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile
    private var lockObserverJob: Job? = null

    private fun isAppLocked(): Boolean =
        appLockObserver.lockState.value == AppLockManager.LockState.LOCKED

    private fun isScopeAllowed(): Boolean =
        SendspinReceiverSession.state.value.activeUserScope !is SendspinUserScope.Disabled

    fun installOnAppStart(
        context: Context,
        prefs: AppPreferences,
        baseHttpClient: OkHttpClient,
        appLockObserver: AppLockObserver = AppLockObserver.NoOp,
    ) {
        this.appLockObserver = appLockObserver
        val appContext = context.applicationContext
        SendspinReceiverService.setAppLockLockedProvider { isAppLocked() }

        // Always inject the SSRF-guarded HTTP stack unconditionally so cold MA actions and later
        // receiver starts never run without a guarded client. Loopback allowlisting is scoped
        // strictly to SendSpinClient via derivedClientFactory.
        val maOutboundClient =
            baseHttpClient
                .newBuilderWithSsrfGuard(
                    policy = UrlTargetPolicy.PUBLIC_OR_LAN,
                    allowCrossOriginRedirects = false,
                )
                .build()
        val derivedClientFactory: ((() -> Set<String>) -> OkHttpClient) = { loopbackTargets ->
            baseHttpClient
                .newBuilderWithSsrfGuard(
                    policy = UrlTargetPolicy.PUBLIC_OR_LAN,
                    allowCrossOriginRedirects = false,
                    loopbackAllowlistProvider = {
                        loopbackTargets()
                            .mapNotNull { hostPort ->
                                val colonIdx = hostPort.lastIndexOf(':')
                                if (colonIdx <= 0) return@mapNotNull null
                                val host = hostPort.substring(0, colonIdx)
                                val port = hostPort.substring(colonIdx + 1).toIntOrNull() ?: return@mapNotNull null
                                host to port
                            }
                            .toSet()
                    },
                )
                .build()
        }
        SendspinReceiverService.setOutboundHttpClient(maOutboundClient, derivedClientFactory)

        if (lockObserverInstalled.compareAndSet(false, true)) {
            lockObserverJob = scope.launch {
                appLockObserver.lockState.collect { lockState ->
                    if (lockState == AppLockManager.LockState.LOCKED) {
                        SendspinReceiverService.stop(appContext)
                    }
                }
            }
        }

        if (FCastReceiverWiring.isReceiverEnabled(prefs) && !isAppLocked() && isScopeAllowed()) {
            tryStartReceiver(appContext, prefs)
        }
    }

    fun onAppUnlocked(context: Context, prefs: AppPreferences) {
        val appContext = context.applicationContext
        if (isAppLocked() || !isScopeAllowed()) return
        if (FCastReceiverWiring.isReceiverEnabled(prefs)) {
            tryStartReceiver(appContext, prefs)
        }
        SendspinReceiverService.onAppUnlocked(appContext)
    }

    fun applyReceiverConfig(context: Context, prefs: AppPreferences) {
        SendspinReceiverService.stop(context.applicationContext)
        if (FCastReceiverWiring.isReceiverEnabled(prefs) && !isAppLocked() && isScopeAllowed()) {
            tryStartReceiver(context.applicationContext, prefs)
        }
    }

    fun stop(context: Context) {
        SendspinReceiverService.stop(context.applicationContext)
        Timber.i("Sendspin receiver service stopped")
    }

    internal fun resetForTests() {
        lockObserverJob?.cancel()
        lockObserverJob = null
        lockObserverInstalled.set(false)
        foregroundRetryInstalled.set(false)
        appLockObserver = AppLockObserver.NoOp
    }

    private val receiverPort: Int
        get() = if (BuildConfig.DEBUG) SendspinReceiverService.DEFAULT_PORT + 1 else SendspinReceiverService.DEFAULT_PORT

    internal fun tryStartReceiver(context: Context, prefs: AppPreferences): Boolean {
        if (isAppLocked() || !isScopeAllowed() || !FCastReceiverWiring.isReceiverEnabled(prefs)) {
            return false
        }
        return try {
            SendspinReceiverService.start(
                context = context,
                displayName = FCastReceiverWiring.resolveDisplayName(prefs),
                clientId = resolveClientId(prefs),
                port = receiverPort,
                softwareVersion = BuildConfig.VERSION_NAME,
            )
            Timber.i("Sendspin receiver service started")
            true
        } catch (e: Exception) {
            Timber.w(e, "Sendspin receiver start deferred (will retry on next Activity resume)")
            installForegroundRetry(context, prefs)
            false
        }
    }

    private fun installForegroundRetry(context: Context, prefs: AppPreferences) {
        val app = context.applicationContext as? Application ?: return
        if (!foregroundRetryInstalled.compareAndSet(false, true)) return
        app.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) {
                    if (isAppLocked() || !isScopeAllowed()) return
                    if (!FCastReceiverWiring.isReceiverEnabled(prefs) || tryStartReceiver(app, prefs)) {
                        foregroundRetryInstalled.set(false)
                        app.unregisterActivityLifecycleCallbacks(this)
                    }
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            },
        )
    }

    private fun resolveClientId(prefs: AppPreferences): String {
        prefs.getValue(prefs.sendspinClientId)?.trim()?.takeIf { it.isNotEmpty() }?.let {
            return it
        }
        val generated = UUID.randomUUID().toString()
        prefs.setValue(prefs.sendspinClientId, generated)
        return generated
    }
}
