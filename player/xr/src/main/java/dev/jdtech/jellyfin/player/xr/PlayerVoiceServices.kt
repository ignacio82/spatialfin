package dev.jdtech.jellyfin.player.xr

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dagger.hilt.android.EntryPointAccessors
import dev.jdtech.jellyfin.player.local.presentation.PlayerViewModel
import dev.jdtech.jellyfin.player.xr.voice.GeminiCloudService
import dev.jdtech.jellyfin.player.xr.voice.GeminiNanoService
import dev.jdtech.jellyfin.player.xr.voice.SmartChatEngine
import dev.jdtech.jellyfin.player.xr.voice.SpatialCommandCoordinator
import dev.jdtech.jellyfin.player.xr.voice.SpatialVoiceService
import dev.jdtech.jellyfin.player.xr.voice.SpatialVoiceSynthesizer
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.repository.JellyfinRepository

/**
 * Bundle of voice / on-device AI singletons the player needs. Collapses 6 separate
 * `remember(context) { ... }` sites + the two lazy-init provider helpers into a
 * single holder. `commandCoordinator` and `chatEngine` are created on first call
 * so they don't warm engines on screens that never invoke the mic.
 */
@Stable
internal class PlayerVoiceServices(
    val voiceService: SpatialVoiceService,
    val geminiNanoService: GeminiNanoService,
    val geminiCloudService: GeminiCloudService,
    val tts: SpatialVoiceSynthesizer,
    val llmEntryPoint: LlmEntryPoint,
    private val appPreferences: AppPreferences,
    private val repository: JellyfinRepository,
    private val appContext: Context,
) {
    val downloadManager get() = llmEntryPoint.downloadManager()

    private var commandCoordinator: SpatialCommandCoordinator? = null
    private var chatEngine: SmartChatEngine? = null

    fun requireCommandCoordinator(): SpatialCommandCoordinator =
        commandCoordinator
            ?: SpatialCommandCoordinator(
                appContext,
                geminiNanoService,
                geminiCloudService,
                appPreferences,
                llmEntryPoint.modelManager(),
            ).also { commandCoordinator = it }

    fun requireChatEngine(): SmartChatEngine =
        chatEngine
            ?: SmartChatEngine(
                geminiNanoService,
                geminiCloudService,
                appPreferences,
                llmEntryPoint.modelManager(),
                repository,
            ).also { chatEngine = it }

    /** Releases every owned service. Call from the screen's `DisposableEffect(context)` onDispose. */
    fun destroy() {
        runCatching { voiceService.destroy() }
        runCatching { commandCoordinator?.destroy() }
        commandCoordinator = null
        runCatching { chatEngine?.destroy() }
        chatEngine = null
        runCatching { geminiNanoService.destroy() }
        runCatching { geminiCloudService.destroy() }
        runCatching { tts.destroy() }
    }
}

@Composable
internal fun rememberPlayerVoiceServices(viewModel: PlayerViewModel): PlayerVoiceServices {
    val context = LocalContext.current
    return remember(context, viewModel) {
        val entryPoint =
            EntryPointAccessors.fromApplication(context.applicationContext, LlmEntryPoint::class.java)
        PlayerVoiceServices(
            voiceService = SpatialVoiceService(context.applicationContext),
            geminiNanoService = GeminiNanoService(context.applicationContext),
            geminiCloudService = GeminiCloudService(
                context.applicationContext,
                viewModel.appPreferences,
                viewModel.repository,
            ),
            tts = SpatialVoiceSynthesizer(context.applicationContext),
            llmEntryPoint = entryPoint,
            appPreferences = viewModel.appPreferences,
            repository = viewModel.repository,
            appContext = context.applicationContext,
        )
    }
}
