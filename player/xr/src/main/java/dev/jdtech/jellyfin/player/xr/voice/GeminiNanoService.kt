package dev.jdtech.jellyfin.player.xr.voice

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

data class GeminiNanoStatus(
    val supported: Boolean,
    val statusCode: Int,
    val statusLabel: String,
    val warmedUp: Boolean,
    val details: String,
)

data class GeminiNanoTextResult(
    val text: String?,
    val usedModel: Boolean,
    val status: GeminiNanoStatus,
    val failure: Throwable? = null,
)

class GeminiNanoService(@Suppress("UNUSED_PARAMETER") private val appContext: Context) {
    private val mutex = Mutex()
    private var client: GenerativeModel? = null
    @Volatile private var environmentSummary: String = "packages not checked yet"
    @Volatile private var lastKnownStatus = GeminiNanoStatus(
        supported = false,
        statusCode = Int.MIN_VALUE,
        statusLabel = "UNINITIALIZED",
        warmedUp = false,
        details = "Gemini Nano not checked yet",
    )

    suspend fun initialize(): GeminiNanoStatus = withContext(Dispatchers.IO) {
        logDeviceContext()
        logPackageEnvironment()
        ensureReady(reason = "startup")
    }

    suspend fun status(): GeminiNanoStatus = withContext(Dispatchers.IO) {
        ensureReady(reason = "status")
    }

    suspend fun generateText(
        prompt: String,
        reason: String,
    ): GeminiNanoTextResult = withContext(Dispatchers.IO) {
        val readyStatus = ensureReady(reason)
        if (!readyStatus.supported) {
            Timber.w("GEMINI: %s unavailable: %s", reason, readyStatus.details)
            return@withContext GeminiNanoTextResult(
                text = null,
                usedModel = false,
                status = readyStatus,
            )
        }

        val generationClient = getClient() ?: run {
            val failedStatus =
                readyStatus.copy(
                    supported = false,
                    details = "${readyStatus.details}; client creation returned null",
                )
            lastKnownStatus = failedStatus
            return@withContext GeminiNanoTextResult(
                text = null,
                usedModel = false,
                status = failedStatus,
            )
        }

        val startedAtMs = System.currentTimeMillis()
        try {
            val baseModelName =
                try {
                    generationClient.getBaseModelName()
                } catch (_: Throwable) {
                    "unknown"
                }
            Timber.d(
                "GEMINI: %s generateContent start promptChars=%s status=%s baseModel=%s",
                reason,
                prompt.length,
                readyStatus.statusLabel,
                baseModelName,
            )
            val response = generationClient.generateContent(prompt)
            val text =
                response.candidates.firstOrNull()?.text
                    ?: response.toString()
            val latencyMs = System.currentTimeMillis() - startedAtMs
            val details =
                "${readyStatus.details}; latency=${latencyMs}ms; promptChars=${prompt.length}; baseModel=${baseModelName}"
            val status = readyStatus.copy(details = details)
            lastKnownStatus = status
            Timber.i(
                "GEMINI: %s generateContent success latencyMs=%s chars=%s response=%s",
                reason,
                latencyMs,
                text.length,
                text.take(240),
            )
            GeminiNanoTextResult(
                text = text,
                usedModel = true,
                status = status,
            )
        } catch (t: Throwable) {
            val latencyMs = System.currentTimeMillis() - startedAtMs
            val failedStatus =
                readyStatus.copy(
                    details =
                        "${readyStatus.details}; generationFailed=${t.javaClass.simpleName}: ${t.message}; latency=${latencyMs}ms",
                )
            lastKnownStatus = failedStatus
            Timber.w(t, "GEMINI: %s generateContent failed after %sms", reason, latencyMs)
            GeminiNanoTextResult(
                text = null,
                usedModel = false,
                status = failedStatus,
                failure = t,
            )
        }
    }

    fun destroy() {
        client?.close()
        client = null
    }

    private suspend fun ensureReady(reason: String): GeminiNanoStatus {
        return mutex.withLock {
            val generationClient = getClient()
            if (generationClient == null) {
                val status =
                    GeminiNanoStatus(
                        supported = false,
                        statusCode = Int.MIN_VALUE,
                        statusLabel = "CLIENT_NULL",
                        warmedUp = false,
                        details = "Generation.getClient() returned null during $reason; $environmentSummary",
                    )
                lastKnownStatus = status
                return status
            }

            val statusCode = generationClient.checkStatus()
            Timber.i(
                "GEMINI: %s checkStatus=%s(%s)",
                reason,
                statusLabel(statusCode),
                statusCode,
            )
            when (statusCode) {
                FeatureStatus.AVAILABLE -> {
                    warmupIfNeeded(generationClient, reason)
                    val status =
                        GeminiNanoStatus(
                            supported = true,
                            statusCode = statusCode,
                            statusLabel = statusLabel(statusCode),
                            warmedUp = true,
                            details = "AICore feature available on device; $environmentSummary",
                        )
                    lastKnownStatus = status
                    status
                }

                FeatureStatus.DOWNLOADABLE -> {
                    Timber.i("GEMINI: %s model downloadable, attempting download", reason)
                    generationClient.download().collect { update ->
                        when (update) {
                            is DownloadStatus.DownloadStarted ->
                                Timber.i("GEMINI: %s download started", reason)
                            is DownloadStatus.DownloadProgress ->
                                Timber.i(
                                    "GEMINI: %s download progress bytes=%s",
                                    reason,
                                    update.totalBytesDownloaded,
                                )
                            is DownloadStatus.DownloadCompleted ->
                                Timber.i("GEMINI: %s download complete", reason)
                            is DownloadStatus.DownloadFailed ->
                                Timber.w(
                                    "GEMINI: %s download failed error=%s",
                                    reason,
                                    update.e,
                                )
                        }
                    }
                    val postDownloadStatus = generationClient.checkStatus()
                    Timber.i(
                        "GEMINI: %s post-download status=%s(%s)",
                        reason,
                        statusLabel(postDownloadStatus),
                        postDownloadStatus,
                    )
                    if (postDownloadStatus == FeatureStatus.AVAILABLE) {
                        warmupIfNeeded(generationClient, "$reason-after-download")
                    }
                    val status =
                        GeminiNanoStatus(
                            supported = postDownloadStatus == FeatureStatus.AVAILABLE,
                            statusCode = postDownloadStatus,
                            statusLabel = statusLabel(postDownloadStatus),
                            warmedUp = postDownloadStatus == FeatureStatus.AVAILABLE,
                            details = "Feature downloadable; download flow completed; $environmentSummary",
                        )
                    lastKnownStatus = status
                    status
                }

                FeatureStatus.DOWNLOADING -> {
                    val status =
                        GeminiNanoStatus(
                            supported = false,
                            statusCode = statusCode,
                            statusLabel = statusLabel(statusCode),
                            warmedUp = false,
                            details = "Feature is currently downloading on device; $environmentSummary",
                        )
                    lastKnownStatus = status
                    status
                }

                else -> {
                    val status =
                        GeminiNanoStatus(
                            supported = false,
                            statusCode = statusCode,
                            statusLabel = statusLabel(statusCode),
                            warmedUp = false,
                            details = "AICore unavailable for reason=$reason; $environmentSummary",
                        )
                    lastKnownStatus = status
                    status
                }
            }
        }
    }

    private fun getClient(): GenerativeModel? {
        if (client == null) {
            client = Generation.getClient()
        }
        return client
    }

    private suspend fun warmupIfNeeded(client: GenerativeModel, reason: String) {
        try {
            Timber.d("GEMINI: %s warmup start", reason)
            client.warmup()
            Timber.i("GEMINI: %s warmup complete", reason)
        } catch (t: Throwable) {
            Timber.w(t, "GEMINI: %s warmup failed", reason)
            throw t
        }
    }

    private fun logDeviceContext() {
        Timber.i(
            "GEMINI: device manufacturer=%s model=%s device=%s product=%s sdk=%s release=%s",
            Build.MANUFACTURER,
            Build.MODEL,
            Build.DEVICE,
            Build.PRODUCT,
            Build.VERSION.SDK_INT,
            Build.VERSION.RELEASE,
        )
    }

    private fun logPackageEnvironment() {
        val packageManager = appContext.packageManager
        val aicore = packageSummary(packageManager, "com.google.android.aicore")
        val gms = packageSummary(packageManager, "com.google.android.gms")
        val googleApp = packageSummary(packageManager, "com.google.android.googlequicksearchbox")
        environmentSummary = "aicore=$aicore; gms=$gms; googleApp=$googleApp"
        Timber.i("GEMINI: package environment %s", environmentSummary)
    }

    private fun packageSummary(
        packageManager: PackageManager,
        packageName: String,
    ): String {
        val info = packageInfoOrNull(packageManager, packageName)
        if (info == null) return "missing"

        val versionName = info.versionName ?: "unknown"
        val versionCode = PackageInfoCompat.longVersionCode(info)
        val enabled =
            try {
                packageManager.getApplicationInfo(packageName, 0).enabled
            } catch (_: Throwable) {
                true
            }
        return "installed(versionName=$versionName,versionCode=$versionCode,enabled=$enabled)"
    }

    private fun packageInfoOrNull(
        packageManager: PackageManager,
        packageName: String,
    ): PackageInfo? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(0),
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun statusLabel(code: Int): String {
        return when (code) {
            FeatureStatus.AVAILABLE -> "AVAILABLE"
            FeatureStatus.DOWNLOADABLE -> "DOWNLOADABLE"
            FeatureStatus.DOWNLOADING -> "DOWNLOADING"
            FeatureStatus.UNAVAILABLE -> "UNAVAILABLE"
            else -> "UNKNOWN_$code"
        }
    }
}

private object PackageInfoCompat {
    fun longVersionCode(info: PackageInfo): Long {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }
}
