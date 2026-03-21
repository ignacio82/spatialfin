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
import dev.jdtech.jellyfin.repository.NetworkMediaRepository
import dev.jdtech.jellyfin.repository.NetworkMediaRepositoryImpl
import dev.jdtech.jellyfin.repository.SmartJellyfinRepository
import dev.jdtech.jellyfin.api.TmdbApi
import dev.jdtech.jellyfin.network.MetadataMatchService
import dev.jdtech.jellyfin.network.NetworkDiscovery
import dev.jdtech.jellyfin.network.NetworkFileClientFactory
import dev.jdtech.jellyfin.network.NetworkStreamProxy
import dev.jdtech.jellyfin.network.NfsFileClient
import dev.jdtech.jellyfin.network.SmbFileClient
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

    @Singleton
    @Provides
    fun provideSmbFileClient(): SmbFileClient = SmbFileClient()

    @Singleton
    @Provides
    fun provideNfsFileClient(): NfsFileClient = NfsFileClient()

    @Singleton
    @Provides
    fun provideNetworkFileClientFactory(
        smbFileClient: SmbFileClient,
        nfsFileClient: NfsFileClient,
    ): NetworkFileClientFactory = NetworkFileClientFactory(smbFileClient, nfsFileClient)

    @Singleton
    @Provides
    fun provideNetworkStreamProxy(
        clientFactory: NetworkFileClientFactory,
        serverDatabase: ServerDatabaseDao,
    ): NetworkStreamProxy = NetworkStreamProxy(clientFactory, serverDatabase)

    @Singleton
    @Provides
    fun provideNetworkDiscovery(): NetworkDiscovery = NetworkDiscovery()

    @Singleton
    @Provides
    fun provideTmdbApi(
        appPreferences: AppPreferences,
    ): TmdbApi = TmdbApi(appPreferences)

    @Singleton
    @Provides
    fun provideMetadataMatchService(
        tmdbApi: TmdbApi,
        serverDatabase: ServerDatabaseDao,
    ): MetadataMatchService = MetadataMatchService(tmdbApi, serverDatabase)

    @Singleton
    @Provides
    fun provideNetworkMediaRepository(
        serverDatabase: ServerDatabaseDao,
        clientFactory: NetworkFileClientFactory,
        streamProxy: NetworkStreamProxy,
        discovery: NetworkDiscovery,
        metadataMatchService: MetadataMatchService,
    ): NetworkMediaRepository = NetworkMediaRepositoryImpl(
        serverDatabase,
        clientFactory,
        streamProxy,
        discovery,
        metadataMatchService,
    )
}
