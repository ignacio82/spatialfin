package dev.jdtech.jellyfin.downloads

import android.content.Context
import java.io.File

/**
 * Private app-internal staging directory (`filesDir/downloads_tmp`, `0o700`) used for
 * in-flight encrypted downloads so plaintext chunks are never written to shared external
 * `Downloads/SpatialFin` before AES-CTR finalization.
 *
 * `DownloadTaskDto.tempPath` and `source.path` remain on `Downloads/SpatialFin/<name>.download`
 * for Room v18 compatibility.
 */
object DownloadStagingPaths {
    const val STAGING_DIR_NAME = "downloads_tmp"

    fun stagingDir(context: Context): File {
        val dir = File(context.filesDir, STAGING_DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        runCatching {
            dir.setReadable(false, false)
            dir.setWritable(false, false)
            dir.setExecutable(false, false)
            dir.setReadable(true, true)
            dir.setWritable(true, true)
            dir.setExecutable(true, true)
        }
        return dir
    }

    fun privateStagingFile(context: Context, taskId: String): File {
        val safeId = taskId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(stagingDir(context), "$safeId.stage")
    }
}
