package dev.spatialfin.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.jdtech.jellyfin.data.musicassistant.api.OkHttpServiceClient
import dev.jdtech.jellyfin.data.musicassistant.api.ServiceClient
import dev.jdtech.jellyfin.data.musicassistant.repository.MaSessionRepository
import dev.jdtech.jellyfin.data.musicassistant.repository.MusicAssistantRepository
import dev.jdtech.jellyfin.network.UrlTargetPolicy
import dev.jdtech.jellyfin.network.newBuilderWithSsrfGuard
import okhttp3.OkHttpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object MusicAssistantModule {

    @Provides
    @Singleton
    fun provideServiceClient(okHttpClient: OkHttpClient): ServiceClient {
        val guardedClient =
            okHttpClient
                .newBuilderWithSsrfGuard(
                    policy = UrlTargetPolicy.PUBLIC_OR_LAN,
                    allowCrossOriginRedirects = false,
                )
                .build()
        return OkHttpServiceClient(guardedClient)
    }

    @Provides
    @Singleton
    fun provideMusicAssistantRepository(
        serviceClient: ServiceClient,
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
