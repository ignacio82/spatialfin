package dev.spatialfin

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.work.CompanionSyncWorker
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import timber.log.Timber

/**
 * Live push channel from the companion app. Connects over WebSocket to the
 * companion's root endpoint and listens for small JSON frames. When a
 * `config_changed` frame arrives we enqueue a one-shot [CompanionSyncWorker]
 * so the normal config-pull code path runs — no sync logic is duplicated here.
 *
 * The connection is best-effort: the companion is typically LAN-only, the
 * device may roam between networks, and a 12-hour periodic worker provides
 * the long-tail safety net. We keep reconnects cheap with exponential backoff
 * capped at 60s.
 */
@Singleton
class CompanionLiveSyncClient @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val appPreferences: AppPreferences,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val client = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private var currentSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    @Volatile private var backoffMs: Long = INITIAL_BACKOFF_MS
    @Volatile private var started = false
    @Volatile private var explicitStop = false

    fun start() {
        if (started) return
        started = true
        explicitStop = false
        scope.launch { connect() }
    }

    fun stop() {
        explicitStop = true
        started = false
        reconnectJob?.cancel()
        reconnectJob = null
        currentSocket?.close(WS_NORMAL_CLOSE, "client stop")
        currentSocket = null
    }

    /** Called when companion URL changes (e.g., pairing completes). */
    fun refreshConnection() {
        if (!started) {
            start()
            return
        }
        scope.launch {
            currentSocket?.close(WS_NORMAL_CLOSE, "url changed")
            currentSocket = null
            backoffMs = INITIAL_BACKOFF_MS
            connect()
        }
    }

    private fun connect() {
        if (explicitStop) return
        val base = appPreferences.getValue(appPreferences.companionUrl)
        if (base.isBlank()) {
            Timber.d("CompanionLiveSync: no companion URL yet; skipping connect")
            scheduleReconnect()
            return
        }
        val wsUrl = httpToWs(base)
        val request = Request.Builder()
            .url(wsUrl)
            .addHeader("X-Setup-Token", appPreferences.getValue(appPreferences.companionToken))
            .build()
        Timber.i("CompanionLiveSync: connecting to %s", wsUrl)
        currentSocket = client.newWebSocket(request, SocketListener())
    }

    private fun scheduleReconnect() {
        if (explicitStop) return
        reconnectJob?.cancel()
        val delayMs = backoffMs
        backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        reconnectJob = scope.launch {
            delay(delayMs)
            if (!explicitStop) connect()
        }
    }

    private fun onMessage(text: String) {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return
        val type = json.optString("type")
        if (type == "config_changed") {
            Timber.i("CompanionLiveSync: received config_changed, triggering sync")
            triggerSync()
        }
        // Other frame types (new_logs, analytics_sessions_ingested, etc.) are
        // intended for the admin web UI and safe to ignore.
    }

    private fun triggerSync() {
        val req = OneTimeWorkRequestBuilder<CompanionSyncWorker>()
            .addTag("CompanionSync")
            .addTag("CompanionSyncPush")
            .build()
        WorkManager.getInstance(appContext).enqueueUniqueWork(
            "CompanionSyncPush",
            ExistingWorkPolicy.REPLACE,
            req,
        )
    }

    private inner class SocketListener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Timber.i("CompanionLiveSync: connected")
            backoffMs = INITIAL_BACKOFF_MS
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            onMessage(text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Timber.d("CompanionLiveSync: closing (%d %s)", code, reason)
            webSocket.close(WS_NORMAL_CLOSE, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Timber.d("CompanionLiveSync: closed (%d %s)", code, reason)
            if (currentSocket === webSocket) currentSocket = null
            scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Timber.w(t, "CompanionLiveSync: failure (response=%s)", response?.code ?: "-")
            if (currentSocket === webSocket) currentSocket = null
            scheduleReconnect()
        }
    }

    private fun httpToWs(httpUrl: String): String = httpUrl
        .replaceFirst(Regex("^http:", RegexOption.IGNORE_CASE), "ws:")
        .replaceFirst(Regex("^https:", RegexOption.IGNORE_CASE), "wss:")

    companion object {
        private const val INITIAL_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 60_000L
        private const val WS_NORMAL_CLOSE = 1000

        fun from(context: Context): CompanionLiveSyncClient =
            EntryPointAccessors.fromApplication(
                context.applicationContext,
                CompanionLiveSyncClientEntryPoint::class.java,
            ).companionLiveSyncClient()
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface CompanionLiveSyncClientEntryPoint {
    fun companionLiveSyncClient(): CompanionLiveSyncClient
}
