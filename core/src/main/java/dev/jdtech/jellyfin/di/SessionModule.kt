package dev.jdtech.jellyfin.di

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import androidx.work.WorkManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.jdtech.jellyfin.companion.CompanionConfigImporter
import dev.jdtech.jellyfin.companion.CompanionLiveSyncClient
import dev.jdtech.jellyfin.core.util.XrPlayerSessionClient
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.downloads.ActiveDownloadRegistry
import dev.jdtech.jellyfin.downloads.DownloadStagingPaths
import dev.jdtech.jellyfin.downloads.DownloadStorageManager
import dev.jdtech.jellyfin.player.core.downloads.EpisodePlaybackStopListener
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.repository.LocalMediaRepository
import dev.jdtech.jellyfin.repository.NetworkMediaRepository
import dev.jdtech.jellyfin.session.ActiveSessionObserver
import dev.jdtech.jellyfin.session.ActiveSessionSnapshot
import dev.jdtech.jellyfin.session.SessionActivationCoordinator
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.settings.domain.XrProcessPreferenceBridge
import dev.jdtech.jellyfin.settings.language.SeriesLanguageOverride
import dev.jdtech.jellyfin.settings.voice.VoiceTelemetryEntry
import dev.jdtech.jellyfin.settings.voice.VoiceTelemetryStore
import dev.jdtech.jellyfin.work.DownloadIntegrityWorker
import dev.spatialfin.unified.applock.AppLockManager
import dev.spatialfin.unified.applock.AppLockObserver
import dev.spatialfin.unified.applock.EncryptedDownloadsPurgeHook
import dev.spatialfin.unified.applock.PreWipeHook
import dev.spatialfin.unified.applock.SystemModalGraceHook
import dev.spatialfin.unified.applock.SystemModalGraceState
import java.io.File
import java.util.UUID
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

@Module
@InstallIn(SingletonComponent::class)
object SessionModule {

    @Singleton
    @Provides
    fun provideActiveSessionObserver(
        coordinator: SessionActivationCoordinator,
    ): ActiveSessionObserver =
        object : ActiveSessionObserver {
            override val activeSessionState: StateFlow<ActiveSessionSnapshot> =
                coordinator.activeSessionState

            override suspend fun ensureInitialized(): ActiveSessionSnapshot {
                coordinator.ensureInitialized()
                return coordinator.activeSessionState.value
            }
        }

    @Singleton
    @Provides
    fun provideAppLockObserver(
        appPreferences: AppPreferences,
        appLockManagerProvider: Provider<AppLockManager>,
    ): AppLockObserver =
        if (appPreferences.isMainProcess) {
            appLockManagerProvider.get()
        } else {
            AppLockObserver.NoOp
        }

    @Singleton
    @Provides
    fun provideSystemModalGraceHook(
        appPreferences: AppPreferences,
        systemModalGraceState: SystemModalGraceState,
    ): SystemModalGraceHook =
        if (appPreferences.isMainProcess) {
            systemModalGraceState
        } else {
            SystemModalGraceHook.NoOp
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Singleton
    @Provides
    fun provideXrProcessPreferenceBridge(
        appPreferences: AppPreferences,
        appLockObserver: AppLockObserver,
        coordinatorProvider: dagger.Lazy<SessionActivationCoordinator>,
        voiceTelemetryStore: dagger.Lazy<VoiceTelemetryStore>,
        jellyfinRepository: dagger.Lazy<JellyfinRepository>,
        localMediaRepository: dagger.Lazy<LocalMediaRepository>,
        networkMediaRepository: dagger.Lazy<NetworkMediaRepository>,
        episodePlaybackStopListener: dagger.Lazy<EpisodePlaybackStopListener>,
    ): XrProcessPreferenceBridge {
        if (!appPreferences.isMainProcess) {
            return object : XrProcessPreferenceBridge {
                override fun setPendingSyncPlayLeave(
                    serverId: String,
                    userId: String,
                    expectedSessionEpoch: Long,
                ) {
                    XrPlayerSessionClient.enqueueSetPendingSyncPlayLeave(serverId, userId, expectedSessionEpoch)
                }

                override fun clearPendingSyncPlayLeave(
                    serverId: String,
                    userId: String,
                    expectedSessionEpoch: Long,
                ) {
                    XrPlayerSessionClient.enqueueClearPendingSyncPlayLeave(serverId, userId, expectedSessionEpoch)
                }

                override fun getSeriesLanguageOverride(seriesId: String): SeriesLanguageOverride? =
                    if (XrPlayerSessionClient.hasShadowSeriesLanguageOverride(seriesId)) {
                        XrPlayerSessionClient.getShadowSeriesLanguageOverride(seriesId)
                    } else {
                        appPreferences.getSeriesLanguageOverride(seriesId)
                    }

                override fun setSeriesLanguageOverride(
                    seriesId: String,
                    override: SeriesLanguageOverride?,
                ) {
                    XrPlayerSessionClient.enqueueSetSeriesLanguageOverride(seriesId, override)
                }

                override fun getPlayerMaxBitrate(): Long =
                    XrPlayerSessionClient.getShadowPlayerMaxBitrate()
                        ?: appPreferences.getValue(appPreferences.playerMaxBitrate)

                override fun setPlayerMaxBitrate(bitrate: Long) {
                    XrPlayerSessionClient.enqueueSetPlayerMaxBitrate(bitrate)
                }

                override fun recordVoiceTelemetry(entry: VoiceTelemetryEntry) {
                    XrPlayerSessionClient.enqueueRecordVoiceTelemetry(entry)
                }

                override fun reportStopAndMaybeRunHygiene(
                    sourceType: XrProcessPreferenceBridge.StopSourceType,
                    itemId: UUID?,
                    localMediaId: Long?,
                    networkVideoId: String?,
                    positionMs: Long,
                    durationMs: Long,
                    playedPercentage: Int,
                    markedPlayed: Boolean,
                    runHygiene: Boolean,
                    serverId: String?,
                    userId: UUID?,
                    expectedSessionEpoch: Long,
                ) {
                    XrPlayerSessionClient.enqueueReportStopAndMaybeRunHygiene(
                        sourceType = sourceType,
                        itemId = itemId,
                        localMediaId = localMediaId,
                        networkVideoId = networkVideoId,
                        positionMs = positionMs,
                        durationMs = durationMs,
                        playedPercentage = playedPercentage,
                        markedPlayed = markedPlayed,
                        runHygiene = runHygiene,
                        serverId = serverId,
                        userId = userId,
                        expectedSessionEpoch = expectedSessionEpoch,
                    )
                }
            }
        }

        val fifoScope = CoroutineScope(
            SupervisorJob() +
                Dispatchers.IO.limitedParallelism(1) +
                CoroutineExceptionHandler { _, t ->
                    Timber.w(t, "XrProcessPreferenceBridge FIFO worker failed")
                },
        )

        fun isSessionFenceValid(
            serverId: String?,
            userId: String?,
            expectedSessionEpoch: Long,
        ): Boolean {
            if (appLockObserver.lockState.value == AppLockManager.LockState.LOCKED) return false
            val coord = coordinatorProvider.get()
            if (coord.isActivating.get()) return false
            if (expectedSessionEpoch > 0L && coord.sessionEpoch.get() != expectedSessionEpoch) return false
            val state = coord.sessionState.value
            if (serverId != null && state.activeServerId != serverId) return false
            if (userId != null && state.activeUserId?.toString() != userId) return false
            return true
        }

        return object : XrProcessPreferenceBridge {
            override fun setPendingSyncPlayLeave(
                serverId: String,
                userId: String,
                expectedSessionEpoch: Long,
            ) {
                fifoScope.launch {
                    if (!isSessionFenceValid(serverId, userId, expectedSessionEpoch)) return@launch
                    appPreferences.commitMainBatch { editor ->
                        editor.putString(appPreferences.pendingSyncPlayLeaveServerId.backendName, serverId)
                        editor.putString(appPreferences.pendingSyncPlayLeaveUserId.backendName, userId)
                    }
                    // Attempt immediate SyncPlay leave in the main process if still authenticated
                    val result = runCatching {
                        if (isSessionFenceValid(serverId, userId, expectedSessionEpoch)) {
                            jellyfinRepository.get().leaveSyncPlayGroup()
                            true
                        } else {
                            false
                        }
                    }.getOrDefault(false)
                    if (result) {
                        appPreferences.commitMainBatch { editor ->
                            if (appPreferences.getValue(appPreferences.pendingSyncPlayLeaveServerId) == serverId &&
                                appPreferences.getValue(appPreferences.pendingSyncPlayLeaveUserId) == userId
                            ) {
                                editor.remove(appPreferences.pendingSyncPlayLeaveServerId.backendName)
                                editor.remove(appPreferences.pendingSyncPlayLeaveUserId.backendName)
                            }
                        }
                    }
                }
            }

            override fun clearPendingSyncPlayLeave(
                serverId: String,
                userId: String,
                expectedSessionEpoch: Long,
            ) {
                fifoScope.launch {
                    if (!isSessionFenceValid(serverId, userId, expectedSessionEpoch)) return@launch
                    appPreferences.commitMainBatch { editor ->
                        editor.remove(appPreferences.pendingSyncPlayLeaveServerId.backendName)
                        editor.remove(appPreferences.pendingSyncPlayLeaveUserId.backendName)
                    }
                }
            }

            override fun getSeriesLanguageOverride(seriesId: String): SeriesLanguageOverride? =
                appPreferences.getSeriesLanguageOverride(seriesId)

            override fun setSeriesLanguageOverride(
                seriesId: String,
                override: SeriesLanguageOverride?,
            ) {
                appPreferences.setSeriesLanguageOverride(seriesId, override)
            }

            override fun getPlayerMaxBitrate(): Long =
                appPreferences.getValue(appPreferences.playerMaxBitrate)

            override fun setPlayerMaxBitrate(bitrate: Long) {
                appPreferences.setValue(appPreferences.playerMaxBitrate, bitrate)
            }

            override fun recordVoiceTelemetry(entry: VoiceTelemetryEntry) {
                voiceTelemetryStore.get().recordLocal(entry)
            }

            override fun reportStopAndMaybeRunHygiene(
                sourceType: XrProcessPreferenceBridge.StopSourceType,
                itemId: UUID?,
                localMediaId: Long?,
                networkVideoId: String?,
                positionMs: Long,
                durationMs: Long,
                playedPercentage: Int,
                markedPlayed: Boolean,
                runHygiene: Boolean,
                serverId: String?,
                userId: UUID?,
                expectedSessionEpoch: Long,
            ) {
                fifoScope.launch {
                    if (appLockObserver.lockState.value == AppLockManager.LockState.LOCKED) return@launch
                    when (sourceType) {
                        XrProcessPreferenceBridge.StopSourceType.JELLYFIN -> {
                            val targetItemId = itemId ?: return@launch
                            if (!isSessionFenceValid(serverId, userId?.toString(), expectedSessionEpoch)) {
                                return@launch
                            }
                            runCatching {
                                jellyfinRepository.get().postPlaybackStop(
                                    itemId = targetItemId,
                                    positionTicks = positionMs * 10_000L,
                                    playedPercentage = playedPercentage,
                                    markedPlayed = markedPlayed,
                                )
                            }.onFailure { Timber.w(it, "postPlaybackStop failed in FIFO bridge") }
                            if (runHygiene &&
                                isSessionFenceValid(serverId, userId?.toString(), expectedSessionEpoch)
                            ) {
                                runCatching {
                                    episodePlaybackStopListener.get().onEpisodePlaybackStopped(
                                        itemId = targetItemId,
                                        playedPercentage = playedPercentage,
                                        markedPlayed = markedPlayed,
                                        serverId = serverId,
                                        userId = userId,
                                        sessionEpoch = expectedSessionEpoch,
                                    )
                                }.onFailure { Timber.w(it, "Episode hygiene failed in FIFO bridge") }
                            }
                        }
                        XrProcessPreferenceBridge.StopSourceType.LOCAL -> {
                            val mediaId = localMediaId ?: return@launch
                            runCatching {
                                localMediaRepository.get().updatePlaybackState(mediaId, positionMs, durationMs)
                            }.onFailure { Timber.w(it, "Local playback stop persist failed") }
                        }
                        XrProcessPreferenceBridge.StopSourceType.NETWORK -> {
                            val videoId = networkVideoId?.takeIf { it.isNotBlank() } ?: return@launch
                            runCatching {
                                networkMediaRepository.get().updatePlaybackState(videoId, positionMs, durationMs)
                            }.onFailure { Timber.w(it, "Network playback stop persist failed") }
                        }
                    }
                }
            }
        }
    }

    @Singleton
    @Provides
    fun providePreWipeHook(
        @ApplicationContext context: Context,
        appPreferences: AppPreferences,
        workManager: dagger.Lazy<WorkManager>,
        activeDownloadRegistry: ActiveDownloadRegistry,
        companionConfigImporter: dagger.Lazy<CompanionConfigImporter>,
        networkMediaRepository: dagger.Lazy<NetworkMediaRepository>,
        companionLiveSyncClient: dagger.Lazy<CompanionLiveSyncClient>,
        downloadStorageManager: dagger.Lazy<DownloadStorageManager>,
        database: ServerDatabaseDao,
    ): PreWipeHook =
        PreWipeHook {
            runCatching {
                if (appPreferences.isMainProcess) {
                    runCatching { workManager.get().cancelAllWork() }
                }
                runCatching { companionLiveSyncClient.get().stop() }
                runCatching { companionConfigImporter.get().cancelPostCommitExtras() }
                runCatching { networkMediaRepository.get().cancelAllBackgroundScans() }
                runBlocking(Dispatchers.IO) {
                    withTimeoutOrNull(2_000L) {
                        activeDownloadRegistry.cancelAll(timeoutMs = 1_500L)
                    }
                }
                runCatching { File(context.cacheDir, "subtitle_ai").deleteRecursively() }
                runCatching {
                    context.cacheDir.listFiles()?.forEach { file ->
                        if (file.isFile && Regex("^transcode_offline_\\d+\\.mp4$").matches(file.name)) {
                            file.delete()
                        }
                    }
                }
                val storage = downloadStorageManager.get()
                runCatching {
                    for (task in database.getAllDownloadTasks()) {
                        if (task.finalPath.isNotBlank()) storage.deletePhysicalFileAndMediaStore(task.finalPath)
                        if (task.tempPath.isNotBlank()) storage.deletePhysicalFileAndMediaStore(task.tempPath)
                        runCatching { DownloadStagingPaths.privateStagingFile(context, task.id).delete() }
                    }
                }
                deleteScopedMediaStoreSpatialFinDownloads(context)
            }.onFailure { Timber.w(it, "PreWipeHook cleanup encountered errors") }
        }

    @Singleton
    @Provides
    fun provideEncryptedDownloadsPurgeHook(
        @ApplicationContext context: Context,
        appPreferences: AppPreferences,
        workManager: dagger.Lazy<WorkManager>,
        activeDownloadRegistry: ActiveDownloadRegistry,
        downloadStorageManager: dagger.Lazy<DownloadStorageManager>,
        database: ServerDatabaseDao,
    ): EncryptedDownloadsPurgeHook =
        EncryptedDownloadsPurgeHook {
            if (appPreferences.isMainProcess) {
                runCatching { workManager.get().cancelAllWorkByTag("download-primary") }
                runCatching { workManager.get().cancelUniqueWork(DownloadIntegrityWorker.UNIQUE_WORK_NAME) }
            }
            activeDownloadRegistry.cancelAll(timeoutMs = 2_000L)
            activeDownloadRegistry.commitMutex.withLock {
                val storage = downloadStorageManager.get()
                val encryptedTasks = database.getEncryptedDownloads()
                val purgedItemIds = encryptedTasks.map { it.itemId }.distinct()
                val purgedSourceIds = encryptedTasks.map { it.sourceId }.filter { it.isNotBlank() }.distinct()

                for (itemId in purgedItemIds) {
                    val paths = encryptedTasks
                        .filter { it.itemId == itemId }
                        .flatMap { listOfNotNull(it.finalPath.takeIf(String::isNotBlank), it.tempPath.takeIf(String::isNotBlank)) }
                    runCatching { storage.markDownloadDeleted(itemId, paths) }
                }

                for (task in encryptedTasks) {
                    if (task.finalPath.isNotBlank()) {
                        runCatching { storage.deletePhysicalFileAndMediaStore(task.finalPath) }
                        runCatching { File("${task.finalPath}.enc.tmp").delete() }
                    }
                    if (task.tempPath.isNotBlank()) {
                        runCatching { storage.deletePhysicalFileAndMediaStore(task.tempPath) }
                    }
                    runCatching { DownloadStagingPaths.privateStagingFile(context, task.id).delete() }
                }
                if (encryptedTasks.isNotEmpty()) {
                    database.deleteDownloadTasksByIds(encryptedTasks.map { it.id })
                }

                // Sweep private downloads_tmp staging directory
                runCatching {
                    DownloadStagingPaths.stagingDir(context).listFiles()?.forEach { it.deleteRecursively() }
                }

                for (sourceId in purgedSourceIds) {
                    runCatching { database.deleteMediaStreamsBySourceId(sourceId) }
                    runCatching { database.deleteSource(sourceId) }
                }

                for (itemId in purgedItemIds) {
                    runCatching { File(context.filesDir, "trickplay/$itemId").deleteRecursively() }
                    runCatching { File(context.filesDir, "images/$itemId").deleteRecursively() }
                    runCatching { database.deleteSyncedUserData(itemId) }
                    val remainingSources = database.getSources(itemId)
                    if (remainingSources.isEmpty()) {
                        runCatching { database.deleteMovie(itemId) }
                        runCatching { database.deleteEpisode(itemId) }
                    }
                }
            }
        }

    private fun deleteScopedMediaStoreSpatialFinDownloads(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val resolver = context.contentResolver
        val collection = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
        )
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf("Download/SpatialFin/%")
        val ownedIds = mutableListOf<Long>()
        val otherIds = mutableListOf<Long>()
        runCatching {
            resolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val ownerCol = cursor.getColumnIndex(MediaStore.MediaColumns.OWNER_PACKAGE_NAME)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val owner = if (ownerCol >= 0) cursor.getString(ownerCol) else null
                    if (owner == context.packageName) ownedIds.add(id) else otherIds.add(id)
                }
            }
        }
        for (id in ownedIds + otherIds) {
            val itemUri = ContentUris.withAppendedId(collection, id)
            runCatching { resolver.delete(itemUri, null, null) }
        }
    }
}
