package dev.jdtech.jellyfin.player.core.transcode

import android.net.Uri
import java.io.File

/**
 * State representing the lifecycle of an on-device video transcoding operation.
 */
sealed interface OnDeviceTranscodeState {
    data object Idle : OnDeviceTranscodeState

    data class InProgress(
        val inputUri: Uri,
        val title: String?,
        val progressPercent: Int,
        val outputFilePath: String,
    ) : OnDeviceTranscodeState

    data class Completed(
        val inputUri: Uri,
        val title: String?,
        val outputFile: File,
    ) : OnDeviceTranscodeState

    data class Failed(
        val inputUri: Uri,
        val title: String?,
        val errorMessage: String,
        val cause: Throwable? = null,
    ) : OnDeviceTranscodeState
}
