package dev.jdtech.jellyfin.player.xr.voice

import android.content.Context
import com.google.ai.edge.aicore.DownloadCallback
import com.google.ai.edge.aicore.DownloadConfig
import com.google.ai.edge.aicore.GenerativeAIException
import com.google.ai.edge.aicore.GenerativeModel
import com.google.ai.edge.aicore.generationConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

class SmartChatEngine(private val appContext: Context) {
    private var generativeModel: GenerativeModel? = null
    var isModelAvailable = false
        private set

    suspend fun initialize() = withContext(Dispatchers.IO) {
        try {
            val generationConfig =
                generationConfig {
                    this.context = appContext.applicationContext
                    temperature = 0.4f
                    maxOutputTokens = 256
                    candidateCount = 1
                }
            val downloadConfig =
                DownloadConfig(
                    object : DownloadCallback {
                        override fun onDownloadDidNotStart(e: GenerativeAIException) {
                            Timber.w(e, "VOICE CHAT: Gemini Nano download did not start")
                        }

                        override fun onDownloadFailed(
                            failureStatus: String,
                            e: GenerativeAIException,
                        ) {
                            Timber.w(e, "VOICE CHAT: Gemini Nano download failed: %s", failureStatus)
                        }
                    }
                )
            generativeModel = GenerativeModel(generationConfig, downloadConfig)
            isModelAvailable = true
        } catch (e: Exception) {
            Timber.w(e, "VOICE CHAT: Gemini Nano unavailable")
            generativeModel = null
            isModelAvailable = false
        }
    }

    suspend fun query(
        question: String,
        mediaTitle: String,
        mediaDescription: String,
        recentSubtitles: String = ""
    ): String? = withContext(Dispatchers.IO) {
        val model = generativeModel ?: return@withContext null

        val prompt = buildPrompt(question, mediaTitle, mediaDescription, recentSubtitles)
        return@withContext try {
            val response = withTimeoutOrNull(8_000L) {
                model.generateContent(prompt).text?.trim()
            }
            response
        } catch (e: Exception) {
            Timber.w(e, "VOICE CHAT: Nano query failed for: %s", question)
            null
        }
    }

    private fun buildPrompt(
        question: String,
        mediaTitle: String,
        mediaDescription: String,
        recentSubtitles: String
    ): String {
        return """
            You are a helpful AI assistant inside an immersive XR media player called SpatialFin. 
            Keep your answers to one or two punchy sentences. 
            Do not reveal major spoilers about the ending unless explicitly requested.

            Context about the current media:
            Title: $mediaTitle
            Description: $mediaDescription

            ${if (recentSubtitles.isNotBlank()) "Recent dialogue (last 60s): $recentSubtitles" else ""}

            User question: "$question"
            Answer:
        """.trimIndent()
    }

    fun destroy() {
        generativeModel?.close()
        generativeModel = null
        isModelAvailable = false
    }
}
