package dev.spatialfin.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.SendspinAvrHttpClient
import dev.jdtech.jellyfin.fcast.sender.FCastCastingController
import dev.jdtech.jellyfin.network.UrlTargetPolicy
import dev.jdtech.jellyfin.network.newBuilderWithSsrfGuard
import javax.inject.Singleton
import okhttp3.OkHttpClient

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

    @Singleton
    @Provides
    fun provideSendspinAvrHttpClient(okHttpClient: OkHttpClient): SendspinAvrHttpClient {
        val guarded = okHttpClient
            .newBuilderWithSsrfGuard(
                policy = UrlTargetPolicy.LAN_ONLY,
                allowCrossOriginRedirects = false,
            )
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
        return SendspinAvrHttpClient(guarded)
    }
}
