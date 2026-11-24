package dev.jdtech.jellyfin.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.api.SeerrApi
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.network.UrlTargetPolicy
import dev.jdtech.jellyfin.network.withSsrfGuard
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
object ApiModule {
    @Singleton
    @Provides
    fun provideOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .withSsrfGuard(UrlTargetPolicy.PUBLIC_OR_LAN)
            .build()
    }

    @Singleton
    @Provides
    fun provideJellyfinApi(
        @ApplicationContext application: Context,
        appPreferences: AppPreferences,
        database: ServerDatabaseDao,
    ): JellyfinApi {
        val jellyfinApi =
            JellyfinApi.getInstance(
                context = application,
                requestTimeout = appPreferences.getValue(appPreferences.requestTimeout),
                connectTimeout = appPreferences.getValue(appPreferences.connectTimeout),
                socketTimeout = appPreferences.getValue(appPreferences.socketTimeout),
            )

        val serverId = appPreferences.getValue(appPreferences.currentServer) ?: return jellyfinApi

        val (serverAddress, user) =
            runBlocking(Dispatchers.IO) {
                val withAddrAndUser = database.getServerWithAddressAndUser(serverId)
                val resolvedAddress =
                    withAddrAndUser?.address ?: database.getServerCurrentAddress(serverId)
                resolvedAddress to withAddrAndUser?.user
            }
        if (serverAddress == null) return jellyfinApi
        val authenticatedUser = user?.takeIf { !it.accessToken.isNullOrBlank() }

        jellyfinApi.apply {
            api.update(
                baseUrl = serverAddress.address,
                accessToken = authenticatedUser?.accessToken,
            )
            userId = authenticatedUser?.id
        }

        return jellyfinApi
    }

    @Singleton
    @Provides
    fun provideSeerrApi(
        appPreferences: AppPreferences,
        client: OkHttpClient,
    ): SeerrApi {
        return SeerrApi(appPreferences, client)
    }
}
