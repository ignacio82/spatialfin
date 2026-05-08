package dev.spatialfin.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.jdtech.jellyfin.fcast.sender.FCastCastingController
import javax.inject.Singleton

/**
 * Provides the process-singleton FCast sender plumbing. The :fcast module deliberately ships no
 * Hilt deps so its codec/controller stays JVM-pure-Kotlin; this module is therefore the binding
 * site, not :fcast itself.
 *
 * [FCastCastingController.shutdown] is intentionally not wired to any teardown hook — its
 * coroutine scope is process-scoped and the OS kills it with the process. Per-screen surfaces
 * call [FCastCastingController.stopCast] (idempotent) instead.
 */
@Module
@InstallIn(SingletonComponent::class)
object FCastModule {

    @Singleton
    @Provides
    fun provideFCastCastingController(): FCastCastingController = FCastCastingController()
}
