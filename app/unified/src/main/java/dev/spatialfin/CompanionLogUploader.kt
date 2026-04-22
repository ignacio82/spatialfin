package dev.spatialfin

import android.content.Context
import android.os.Build
import android.util.Log
import dev.jdtech.jellyfin.models.companion.DeviceIdentity
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

object CompanionLogUploader {
    private val lock = Any()
    private val client = OkHttpClient.Builder().build()
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val timestampFormat =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

    private lateinit var appContext: Context
    private lateinit var appPreferences: AppPreferences

    @Volatile private var initialized = false
    @Volatile private var sessionId = UUID.randomUUID().toString()

    private val pendingLogs = mutableListOf<JSONObject>()

    fun initialize(context: Context, preferences: AppPreferences) {
        if (initialized) return
        appContext = context.applicationContext
        appPreferences = preferences
        executor.scheduleWithFixedDelay(
            { runCatching { flushNow() } },
            5,
            5,
            TimeUnit.SECONDS,
        )
        initialized = true
    }

    fun enqueue(priority: Int, tag: String?, message: String, throwable: Throwable?) {
        if (!initialized || !shouldUpload()) return
        val entry =
            JSONObject()
                .put("timestamp", isoTimestamp())
                .put("level", priorityLabel(priority))
                .put("tag", tag ?: "SpatialFin")
                .put("message", message)
                .put("stack", throwable?.stackTraceToString())

        var flushImmediately = false
        synchronized(lock) {
            pendingLogs += entry
            flushImmediately = pendingLogs.size >= 25
        }
        if (flushImmediately) {
            executor.execute { flushNow() }
        }
    }

    fun flushNow() {
        if (!initialized) return
        val companionUrl = appPreferences.getValue(appPreferences.companionUrl).trim().removeSuffix("/")
        val setupToken = appPreferences.getValue(appPreferences.companionToken).trim()
        if (companionUrl.isEmpty() || setupToken.isEmpty()) return
        // `shouldUpload()` already rejected unsafe URLs before enqueue, but
        // re-check in case the URL changed mid-session — we must never put
        // the X-Setup-Token on an http:// request to a public host.
        if (!isSafeForSetupToken(companionUrl)) {
            synchronized(lock) { pendingLogs.clear() }
            return
        }

        val batch =
            synchronized(lock) {
                if (pendingLogs.isEmpty()) return
                pendingLogs.toList().also { pendingLogs.clear() }
            }

        val payload =
            JSONObject()
                .put("deviceId", deviceId())
                .put("deviceName", deviceName())
                .put("manufacturer", Build.MANUFACTURER ?: "")
                .put("model", Build.MODEL ?: "")
                .put("androidVersion", Build.VERSION.RELEASE ?: "")
                .put("appVersion", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                .put("sessionId", sessionId)
                .put("logs", JSONArray(batch))

        val request =
            Request.Builder()
                .url("$companionUrl/api/v1/device-logs")
                .addHeader("X-Setup-Token", setupToken)
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("HTTP ${response.code}")
                }
            }
        } catch (error: Exception) {
            Log.w("CompanionLogUploader", "Failed to upload ${batch.size} log lines", error)
            synchronized(lock) {
                pendingLogs.addAll(0, batch.takeLast(250))
                if (pendingLogs.size > 1000) {
                    pendingLogs.subList(1000, pendingLogs.size).clear()
                }
            }
        }
    }

    private fun shouldUpload(): Boolean {
        if (!initialized) return false
        if (!appPreferences.getValue(appPreferences.loggingEnabled)) return false
        val companionUrl = appPreferences.getValue(appPreferences.companionUrl).trim()
        if (companionUrl.isBlank()) return false
        if (!isSafeForSetupToken(companionUrl)) return false
        return appPreferences.getValue(appPreferences.companionToken).isNotBlank()
    }

    /**
     * Allow the X-Setup-Token on https://… unconditionally, and on http://…
     * only when the host is clearly a local-network destination (loopback,
     * .local mDNS, or RFC1918). The companion is almost always run on the
     * same LAN without a TLS cert, so forcing HTTPS there would make the
     * feature unusable — but we still must not leak the token over cleartext
     * to a public host.
     */
    private fun isSafeForSetupToken(url: String): Boolean {
        val lower = url.lowercase(Locale.US)
        if (lower.startsWith("https://")) return true
        if (!lower.startsWith("http://")) return false
        val hostEnd = lower.indexOfAny(charArrayOf('/', '?', '#'), startIndex = 7)
            .let { if (it == -1) lower.length else it }
        val hostPort = lower.substring(7, hostEnd)
        val host = hostPort.substringBefore(':').trim('[', ']')
        if (host.isEmpty()) return false
        return isLocalNetworkHost(host)
    }

    private fun isLocalNetworkHost(host: String): Boolean {
        if (host == "localhost" || host == "127.0.0.1" || host == "::1") return true
        if (host.endsWith(".local")) return true
        val octets = host.split('.')
        if (octets.size != 4) return false
        val bytes = octets.map { it.toIntOrNull() ?: return false }
        if (bytes.any { it !in 0..255 }) return false
        val (a, b) = bytes[0] to bytes[1]
        // RFC1918 + loopback /8 + link-local 169.254/16.
        return a == 10 ||
            (a == 172 && b in 16..31) ||
            (a == 192 && b == 168) ||
            a == 127 ||
            (a == 169 && b == 254)
    }

    private fun priorityLabel(priority: Int): String =
        when (priority) {
            android.util.Log.VERBOSE -> "V"
            android.util.Log.DEBUG -> "D"
            android.util.Log.INFO -> "I"
            android.util.Log.WARN -> "W"
            android.util.Log.ERROR -> "E"
            android.util.Log.ASSERT -> "A"
            else -> "?"
        }

    private fun deviceId(): String = DeviceIdentity.deviceId(appContext)

    private fun deviceName(): String = listOfNotNull(Build.MANUFACTURER, Build.MODEL).joinToString(" ")

    private fun isoTimestamp(): String = synchronized(timestampFormat) { timestampFormat.format(Date()) }
}
