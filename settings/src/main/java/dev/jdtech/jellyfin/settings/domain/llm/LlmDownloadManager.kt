package dev.jdtech.jellyfin.settings.domain.llm

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

sealed interface DownloadState {
    data object Idle : DownloadState
    data class Downloading(val progress: Float) : DownloadState
    data class Ready(val file: File) : DownloadState
    data class Error(val message: String) : DownloadState
}

/** Lifecycle of the LLM engine (set by LlmModelManager in :core). */
sealed interface ModelState {
    data object Idle : ModelState
    data object Initializing : ModelState
    data class Ready(val backendName: String) : ModelState
    data class Error(val message: String) : ModelState
}

@Singleton
class LlmDownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
) {
    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    private val _modelState = MutableStateFlow<ModelState>(ModelState.Idle)
    val modelState: StateFlow<ModelState> = _modelState.asStateFlow()

    /** Called by LlmModelManager (in :core) to push engine lifecycle updates. */
    fun updateModelState(state: ModelState) {
        _modelState.value = state
    }

    private val modelDir = File(context.filesDir, "models").apply { mkdirs() }
    val modelFile = File(modelDir, MODEL_FILE_NAME)
    private val tempFile = File(modelDir, "$MODEL_FILE_NAME.tmp")

    init {
        if (modelFile.exists() && modelFile.length() > 0) {
            _downloadState.value = DownloadState.Ready(modelFile)
        }
    }

    suspend fun downloadModel() = withContext(Dispatchers.IO) {
        if (modelFile.exists() && modelFile.length() > 0) {
            _downloadState.value = DownloadState.Ready(modelFile)
            return@withContext
        }

        _downloadState.value = DownloadState.Downloading(0f)
        try {
            val resumeFrom = if (tempFile.exists()) tempFile.length() else 0L

            val requestBuilder = Request.Builder()
                .url(MODEL_URL)
                // Disable compression so Range-based resuming works correctly
                .header("Accept-Encoding", "identity")

            if (resumeFrom > 0) {
                requestBuilder.header("Range", "bytes=$resumeFrom-")
                Timber.i("LlmDownloadManager: resuming from byte $resumeFrom")
            }

            val response = okHttpClient.newCall(requestBuilder.build()).execute()
            val code = response.code

            if (code != 200 && code != 206) {
                _downloadState.value = DownloadState.Error("HTTP $code")
                response.close()
                return@withContext
            }

            val body = response.body ?: throw IllegalStateException("Empty response body")
            val contentLength = body.contentLength()
            // For a 206 response the server sends only the remaining slice; add the already-
            // downloaded bytes back to get the true total.
            val totalBytes = if (code == 206 && contentLength > 0) resumeFrom + contentLength
                             else contentLength

            var downloadedBytes = resumeFrom
            var lastProgressTs = System.currentTimeMillis()

            // Append when resuming a partial download, overwrite otherwise.
            FileOutputStream(tempFile, /* append = */ code == 206 && resumeFrom > 0).use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(DEFAULT_BUFFER_SIZE)
                    var bytesRead: Int
                    while (input.read(buf).also { bytesRead = it } != -1) {
                        out.write(buf, 0, bytesRead)
                        downloadedBytes += bytesRead
                        val now = System.currentTimeMillis()
                        if (now - lastProgressTs >= PROGRESS_UPDATE_INTERVAL_MS && totalBytes > 0) {
                            _downloadState.value =
                                DownloadState.Downloading(downloadedBytes.toFloat() / totalBytes)
                            lastProgressTs = now
                        }
                    }
                }
            }

            tempFile.renameTo(modelFile)
            _downloadState.value = DownloadState.Ready(modelFile)
            Timber.i("LlmDownloadManager: model downloaded successfully")
        } catch (e: Exception) {
            Timber.e(e, "LlmDownloadManager: download failed")
            _downloadState.value = DownloadState.Error(e.message ?: "Unknown error")
        }
    }

    fun deleteModel() {
        modelFile.delete()
        tempFile.delete()
        _downloadState.value = DownloadState.Idle
    }

    companion object {
        // Gemma 4 E2B (2.6 GB) — public LiteRT-LM model from litert-community on HuggingFace.
        // No auth token required.
        private const val MODEL_URL =
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm" +
            "/resolve/7fa1d78473894f7e736a21d920c3aa80f950c0db" +
            "/gemma-4-E2B-it.litertlm?download=true"
        private const val MODEL_FILE_NAME = "gemma-4-E2B-it.litertlm"
        private const val PROGRESS_UPDATE_INTERVAL_MS = 200L
    }
}
