package dev.jdtech.jellyfin.work

import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.DownloadTaskKind
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber

@HiltWorker
class ResumableDownloadWorker
@AssistedInject
constructor(
    @Assisted private val appContext: Context,
    @Assisted private val params: WorkerParameters,
    private val database: ServerDatabaseDao,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            val taskId = params.inputData.getString(KEY_TASK_ID) ?: return@withContext Result.failure()
            val task = database.getDownloadTaskById(taskId) ?: return@withContext Result.failure()
            Timber.i(
                "Resumable worker starting taskId=%s kind=%s itemId=%s sourceId=%s mediaStreamId=%s bytesDownloaded=%s totalBytes=%s tempPath=%s finalPath=%s",
                taskId,
                task.kind,
                task.itemId,
                task.sourceId,
                task.mediaStreamId,
                task.bytesDownloaded,
                task.totalBytes,
                task.tempPath,
                task.finalPath,
            )

            setForeground(createForegroundInfo(task.progress, taskId))

            val networkRestriction = currentNetworkRestrictionMessage(task.accessToken != null)
            if (networkRestriction != null) {
                Timber.w("Resumable worker paused taskId=%s reason=%s", taskId, networkRestriction)
                updateTask(
                    taskId = taskId,
                    bytesDownloaded = task.bytesDownloaded,
                    totalBytes = task.totalBytes,
                    eTag = task.eTag,
                    lastModified = task.lastModified,
                    status = DownloadManager.STATUS_PAUSED,
                    progress = task.progress,
                    errorMessage = networkRestriction,
                )
                return@withContext Result.retry()
            }

            val tempFile = File(task.tempPath)
            val finalFile = File(task.finalPath)
            val parentDir = tempFile.parentFile
            if (parentDir != null && !parentDir.exists() && !parentDir.mkdirs()) {
                Timber.e("Resumable worker failed to create parent dir taskId=%s dir=%s", taskId, parentDir.path)
                markFailed(taskId, task.bytesDownloaded, task.totalBytes, task.eTag, task.lastModified, "Failed to create download directory")
                return@withContext Result.failure()
            }
            Timber.i(
                "Resumable worker file prep taskId=%s parent=%s parentExists=%s tempExists=%s finalExists=%s",
                taskId,
                parentDir?.path,
                parentDir?.exists(),
                tempFile.exists(),
                finalFile.exists(),
            )

            val existingBytes = tempFile.takeIf(File::exists)?.length() ?: 0L
            val requestBuilder = Request.Builder().url(task.requestUrl)
            task.accessToken?.takeIf { it.isNotBlank() }?.let {
                requestBuilder.header("X-Emby-Token", it)
            }
            if (existingBytes > 0L) {
                requestBuilder.header("Range", "bytes=$existingBytes-")
                task.eTag?.takeIf { it.isNotBlank() }?.let { requestBuilder.header("If-Range", it) }
                    ?: task.lastModified?.takeIf { it.isNotBlank() }?.let {
                        requestBuilder.header("If-Range", it)
                    }
            }

            val client = OkHttpClient()
            val response =
                try {
                    Timber.i(
                        "Resumable worker request taskId=%s url=%s existingBytes=%s hasRange=%s",
                        taskId,
                        task.requestUrl,
                        existingBytes,
                        existingBytes > 0L,
                    )
                    client.newCall(requestBuilder.build()).execute()
                } catch (e: Exception) {
                    markFailed(taskId, existingBytes, task.totalBytes, task.eTag, task.lastModified, e.message ?: "Download failed")
                    Timber.e(e, "Resumable worker network failure taskId=%s", taskId)
                    return@withContext Result.retry()
                }

            response.use { httpResponse ->
                Timber.i(
                    "Resumable worker response taskId=%s code=%s contentLength=%s etag=%s lastModified=%s",
                    taskId,
                    httpResponse.code,
                    httpResponse.body.contentLength(),
                    httpResponse.header("ETag"),
                    httpResponse.header("Last-Modified"),
                )
                when {
                    httpResponse.code == 416 -> {
                        if (tempFile.exists() && tempFile.length() > 0L) {
                            finalizeSuccess(taskId, task.tempPath, task.finalPath, tempFile.length())
                            return@withContext Result.success()
                        }
                        Timber.w("Resumable worker range not satisfiable taskId=%s", taskId)
                        markFailed(taskId, existingBytes, task.totalBytes, task.eTag, task.lastModified, "Range not satisfiable")
                        return@withContext Result.failure()
                    }
                    !httpResponse.isSuccessful -> {
                        Timber.w("Resumable worker http failure taskId=%s code=%s", taskId, httpResponse.code)
                        markFailed(taskId, existingBytes, task.totalBytes, task.eTag, task.lastModified, "HTTP ${httpResponse.code}")
                        return@withContext Result.retry()
                    }
                }

                val append = existingBytes > 0L && httpResponse.code == 206
                if (!append && existingBytes > 0L && tempFile.exists()) {
                    tempFile.delete()
                }

                val responseBody = httpResponse.body ?: run {
                    markFailed(taskId, existingBytes, task.totalBytes, task.eTag, task.lastModified, "Empty response body")
                    return@withContext Result.failure()
                }

                val responseContentLength = responseBody.contentLength().takeIf { it >= 0L }
                val totalBytes =
                    if (append) {
                        responseContentLength?.plus(existingBytes) ?: task.totalBytes
                    } else {
                        responseContentLength ?: task.totalBytes
                    }
                val eTag = httpResponse.header("ETag") ?: task.eTag
                val lastModified = httpResponse.header("Last-Modified") ?: task.lastModified

                try {
                    RandomAccessFile(tempFile, "rw").use { output ->
                        if (append) {
                            output.seek(existingBytes)
                        } else {
                            output.setLength(0L)
                        }

                        responseBody.byteStream().use { input ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var downloadedBytes = if (append) existingBytes else 0L
                            var lastReportedBytes = downloadedBytes

                            updateTask(
                                taskId = taskId,
                                bytesDownloaded = downloadedBytes,
                                totalBytes = totalBytes,
                                eTag = eTag,
                                lastModified = lastModified,
                                status = DownloadManager.STATUS_RUNNING,
                                progress = progressFor(downloadedBytes, totalBytes),
                                errorMessage = null,
                            )

                            while (true) {
                                if (isStopped) {
                                    updateTask(
                                        taskId = taskId,
                                        bytesDownloaded = downloadedBytes,
                                        totalBytes = totalBytes,
                                        eTag = eTag,
                                        lastModified = lastModified,
                                        status = DownloadManager.STATUS_PAUSED,
                                        progress = progressFor(downloadedBytes, totalBytes),
                                        errorMessage = "Download paused",
                                    )
                                    return@withContext Result.retry()
                                }

                                val read = input.read(buffer)
                                if (read == -1) break

                                output.write(buffer, 0, read)
                                downloadedBytes += read

                                if (downloadedBytes - lastReportedBytes >= PROGRESS_UPDATE_BYTES) {
                                    val progress = progressFor(downloadedBytes, totalBytes)
                                    updateTask(
                                        taskId = taskId,
                                        bytesDownloaded = downloadedBytes,
                                        totalBytes = totalBytes,
                                        eTag = eTag,
                                        lastModified = lastModified,
                                        status = DownloadManager.STATUS_RUNNING,
                                        progress = progress,
                                        errorMessage = null,
                                    )
                                    Timber.d(
                                        "Resumable worker progress taskId=%s downloadedBytes=%s totalBytes=%s progress=%s",
                                        taskId,
                                        downloadedBytes,
                                        totalBytes,
                                        progress,
                                    )
                                    setForeground(createForegroundInfo(progress, taskId))
                                    lastReportedBytes = downloadedBytes
                                }
                            }

                            output.fd.sync()
                            if (finalFile.exists()) {
                                finalFile.delete()
                            }
                            finalizeSuccess(taskId, task.tempPath, task.finalPath, downloadedBytes)
                            return@withContext Result.success()
                        }
                    }
                } catch (e: IOException) {
                    Timber.e(e, "Resumable worker file I/O failure taskId=%s temp=%s final=%s", taskId, task.tempPath, task.finalPath)
                    markFailed(taskId, existingBytes, totalBytes, eTag, lastModified, "File I/O failure: ${e.message}")
                    return@withContext Result.failure()
                } catch (e: SecurityException) {
                    Timber.e(e, "Resumable worker storage security failure taskId=%s temp=%s final=%s", taskId, task.tempPath, task.finalPath)
                    markFailed(taskId, existingBytes, totalBytes, eTag, lastModified, "Storage access denied: ${e.message}")
                    return@withContext Result.failure()
                }
            }
        }

    private fun finalizeSuccess(taskId: String, tempPath: String, finalPath: String, bytesDownloaded: Long) {
        val tempFile = File(tempPath)
        val finalFile = File(finalPath)
        finalFile.parentFile?.mkdirs()
        val renamed = tempFile.renameTo(finalFile)
        val task = database.getDownloadTaskById(taskId)
        if (!renamed || task == null) {
            Timber.e("Resumable worker failed final rename taskId=%s temp=%s final=%s", taskId, tempPath, finalPath)
            markFailed(taskId, bytesDownloaded, task?.totalBytes, task?.eTag, task?.lastModified, "Failed to finalize download")
            return
        }
        Timber.i("Resumable worker finalized taskId=%s final=%s bytes=%s kind=%s", taskId, finalPath, bytesDownloaded, task.kind)
        when (task.kind) {
            DownloadTaskKind.PRIMARY -> {
                database.setSourcePath(task.sourceId, finalPath)
                database.clearSourceDownloadId(task.sourceId)
            }
            DownloadTaskKind.SUBTITLE -> {
                val mediaStreamId = task.mediaStreamId
                if (mediaStreamId != null) {
                    database.setMediaStreamPath(mediaStreamId, finalPath)
                    database.clearMediaStreamDownloadId(mediaStreamId)
                }
            }
        }
        updateTask(
            taskId = taskId,
            bytesDownloaded = bytesDownloaded,
            totalBytes = task.totalBytes ?: bytesDownloaded,
            eTag = task.eTag,
            lastModified = task.lastModified,
            status = DownloadManager.STATUS_SUCCESSFUL,
            progress = 100,
            errorMessage = null,
        )
    }

    private fun markFailed(
        taskId: String,
        bytesDownloaded: Long,
        totalBytes: Long?,
        eTag: String?,
        lastModified: String?,
        message: String,
    ) {
        Timber.w(
            "Resumable worker marking failed taskId=%s bytesDownloaded=%s totalBytes=%s message=%s",
            taskId,
            bytesDownloaded,
            totalBytes,
            message,
        )
        updateTask(
            taskId = taskId,
            bytesDownloaded = bytesDownloaded,
            totalBytes = totalBytes,
            eTag = eTag,
            lastModified = lastModified,
            status = DownloadManager.STATUS_FAILED,
            progress = progressFor(bytesDownloaded, totalBytes),
            errorMessage = message,
        )
    }

    private fun updateTask(
        taskId: String,
        bytesDownloaded: Long,
        totalBytes: Long?,
        eTag: String?,
        lastModified: String?,
        status: Int,
        progress: Int,
        errorMessage: String?,
    ) {
        val task = database.getDownloadTaskById(taskId) ?: return
        database.updateDownloadTask(
            id = taskId,
            downloadId = task.downloadId,
            bytesDownloaded = bytesDownloaded,
            totalBytes = totalBytes,
            eTag = eTag,
            lastModified = lastModified,
            status = status,
            progress = progress,
            errorMessage = errorMessage,
            updatedAt = System.currentTimeMillis(),
        )
    }

    private fun createForegroundInfo(progress: Int, taskId: String): ForegroundInfo {
        ensureNotificationChannel()
        val notification =
            NotificationCompat.Builder(appContext, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(CoreR.drawable.ic_download)
                .setContentTitle("Downloading media")
                .setContentText(if (progress in 1..99) "$progress%" else "Preparing download")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(100, progress.coerceIn(0, 100), progress <= 0)
                .build()
        return ForegroundInfo(
            taskId.hashCode(),
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val notificationManager =
            appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val existing = notificationManager.getNotificationChannel(NOTIFICATION_CHANNEL_ID)
        if (existing != null) return
        val channel =
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Downloads",
                NotificationManager.IMPORTANCE_LOW,
            )
        notificationManager.createNotificationChannel(channel)
    }

    private fun progressFor(downloadedBytes: Long, totalBytes: Long?): Int {
        if (totalBytes == null || totalBytes <= 0L) return 0
        return ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
    }

    private fun currentNetworkRestrictionMessage(hasAuthenticatedRequest: Boolean): String? {
        val connectivityManager = appContext.getSystemService(ConnectivityManager::class.java)
        val activeNetwork = connectivityManager.activeNetwork ?: return "Waiting for network"
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return "Waiting for network"
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return "Waiting for network"
        }
        return null
    }

    companion object {
        const val KEY_TASK_ID = "task_id"
        private const val NOTIFICATION_CHANNEL_ID = "resumable_downloads"
        private const val PROGRESS_UPDATE_BYTES = 512L * 1024L

        fun uniqueWorkName(taskId: String): String = "resumable-download:$taskId"
    }
}
