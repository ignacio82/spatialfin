package dev.jdtech.jellyfin.di

import android.app.Application
import androidx.work.WorkManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.downloads.ActiveDownloadRegistry
import dev.jdtech.jellyfin.downloads.DownloadStorageManager
import dev.jdtech.jellyfin.player.core.downloads.EpisodePlaybackStopListener
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.security.ContentKeyManager
import dev.jdtech.jellyfin.session.SessionActivationCoordinator
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.utils.Downloader
import dev.jdtech.jellyfin.utils.DownloaderImpl
import dev.jdtech.jellyfin.utils.EpisodeDownloadHygieneCoordinator
import dev.spatialfin.unified.applock.AppLockObserver
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DownloaderModule {
    @Singleton
    @Provides
    fun provideDownloader(
        application: Application,
        serverDatabase: ServerDatabaseDao,
        jellyfinRepository: JellyfinRepository,
        appPreferences: AppPreferences,
        workManager: WorkManager,
        downloadStorageManager: DownloadStorageManager,
        sessionActivationCoordinator: SessionActivationCoordinator,
        appLockObserver: AppLockObserver,
        activeDownloadRegistry: ActiveDownloadRegistry,
        contentKeyManager: ContentKeyManager,
    ): Downloader {
        return DownloaderImpl(
            context = application,
            database = serverDatabase,
            jellyfinRepository = jellyfinRepository,
            appPreferences = appPreferences,
            workManager = workManager,
            downloadStorageManager = downloadStorageManager,
            sessionActivationCoordinator = sessionActivationCoordinator,
            appLockObserver = appLockObserver,
            activeDownloadRegistry = activeDownloadRegistry,
            contentKeyManager = contentKeyManager,
        )
    }

    @Singleton
    @Provides
    fun provideEpisodePlaybackStopListener(
        application: Application,
        serverDatabase: dagger.Lazy<ServerDatabaseDao>,
        jellyfinRepository: dagger.Lazy<JellyfinRepository>,
        downloader: dagger.Lazy<Downloader>,
        appPreferences: AppPreferences,
        sessionActivationCoordinator: dagger.Lazy<SessionActivationCoordinator>,
        appLockObserver: AppLockObserver,
    ): EpisodePlaybackStopListener {
        val isMainProcess = Application.getProcessName() == application.packageName
        if (!isMainProcess) {
            return EpisodePlaybackStopListener.NoOp
        }
        return EpisodeDownloadHygieneCoordinator(
            database = serverDatabase.get(),
            repository = jellyfinRepository.get(),
            downloader = downloader.get(),
            appPreferences = appPreferences,
            sessionActivationCoordinator = sessionActivationCoordinator.get(),
            appLockObserver = appLockObserver,
        )
    }
}
