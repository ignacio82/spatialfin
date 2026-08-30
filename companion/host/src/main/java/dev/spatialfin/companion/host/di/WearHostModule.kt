package dev.spatialfin.companion.host.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object WearHostModule {
    // Singletons are injected via @Inject constructor
}
