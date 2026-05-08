package dev.spatialfin.fcast

import android.content.Context
import dev.jdtech.jellyfin.fcast.receiver.ExternalStreamIngressRouter
import dev.jdtech.jellyfin.fcast.receiver.ExternalStreamPlayer
import dev.jdtech.jellyfin.fcast.receiver.ExternalStreamRequest
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

        if (prefs.getValue(prefs.fcastReceiverEnabled)) {
            FCastReceiverService.start(
                context = context.applicationContext,
                displayName = prefs.getValue(prefs.fcastReceiverDisplayName)
                    .ifBlank { "SpatialFin" },
                appVersion = BuildConfig.VERSION_NAME,
            )
            Timber.i("FCast receiver service started")
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

    override fun pause() = Unit
    override fun resume() = Unit
    override fun stop() = Unit
    override fun seek(seconds: Double) = Unit
    override fun setVolume(volume: Double) = Unit
    override fun setSpeed(speed: Double) = Unit
}
