package dev.jdtech.jellyfin.player.xr.voice

import android.content.Context
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

data class GeminiCloudStatus(
    val configured: Boolean,
    val usedModel: Boolean,
    val model: String,
    val details: String,
)

data class GeminiCloudTextResult(
    val text: String?,
    val status: GeminiCloudStatus,
    val failure: Throwable? = null,
)

class GeminiCloudService(
    @Suppress("UNUSED_PARAMETER") private val appContext: Context,
    private val appPreferences: AppPreferences,
    private val repository: JellyfinRepository,
) {
    private val httpClient = OkHttpClient()

    suspend fun generateText(
        prompt: String,
        reason: String,
        temperature: Double = 0.2,
        maxOutputTokens: Int = 256,
    ): GeminiCloudTextResult = withContext(Dispatchers.IO) {
        val apiKey = appPreferences.getValue(appPreferences.voiceAssistantCloudApiKey).orEmpty().trim()
        val useProxy = apiKey.isBlank()
        
        val startedAtMs = System.currentTimeMillis()
        val requestJson =
            JSONObject()
                .put(
                    "contents",
                    JSONArray().put(
                        JSONObject().put(
                            "parts",
                            JSONArray().put(JSONObject().put("text", prompt)),
                        ),
                    ),
                ).put(
                    "generationConfig",
                    JSONObject()
                        .put("temperature", temperature)
                        .put("maxOutputTokens", maxOutputTokens),
                )

        val request = if (useProxy) {
            val baseUrl = repository.getBaseUrl().trimEnd('/')
            Request.Builder()
                .url("$baseUrl/SpatialFin/AI/Proxy")
                .addHeader("Content-Type", "application/json")
                // Jellyfin authentication is usually handled by headers added by the repository or a separate interceptor.
                // If we need to manually add it, we'd need the access token. 
                // Assuming the repository's underlying client or a global interceptor handles auth.
                .post(requestJson.toString().toRequestBody(JSON.toMediaType()))
                .build()
        } else {
            Request.Builder()
                .url("$BASE_URL/$MODEL:generateContent")
                .addHeader("Content-Type", "application/json")
                .addHeader("x-goog-api-key", apiKey)
                .post(requestJson.toString().toRequestBody(JSON.toMediaType()))
                .build()
        }

        try {
            Timber.d(
                "GEMINI: %s %s start model=%s promptChars=%s",
                if (useProxy) "proxy" else "cloud",
                reason,
                MODEL,
                prompt.length,
            )
            httpClient.newCall(request).execute().use { response ->
                val responseBody = response.body.string()
                val latencyMs = System.currentTimeMillis() - startedAtMs

                if (!response.isSuccessful) {
                    val details =
                        "${if (useProxy) "Proxy" else "Cloud"} request failed http=${response.code}; model=$MODEL; latency=${latencyMs}ms; body=${responseBody.take(400)}"
                    Timber.w("GEMINI: %s %s failed %s", if (useProxy) "proxy" else "cloud", reason, details)
                    return@withContext GeminiCloudTextResult(
                        text = null,
                        status =
                            GeminiCloudStatus(
                                configured = true,
                                usedModel = false,
                                model = MODEL,
                                details = details,
                            ),
                    )
                }

                val text = extractText(responseBody)
                val details = "${if (useProxy) "Proxy" else "Cloud"} request succeeded model=$MODEL; latency=${latencyMs}ms"
                if (text.isNullOrBlank()) {
                    Timber.w(
                        "GEMINI: %s %s empty response model=%s body=%s",
                        if (useProxy) "proxy" else "cloud",
                        reason,
                        MODEL,
                        responseBody.take(400),
                    )
                    return@withContext GeminiCloudTextResult(
                        text = null,
                        status =
                            GeminiCloudStatus(
                                configured = true,
                                usedModel = false,
                                model = MODEL,
                                details = "$details; empty response body",
                            ),
                    )
                }

                Timber.i(
                    "GEMINI: %s %s success model=%s latencyMs=%s chars=%s response=%s",
                    if (useProxy) "proxy" else "cloud",
                    reason,
                    MODEL,
                    latencyMs,
                    text.length,
                    text.take(240),
                )
                GeminiCloudTextResult(
                    text = text,
                    status =
                        GeminiCloudStatus(
                            configured = true,
                            usedModel = true,
                            model = MODEL,
                            details = details,
                        ),
                )
            }
        } catch (t: Throwable) {
            val latencyMs = System.currentTimeMillis() - startedAtMs
            val details =
                "${if (useProxy) "Proxy" else "Cloud"} request exception model=$MODEL; latency=${latencyMs}ms; ${t.javaClass.simpleName}: ${t.message}"
            Timber.w(t, "GEMINI: %s %s exception", if (useProxy) "proxy" else "cloud", reason)
            GeminiCloudTextResult(
                text = null,
                status =
                    GeminiCloudStatus(
                        configured = true,
                        usedModel = false,
                        model = MODEL,
                        details = details,
                    ),
                failure = t,
            )
        }
    }

    private fun extractText(responseBody: String): String? {
        val root = JSONObject(responseBody)
        val candidates = root.optJSONArray("candidates") ?: return null
        for (i in 0 until candidates.length()) {
            val candidate = candidates.optJSONObject(i) ?: continue
            val content = candidate.optJSONObject("content") ?: continue
            val parts = content.optJSONArray("parts") ?: continue
            val builder = StringBuilder()
            for (j in 0 until parts.length()) {
                val part = parts.optJSONObject(j) ?: continue
                val text = part.optString("text")
                if (text.isNotBlank()) {
                    if (builder.isNotEmpty()) builder.append('\n')
                    builder.append(text)
                }
            }
            if (builder.isNotEmpty()) return builder.toString()
        }
        return null
    }

    companion object {
        private const val MODEL = "gemini-3.1-flash-lite-preview"
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
        private const val JSON = "application/json; charset=utf-8"
    }
}
