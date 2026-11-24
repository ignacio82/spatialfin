package dev.jdtech.jellyfin.work

import dev.jdtech.jellyfin.models.companion.CompanionMusicAssistant

sealed interface MusicAssistantUserScope {
    data object Disabled : MusicAssistantUserScope

    data object Standalone : MusicAssistantUserScope

    data class Authenticated(val userId: String) : MusicAssistantUserScope
}

/**
 * Applies per-Jellyfin-user "extras" and cross-module App Lock / session teardown hooks
 * for modules `:core` cannot depend on directly without a dependency cycle (`:sendspin`,
 * `:fcast`, `:plugins`, `:modes:music`).
 *
 * Implemented in `:app:unified` (`CompanionUserExtrasApplierImpl`).
 */
interface CompanionUserExtrasApplier {
    /**
     * Authenticate (when credentials are supplied) and persist [musicAssistant]
     * into the receiver's per-user MA store for [jellyfinUserId] off the pairing
     * socket. When the active scope still matches [jellyfinUserId] and unlocked,
     * refreshes the live receiver session.
     */
    suspend fun loginMusicAssistant(jellyfinUserId: String?, musicAssistant: CompanionMusicAssistant)

    /**
     * Rebind Music Assistant WebSocket/repository/Sendspin state to the 3-state [scope].
     * Always hydrates `SendspinReceiverSession` when unlocked and non-[MusicAssistantUserScope.Disabled],
     * independent of whether inbound cast reception is enabled.
     */
    fun refreshMusicAssistant(scope: MusicAssistantUserScope)

    /**
     * Tears down Music Assistant, Sendspin, FCast, and plugin cookies/caches when App Lock locks.
     */
    fun onAppLocked() = Unit

    /**
     * Restores FCast and (when [scope] is non-[MusicAssistantUserScope.Disabled]) Music Assistant
     * and Sendspin when App Lock unlocks.
     */
    fun onAppUnlocked(scope: MusicAssistantUserScope) = Unit

    /**
     * Migrates per-user Sendspin Music Assistant and plugin storage from [oldUserId] to [newUserId].
     */
    fun migrateUserScope(oldUserId: String, newUserId: String) = Unit

    /**
     * Clears per-user Sendspin Music Assistant, plugin storage/caches, and HTTP cookies for [userId].
     */
    fun clearUserScope(userId: String) = Unit

    /**
     * Clears in-memory plugin HTTP cookies and cached plugin rows without wiping persisted scopes.
     */
    fun clearPluginRuntimeCaches() = Unit

    /**
     * Install each manifest URL in [manifestUrls] into [jellyfinUserId]'s plugin
     * scope, skipping any already installed. Additive: removing a URL in the
     * companion does not uninstall on device. Failures are per-URL and must not
     * abort the rest of the sync.
     */
    suspend fun installPlugins(jellyfinUserId: String, manifestUrls: List<String>)
}
