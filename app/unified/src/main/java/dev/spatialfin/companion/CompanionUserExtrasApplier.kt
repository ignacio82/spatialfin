package dev.spatialfin.companion

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.jdtech.jellyfin.data.musicassistant.api.ServiceClient
import dev.jdtech.jellyfin.data.musicassistant.repository.MaSessionRepository
import dev.jdtech.jellyfin.models.companion.CompanionMusicAssistant
import dev.jdtech.jellyfin.network.UrlTargetPolicy
import dev.jdtech.jellyfin.network.newBuilderWithSsrfGuard
import dev.jdtech.jellyfin.plugins.bridge.HttpBridge
import dev.jdtech.jellyfin.plugins.repository.PluginContentRepository
import dev.jdtech.jellyfin.plugins.repository.PluginRepository
import dev.jdtech.jellyfin.sendspin.receiver.SendspinReceiverService
import dev.jdtech.jellyfin.sendspin.receiver.SendspinReceiverSession
import dev.jdtech.jellyfin.sendspin.receiver.SendspinUserScope
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.work.CompanionUserExtrasApplier
import dev.jdtech.jellyfin.work.MusicAssistantUserScope
import dev.spatialfin.fcast.FCastReceiverWiring
import dev.spatialfin.sendspin.SendspinReceiverWiring
import dev.spatialfin.unified.detectDeviceClass
import dev.spatialfin.unified.applock.AppLockManager
import dev.spatialfin.unified.applock.AppLockObserver
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.OkHttpClient
import timber.log.Timber

/**
 * `:app:unified` implementation of [CompanionUserExtrasApplier]. Bridges `:sendspin`
 * (Music Assistant), `:fcast`, `:modes:music`, and `:plugins` (universal plugins) for
 * `:core`'s [CompanionConfigImporter] and [SessionActivationCoordinator].
 */
@Singleton
class CompanionUserExtrasApplierImpl
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val pluginRepository: PluginRepository,
    private val okHttpClient: OkHttpClient,
    private val appPreferences: AppPreferences,
    private val appLockObserver: AppLockObserver,
    private val serviceClient: ServiceClient,
    private val maSessionRepository: MaSessionRepository,
    private val httpBridge: HttpBridge,
    private val pluginContentRepository: PluginContentRepository,
) : CompanionUserExtrasApplier {

    private val maHttpClient: OkHttpClient by lazy {
        okHttpClient
            .newBuilderWithSsrfGuard(
                policy = UrlTargetPolicy.PUBLIC_OR_LAN,
                allowCrossOriginRedirects = false,
            )
            .build()
    }

    private fun isAppLocked(): Boolean =
        appLockObserver.lockState.value == AppLockManager.LockState.LOCKED

    private fun MusicAssistantUserScope.toSendspinScope(): SendspinUserScope =
        when (this) {
            MusicAssistantUserScope.Disabled -> SendspinUserScope.Disabled
            MusicAssistantUserScope.Standalone -> SendspinUserScope.Standalone
            is MusicAssistantUserScope.Authenticated -> SendspinUserScope.Authenticated(userId)
        }

    override suspend fun loginMusicAssistant(
        jellyfinUserId: String?,
        musicAssistant: CompanionMusicAssistant,
    ) {
        if (isAppLocked()) return
        val url = musicAssistant.url.trim()
        if (url.isBlank()) return
        val normalizedUserId = jellyfinUserId?.takeIf { it.isNotBlank() }
        SendspinReceiverService.loginAndStoreMusicAssistantConfig(
            context = context,
            jellyfinUserId = normalizedUserId,
            serverUrl = url,
            token = musicAssistant.token,
            username = musicAssistant.username,
            password = musicAssistant.password,
            httpClient = maHttpClient,
        ).onFailure {
            Timber.w(it, "COMPANION SYNC (Extras): Music Assistant login/store failed")
        }
        val activeScope = SendspinReceiverSession.state.value.activeUserScope
        val targetScope =
            if (normalizedUserId != null) {
                MusicAssistantUserScope.Authenticated(normalizedUserId)
            } else {
                MusicAssistantUserScope.Standalone
            }
        if (activeScope == targetScope.toSendspinScope()) {
            refreshMusicAssistant(targetScope)
        }
    }

    override fun refreshMusicAssistant(scope: MusicAssistantUserScope) {
        val sendspinScope = scope.toSendspinScope()
        if (isAppLocked() || scope is MusicAssistantUserScope.Disabled) {
            serviceClient.disconnectForRebind()
            maSessionRepository.resetSession()
            SendspinReceiverSession.clearMusicAssistantState(sendspinScope)
            SendspinReceiverService.stop(context)
            return
        }
        serviceClient.disconnectForRebind()
        maSessionRepository.resetSession()
        // Always hydrate SendspinReceiverSession for unlocked non-Disabled scopes,
        // independent of whether inbound cast/Sendspin reception is enabled.
        SendspinReceiverService.hydrateMusicAssistantSessionForScope(context, sendspinScope)
        SendspinReceiverService.setMusicAssistantUser(context = context, scope = sendspinScope)
        if (FCastReceiverWiring.isReceiverEnabled(appPreferences)) {
            SendspinReceiverWiring.tryStartReceiver(context, appPreferences)
        }
    }

    override fun onAppLocked() {
        serviceClient.disconnectForRebind()
        maSessionRepository.resetSession()
        SendspinReceiverSession.clearMusicAssistantState(SendspinUserScope.Disabled)
        SendspinReceiverWiring.stop(context)
        FCastReceiverWiring.onAppLocked(context)
        clearPluginRuntimeCaches()
    }

    override fun onAppUnlocked(scope: MusicAssistantUserScope) {
        if (isAppLocked()) return
        val deviceClass = context.detectDeviceClass()
        FCastReceiverWiring.onAppUnlocked(context, appPreferences, deviceClass)
        refreshMusicAssistant(scope)
        if (scope !is MusicAssistantUserScope.Disabled) {
            SendspinReceiverWiring.onAppUnlocked(context, appPreferences)
        }
    }

    override fun migrateUserScope(oldUserId: String, newUserId: String) {
        SendspinReceiverService.migrateUserScope(context, oldUserId, newUserId)
        pluginRepository.migrateUserScope(oldUserId, newUserId)
    }

    override fun clearUserScope(userId: String) {
        SendspinReceiverService.clearUserScope(context, userId)
        pluginRepository.clearUserScope(userId)
        httpBridge.clearUserCookies(userId)
        clearPluginRuntimeCaches()
    }

    override fun clearPluginRuntimeCaches() {
        httpBridge.clearAllCookies()
        pluginContentRepository.clearCache()
    }

    override suspend fun installPlugins(jellyfinUserId: String, manifestUrls: List<String>) {
        if (isAppLocked()) return
        val already = pluginRepository.installedManifestUrls(jellyfinUserId)
        manifestUrls
            .map { it.trim() }
            .filter { it.isNotBlank() && it !in already }
            .forEach { url ->
                pluginRepository.installPlugin(
                    manifestUrl = url,
                    scopeOverride = jellyfinUserId,
                    requireHttps = true,
                ).onFailure {
                    Timber.w(it, "Companion plugin install failed: %s", url.substringBefore('?'))
                }
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
