package dev.spatialfin.beam

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
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

object BeamCompanionLogUploader {
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
        sessionId = UUID.randomUUID().toString()
        executor.scheduleWithFixedDelay(
            { runCatching { flushNow() } },
            5,
            5,
            TimeUnit.SECONDS,
        )
        initialized = true
        enqueue(
            priority = Log.INFO,
            tag = "SpatialFinBeam",
            message =
                "SESSION START sessionId=$sessionId model=${Build.MODEL ?: ""} sdk=${Build.VERSION.SDK_INT} app=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            throwable = null,
        )
    }

    fun enqueue(priority: Int, tag: String?, message: String, throwable: Throwable?) {
        if (!initialized || !shouldUpload()) return
        val entry =
            JSONObject()
                .put("timestamp", isoTimestamp())
                .put("level", priorityLabel(priority))
                .put("tag", tag ?: "SpatialFinBeam")
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
            Log.w("BeamCompanionLogUploader", "Failed to upload ${batch.size} log lines", error)
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
        return appPreferences.getValue(appPreferences.companionUrl).isNotBlank() &&
            appPreferences.getValue(appPreferences.companionToken).isNotBlank()
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

    private fun deviceId(): String =
        Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
            ?.takeIf { it.isNotBlank() }
            ?: "spatialfin-beam-${Build.MODEL}-${Build.VERSION.SDK_INT}"

    private fun deviceName(): String = listOfNotNull(Build.MANUFACTURER, Build.MODEL).joinToString(" ")

    private fun isoTimestamp(): String = synchronized(timestampFormat) { timestampFormat.format(Date()) }
}
