package dev.spatialfin.fcast

import android.app.Activity
import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.os.Bundle
import dev.jdtech.jellyfin.core.util.XrPlayerSessionClient
import dev.jdtech.jellyfin.fcast.protocol.FCAST_DEFAULT_PORT
import dev.jdtech.jellyfin.fcast.receiver.ExternalStreamIngressRouter
import dev.jdtech.jellyfin.fcast.receiver.ExternalStreamPlayer
import dev.jdtech.jellyfin.fcast.receiver.ExternalStreamRequest
import dev.jdtech.jellyfin.fcast.receiver.FCastCompanionRegistrar
import dev.jdtech.jellyfin.fcast.receiver.FCastInboundSession
import dev.jdtech.jellyfin.fcast.receiver.FCastIngressRouter
import dev.jdtech.jellyfin.fcast.receiver.FCastReceiverService
import dev.jdtech.jellyfin.fcast.receiver.RegistrationResult
import dev.jdtech.jellyfin.models.companion.CompanionEndpoint
import dev.jdtech.jellyfin.network.PhysicalLanAddressResolver
import dev.jdtech.jellyfin.network.UrlTargetPolicy
import dev.jdtech.jellyfin.network.newBuilderWithSsrfGuard
import dev.jdtech.jellyfin.player.xr.XrFCastInboundPlayerActivity
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.spatialfin.BuildConfig
import dev.spatialfin.unified.DeviceClass
import dev.spatialfin.unified.applock.AppLockManager
import dev.spatialfin.unified.applock.AppLockObserver
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber

/**
 * Glue between the `:fcast` receiver service and the rest of the app.
 */
object FCastReceiverWiring {

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    @Volatile
    private var authRevoked: Boolean = false

    @Volatile
    private var appLockObserver: AppLockObserver = AppLockObserver.NoOp

    private val foregroundRetryInstalled = AtomicBoolean(false)

    private fun isAppLocked(): Boolean =
        appLockObserver.lockState.value == AppLockManager.LockState.LOCKED

    fun installOnAppStart(
        context: Context,
        prefs: AppPreferences,
        deviceClass: DeviceClass,
        baseHttpClient: OkHttpClient,
        appLockObserver: AppLockObserver,
    ) {
        this.appLockObserver = appLockObserver
        val appContext = context.applicationContext
        FCastReceiverService.setAppLockLockedProvider { isAppLocked() }

        val registrarClient =
            baseHttpClient
                .newBuilderWithSsrfGuard(UrlTargetPolicy.LAN_ONLY)
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build()

        FCastReceiverService.setCompanionRegistrar(
            buildCompanionRegistrar(
                appContext = appContext,
                prefs = prefs,
                httpClient = registrarClient,
            ),
        )

        val player =
            IntentBasedExternalStreamPlayer(
                context = appContext,
                deviceClass = deviceClass,
                fullSpaceEnabled = { prefs.getValue(prefs.fcastReceiverAutopromote) },
                isAppLocked = { isAppLocked() },
            )
        val router: FCastIngressRouter = ExternalStreamIngressRouter(player)
        FCastReceiverService.setRouterProvider { router }

        if (isReceiverEnabled(prefs, deviceClass) && !isAppLocked()) {
            tryStartReceiver(appContext, prefs)
        }
    }

    internal fun buildCompanionRegistrar(
        appContext: Context,
        prefs: AppPreferences,
        httpClient: OkHttpClient,
    ): FCastCompanionRegistrar =
        FCastCompanionRegistrar { port, displayName ->
            if (isAppLocked()) {
                return@FCastCompanionRegistrar RegistrationResult.TRANSIENT_FAILURE
            }
            if (authRevoked) {
                return@FCastCompanionRegistrar RegistrationResult.AUTH_REJECTED
            }
            val endpoint =
                CompanionEndpoint.fromPreferences(prefs)
                    ?: return@FCastCompanionRegistrar RegistrationResult.OK
            val cm = appContext.getSystemService(ConnectivityManager::class.java)
            val ip =
                PhysicalLanAddressResolver
                    .resolvePhysicalLanAddresses(cm, includeVpn = true)
                    .firstOrNull()
                    ?.hostAddress
                    ?.substringBefore('%')
                    ?: return@FCastCompanionRegistrar RegistrationResult.TRANSIENT_FAILURE

            withContext(Dispatchers.IO) {
                try {
                    val body =
                        JSONObject()
                            .put("ip", ip)
                            .put("port", port)
                            .put("name", displayName)
                            .toString()
                            .toRequestBody(jsonMediaType)
                    val request =
                        endpoint
                            .authorizedRequest(endpoint.fcastRegisterUrl)
                            .post(body)
                            .build()
                    httpClient.newCall(request).execute().use { response ->
                        when {
                            response.isSuccessful -> RegistrationResult.OK
                            response.code == 401 || response.code == 403 -> {
                                authRevoked = true
                                Timber.w(
                                    "FCast companion registration rejected (HTTP %d); suspending retries until re-paired",
                                    response.code,
                                )
                                RegistrationResult.AUTH_REJECTED
                            }
                            else -> RegistrationResult.TRANSIENT_FAILURE
                        }
                    }
                } catch (e: Exception) {
                    Timber.d(e, "FCast companion registration failed transiently")
                    RegistrationResult.TRANSIENT_FAILURE
                }
            }
        }

    fun onCompanionConfigChanged() {
        authRevoked = false
        if (!isAppLocked()) {
            FCastReceiverService.triggerCompanionRegistrationNow()
        }
    }

    fun onAppLocked(context: Context) {
        FCastReceiverService.stop(context.applicationContext)
    }

    fun onAppUnlocked(context: Context, prefs: AppPreferences, deviceClass: DeviceClass) {
        if (!isAppLocked() && isReceiverEnabled(prefs, deviceClass)) {
            tryStartReceiver(context.applicationContext, prefs)
        }
    }

    internal fun resetAuthRevokedForTests() {
        authRevoked = false
        foregroundRetryInstalled.set(false)
        appLockObserver = AppLockObserver.NoOp
    }

    private val receiverPort: Int
        get() = if (BuildConfig.DEBUG) FCAST_DEFAULT_PORT + 1 else FCAST_DEFAULT_PORT

    private fun tryStartReceiver(context: Context, prefs: AppPreferences): Boolean {
        if (isAppLocked() || !isReceiverEnabled(prefs)) return false
        return try {
            FCastReceiverService.start(
                context = context,
                displayName = resolveDisplayName(prefs),
                port = receiverPort,
                appVersion = BuildConfig.VERSION_NAME,
            )
            Timber.i("FCast receiver service started")
            true
        } catch (e: Exception) {
            Timber.w(e, "FCast receiver start deferred (will retry on next Activity resume)")
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
                    if (isAppLocked()) return
                    if (!isReceiverEnabled(prefs) || tryStartReceiver(app, prefs)) {
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

    fun resolveDisplayName(prefs: AppPreferences): String {
        val key = prefs.fcastReceiverDisplayName.backendName
        if (prefs.sharedPreferences.contains(key)) {
            val written = prefs.getValue(prefs.fcastReceiverDisplayName).trim()
            if (written.isNotEmpty()) return written
        }
        val model = (android.os.Build.MODEL ?: "").trim()
        val base = if (model.isNotEmpty()) model else "SpatialFin"
        return if (BuildConfig.DEBUG) "Dev $base" else base
    }

    fun isReceiverEnabled(prefs: AppPreferences): Boolean {
        val key = prefs.fcastReceiverEnabled.backendName
        return if (prefs.sharedPreferences.contains(key)) {
            prefs.getValue(prefs.fcastReceiverEnabled)
        } else {
            true
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun isReceiverEnabled(prefs: AppPreferences, deviceClass: DeviceClass): Boolean =
        isReceiverEnabled(prefs)

    fun applyReceiverConfig(context: Context, prefs: AppPreferences) {
        FCastReceiverService.stop(context.applicationContext)
        if (!isAppLocked() && isReceiverEnabled(prefs)) {
            tryStartReceiver(context.applicationContext, prefs)
        }
    }

    fun stop(context: Context) {
        FCastReceiverService.stop(context.applicationContext)
        Timber.i("FCast receiver service stopped")
    }
}

private class IntentBasedExternalStreamPlayer(
    private val context: Context,
    private val deviceClass: DeviceClass,
    private val fullSpaceEnabled: () -> Boolean,
    private val isAppLocked: () -> Boolean,
) : ExternalStreamPlayer {
    private var currentDestination: InboundPlaybackDestination? = null

    override fun play(request: ExternalStreamRequest): ExternalStreamPlayer.PlayResult {
        if (isAppLocked()) {
            Timber.i("FCast inbound play rejected while device is locked")
            return ExternalStreamPlayer.PlayResult.Rejected("Device is locked")
        }
        try {
            val destination =
                InboundPlaybackRoutingPolicy.select(
                    deviceClass = deviceClass,
                    fullSpaceEnabled = fullSpaceEnabled(),
                    request = request,
                )
            val intent =
                when (destination) {
                    InboundPlaybackDestination.FLAT ->
                        FCastInboundPlayerActivity.createIntent(context, request)
                    InboundPlaybackDestination.IMMERSIVE_XR ->
                        XrFCastInboundPlayerActivity.createIntent(context, request)
                }
            if (currentDestination != null && currentDestination != destination) {
                FCastInboundSession.suspendControlForReplacement()
            }
            FCastInboundSession.prepareForNewPlayback()
            currentDestination = destination
            if (destination == InboundPlaybackDestination.IMMERSIVE_XR) {
                XrPlayerSessionClient.notifyPendingLaunch(intent)
            }
            context.startActivity(intent)
            return ExternalStreamPlayer.PlayResult.Accepted
        } catch (e: Exception) {
            Timber.e(e, "FCast inbound play launch failed")
            return ExternalStreamPlayer.PlayResult.Rejected(
                "Could not start inbound player: ${e.message ?: "unknown error"}",
            )
        }
    }

    override fun pause() {
        if (isAppLocked()) return
        FCastInboundSession.pause()
    }

    override fun resume() {
        if (isAppLocked()) return
        FCastInboundSession.resume()
    }

    override fun resumeAt(atReceiverMonotonicMs: Long) {
        if (isAppLocked()) return
        FCastInboundSession.resumeAt(atReceiverMonotonicMs)
    }

    override fun stop() {
        if (isAppLocked()) return
        FCastInboundSession.stop()
    }

    override fun seek(seconds: Double) {
        if (isAppLocked()) return
        FCastInboundSession.seek(seconds)
    }

    override fun setVolume(volume: Double) {
        if (isAppLocked()) return
        FCastInboundSession.setVolume(volume)
    }

    override fun setSpeed(speed: Double) {
        if (isAppLocked()) return
        FCastInboundSession.setSpeed(speed)
    }

    override fun setTrack(type: Int, trackId: String) {
        if (isAppLocked()) return
        FCastInboundSession.setTrack(type, trackId)
    }
}
