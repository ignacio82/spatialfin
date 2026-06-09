package dev.spatialfin.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.jdtech.jellyfin.data.musicassistant.api.OkHttpServiceClient
import dev.jdtech.jellyfin.data.musicassistant.api.ServiceClient
import dev.jdtech.jellyfin.data.musicassistant.repository.MaSessionRepository
import dev.jdtech.jellyfin.data.musicassistant.repository.MusicAssistantRepository
import okhttp3.OkHttpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object MusicAssistantModule {

    @Provides
    @Singleton
    fun provideServiceClient(): ServiceClient {
        // SpatialFin's OkHttpClient is not globally provided as a generic @Singleton
        // to avoid clashing. We instantiate a dedicated client for MA WS.
        val okHttpClient = OkHttpClient.Builder().build()
        return OkHttpServiceClient(okHttpClient)
    }

    @Provides
    @Singleton
    fun provideMusicAssistantRepository(
        serviceClient: ServiceClient
    ): MusicAssistantRepository {
        return MusicAssistantRepository(serviceClient)
    }

    @Provides
    @Singleton
    fun provideMaSessionRepository(
        serviceClient: ServiceClient,
    ): MaSessionRepository {
        return MaSessionRepository(serviceClient)
    }
}
