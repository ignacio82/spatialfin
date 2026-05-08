package dev.jdtech.jellyfin.fcast.receiver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val displayName = intent?.getStringExtra(EXTRA_DISPLAY_NAME) ?: getString(applicationInfo.labelRes).orFallback("SpatialFin")
        val port = intent?.getIntExtra(EXTRA_PORT, FCAST_DEFAULT_PORT) ?: FCAST_DEFAULT_PORT
        val appVersion = intent?.getStringExtra(EXTRA_APP_VERSION)

        startForegroundCompat(displayName)
        bootstrapJob?.cancel()
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
        server?.stop()
        server = null
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
