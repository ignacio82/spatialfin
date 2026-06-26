package dev.jdtech.jellyfin.core.llm

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.jdtech.jellyfin.settings.domain.llm.LlmDownloadManager

/**
 * Singleton accessor for the on-device LLM managers. Hilt can't inject
 * directly into a plain @Composable, but a SingletonComponent EntryPoint
 * gives us the same instances the XR / settings ViewModels already use.
 *
 * Lives in `:core` (not a shell) because the Gemma + AICore management cards
 * are rendered on both the Beam and TV settings hubs, which are separate
 * form-factor shell modules — both pull these managers from the single Hilt
 * singleton graph via `EntryPointAccessors.fromApplication`.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface LlmManagerEntryPoint {
    fun llmDownloadManager(): LlmDownloadManager
    fun llmModelManager(): LlmModelManager
}
