package dev.jdtech.jellyfin.player.core.transcode

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File

/**
 * On-device video transcoder powered by [androidx.media3.transformer.Transformer].
 *
 * Converts input media files or streams (from arbitrary codecs/containers) into universal
 * H.264 video and AAC audio inside an MP4 container, allowing smooth offline playback in
 * airplane mode without requiring a remote Jellyfin server.
 */
@OptIn(UnstableApi::class)
class OnDeviceTranscoder(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main + Job()),
) {
    private val _state = MutableStateFlow<OnDeviceTranscodeState>(OnDeviceTranscodeState.Idle)
    val state: StateFlow<OnDeviceTranscodeState> = _state.asStateFlow()

    private var activeTransformer: Transformer? = null
    private var progressPollingJob: Job? = null
    private var pendingOutputFile: File? = null

    /**
     * Initiates on-device transcoding of [inputUri].
     *
     * @param inputUri URI of the media to transcode (content://, file://, etc.)
     * @param title User-facing title for notifications/progress.
     * @param targetOutputFile Optional destination file. If null, a file in app cache is used.
     * @param videoMimeType Output video MIME type, defaults to H.264 for maximum compatibility.
     * @param audioMimeType Output audio MIME type, defaults to AAC.
     * @param onComplete Optional callback on successful completion.
     * @param onError Optional callback on failure.
     */
    fun startTranscode(
        inputUri: Uri,
        title: String? = null,
        targetOutputFile: File? = null,
        videoMimeType: String = MimeTypes.VIDEO_H264,
        audioMimeType: String = MimeTypes.AUDIO_AAC,
        onComplete: ((File) -> Unit)? = null,
        onError: ((Throwable) -> Unit)? = null,
    ) {
        // Cancel any active transcoding first
        cancel()

        val destFile = targetOutputFile ?: File(
            context.cacheDir,
            "transcode_offline_${System.currentTimeMillis()}.mp4",
        )
        pendingOutputFile = destFile

        // Delete any existing stale file at destination
        if (destFile.exists()) {
            destFile.delete()
        }

        _state.value = OnDeviceTranscodeState.InProgress(
            inputUri = inputUri,
            title = title,
            progressPercent = 0,
            outputFilePath = destFile.absolutePath,
        )

        val mainHandler = Handler(Looper.getMainLooper())
        val transformerListener = object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                stopProgressPolling()
                activeTransformer = null
                Timber.i(
                    "OnDeviceTranscoder: completed transcode for %s -> %s (durationMs=%d, bytes=%d)",
                    title ?: inputUri,
                    destFile.absolutePath,
                    exportResult.durationMs,
                    exportResult.fileSizeBytes,
                )
                _state.value = OnDeviceTranscodeState.Completed(
                    inputUri = inputUri,
                    title = title,
                    outputFile = destFile,
                )
                onComplete?.invoke(destFile)
            }

            override fun onError(
                composition: Composition,
                exportResult: ExportResult,
                exportException: ExportException,
            ) {
                stopProgressPolling()
                activeTransformer = null
                destFile.takeIf(File::exists)?.delete()
                Timber.e(
                    exportException,
                    "OnDeviceTranscoder: error transcoding %s: %s",
                    title ?: inputUri,
                    exportException.message,
                )
                _state.value = OnDeviceTranscodeState.Failed(
                    inputUri = inputUri,
                    title = title,
                    errorMessage = exportException.localizedMessage ?: "Transcoding failed",
                    cause = exportException,
                )
                onError?.invoke(exportException)
            }
        }

        try {
            val transformer = Transformer.Builder(context)
                .setVideoMimeType(videoMimeType)
                .setAudioMimeType(audioMimeType)
                .addListener(transformerListener)
                .build()

            activeTransformer = transformer

            val mediaItem = MediaItem.fromUri(inputUri)
            val editedMediaItem = EditedMediaItem.Builder(mediaItem).build()

            transformer.start(editedMediaItem, destFile.absolutePath)
            startProgressPolling(transformer, inputUri, title, destFile.absolutePath)
        } catch (e: Exception) {
            stopProgressPolling()
            activeTransformer = null
            destFile.takeIf(File::exists)?.delete()
            Timber.e(e, "OnDeviceTranscoder: failed to start transformer")
            _state.value = OnDeviceTranscodeState.Failed(
                inputUri = inputUri,
                title = title,
                errorMessage = e.localizedMessage ?: "Failed to start transcoder",
                cause = e,
            )
            onError?.invoke(e)
        }
    }

    private fun startProgressPolling(
        transformer: Transformer,
        inputUri: Uri,
        title: String?,
        outputPath: String,
    ) {
        progressPollingJob?.cancel()
        val progressHolder = ProgressHolder()

        progressPollingJob = scope.launch(Dispatchers.Main) {
            while (isActive && activeTransformer != null) {
                val progressState = transformer.getProgress(progressHolder)
                if (progressState == Transformer.PROGRESS_STATE_AVAILABLE) {
                    val percent = progressHolder.progress.coerceIn(0, 100)
                    _state.value = OnDeviceTranscodeState.InProgress(
                        inputUri = inputUri,
                        title = title,
                        progressPercent = percent,
                        outputFilePath = outputPath,
                    )
                }
                delay(300L)
            }
        }
    }

    private fun stopProgressPolling() {
        progressPollingJob?.cancel()
        progressPollingJob = null
    }

    /**
     * Cancels an ongoing transcoding job and cleans up partial output files.
     */
    fun cancel() {
        stopProgressPolling()
        activeTransformer?.cancel()
        activeTransformer = null
        pendingOutputFile?.takeIf(File::exists)?.delete()
        pendingOutputFile = null
        _state.value = OnDeviceTranscodeState.Idle
    }

    /**
     * Resets the transcoder to [OnDeviceTranscodeState.Idle].
     */
    fun reset() {
        cancel()
    }
}
