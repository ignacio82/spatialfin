package dev.jdtech.jellyfin.utils

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.downloads.DownloadStorageManager
import dev.jdtech.jellyfin.models.downloadTaskId
import java.io.File
import java.util.UUID
import javax.inject.Inject

@AndroidEntryPoint
class DownloadReceiver : BroadcastReceiver() {

    @Inject lateinit var database: ServerDatabaseDao

    @Inject lateinit var downloadStorageManager: DownloadStorageManager

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "android.intent.action.DOWNLOAD_COMPLETE") {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            if (id != -1L) {
                val downloadManager = context.getSystemService(DownloadManager::class.java)
                val query = DownloadManager.Query().setFilterById(id)
                val cursor = downloadManager.query(query)
                if (!cursor.moveToFirst()) {
                    cursor.close()
                    return
                }
                val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                cursor.close()

                val source = database.getSourceByDownloadId(id)
                if (source != null) {
                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        val path = downloadStorageManager.completedPathFor(source.path)
                        val successfulRename = File(source.path).renameTo(File(path))
                        if (successfulRename) {
                            val task = database.getDownloadTaskById(downloadTaskId(source.itemId, source.id))
                            database.setSourcePath(source.id, path)
                            database.clearSourceDownloadId(source.id)
                            database.updateDownloadTask(
                                id = downloadTaskId(source.itemId, source.id),
                                downloadId = null,
                                bytesDownloaded = task?.bytesDownloaded ?: 0L,
                                totalBytes = task?.totalBytes,
                                eTag = task?.eTag,
                                lastModified = task?.lastModified,
                                status = DownloadManager.STATUS_SUCCESSFUL,
                                progress = 100,
                                errorMessage = null,
                                updatedAt = System.currentTimeMillis(),
                            )
                        } else {
                            markFailedSource(source.itemId, source.id, "Failed to finalize download")
                        }
                    } else {
                        database.clearSourceDownloadId(source.id)
                        markFailedSource(source.itemId, source.id, "Download failed (reason $reason)")
                    }
                } else {
                    val mediaStream = database.getMediaStreamByDownloadId(id)
                    if (mediaStream != null) {
                        if (status == DownloadManager.STATUS_SUCCESSFUL) {
                            val path = downloadStorageManager.completedPathFor(mediaStream.path)
                            val successfulRename = File(mediaStream.path).renameTo(File(path))
                            if (successfulRename) {
                                database.setMediaStreamPath(mediaStream.id, path)
                                database.clearMediaStreamDownloadId(mediaStream.id)
                            } else {
                                database.deleteMediaStream(mediaStream.id)
                            }
                        } else {
                            database.clearMediaStreamDownloadId(mediaStream.id)
                        }
                    }
                }
            }
        }
    }

    private fun markFailedSource(itemId: UUID, sourceId: String, message: String) {
        val task = database.getDownloadTaskById(downloadTaskId(itemId, sourceId))
        database.updateDownloadTask(
            id = downloadTaskId(itemId, sourceId),
            downloadId = null,
            bytesDownloaded = task?.bytesDownloaded ?: 0L,
            totalBytes = task?.totalBytes,
            eTag = task?.eTag,
            lastModified = task?.lastModified,
            status = DownloadManager.STATUS_FAILED,
            progress = 0,
            errorMessage = message,
            updatedAt = System.currentTimeMillis(),
        )
    }
}
