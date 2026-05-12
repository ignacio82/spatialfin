package dev.jdtech.jellyfin.fcast.receiver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dev.jdtech.jellyfin.fcast.discovery.FCastReceiverAdvertiser
import dev.jdtech.jellyfin.fcast.protocol.FCAST_DEFAULT_PORT
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Foreground service that runs the [FCastReceiverServer] and advertises via mDNS.
 *
 * The library cannot wire the [FCastIngressRouter] directly (it would need to know about
 * `PlayerViewModel` etc.), so the app installs a router via [setRouterProvider] **before** the
 * service starts. If no router is set, [FCastIngressRouter.NoOp] is used and Play frames will be
 * rejected back to the sender with a PlaybackError.
 *
 * Notification UX: a low-priority persistent notification while the receiver is running. Channel
 * id is exposed via [NOTIFICATION_CHANNEL_ID] so the app can create / localize the channel itself
 * if it wants richer copy.
 */
class FCastReceiverService : Service() {

    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var server: FCastReceiverServer? = null
    private var advertiser: FCastReceiverAdvertiser? = null
    private var bootstrapJob: Job? = null

    /**
     * High-performance Wi-Fi lock held while the receiver service is alive.
     *
     * Without this, Android (especially Pixel devices on doze-aggressive firmware) drops the
     * Wi-Fi radio to a low-power state when the screen turns off. The foreground-service
     * notification alone is not enough — the sender's TCP connection sees ~30 s of latency
     * then RST/timeout, and split-A/V audio silently dies even though the receiver service
     * itself is still running. The `WIFI_MODE_FULL_HIGH_PERF` lock keeps the radio active so
     * sender pings round-trip without the Wi-Fi modem napping.
     *
     * Released in [onDestroy]. The CHANGE_WIFI_MULTICAST_STATE permission is already declared
     * in the unified manifest (mDNS requires it); no extra permission is needed for WifiLock.
     */
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        acquireWifiLock()
    }

    private fun acquireWifiLock() {
        if (wifiLock != null) return
        val wifi = applicationContext.getSystemService(WifiManager::class.java) ?: run {
            Timber.tag(TAG).w("WifiManager unavailable — skipping WifiLock")
            return
        }
        val lock = wifi.createWifiLock(
            WifiManager.WIFI_MODE_FULL_HIGH_PERF,
            "SpatialFinFCastReceiver",
        ).apply { setReferenceCounted(false) }
        lock.acquire()
        wifiLock = lock
        Timber.tag(TAG).i("WifiLock acquired (WIFI_MODE_FULL_HIGH_PERF)")
    }

    private fun releaseWifiLock() {
        val lock = wifiLock ?: return
        if (lock.isHeld) {
            runCatching { lock.release() }
                .onFailure { Timber.tag(TAG).w(it, "WifiLock release failed") }
        }
        wifiLock = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val displayName = intent?.getStringExtra(EXTRA_DISPLAY_NAME) ?: getString(applicationInfo.labelRes).orFallback("SpatialFin")
        val port = intent?.getIntExtra(EXTRA_PORT, FCAST_DEFAULT_PORT) ?: FCAST_DEFAULT_PORT
        val appVersion = intent?.getStringExtra(EXTRA_APP_VERSION)

        startForegroundCompat(displayName)
        // Idempotent guard. After a crash, the OS replays its START_STICKY
        // restart intent at the same time as UnifiedApplication.installOnAppStart
        // fires its own startForegroundService — onStartCommand is called twice.
        // Without this, the second bootstrap binds against an already-bound
        // port and fails with EADDRINUSE; its stopSelf() then tears down the
        // first (working) server too, leaving the receiver dead and the mDNS
        // advertiser unregistered.
        if (bootstrapJob?.isActive == true || server != null) {
            return START_STICKY
        }
        bootstrapJob = scope.launch { startServer(displayName, port, appVersion) }
        return START_STICKY
    }

    private suspend fun startServer(displayName: String, port: Int, appVersion: String?) {
        val router = routerProvider.invoke() ?: FCastIngressRouter.NoOp
        val newServer = FCastReceiverServer(
            config = FCastReceiverServer.Config(
                port = port,
                displayName = displayName,
                appVersion = appVersion,
            ),
            routerFactory = { router },
            parentScope = scope,
        )
        try {
            newServer.start()
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "FCast service failed to bind on port %d", port)
            stopSelf()
            return
        }
        server = newServer

        // Hand the inbound-session bridge a way to broadcast state from the Activity to every
        // connected sender, so the sender's mini-controller play/pause icon and seek bar
        // reflect what's actually happening on the receiver.
        FCastInboundSession.bindBroadcaster(
            playback = { update -> scope.launch { newServer.broadcastPlaybackUpdate(update) } },
            volume = { update -> scope.launch { newServer.broadcastVolumeUpdate(update) } },
        )

        val ad = FCastReceiverAdvertiser(applicationContext)
        ad.register(
            instanceName = displayName,
            port = port,
            properties = buildMap {
                put("appName", "SpatialFin")
                if (appVersion != null) put("appVersion", appVersion)
            },
        )
        advertiser = ad
    }

    override fun onDestroy() {
        super.onDestroy()
        bootstrapJob?.cancel()
        FCastInboundSession.unbindBroadcaster()
        server?.stop()
        server = null
        releaseWifiLock()
        scope.launch { advertiser?.unregister() }
            .invokeOnCompletion { scope.cancel() }
    }

    private fun startForegroundCompat(displayName: String) {
        val notification: Notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("SpatialFin is ready to receive")
            .setContentText("$displayName — FCast")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(NOTIFICATION_CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "FCast receiver",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Indicates SpatialFin can accept FCast media from the LAN" }
        nm.createNotificationChannel(channel)
    }

    private fun Context.getString(resId: Int): String? =
        if (resId != 0) try { getString(resId) } catch (_: Exception) { null } else null

    private fun String?.orFallback(fallback: String): String = this ?: fallback

    companion object {
        const val NOTIFICATION_CHANNEL_ID: String = "fcast_receiver"
        const val NOTIFICATION_ID: Int = 0xFCA5

        const val EXTRA_DISPLAY_NAME: String = "fcast.displayName"
        const val EXTRA_PORT: String = "fcast.port"
        const val EXTRA_APP_VERSION: String = "fcast.appVersion"

        internal const val TAG: String = "FCastService"

        /**
         * The app calls this **before** starting the service so the receiver can route inbound
         * Play frames to the player. Provider is invoked once per accepted sender connection so
         * implementations can hand back a fresh router per session if desired.
         */
        @Volatile
        private var routerProvider: () -> FCastIngressRouter? = { null }

        fun setRouterProvider(provider: () -> FCastIngressRouter?) {
            routerProvider = provider
        }

        fun start(context: Context, displayName: String, port: Int = FCAST_DEFAULT_PORT, appVersion: String? = null) {
            val intent = Intent(context, FCastReceiverService::class.java).apply {
                putExtra(EXTRA_DISPLAY_NAME, displayName)
                putExtra(EXTRA_PORT, port)
                appVersion?.let { putExtra(EXTRA_APP_VERSION, it) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FCastReceiverService::class.java))
        }
    }
}
