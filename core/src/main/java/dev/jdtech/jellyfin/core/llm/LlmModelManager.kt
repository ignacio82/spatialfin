package dev.jdtech.jellyfin.core.llm

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.settings.domain.llm.DownloadState
import dev.jdtech.jellyfin.settings.domain.llm.LlmDownloadManager
import dev.jdtech.jellyfin.settings.domain.llm.ModelState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-wide singleton that manages the LiteRT LM engine lifecycle.
 *
 * Watches [LlmDownloadManager.downloadState] and auto-initialises the engine as soon as the model
 * file is ready. Once initialized, the engine instance is available to any screen via
 * [instance] — not just the XR player.
 *
 * State is also pushed back to [LlmDownloadManager.modelState] so that [SettingsViewModel]
 * (which lives in :settings and cannot depend on :core) can react without a circular dependency.
 */
@Singleton
class LlmModelManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloadManager: LlmDownloadManager,
    private val appPreferences: AppPreferences,
) {
    val modelState: StateFlow<ModelState> = downloadManager.modelState

    /** The live engine instance; null if not yet initialized. */
    @Volatile
    var instance: LlmModelInstance? = null
        private set

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        scope.launch {
            downloadManager.downloadState.collect { downloadState ->
                if (downloadState is DownloadState.Ready &&
                    modelState.value.let { it is ModelState.Idle || it is ModelState.Error }) {
                    initializeEngine(downloadState.file.absolutePath)
                }
            }
        }
    }

    /**
     * Ensures the engine is initialized. Safe to call from any coroutine context.
     * Returns immediately if already ready or initializing.
     */
    suspend fun ensureInitialized() {
        val downloadState = downloadManager.downloadState.value
        if (downloadState !is DownloadState.Ready) return
        if (modelState.value.let { it is ModelState.Ready || it is ModelState.Initializing }) return
        initializeEngine(downloadState.file.absolutePath)
    }

    private suspend fun initializeEngine(modelPath: String) {
        downloadManager.updateModelState(ModelState.Initializing)
        Timber.i("LlmModelManager: initializing engine from %s", modelPath)

        // Log a heartbeat every 15 s so the log shows we're not stuck
        val heartbeat = scope.launch {
            var elapsed = 0L
            while (true) {
                delay(15_000)
                elapsed += 15
                Timber.i("LlmModelManager: still initializing… (%ds elapsed)", elapsed)
            }
        }

        try {
            val newInstance = LlmChatModelHelper.initializeEngine(context, modelPath)
            heartbeat.cancel()
            if (newInstance != null) {
                // Close any existing instance before replacing it.
                instance?.let { LlmChatModelHelper.close(it) }
                instance = newInstance
                downloadManager.updateModelState(ModelState.Ready(newInstance.backendName))

                // Always store the actual backend so settings reflects current hardware capability.
                val previousBackend = appPreferences.getValue(appPreferences.voiceAssistantGemmaBackend)
                appPreferences.setValue(appPreferences.voiceAssistantGemmaBackend, newInstance.backendName)
                // Auto-enable/disable only on first init (or if backend was previously unknown).
                if (previousBackend == "Requires Model" || previousBackend == "Unknown" || previousBackend == "CPU") {
                    if (newInstance.backendName == "CPU") {
                        appPreferences.setValue(appPreferences.voiceAssistantGemmaEnabled, false)
                        Timber.i("LlmModelManager: only CPU available, disabling Gemma by default")
                    } else {
                        appPreferences.setValue(appPreferences.voiceAssistantGemmaEnabled, true)
                        Timber.i("LlmModelManager: GPU/NPU available, enabling Gemma")
                    }
                }
                Timber.i("LlmModelManager: ready on %s", newInstance.backendName)
            } else {
                heartbeat.cancel()
                downloadManager.updateModelState(ModelState.Error("Engine initialization returned null"))
                Timber.e("LlmModelManager: initializeEngine returned null")
            }
        } catch (e: Exception) {
            heartbeat.cancel()
            downloadManager.updateModelState(ModelState.Error(e.message ?: "Unknown error"))
            Timber.e(e, "LlmModelManager: initialization failed")
        }
    }
}
