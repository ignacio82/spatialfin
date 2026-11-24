package dev.jdtech.jellyfin.core.util

import android.content.Context
import android.os.Build
import android.util.Log
import dev.jdtech.jellyfin.models.companion.CompanionEndpoint
import dev.jdtech.jellyfin.models.companion.DeviceIdentity
import dev.jdtech.jellyfin.network.UrlTargetPolicy
import dev.jdtech.jellyfin.network.newBuilderWithSsrfGuard
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.spatialfin.unified.applock.AppLockManager
import dev.spatialfin.unified.applock.AppLockObserver
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Shared diagnostic log uploader for XR, Beam (Phone), and TV form factors.
 *
 * Uses the injected `@Singleton OkHttpClient` derived with `UrlTargetPolicy.LAN_ONLY`, respects
 * `authRevoked` on HTTP `401`/`403` until [onCompanionConfigChanged] is called, and never uses
 * Timber internally (using `android.util.Log.w`) to avoid recursive log loops.
 */
object CompanionLogUploader {
    private val lock = Any()
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val timestampFormat =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

    private lateinit var appContext: Context
    private lateinit var appPreferences: AppPreferences
    @Volatile private var httpClient: OkHttpClient? = null
    @Volatile private var appLockObserver: AppLockObserver = AppLockObserver.NoOp
    @Volatile private var scheduledTask: ScheduledFuture<*>? = null

    @Volatile private var initialized = false
    @Volatile private var sessionId = UUID.randomUUID().toString()
    @Volatile var authRevoked: Boolean = false
        internal set

    private val pendingLogs = mutableListOf<JSONObject>()

    fun initialize(
        context: Context,
        preferences: AppPreferences,
        baseHttpClient: OkHttpClient,
        appLockObserver: AppLockObserver = AppLockObserver.NoOp,
    ) {
        synchronized(lock) {
            if (initialized) return
            appContext = context.applicationContext
            appPreferences = preferences
            this.appLockObserver = appLockObserver
            httpClient = baseHttpClient.newBuilderWithSsrfGuard(UrlTargetPolicy.LAN_ONLY).build()
            sessionId = UUID.randomUUID().toString()
            authRevoked = false
            scheduledTask =
                executor.scheduleWithFixedDelay(
                    { runCatching { flushNow() } },
                    5,
                    5,
                    TimeUnit.SECONDS,
                )
            initialized = true
        }
        enqueue(
            priority = Log.INFO,
            tag = "SpatialFin",
            message =
                "SESSION START sessionId=$sessionId model=${Build.MODEL ?: ""} sdk=${Build.VERSION.SDK_INT} app=${appVersionLabel()}",
            throwable = null,
        )
    }

    internal fun resetForTests() {
        synchronized(lock) {
            scheduledTask?.cancel(false)
            scheduledTask = null
            pendingLogs.clear()
            httpClient = null
            appLockObserver = AppLockObserver.NoOp
            authRevoked = false
            initialized = false
        }
    }

    fun onAppUnlocked() {
        if (!initialized || authRevoked || isAppLocked()) return
        val hasBuffered = synchronized(lock) { pendingLogs.isNotEmpty() }
        if (hasBuffered) {
            flushAsync()
        }
    }

    private fun isAppLocked(): Boolean =
        appLockObserver.lockState.value == AppLockManager.LockState.LOCKED

    fun onCompanionConfigChanged() {
        authRevoked = false
        flushAsync()
    }

    fun enqueue(priority: Int, tag: String?, message: String, throwable: Throwable?) {
        if (!initialized || authRevoked || !shouldUpload()) return
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
        if (flushImmediately && !isAppLocked()) {
            flushAsync()
        }
    }

    fun flushAsync() {
        if (!initialized || isAppLocked()) return
        executor.execute { runCatching { flushNow() } }
    }

    fun flushNow() {
        if (!initialized || authRevoked || isAppLocked()) return
        val client = httpClient ?: return
        val endpoint = CompanionEndpoint.fromPreferences(appPreferences) ?: run {
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
                .put("deviceId", DeviceIdentity.deviceId(appContext))
                .put("deviceName", deviceName())
                .put("manufacturer", Build.MANUFACTURER ?: "")
                .put("model", Build.MODEL ?: "")
                .put("androidVersion", Build.VERSION.RELEASE ?: "")
                .put("appVersion", appVersionLabel())
                .put("sessionId", sessionId)
                .put("logs", JSONArray(batch))

        val request =
            endpoint.authorizedRequest(endpoint.deviceLogsUrl)
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()

        try {
            client.newCall(request).execute().use { response ->
                if (response.code == 401 || response.code == 403) {
                    authRevoked = true
                    Log.w("CompanionLogUploader", "Companion rejected setup token (HTTP ${response.code}); suspending uploads")
                    return
                }
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
        if (!initialized || authRevoked) return false
        if (!appPreferences.getValue(appPreferences.loggingEnabled)) return false
        return CompanionEndpoint.fromPreferences(appPreferences) != null
    }

    private fun priorityLabel(priority: Int): String =
        when (priority) {
            Log.VERBOSE -> "V"
            Log.DEBUG -> "D"
            Log.INFO -> "I"
            Log.WARN -> "W"
            Log.ERROR -> "E"
            Log.ASSERT -> "A"
            else -> "?"
        }

    private fun appVersionLabel(): String =
        runCatching {
            val pkg = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
            val code =
                if (Build.VERSION.SDK_INT >= 28) pkg.longVersionCode
                else @Suppress("DEPRECATION") pkg.versionCode.toLong()
            "${pkg.versionName} ($code)"
        }.getOrDefault("?")

    private fun deviceName(): String = listOfNotNull(Build.MANUFACTURER, Build.MODEL).joinToString(" ")

    private fun isoTimestamp(): String = synchronized(timestampFormat) { timestampFormat.format(Date()) }
}
