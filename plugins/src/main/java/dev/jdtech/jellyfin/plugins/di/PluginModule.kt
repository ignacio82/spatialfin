package dev.jdtech.jellyfin.plugins.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.jdtech.jellyfin.plugins.bridge.DOMParserBridge
import dev.jdtech.jellyfin.plugins.bridge.HttpBridge
import dev.jdtech.jellyfin.plugins.bridge.RealDOMParserBridge
import dev.jdtech.jellyfin.plugins.bridge.RealHttpBridge
import dev.jdtech.jellyfin.plugins.bridge.RealUtilitiesBridge
import dev.jdtech.jellyfin.plugins.bridge.UtilitiesBridge
import okhttp3.OkHttpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object PluginModule {

    @Provides
    @Singleton
    fun provideHttpBridge(client: OkHttpClient): HttpBridge {
        return RealHttpBridge(client)
    }

    @Provides
    @Singleton
    fun provideDOMParserBridge(): DOMParserBridge {
        return RealDOMParserBridge()
    }

    @Provides
    @Singleton
    fun provideUtilitiesBridge(): UtilitiesBridge {
        return RealUtilitiesBridge()
    }
    
    @Provides
    @Singleton
    fun provideContext(@ApplicationContext context: Context): Context {
        return context
    }
}
