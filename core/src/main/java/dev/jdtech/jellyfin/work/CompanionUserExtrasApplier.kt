package dev.jdtech.jellyfin.work

import dev.jdtech.jellyfin.models.companion.CompanionMusicAssistant

/**
 * Applies the per-Jellyfin-user "extras" a companion config carries that live in
 * modules `:core` cannot depend on without a dependency cycle: Music Assistant
 * config (`:sendspin`) and universal source plugins (`:plugins`, which itself
 * depends on `:core`).
 *
 * Implemented in `:app:unified` (which can see both modules) and injected via
 * Hilt into [CompanionSyncWorker] and the initial-setup companion importers
 * (XR, Beam, TV). [applyMusicAssistant] is called for **every** imported user;
 * [installPlugins] only for the active user. Implementations must therefore key
 * strictly off the [jellyfinUserId] parameter, never the live session identity.
 */
interface CompanionUserExtrasApplier {
    /**
     * Persist [musicAssistant] into the receiver's per-user MA store for
     * [jellyfinUserId]. A null/blank URL clears nothing here — callers pass a
     * non-null value only when the companion supplied one.
     */
    fun applyMusicAssistant(jellyfinUserId: String, musicAssistant: CompanionMusicAssistant)

    /**
     * Rebind the live receiver/session UI to [jellyfinUserId] after foreground
     * onboarding imports MA config for the active user.
     */
    fun refreshMusicAssistant(jellyfinUserId: String)

    /**
     * Install each manifest URL in [manifestUrls] into [jellyfinUserId]'s plugin
     * scope, skipping any already installed. Additive: removing a URL in the
     * companion does not uninstall on device. Failures are per-URL and must not
     * abort the rest of the sync.
     */
    suspend fun installPlugins(jellyfinUserId: String, manifestUrls: List<String>)
}
