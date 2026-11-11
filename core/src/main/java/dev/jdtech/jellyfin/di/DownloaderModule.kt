package dev.jdtech.jellyfin.di

import android.app.Application
import androidx.work.WorkManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.downloads.DownloadStorageManager
import dev.jdtech.jellyfin.player.core.downloads.EpisodePlaybackStopListener
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.utils.Downloader
import dev.jdtech.jellyfin.utils.DownloaderImpl
import dev.jdtech.jellyfin.utils.EpisodeDownloadHygieneCoordinator
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
    ): Downloader {
        return DownloaderImpl(
            application,
            serverDatabase,
            jellyfinRepository,
            appPreferences,
            workManager,
            downloadStorageManager,
        )
    }

    @Singleton
    @Provides
    fun provideEpisodePlaybackStopListener(
        serverDatabase: ServerDatabaseDao,
        jellyfinRepository: JellyfinRepository,
        downloader: Downloader,
        appPreferences: AppPreferences,
    ): EpisodePlaybackStopListener {
        return EpisodeDownloadHygieneCoordinator(
            database = serverDatabase,
            repository = jellyfinRepository,
            downloader = downloader,
            appPreferences = appPreferences,
        )
    }
}
