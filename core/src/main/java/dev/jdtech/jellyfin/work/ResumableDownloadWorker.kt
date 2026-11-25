package dev.jdtech.jellyfin.work

import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.downloads.ActiveDownloadRegistry
import dev.jdtech.jellyfin.downloads.DownloadStagingPaths
import dev.jdtech.jellyfin.models.DownloadTaskDto
import dev.jdtech.jellyfin.models.DownloadTaskKind
import dev.jdtech.jellyfin.network.SafeUrlBlockedException
import dev.jdtech.jellyfin.network.UrlTargetPolicy
import dev.jdtech.jellyfin.network.newBuilderWithSsrfGuard
import dev.jdtech.jellyfin.security.ContentKeyManager
import dev.jdtech.jellyfin.session.SessionActivationCoordinator
import dev.jdtech.jellyfin.session.SessionMode
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.spatialfin.unified.applock.AppLockManager
import dev.spatialfin.unified.applock.AppLockObserver
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
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
    private val appPreferences: AppPreferences,
    private val contentKeyManager: ContentKeyManager,
    private val okHttpClient: OkHttpClient,
    private val sessionActivationCoordinator: SessionActivationCoordinator,
    private val appLockObserver: AppLockObserver,
    private val activeDownloadRegistry: ActiveDownloadRegistry,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            val taskId = params.inputData.getString(KEY_TASK_ID) ?: return@withContext Result.failure()
            val task = database.getDownloadTaskById(taskId) ?: return@withContext Result.failure()

            val encryptPrimary =
                task.kind == DownloadTaskKind.PRIMARY &&
                    appPreferences.getValue(appPreferences.contentEncryptionEnabled)
            val stagingFile = resolveStagingFile(task, encryptPrimary)

            // Pre-check 1: Session initialized + authenticated + unlocked
            val session = sessionActivationCoordinator.ensureInitialized()
            val expectedSessionEpoch = session.sessionEpoch
            val activeServerId = session.activeServerId
            val activeUserId = session.activeUserId

            if (isLocked()) {
                Timber.i("Resumable worker paused before start (App Lock locked) taskId=%s", taskId)
                pauseForLock(taskId, task, stagingFile, encryptPrimary)
                return@withContext Result.failure()
            }
            if (session.sessionMode != SessionMode.SERVER_AUTHENTICATED ||
                sessionActivationCoordinator.isActivating.get() ||
                activeServerId.isNullOrBlank() ||
                activeUserId == null
            ) {
                Timber.i("Resumable worker paused before start (unauthenticated) taskId=%s", taskId)
                pauseTask(taskId, task, "Download paused")
                return@withContext Result.failure()
            }

            // Pre-check 2: Non-blank tempPath and finalPath
            if (task.tempPath.isBlank() || task.finalPath.isBlank()) {
                Timber.w("Resumable worker paused before start (blank paths) taskId=%s", taskId)
                pauseTask(taskId, task, "Download needs preparation")
                return@withContext Result.failure()
            }

            // Pre-check 3: Task belongs to active server
            val taskServerId = database.resolveDownloadTaskServerId(task)
            if (taskServerId != activeServerId) {
                Timber.w(
                    "Resumable worker paused before start (server mismatch task=%s active=%s) taskId=%s",
                    taskServerId,
                    activeServerId,
                    taskId,
                )
                pauseTask(taskId, task, "Download paused")
                return@withContext Result.failure()
            }

            // Pre-check 4: Active user's non-revoked token matches
            val activeUser = database.getUser(activeUserId)
            val activeToken = activeUser?.accessToken?.takeIf { it.isNotBlank() }
            if (activeToken == null ||
                (!task.accessToken.isNullOrBlank() && task.accessToken != activeToken)
            ) {
                Timber.w("Resumable worker paused before start (token mismatch or revoked) taskId=%s", taskId)
                pauseTask(taskId, task, "Sign in required to resume download")
                return@withContext Result.failure()
            }

            // Bootstrap Off-mode DEK once and verify DEK availability before setForeground / writes
            if (encryptPrimary) {
                val appLockMode = appPreferences.getValue(appPreferences.appLockMode)
                if (!contentKeyManager.hasStoredDek() && appLockMode == "off") {
                    contentKeyManager.getOrCreateDek().fill(0)
                }
                val probeDek = contentKeyManager.getDekOrNull()
                if (probeDek == null) {
                    Timber.i("Resumable worker paused before start (DEK unavailable) taskId=%s", taskId)
                    pauseForLock(taskId, task, stagingFile, encryptPrimary = true)
                    return@withContext Result.failure()
                }
                probeDek.fill(0)
            }

            // Clear opposite staging file on mid-pause encryption toggle
            val oppositeStagingFile =
                if (encryptPrimary) File(task.tempPath)
                else DownloadStagingPaths.privateStagingFile(appContext, task.id)
            if (oppositeStagingFile.exists() && oppositeStagingFile.absolutePath != stagingFile.absolutePath) {
                oppositeStagingFile.delete()
            }

            // Clear stale encryption metadata when starting a non-encrypted run
            if (!encryptPrimary && (task.isEncrypted || task.encryptionIv != null)) {
                database.setDownloadTaskEncryption(
                    id = taskId,
                    isEncrypted = false,
                    encryptionIv = null,
                    updatedAt = System.currentTimeMillis(),
                )
            }

            Timber.i(
                "Resumable worker starting taskId=%s kind=%s itemId=%s sourceId=%s encrypt=%s bytesDownloaded=%s totalBytes=%s",
                taskId,
                task.kind,
                task.itemId,
                task.sourceId,
                encryptPrimary,
                task.bytesDownloaded,
                task.totalBytes,
            )

            setForeground(createForegroundInfo(task.progress, taskId))

            val networkRestriction = currentNetworkRestrictionMessage()
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

            val finalFile = File(task.finalPath)
            val stagingParent = stagingFile.parentFile
            if (stagingParent != null && !stagingParent.exists() && !stagingParent.mkdirs()) {
                Timber.e("Resumable worker failed to create staging dir taskId=%s dir=%s", taskId, stagingParent.path)
                markFailed(taskId, task.bytesDownloaded, task.totalBytes, task.eTag, task.lastModified, "Failed to create download directory")
                return@withContext Result.failure()
            }
            val finalParent = finalFile.parentFile
            if (finalParent != null && !finalParent.exists() && !finalParent.mkdirs()) {
                Timber.e("Resumable worker failed to create final dir taskId=%s dir=%s", taskId, finalParent.path)
                markFailed(taskId, task.bytesDownloaded, task.totalBytes, task.eTag, task.lastModified, "Failed to create download directory")
                return@withContext Result.failure()
            }

            val isTranscoded =
                task.finalPath.endsWith("_transcoded.mp4", ignoreCase = true) ||
                    task.requestUrl.contains("Static=false", ignoreCase = true)
            val existingBytes = if (isTranscoded) {
                0L
            } else {
                stagingFile.takeIf(File::exists)?.length() ?: 0L
            }

            // Pre-check free space before opening a network connection.
            // When encryptPrimary is true, check BOTH the private staging volume and final volume.
            val totalKnown = task.totalBytes
            if (totalKnown != null && totalKnown > 0L) {
                val remaining = (totalKnown - existingBytes).coerceAtLeast(0L)
                val stagingNeeded = remaining + LOW_STORAGE_SAFETY_MARGIN_BYTES
                if (stagingParent != null && stagingParent.usableSpace < stagingNeeded) {
                    val mbNeeded = remaining / 1_048_576L
                    val mbAvailable = stagingParent.usableSpace / 1_048_576L
                    val message = "Not enough storage — need ${mbNeeded} MB, have ${mbAvailable} MB"
                    markFailed(taskId, existingBytes, task.totalBytes, task.eTag, task.lastModified, message)
                    showLowStorageNotification(params.inputData.getString(KEY_ITEM_TITLE), mbNeeded, mbAvailable)
                    return@withContext Result.failure()
                }
                if (encryptPrimary && finalParent != null) {
                    val finalNeeded = totalKnown + LOW_STORAGE_SAFETY_MARGIN_BYTES
                    if (finalParent.usableSpace < finalNeeded) {
                        val mbNeeded = totalKnown / 1_048_576L
                        val mbAvailable = finalParent.usableSpace / 1_048_576L
                        val message = "Not enough storage — need ${mbNeeded} MB, have ${mbAvailable} MB"
                        markFailed(taskId, existingBytes, task.totalBytes, task.eTag, task.lastModified, message)
                        showLowStorageNotification(params.inputData.getString(KEY_ITEM_TITLE), mbNeeded, mbAvailable)
                        return@withContext Result.failure()
                    }
                }
            }

            val requestUrl = task.requestUrl
            if (requestUrl.isBlank()) {
                markFailed(taskId, existingBytes, task.totalBytes, task.eTag, task.lastModified, "Missing download URL")
                return@withContext Result.failure()
            }

            val requestBuilder = Request.Builder().url(requestUrl)
            requestBuilder.header("Authorization", """MediaBrowser Token="$activeToken"""")
            requestBuilder.header("X-Emby-Token", activeToken)
            if (existingBytes > 0L && !isTranscoded) {
                requestBuilder.header("Range", "bytes=$existingBytes-")
                task.eTag?.takeIf { it.isNotBlank() }?.let { requestBuilder.header("If-Range", it) }
                    ?: task.lastModified?.takeIf { it.isNotBlank() }?.let {
                        requestBuilder.header("If-Range", it)
                    }
            }

            val client = okHttpClient
                .newBuilderWithSsrfGuard(UrlTargetPolicy.PUBLIC_OR_LAN)
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .build()
            val sanitizedUrlForLog =
                task.requestUrl
                    .substringBefore('?')
                    .replace(Regex("(?i)(api_key|apikey)=[^&\\s]+"), "$1=***")

            val call: Call = client.newCall(requestBuilder.build())
            val currentJob = coroutineContext.job
            val cancelRegistration = currentJob.invokeOnCompletion { cause ->
                if (cause != null) {
                    runCatching { call.cancel() }
                }
            }
            val registryHandle = ActiveDownloadRegistry.Handle(
                taskId = taskId,
                itemId = task.itemId,
                serverId = activeServerId,
                userId = activeUserId,
                kind = task.kind.name,
                call = call,
                job = currentJob,
            )
            activeDownloadRegistry.register(registryHandle)

            try {
                if (isLocked()) {
                    pauseForLock(taskId, task, stagingFile, encryptPrimary)
                    return@withContext Result.failure()
                }
                if (isStopped || !isSessionStillValid(expectedSessionEpoch, activeServerId, activeUserId)) {
                    pauseTask(taskId, task, "Download paused")
                    return@withContext Result.failure()
                }

                val response =
                    try {
                        Timber.i(
                            "Resumable worker request taskId=%s url=%s existingBytes=%s hasRange=%s",
                            taskId,
                            sanitizedUrlForLog,
                            existingBytes,
                            existingBytes > 0L,
                        )
                        call.execute()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: SafeUrlBlockedException) {
                        markFailed(taskId, existingBytes, task.totalBytes, task.eTag, task.lastModified, "Blocked download URL")
                        Timber.w(e, "Resumable worker blocked URL taskId=%s", taskId)
                        return@withContext Result.failure()
                    } catch (e: IOException) {
                        if (isLocked()) {
                            pauseForLock(taskId, task, stagingFile, encryptPrimary)
                            return@withContext Result.failure()
                        }
                        if (isStopped || call.isCanceled() || !isSessionStillValid(expectedSessionEpoch, activeServerId, activeUserId)) {
                            pauseTask(taskId, task, "Download paused")
                            return@withContext Result.failure()
                        }
                        markFailed(taskId, existingBytes, task.totalBytes, task.eTag, task.lastModified, e.message ?: "Download failed")
                        Timber.e(e, "Resumable worker network failure taskId=%s", taskId)
                        return@withContext Result.retry()
                    } catch (e: Exception) {
                        if (isLocked()) {
                            pauseForLock(taskId, task, stagingFile, encryptPrimary)
                            return@withContext Result.failure()
                        }
                        if (isStopped || !isSessionStillValid(expectedSessionEpoch, activeServerId, activeUserId)) {
                            pauseTask(taskId, task, "Download paused")
                            return@withContext Result.failure()
                        }
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
                            if (stagingFile.exists() && stagingFile.length() > 0L) {
                                // Ensure task is STATUS_RUNNING so @Transaction finalizeCompletedDownload succeeds
                                updateTask(
                                    taskId = taskId,
                                    bytesDownloaded = stagingFile.length(),
                                    totalBytes = task.totalBytes ?: stagingFile.length(),
                                    eTag = task.eTag,
                                    lastModified = task.lastModified,
                                    status = DownloadManager.STATUS_RUNNING,
                                    progress = 99,
                                    errorMessage = null,
                                )
                                return@withContext if (
                                    finalizeSuccess(
                                        taskId = taskId,
                                        stagingFile = stagingFile,
                                        finalPath = task.finalPath,
                                        bytesDownloaded = stagingFile.length(),
                                        eTag = task.eTag,
                                        lastModified = task.lastModified,
                                        encryptPrimary = encryptPrimary,
                                        expectedSessionEpoch = expectedSessionEpoch,
                                        activeServerId = activeServerId,
                                        activeUserId = activeUserId,
                                    )
                                ) {
                                    Result.success()
                                } else {
                                    Result.failure()
                                }
                            }
                            Timber.w("Resumable worker range not satisfiable taskId=%s", taskId)
                            markFailed(taskId, existingBytes, task.totalBytes, task.eTag, task.lastModified, "Range not satisfiable")
                            return@withContext Result.failure()
                        }
                        httpResponse.code == 401 || httpResponse.code == 403 -> {
                            Timber.w("Resumable worker auth failure taskId=%s code=%s", taskId, httpResponse.code)
                            markFailed(taskId, existingBytes, task.totalBytes, task.eTag, task.lastModified, "HTTP ${httpResponse.code}")
                            return@withContext Result.failure()
                        }
                        !httpResponse.isSuccessful -> {
                            Timber.w("Resumable worker http failure taskId=%s code=%s", taskId, httpResponse.code)
                            markFailed(taskId, existingBytes, task.totalBytes, task.eTag, task.lastModified, "HTTP ${httpResponse.code}")
                            return@withContext Result.retry()
                        }
                    }

                    val append = existingBytes > 0L && httpResponse.code == 206 && !isTranscoded
                    if (!append && existingBytes > 0L && stagingFile.exists()) {
                        stagingFile.delete()
                        if (!encryptPrimary) {
                            database.setDownloadTaskEncryption(
                                id = taskId,
                                isEncrypted = false,
                                encryptionIv = null,
                                updatedAt = System.currentTimeMillis(),
                            )
                        }
                    }

                    val responseBody = httpResponse.body
                    val responseContentLength = responseBody.contentLength().takeIf { it >= 0L }
                    val totalBytes =
                        if (append) {
                            responseContentLength?.plus(existingBytes) ?: task.totalBytes
                        } else {
                            responseContentLength ?: task.totalBytes
                        }
                    val eTag = httpResponse.header("ETag") ?: task.eTag
                    val lastModified = httpResponse.header("Last-Modified") ?: task.lastModified
                    var downloadedBytes = if (append) existingBytes else 0L

                    try {
                        RandomAccessFile(stagingFile, "rw").use { output ->
                            if (append) {
                                output.seek(existingBytes)
                            } else {
                                output.setLength(0L)
                            }

                            responseBody.byteStream().use { input ->
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
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
                                    if (isLocked()) {
                                        pauseForLock(taskId, task.copy(bytesDownloaded = downloadedBytes, totalBytes = totalBytes, eTag = eTag, lastModified = lastModified), stagingFile, encryptPrimary)
                                        return@withContext Result.failure()
                                    }
                                    if (isStopped || !isSessionStillValid(expectedSessionEpoch, activeServerId, activeUserId)) {
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
                                        return@withContext Result.failure()
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
                                        setForeground(createForegroundInfo(progress, taskId))
                                        lastReportedBytes = downloadedBytes
                                    }
                                }

                                output.fd.sync()
                            }
                        }

                        if (isLocked()) {
                            pauseForLock(taskId, task.copy(bytesDownloaded = downloadedBytes, totalBytes = totalBytes, eTag = eTag, lastModified = lastModified), stagingFile, encryptPrimary)
                            return@withContext Result.failure()
                        }
                        if (isStopped || !isSessionStillValid(expectedSessionEpoch, activeServerId, activeUserId)) {
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
                            return@withContext Result.failure()
                        }

                        return@withContext if (
                            finalizeSuccess(
                                taskId = taskId,
                                stagingFile = stagingFile,
                                finalPath = task.finalPath,
                                bytesDownloaded = downloadedBytes,
                                eTag = eTag,
                                lastModified = lastModified,
                                encryptPrimary = encryptPrimary,
                                expectedSessionEpoch = expectedSessionEpoch,
                                activeServerId = activeServerId,
                                activeUserId = activeUserId,
                            )
                        ) {
                            Result.success()
                        } else {
                            Result.failure()
                        }
                    } catch (e: CancellationException) {
                        if (isLocked()) {
                            pauseForLock(taskId, task.copy(bytesDownloaded = downloadedBytes, totalBytes = totalBytes, eTag = eTag, lastModified = lastModified), stagingFile, encryptPrimary)
                        } else if (!isSessionStillValid(expectedSessionEpoch, activeServerId, activeUserId) || isStopped) {
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
                        }
                        throw e
                    } catch (e: IOException) {
                        if (isLocked()) {
                            pauseForLock(taskId, task.copy(bytesDownloaded = downloadedBytes, totalBytes = totalBytes, eTag = eTag, lastModified = lastModified), stagingFile, encryptPrimary)
                            return@withContext Result.failure()
                        }
                        if (isStopped || call.isCanceled() || !isSessionStillValid(expectedSessionEpoch, activeServerId, activeUserId)) {
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
                            return@withContext Result.failure()
                        }
                        Timber.e(e, "Resumable worker file I/O failure taskId=%s attempt=%s", taskId, runAttemptCount)
                        markFailed(taskId, downloadedBytes, totalBytes, eTag, lastModified, "File I/O failure: ${e.message}")
                        return@withContext if (runAttemptCount < MAX_AUTO_RETRIES) Result.retry() else Result.failure()
                    } catch (e: SecurityException) {
                        Timber.e(e, "Resumable worker storage security failure taskId=%s", taskId)
                        markFailed(taskId, downloadedBytes, totalBytes, eTag, lastModified, "Storage access denied: ${e.message}")
                        return@withContext Result.failure()
                    }
                }
            } finally {
                cancelRegistration.dispose()
                activeDownloadRegistry.unregister(registryHandle)
            }
        }

    private suspend fun finalizeSuccess(
        taskId: String,
        stagingFile: File,
        finalPath: String,
        bytesDownloaded: Long,
        eTag: String?,
        lastModified: String?,
        encryptPrimary: Boolean,
        expectedSessionEpoch: Long,
        activeServerId: String,
        activeUserId: UUID,
    ): Boolean {
        val finalFile = File(finalPath)
        finalFile.parentFile?.mkdirs()

        if (encryptPrimary) {
            val dek = contentKeyManager.getDekOrNull()
            if (dek == null || isLocked()) {
                dek?.fill(0)
                val latest = database.getDownloadTaskById(taskId)
                if (latest != null) {
                    pauseForLock(taskId, latest, stagingFile, encryptPrimary = true)
                } else {
                    stagingFile.delete()
                }
                return false
            }

            val iv = ByteArray(ResumableDownloadFileOps.AES_IV_BYTES).also { SecureRandom().nextBytes(it) }
            val ivBase64 = Base64.encodeToString(iv, Base64.NO_WRAP)
            val encTmp = File(finalFile.parentFile, "${finalFile.name}.enc.tmp")

            val encryptedOk =
                try {
                    ResumableDownloadFileOps.encryptStreamToFile(
                        sourceFile = stagingFile,
                        targetTmpFile = encTmp,
                        dek = dek,
                        iv = iv,
                        shouldAbort = {
                            isStopped || isLocked() || !isSessionStillValid(expectedSessionEpoch, activeServerId, activeUserId)
                        },
                    )
                } finally {
                    dek.fill(0)
                }

            if (!encryptedOk) {
                encTmp.delete()
                val latest = database.getDownloadTaskById(taskId)
                if (isLocked()) {
                    if (latest != null) pauseForLock(taskId, latest, stagingFile, encryptPrimary = true)
                    else stagingFile.delete()
                } else if (isStopped || !isSessionStillValid(expectedSessionEpoch, activeServerId, activeUserId)) {
                    if (latest != null) pauseTask(taskId, latest, "Download paused")
                } else {
                    markFailed(
                        taskId = taskId,
                        bytesDownloaded = bytesDownloaded,
                        totalBytes = latest?.totalBytes ?: bytesDownloaded,
                        eTag = eTag,
                        lastModified = lastModified,
                        message = "Failed to encrypt downloaded content",
                    )
                }
                return false
            }

            val committed = activeDownloadRegistry.commitMutex.withLock {
                if (isStopped || isLocked() || !isSessionStillValid(expectedSessionEpoch, activeServerId, activeUserId)) {
                    encTmp.delete()
                    if (isLocked()) {
                        database.getDownloadTaskById(taskId)?.let {
                            pauseForLock(taskId, it, stagingFile, encryptPrimary = true)
                        } ?: stagingFile.delete()
                    }
                    return@withLock false
                }
                val task = database.getDownloadTaskById(taskId)
                if (task == null || task.status != DownloadManager.STATUS_RUNNING) {
                    encTmp.delete()
                    return@withLock false
                }

                val now = System.currentTimeMillis()
                // Persist encryption metadata BEFORE replacing finalFile so a crash after rename never
                // exposes ciphertext marked as plaintext.
                database.setDownloadTaskEncryption(
                    id = taskId,
                    isEncrypted = true,
                    encryptionIv = ivBase64,
                    updatedAt = now,
                )

                if (!ResumableDownloadFileOps.replaceFile(encTmp, finalFile)) {
                    database.setDownloadTaskEncryption(
                        id = taskId,
                        isEncrypted = false,
                        encryptionIv = null,
                        updatedAt = System.currentTimeMillis(),
                    )
                    markFailed(taskId, bytesDownloaded, task.totalBytes, eTag, lastModified, "Failed to finalize download")
                    return@withLock false
                }

                val finalizedSize = finalFile.length().takeIf { it > 0L } ?: bytesDownloaded
                val finalizedRow = database.finalizeCompletedDownload(
                    id = taskId,
                    bytesDownloaded = finalizedSize,
                    totalBytes = finalizedSize,
                    eTag = eTag,
                    lastModified = lastModified,
                    isEncrypted = true,
                    encryptionIv = ivBase64,
                    updatedAt = System.currentTimeMillis(),
                )
                if (!finalizedRow) {
                    finalFile.delete()
                    return@withLock false
                }

                database.setSourcePath(task.sourceId, finalPath)
                database.clearSourceDownloadId(task.sourceId)
                stagingFile.delete()
                true
            }

            if (committed) {
                Timber.i("Resumable worker finalized encrypted taskId=%s final=%s bytes=%s", taskId, finalPath, bytesDownloaded)
                showDownloadCompleteNotification(params.inputData.getString(KEY_ITEM_TITLE))
            }
            return committed
        }

        // Plaintext / subtitle finalization under commitMutex
        val committed = activeDownloadRegistry.commitMutex.withLock {
            if (isStopped || isLocked() || !isSessionStillValid(expectedSessionEpoch, activeServerId, activeUserId)) {
                return@withLock false
            }
            val task = database.getDownloadTaskById(taskId)
            if (task == null || task.status != DownloadManager.STATUS_RUNNING) {
                return@withLock false
            }

            database.setDownloadTaskEncryption(
                id = taskId,
                isEncrypted = false,
                encryptionIv = null,
                updatedAt = System.currentTimeMillis(),
            )

            if (!ResumableDownloadFileOps.replaceFile(stagingFile, finalFile)) {
                Timber.e("Resumable worker failed final move taskId=%s final=%s", taskId, finalPath)
                markFailed(taskId, bytesDownloaded, task.totalBytes, eTag, lastModified, "Failed to finalize download")
                return@withLock false
            }

            val finalizedSize = finalFile.length().takeIf { it > 0L } ?: bytesDownloaded
            val finalizedRow = database.finalizeCompletedDownload(
                id = taskId,
                bytesDownloaded = finalizedSize,
                totalBytes = finalizedSize,
                eTag = eTag,
                lastModified = lastModified,
                isEncrypted = false,
                encryptionIv = null,
                updatedAt = System.currentTimeMillis(),
            )
            if (!finalizedRow) {
                finalFile.delete()
                return@withLock false
            }

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
            true
        }

        if (committed) {
            Timber.i("Resumable worker finalized taskId=%s final=%s bytes=%s", taskId, finalPath, bytesDownloaded)
            val latest = database.getDownloadTaskById(taskId)
            if (latest?.kind == DownloadTaskKind.PRIMARY) {
                showDownloadCompleteNotification(params.inputData.getString(KEY_ITEM_TITLE))
            }
        }
        return committed
    }

    private fun resolveStagingFile(task: DownloadTaskDto, encryptPrimary: Boolean): File =
        if (encryptPrimary) {
            DownloadStagingPaths.privateStagingFile(appContext, task.id)
        } else {
            File(task.tempPath)
        }

    private fun isLocked(): Boolean =
        appLockObserver.lockState.value == AppLockManager.LockState.LOCKED

    private fun isSessionStillValid(
        expectedSessionEpoch: Long,
        activeServerId: String,
        activeUserId: UUID,
    ): Boolean {
        val snap = sessionActivationCoordinator.sessionState.value
        return snap.sessionMode == SessionMode.SERVER_AUTHENTICATED &&
            !sessionActivationCoordinator.isActivating.get() &&
            snap.sessionEpoch == expectedSessionEpoch &&
            snap.activeServerId == activeServerId &&
            snap.activeUserId == activeUserId
    }

    private fun pauseForLock(
        taskId: String,
        task: DownloadTaskDto,
        stagingFile: File,
        encryptPrimary: Boolean,
    ) {
        if (encryptPrimary) {
            runCatching { stagingFile.delete() }
            if (task.tempPath.isNotBlank()) {
                runCatching { File(task.tempPath).delete() }
            }
            if (task.finalPath.isNotBlank()) {
                runCatching { File("${task.finalPath}.enc.tmp").delete() }
            }
            database.setDownloadTaskEncryption(
                id = taskId,
                isEncrypted = false,
                encryptionIv = null,
                updatedAt = System.currentTimeMillis(),
            )
            updateTask(
                taskId = taskId,
                bytesDownloaded = 0L,
                totalBytes = task.totalBytes,
                eTag = null,
                lastModified = null,
                status = DownloadManager.STATUS_PAUSED,
                progress = 0,
                errorMessage = DEK_LOCKED_PAUSE_MESSAGE,
            )
        } else {
            updateTask(
                taskId = taskId,
                bytesDownloaded = task.bytesDownloaded,
                totalBytes = task.totalBytes,
                eTag = task.eTag,
                lastModified = task.lastModified,
                status = DownloadManager.STATUS_PAUSED,
                progress = task.progress,
                errorMessage = DEK_LOCKED_PAUSE_MESSAGE,
            )
        }
    }

    private fun pauseTask(taskId: String, task: DownloadTaskDto, message: String) {
        updateTask(
            taskId = taskId,
            bytesDownloaded = task.bytesDownloaded,
            totalBytes = task.totalBytes,
            eTag = task.eTag,
            lastModified = task.lastModified,
            status = DownloadManager.STATUS_PAUSED,
            progress = task.progress,
            errorMessage = message,
        )
    }

    private fun showDownloadCompleteNotification(itemTitle: String?) {
        ensureNotificationChannel()
        val notificationManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val title = itemTitle?.takeIf { it.isNotBlank() } ?: "Download complete"
        val notification = NotificationCompat.Builder(appContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(CoreR.drawable.ic_download)
            .setContentTitle(title)
            .setContentText("Download finished successfully")
            .setAutoCancel(true)
            .build()
        notificationManager.notify(("complete:$itemTitle").hashCode(), notification)
    }

    private fun showLowStorageNotification(itemTitle: String?, mbNeeded: Long, mbAvailable: Long) {
        ensureNotificationChannel()
        val notificationManager =
            appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val title = itemTitle?.takeIf { it.isNotBlank() } ?: "Download paused"
        val notification = NotificationCompat.Builder(appContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(CoreR.drawable.ic_download)
            .setContentTitle("Not enough storage for $title")
            .setContentText("Need ${mbNeeded} MB, have ${mbAvailable} MB free")
            .setAutoCancel(true)
            .build()
        notificationManager.notify(("low-storage:$itemTitle").hashCode(), notification)
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
        val title = params.inputData.getString(KEY_ITEM_TITLE)?.takeIf { it.isNotBlank() }
            ?: "Downloading media"
        val text = when {
            progress in 1..99 -> "$progress%"
            runAttemptCount > 0 -> "Retrying… (attempt ${runAttemptCount + 1})"
            else -> "Preparing download"
        }
        val notification =
            NotificationCompat.Builder(appContext, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(CoreR.drawable.ic_download)
                .setContentTitle(title)
                .setContentText(text)
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

    private fun progressFor(downloadedBytes: Long, totalBytes: Long?): Int =
        ResumableDownloadFileOps.progressFor(downloadedBytes, totalBytes).coerceIn(0, 99)

    private fun currentNetworkRestrictionMessage(): String? {
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
        const val KEY_ITEM_TITLE = "item_title"
        const val DEK_LOCKED_PAUSE_MESSAGE = "Paused while App Lock is locked"
        private const val NOTIFICATION_CHANNEL_ID = "resumable_downloads"
        private const val PROGRESS_UPDATE_BYTES = 512L * 1024L
        private const val MAX_AUTO_RETRIES = 5
        private const val LOW_STORAGE_SAFETY_MARGIN_BYTES = 64L * 1_048_576L

        fun uniqueWorkName(taskId: String): String = "resumable-download:$taskId"
    }
}
