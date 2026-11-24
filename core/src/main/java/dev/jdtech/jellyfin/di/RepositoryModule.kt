package dev.jdtech.jellyfin.di

import android.app.Application
import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.api.OmdbApi
import dev.jdtech.jellyfin.api.TmdbApi
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.downloads.DownloadStorageManager
import dev.jdtech.jellyfin.network.MetadataMatchService
import dev.jdtech.jellyfin.network.NetworkDiscovery
import dev.jdtech.jellyfin.network.NetworkFileClientFactory
import dev.jdtech.jellyfin.network.NetworkStreamProxy
import dev.jdtech.jellyfin.network.NfsFileClient
import dev.jdtech.jellyfin.network.SmbFileClient
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.repository.JellyfinRepositoryImpl
import dev.jdtech.jellyfin.repository.JellyfinRepositoryOfflineImpl
import dev.jdtech.jellyfin.repository.LocalMediaRepository
import dev.jdtech.jellyfin.repository.LocalMediaRepositoryImpl
import dev.jdtech.jellyfin.repository.NetworkMediaRepository
import dev.jdtech.jellyfin.repository.NetworkMediaRepositoryImpl
import dev.jdtech.jellyfin.repository.SmartJellyfinRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.settings.domain.XrProcessPreferenceBridge
import dev.spatialfin.unified.applock.AppLockObserver
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {
    @Singleton
    @Provides
    fun provideDownloadStorageManager(
        application: Application,
        serverDatabase: ServerDatabaseDao,
        appPreferences: AppPreferences,
    ): DownloadStorageManager =
        DownloadStorageManager(
            context = application,
            database = serverDatabase,
            isMainProcess = appPreferences.isMainProcess,
            isContentEncryptionEnabled = { appPreferences.getValue(appPreferences.contentEncryptionEnabled) },
        )

    @Singleton
    @Provides
    fun provideJellyfinRepositoryImpl(
        application: Application,
        jellyfinApi: JellyfinApi,
        serverDatabase: ServerDatabaseDao,
        appPreferences: AppPreferences,
        downloadStorageManager: DownloadStorageManager,
        xrProcessPreferenceBridge: XrProcessPreferenceBridge,
    ): JellyfinRepositoryImpl {
        return JellyfinRepositoryImpl(
            context = application,
            jellyfinApi = jellyfinApi,
            database = serverDatabase,
            appPreferences = appPreferences,
            downloadStorageManager = downloadStorageManager,
            xrProcessPreferenceBridge = xrProcessPreferenceBridge,
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
    ): JellyfinRepository = smartJellyfinRepository

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
        appLockObserver: AppLockObserver,
    ): NetworkStreamProxy = NetworkStreamProxy(clientFactory, serverDatabase, appLockObserver)

    @Singleton
    @Provides
    fun provideNetworkDiscovery(
        @ApplicationContext context: Context,
    ): NetworkDiscovery = NetworkDiscovery(context)

    @Singleton
    @Provides
    fun provideTmdbApi(
        appPreferences: AppPreferences,
        client: okhttp3.OkHttpClient,
    ): TmdbApi = TmdbApi(appPreferences, client)

    @Singleton
    @Provides
    fun provideOmdbApi(
        appPreferences: AppPreferences,
        client: okhttp3.OkHttpClient,
    ): OmdbApi = OmdbApi(appPreferences, client)

    @Singleton
    @Provides
    fun provideMetadataMatchService(
        tmdbApi: TmdbApi,
        omdbApi: OmdbApi,
        serverDatabase: ServerDatabaseDao,
    ): MetadataMatchService = MetadataMatchService(tmdbApi, omdbApi, serverDatabase)

    @Singleton
    @Provides
    fun provideNetworkMediaRepository(
        serverDatabase: ServerDatabaseDao,
        clientFactory: NetworkFileClientFactory,
        streamProxy: NetworkStreamProxy,
        discovery: NetworkDiscovery,
        smbFileClient: SmbFileClient,
        metadataMatchService: MetadataMatchService,
        appLockObserver: AppLockObserver,
    ): NetworkMediaRepository = NetworkMediaRepositoryImpl(
        serverDatabase,
        clientFactory,
        streamProxy,
        discovery,
        smbFileClient,
        metadataMatchService,
        appLockObserver,
    )
}
