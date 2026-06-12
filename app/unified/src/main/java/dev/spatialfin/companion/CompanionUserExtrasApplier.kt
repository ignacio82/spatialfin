package dev.spatialfin.companion

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.jdtech.jellyfin.models.companion.CompanionMusicAssistant
import dev.jdtech.jellyfin.plugins.repository.PluginRepository
import dev.jdtech.jellyfin.sendspin.receiver.SendspinReceiverService
import dev.jdtech.jellyfin.work.CompanionUserExtrasApplier
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `:app:unified` implementation of [CompanionUserExtrasApplier]. Lives here
 * because it bridges `:sendspin` (Music Assistant) and `:plugins` (universal
 * plugins), which `:core` — where [CompanionUserExtrasApplier] and the
 * companion-sync worker live — cannot depend on.
 */
@Singleton
class CompanionUserExtrasApplierImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pluginRepository: PluginRepository,
) : CompanionUserExtrasApplier {

    override fun applyMusicAssistant(jellyfinUserId: String, musicAssistant: CompanionMusicAssistant) {
        val url = musicAssistant.url.trim()
        if (url.isBlank()) return
        // Persist straight to the per-user MA store; no need to start the
        // receiver service just to record config for a user who isn't casting.
        SendspinReceiverService.storeMusicAssistantConfig(
            context = context,
            jellyfinUserId = jellyfinUserId,
            serverUrl = url,
            token = musicAssistant.token,
        )
    }

    override suspend fun installPlugins(jellyfinUserId: String, manifestUrls: List<String>) {
        val already = pluginRepository.installedManifestUrls(jellyfinUserId)
        manifestUrls
            .map { it.trim() }
            .filter { it.isNotBlank() && it !in already }
            .forEach { url ->
                pluginRepository.installPlugin(url, scopeOverride = jellyfinUserId)
                    .onFailure { Timber.e(it, "Companion plugin install failed: $url") }
            }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class CompanionUserExtrasModule {
    @Binds
    abstract fun bindCompanionUserExtrasApplier(
        impl: CompanionUserExtrasApplierImpl,
    ): CompanionUserExtrasApplier
}
