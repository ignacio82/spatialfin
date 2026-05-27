package dev.spatialfin.fcast

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import dev.jdtech.jellyfin.fcast.receiver.ExternalStreamIngressRouter
import dev.jdtech.jellyfin.fcast.receiver.ExternalStreamPlayer
import dev.jdtech.jellyfin.fcast.receiver.ExternalStreamRequest
import dev.jdtech.jellyfin.fcast.receiver.FCastInboundSession
import dev.jdtech.jellyfin.fcast.receiver.FCastIngressRouter
import dev.jdtech.jellyfin.player.xr.XrFCastInboundPlayerActivity
import dev.jdtech.jellyfin.fcast.protocol.FCAST_DEFAULT_PORT
import dev.jdtech.jellyfin.fcast.receiver.FCastReceiverService
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.spatialfin.BuildConfig
import dev.spatialfin.unified.DeviceClass
import timber.log.Timber

/**
 * Glue between the `:fcast` receiver service and the rest of the app.
 *
 * - Builds an [ExternalStreamPlayer] that turns inbound FCast Play frames into Activity launches.
 * - On XR, routes video into the dedicated Full Space inbound player when the preference allows.
 * - Registers the router with [FCastReceiverService] **before** the service is started.
 * - Starts/stops the foreground receiver service based on the `fcastReceiverEnabled` preference.
 *
 * Idempotent: [installOnAppStart] is safe to call multiple times.
 */
object FCastReceiverWiring {

    fun installOnAppStart(
        context: Context,
        prefs: AppPreferences,
        deviceClass: DeviceClass,
    ) {
        // The router handed to the service builds an Activity-launching player.
        val player = IntentBasedExternalStreamPlayer(
            context = context.applicationContext,
            deviceClass = deviceClass,
            fullSpaceEnabled = { prefs.getValue(prefs.fcastReceiverAutopromote) },
        )
        val router: FCastIngressRouter = ExternalStreamIngressRouter(player)
        FCastReceiverService.setRouterProvider { router }

        if (isReceiverEnabled(prefs, deviceClass)) {
            val started = tryStartReceiver(context.applicationContext, prefs)
            if (!started) {
                // Application.onCreate can run while the process is in CEM/cached state
                // (post-install package-replaced restart, background broadcast wake-up).
                // Android 12+ rejects startForegroundService() in that state with
                // ForegroundServiceStartNotAllowedException. Retry once a user-visible
                // Activity resumes — by then the process is TOP and the call is allowed.
                installForegroundRetry(context.applicationContext, prefs)
            }
        }
    }

    /**
     * Best-effort foreground-service start. Returns true on success, false if the OS denied the
     * start (background-FGS restriction) or any other launch error occurred. Never throws.
     */
    /**
     * FCast receiver TCP port. The debug ("Dev") build installs *alongside* the release app
     * (applicationIdSuffix `.debug`), so two SpatialFin receivers run on the same device —
     * they cannot both bind the single fixed [FCAST_DEFAULT_PORT]. Whoever loses the race
     * gets EADDRINUSE, `stopSelf()`s, and never advertises (the device then appears/disappears
     * from pickers nondeterministically). Give the dev build its own port so prod and dev
     * coexist as independent receivers. Senders honour the mDNS-advertised SRV port, so
     * discovery is unaffected.
     */
    private val receiverPort: Int
        get() = if (BuildConfig.DEBUG) FCAST_DEFAULT_PORT + 1 else FCAST_DEFAULT_PORT

    private fun tryStartReceiver(context: Context, prefs: AppPreferences): Boolean = try {
        FCastReceiverService.start(
            context = context,
            displayName = resolveDisplayName(prefs),
            port = receiverPort,
            appVersion = BuildConfig.VERSION_NAME,
        )
        Timber.i("FCast receiver service started")
        true
    } catch (e: Exception) {
        // ForegroundServiceStartNotAllowedException (API 31+) lives in android.app and is the
        // common case; catch broadly so any OEM-specific FGS gate also degrades gracefully.
        Timber.w(e, "FCast receiver start deferred (will retry on next Activity resume)")
        false
    }

    private fun installForegroundRetry(context: Context, prefs: AppPreferences) {
        val app = context.applicationContext as? Application ?: return
        app.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) {
                    if (tryStartReceiver(app, prefs)) {
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

    /**
     * The advertised receiver name. Used by every SpatialFin install on the LAN as their
     * mDNS service instance name and the Initial-message displayName, so each device shows
     * up distinguishable in another sender's picker.
     *
     * Resolution order:
     *  1. User-written pref value (non-blank wins outright).
     *  2. Device-derived default — `Build.MODEL`, falling back to `"SpatialFin"`.
     *
     * The pref's literal default is `"SpatialFin"`, so we treat that as "unset" too — it's
     * only the literal default value, not a deliberate user choice. Once the user picks any
     * other name (including `"SpatialFin"` typed back in deliberately), it sticks.
     */
    fun resolveDisplayName(prefs: AppPreferences): String {
        val key = prefs.fcastReceiverDisplayName.backendName
        if (prefs.sharedPreferences.contains(key)) {
            val written = prefs.getValue(prefs.fcastReceiverDisplayName).trim()
            if (written.isNotEmpty()) return written
        }
        val model = (android.os.Build.MODEL ?: "").trim()
        val base = if (model.isNotEmpty()) model else "SpatialFin"
        // Debug build coexists with release on the same device; prefix so the dev receiver is
        // unmistakable in a picker and never collides with the release install's mDNS
        // instance name. An explicit user-written name (handled above) still wins outright.
        return if (BuildConfig.DEBUG) "Dev $base" else base
    }

    /**
     * Default-on for every form factor (TV, Beam phone, XR). Every SpatialFin install can
     * receive a cast — pick any of them in another sender's picker and play. An explicit
     * user-written pref always wins.
     */
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

    /**
     * Stop and (if currently enabled) restart the receiver service so a fresh display name
     * or enabled-toggle takes effect immediately. mDNS announcements only carry the
     * instance name observed at registration time, so renaming requires a full
     * unregister/re-register cycle — easiest via service restart.
     *
     * Call this from settings screens after writing the relevant prefs.
     */
    fun applyReceiverConfig(context: Context, prefs: AppPreferences) {
        FCastReceiverService.stop(context.applicationContext)
        if (isReceiverEnabled(prefs)) {
            tryStartReceiver(context.applicationContext, prefs)
        }
    }

    fun stop(context: Context) {
        FCastReceiverService.stop(context.applicationContext)
        Timber.i("FCast receiver service stopped")
    }
}

/**
 * [ExternalStreamPlayer] that translates each FCast frame into a flat or immersive Activity
 * launch. Control frames route through [FCastInboundSession]; immersive playback registers a
 * main-process proxy there through `FCastInboundBridgeService`.
 */
private class IntentBasedExternalStreamPlayer(
    private val context: Context,
    private val deviceClass: DeviceClass,
    private val fullSpaceEnabled: () -> Boolean,
) : ExternalStreamPlayer {
    private var currentDestination: InboundPlaybackDestination? = null

    override fun play(request: ExternalStreamRequest): ExternalStreamPlayer.PlayResult {
        try {
            val destination = InboundPlaybackRoutingPolicy.select(
                deviceClass = deviceClass,
                fullSpaceEnabled = fullSpaceEnabled(),
                request = request,
            )
            val intent = when (destination) {
                InboundPlaybackDestination.FLAT ->
                    FCastInboundPlayerActivity.createIntent(context, request)
                InboundPlaybackDestination.IMMERSIVE_XR ->
                    XrFCastInboundPlayerActivity.createIntent(context, request)
            }
            if (currentDestination != null && currentDestination != destination) {
                FCastInboundSession.suspendControlForReplacement()
            }
            currentDestination = destination
            context.startActivity(intent)
            return ExternalStreamPlayer.PlayResult.Accepted
        } catch (e: Exception) {
            Timber.e(e, "FCast inbound play launch failed")
            return ExternalStreamPlayer.PlayResult.Rejected(
                "Could not start inbound player: ${e.message ?: "unknown error"}",
            )
        }
    }

    // Delegate to the active FCastInboundPlayerActivity through the process-wide bridge.
    // The Activity registered an [ExternalStreamPlayer] backed by its ExoPlayer in onCreate;
    // these calls re-enter that on the main thread. Silent no-ops if no Activity is alive.
    override fun pause() = FCastInboundSession.pause()
    override fun resume() = FCastInboundSession.resume()
    override fun resumeAt(atReceiverMonotonicMs: Long) =
        FCastInboundSession.resumeAt(atReceiverMonotonicMs)
    override fun stop() = FCastInboundSession.stop()
    override fun seek(seconds: Double) {
        FCastInboundSession.seek(seconds)
    }

    override fun setVolume(volume: Double) {
        FCastInboundSession.setVolume(volume)
    }

    override fun setSpeed(speed: Double) {
        FCastInboundSession.setSpeed(speed)
    }

    override fun setTrack(type: Int, trackId: String) {
        FCastInboundSession.setTrack(type, trackId)
    }
}
