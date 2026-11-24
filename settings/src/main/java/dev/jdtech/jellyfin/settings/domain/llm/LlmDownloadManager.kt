package dev.jdtech.jellyfin.settings.domain.llm

import android.content.Context
import androidx.annotation.VisibleForTesting
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber

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
class LlmDownloadManager @VisibleForTesting constructor(
    private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val isMainProcess: Boolean = true,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        okHttpClient: OkHttpClient,
        appPreferences: AppPreferences,
    ) : this(context, okHttpClient, appPreferences.isMainProcess)

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    private val _modelState = MutableStateFlow<ModelState>(ModelState.Idle)
    val modelState: StateFlow<ModelState> = _modelState.asStateFlow()

    private val opLock = Any()
    private var activeCall: Call? = null
    private var downloadActive = false
    private var generation = 0L

    /** Called by LlmModelManager (in :core) to push engine lifecycle updates. */
    fun updateModelState(state: ModelState) {
        _modelState.value = state
    }

    private val modelDir = File(context.filesDir, "models").apply { mkdirs() }
    val modelFile = File(modelDir, MODEL_FILE_NAME)
    private val tempFile = File(modelDir, "$MODEL_FILE_NAME.tmp")
    private val lockFile = File(modelDir, "$MODEL_FILE_NAME.lock")

    init {
        val devOverride = File("/data/local/tmp", MODEL_FILE_NAME)
        if (devOverride.exists() && devOverride.length() > 0) {
            Timber.i(
                "LlmDownloadManager: using /data/local/tmp override %s (%d bytes)",
                devOverride.absolutePath,
                devOverride.length(),
            )
            _downloadState.value = DownloadState.Ready(devOverride)
        } else if (modelFile.exists() && modelFile.length() > 0) {
            _downloadState.value = DownloadState.Ready(modelFile)
        }
    }

    suspend fun downloadModel() = withContext(Dispatchers.IO) {
        if (!isMainProcess) {
            Timber.w("LlmDownloadManager: downloadModel skipped in non-main process")
            return@withContext
        }
        val myGen: Long
        synchronized(opLock) {
            if (downloadActive) return@withContext
            if (modelFile.exists() && modelFile.length() > 0) {
                _downloadState.value = DownloadState.Ready(modelFile)
                return@withContext
            }
            downloadActive = true
            myGen = ++generation
            _downloadState.value = DownloadState.Downloading(0f)
        }

        try {
            modelDir.mkdirs()
            RandomAccessFile(lockFile, "rw").use { raf ->
                val fileLock =
                    try {
                        raf.channel.tryLock()
                    } catch (e: Exception) {
                        Timber.w(e, "LlmDownloadManager: failed to acquire file lock")
                        null
                    }
                if (fileLock == null) {
                    Timber.w("LlmDownloadManager: another process holds model download lock")
                    synchronized(opLock) {
                        if (generation == myGen) {
                            _downloadState.value = DownloadState.Idle
                        }
                    }
                    return@withContext
                }
                try {
                    val resumeFrom = if (tempFile.exists()) tempFile.length() else 0L
                    val requestBuilder = Request.Builder()
                        .url(MODEL_URL)
                        .header("Accept-Encoding", "identity")
                    if (resumeFrom > 0) {
                        requestBuilder.header("Range", "bytes=$resumeFrom-")
                        Timber.i("LlmDownloadManager: resuming from byte $resumeFrom")
                    }

                    val call = okHttpClient.newCall(requestBuilder.build())
                    synchronized(opLock) {
                        if (generation != myGen) {
                            call.cancel()
                            return@withContext
                        }
                        activeCall = call
                    }

                    call.execute().use { response ->
                        val code = response.code
                        if (code == 416) {
                            tempFile.delete()
                            synchronized(opLock) {
                                if (generation == myGen) {
                                    _downloadState.value = DownloadState.Error("HTTP 416")
                                }
                            }
                            return@withContext
                        }
                        if (code != 200 && code != 206) {
                            synchronized(opLock) {
                                if (generation == myGen) {
                                    _downloadState.value = DownloadState.Error("HTTP $code")
                                }
                            }
                            return@withContext
                        }

                        val body = response.body
                        val contentLength = body.contentLength()
                        val isResuming = (code == 206 && resumeFrom > 0)
                        val totalBytes =
                            if (isResuming && contentLength > 0) {
                                resumeFrom + contentLength
                            } else {
                                contentLength
                            }
                        var downloadedBytes = if (isResuming) resumeFrom else 0L
                        var lastProgressTs = System.currentTimeMillis()

                        FileOutputStream(tempFile, /* append = */ isResuming).use { out ->
                            body.byteStream().use { input ->
                                val buf = ByteArray(DEFAULT_BUFFER_SIZE)
                                var bytesRead: Int
                                while (input.read(buf).also { bytesRead = it } != -1) {
                                    if (synchronized(opLock) { generation != myGen }) {
                                        return@withContext
                                    }
                                    out.write(buf, 0, bytesRead)
                                    downloadedBytes += bytesRead
                                    val now = System.currentTimeMillis()
                                    if (now - lastProgressTs >= PROGRESS_UPDATE_INTERVAL_MS && totalBytes > 0) {
                                        synchronized(opLock) {
                                            if (generation == myGen) {
                                                _downloadState.value =
                                                    DownloadState.Downloading(
                                                        (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
                                                    )
                                            }
                                        }
                                        lastProgressTs = now
                                    }
                                }
                            }
                            out.fd.sync()
                        }

                        if (synchronized(opLock) { generation != myGen }) {
                            tempFile.delete()
                            return@withContext
                        }

                        val lengthValid =
                            totalBytes <= 0 ||
                                (downloadedBytes == totalBytes && tempFile.length() == totalBytes)
                        if (!lengthValid) {
                            Timber.e(
                                "LlmDownloadManager: byte count mismatch (downloaded=%d, file=%d, expected=%d)",
                                downloadedBytes,
                                tempFile.length(),
                                totalBytes,
                            )
                            tempFile.delete()
                            synchronized(opLock) {
                                if (generation == myGen) {
                                    _downloadState.value =
                                        DownloadState.Error("Incomplete model download")
                                }
                            }
                            return@withContext
                        }

                        if (!tempFile.renameTo(modelFile)) {
                            tempFile.copyTo(modelFile, overwrite = true)
                            tempFile.delete()
                        }
                        synchronized(opLock) {
                            if (generation == myGen) {
                                _downloadState.value = DownloadState.Ready(modelFile)
                            }
                        }
                        Timber.i("LlmDownloadManager: model downloaded successfully")
                    }
                } finally {
                    runCatching { fileLock.release() }
                }
            }
        } catch (e: CancellationException) {
            synchronized(opLock) {
                activeCall?.cancel()
                if (generation == myGen && _downloadState.value is DownloadState.Downloading) {
                    _downloadState.value = DownloadState.Idle
                }
            }
            throw e
        } catch (e: Exception) {
            Timber.e(e, "LlmDownloadManager: download failed")
            synchronized(opLock) {
                if (generation == myGen) {
                    _downloadState.value = DownloadState.Error(e.message ?: "Unknown error")
                }
            }
        } finally {
            synchronized(opLock) {
                if (generation == myGen) {
                    activeCall = null
                    downloadActive = false
                }
            }
        }
    }

    fun deleteModel() {
        if (!isMainProcess) {
            Timber.w("LlmDownloadManager: deleteModel skipped in non-main process")
            return
        }
        val callToCancel: Call?
        synchronized(opLock) {
            generation++
            callToCancel = activeCall
            activeCall = null
            downloadActive = false
            _downloadState.value = DownloadState.Idle
        }
        callToCancel?.cancel()
        runCatching { modelFile.delete() }
        runCatching { tempFile.delete() }
    }

    companion object {
        private const val MODEL_URL =
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm" +
            "/resolve/7fa1d78473894f7e736a21d920c3aa80f950c0db" +
            "/gemma-4-E2B-it.litertlm?download=true"
        private const val MODEL_FILE_NAME = "gemma-4-E2B-it.litertlm"
        private const val PROGRESS_UPDATE_INTERVAL_MS = 200L
    }
}
