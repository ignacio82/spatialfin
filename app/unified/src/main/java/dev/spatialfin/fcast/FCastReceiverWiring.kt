package dev.spatialfin.fcast

import android.content.Context
import dev.jdtech.jellyfin.fcast.receiver.ExternalStreamIngressRouter
import dev.jdtech.jellyfin.fcast.receiver.ExternalStreamPlayer
import dev.jdtech.jellyfin.fcast.receiver.ExternalStreamRequest
import dev.jdtech.jellyfin.fcast.receiver.FCastInboundSession
import dev.jdtech.jellyfin.fcast.receiver.FCastIngressRouter
import dev.jdtech.jellyfin.fcast.receiver.FCastReceiverService
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.spatialfin.BuildConfig
import dev.spatialfin.unified.DeviceClass
import timber.log.Timber

/**
 * Glue between the `:fcast` receiver service and the rest of the app.
 *
 * - Builds an [ExternalStreamPlayer] that turns inbound FCast Play frames into Activity launches.
 * - On XR, when the autopromote pref is on, asks the active home Activity to enter Full Space
 *   before the inbound player Activity starts (best-effort; no-op if SpatialFin isn't foregrounded).
 * - Registers the router with [FCastReceiverService] **before** the service is started.
 * - Starts/stops the foreground receiver service based on the `fcastReceiverEnabled` preference.
 *
 * Idempotent: [installOnAppStart] is safe to call multiple times.
 */
object FCastReceiverWiring {

    /**
     * Hook installed by `UnifiedMainActivity` so the receiver can ask the active session to
     * autopromote into Full Space when an inbound Play arrives. Null when no XR session is alive.
     */
    @Volatile
    var requestEnterFullSpace: (() -> Unit)? = null

    fun installOnAppStart(
        context: Context,
        prefs: AppPreferences,
        deviceClass: DeviceClass,
    ) {
        // The router handed to the service builds an Activity-launching player.
        val player = IntentBasedExternalStreamPlayer(
            context = context.applicationContext,
            shouldAutopromoteOnXr = {
                deviceClass == DeviceClass.XR && prefs.getValue(prefs.fcastReceiverAutopromote)
            },
        )
        val router: FCastIngressRouter = ExternalStreamIngressRouter(player)
        FCastReceiverService.setRouterProvider { router }

        if (isReceiverEnabled(prefs, deviceClass)) {
            FCastReceiverService.start(
                context = context.applicationContext,
                displayName = resolveDisplayName(prefs),
                appVersion = BuildConfig.VERSION_NAME,
            )
            Timber.i("FCast receiver service started")
        }
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
        return if (model.isNotEmpty()) model else "SpatialFin"
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
            FCastReceiverService.start(
                context = context.applicationContext,
                displayName = resolveDisplayName(prefs),
                appVersion = BuildConfig.VERSION_NAME,
            )
        }
    }

    fun stop(context: Context) {
        FCastReceiverService.stop(context.applicationContext)
        Timber.i("FCast receiver service stopped")
    }
}

/**
 * [ExternalStreamPlayer] that translates each FCast frame into an Intent against the simple
 * [FCastInboundPlayerActivity]. Pause/Resume/Stop/Seek are best-effort — the inbound Activity
 * owns its own ExoPlayer instance and we have no direct handle, so the v1 implementation
 * simply rejects those control actions until the Activity exposes a callback channel.
 */
private class IntentBasedExternalStreamPlayer(
    private val context: Context,
    private val shouldAutopromoteOnXr: () -> Boolean,
) : ExternalStreamPlayer {

    override fun play(request: ExternalStreamRequest): ExternalStreamPlayer.PlayResult {
        try {
            // Best-effort autopromote: tell the active XR session to switch to Full Space
            // before the new Activity is brought up. If the session isn't alive, this is
            // a no-op and the inbound player just launches into whatever space is current.
            if (shouldAutopromoteOnXr()) {
                runCatching { FCastReceiverWiring.requestEnterFullSpace?.invoke() }
                    .onFailure { Timber.w(it, "FCast autopromote failed") }
            }

            val intent = FCastInboundPlayerActivity.createIntent(
                context = context,
                url = request.url,
                container = request.container,
                startMs = (request.startPositionSeconds * 1000.0).toLong(),
                title = request.title,
            )
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
    override fun stop() = FCastInboundSession.stop()
    override fun seek(seconds: Double) = FCastInboundSession.seek(seconds)
    override fun setVolume(volume: Double) = FCastInboundSession.setVolume(volume)
    override fun setSpeed(speed: Double) = FCastInboundSession.setSpeed(speed)
}
