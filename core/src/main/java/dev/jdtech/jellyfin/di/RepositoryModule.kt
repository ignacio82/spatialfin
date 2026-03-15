package dev.jdtech.jellyfin.di

import android.app.Application
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.downloads.DownloadStorageManager
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.repository.JellyfinRepositoryImpl
import dev.jdtech.jellyfin.repository.JellyfinRepositoryOfflineImpl
import dev.jdtech.jellyfin.repository.LocalMediaRepository
import dev.jdtech.jellyfin.repository.LocalMediaRepositoryImpl
import dev.jdtech.jellyfin.repository.SmartJellyfinRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {
    @Singleton
    @Provides
    fun provideDownloadStorageManager(
        application: Application,
        serverDatabase: ServerDatabaseDao,
    ): DownloadStorageManager = DownloadStorageManager(application, serverDatabase)

    @Singleton
    @Provides
    fun provideJellyfinRepositoryImpl(
        application: Application,
        jellyfinApi: JellyfinApi,
        serverDatabase: ServerDatabaseDao,
        appPreferences: AppPreferences,
        downloadStorageManager: DownloadStorageManager,
    ): JellyfinRepositoryImpl {
        println("Creating new jellyfinRepositoryImpl")
        return JellyfinRepositoryImpl(
            application,
            jellyfinApi,
            serverDatabase,
            appPreferences,
            downloadStorageManager,
        )
    }

    @Singleton
    @Provides
    fun provideJellyfinRepositoryOfflineImpl(
        application: Application,
        jellyfinApi: JellyfinApi,
        serverDatabase: ServerDatabaseDao,
        appPreferences: AppPreferences,
        downloadStorageManager: DownloadStorageManager,
    ): JellyfinRepositoryOfflineImpl {
        println("Creating new jellyfinRepositoryOfflineImpl")
        return JellyfinRepositoryOfflineImpl(
            application,
            jellyfinApi,
            serverDatabase,
            appPreferences,
            downloadStorageManager,
        )
    }

    @Provides
    fun provideJellyfinRepository(
        smartJellyfinRepository: SmartJellyfinRepository,
    ): JellyfinRepository {
        println("Creating new JellyfinRepository")
        return smartJellyfinRepository
    }

    @Singleton
    @Provides
    fun provideLocalMediaRepository(
        application: Application,
        serverDatabase: ServerDatabaseDao,
    ): LocalMediaRepository = LocalMediaRepositoryImpl(application, serverDatabase)
}
