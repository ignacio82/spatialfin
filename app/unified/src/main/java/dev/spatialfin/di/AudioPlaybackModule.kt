package dev.spatialfin.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.spatialfin.unified.audio.AudioOutputEngine
import dev.spatialfin.unified.audio.Media3JellyfinAudioOutputEngine
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AudioPlaybackModule {
    @Binds
    @Singleton
    abstract fun bindAudioOutputEngine(engine: Media3JellyfinAudioOutputEngine): AudioOutputEngine
}
