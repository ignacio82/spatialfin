package dev.jdtech.jellyfin.core.llm

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.settings.domain.llm.DownloadState
import dev.jdtech.jellyfin.settings.domain.llm.LlmDownloadManager
import dev.jdtech.jellyfin.settings.domain.llm.ModelState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether the current on-device AI setup can drive a good voice experience.
 * [CPU_ONLY] means LiteRT landed on CPU and there's no cloud API key to offload
 * to — voice UX will be painfully slow and should be hidden rather than offered.
 */
enum class VoiceCapability { UNKNOWN, CAPABLE, CPU_ONLY }

/**
 * App-wide singleton that owns the on-device voice AI engine lifecycle.
 *
 * Previously this only managed LiteRT-LM. It now probes [AICoreModelHelper] on
 * start-up and prefers Google's system Gemini Nano (AICore) when the device
 * supports it — the fast path on Pixel 10 Pro and other AICore-capable
 * devices. LiteRT remains the universal fallback for devices that don't ship
 * AICore (older Pixels, non-Samsung non-Pixel, etc.).
 *
 * Surface area for consumers:
 *   - [engine]: the active [VoiceAiEngine] (AICore or LiteRT) once something
 *     is ready. Null while still probing or if nothing is usable.
 *   - [aiCoreStatus]: live AICore probe/download/warm-up state, used by the
 *     Settings UI.
 *   - [modelState]: forwarded LiteRT state flow — still exposed so the LiteRT
 *     management UI keeps working.
 */
@Singleton
class LlmModelManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloadManager: LlmDownloadManager,
    private val appPreferences: AppPreferences,
) {
    val modelState: StateFlow<ModelState> = downloadManager.modelState

    private val _aiCoreStatus = MutableStateFlow<AICoreStatus>(AICoreStatus.Unknown)
    val aiCoreStatus: StateFlow<AICoreStatus> = _aiCoreStatus.asStateFlow()

    private val _engine = MutableStateFlow<VoiceAiEngine?>(null)
    val engine: StateFlow<VoiceAiEngine?> = _engine.asStateFlow()

    private val _voiceCapability = MutableStateFlow(VoiceCapability.UNKNOWN)
    /**
     * Derived signal that UIs (Beam mic FAB, future Home/XR affordances) use to
     * decide whether to surface voice at all. Recomputes when the active engine
     * changes and when [AppPreferences.voiceAssistantCloudApiKey] is written.
     */
    val voiceCapability: StateFlow<VoiceCapability> = _voiceCapability.asStateFlow()

    // Listener held as a field so it stays strongly referenced — SharedPreferences
    // registers listeners via WeakReference, so a local would be GC'd immediately.
    private val cloudKeyListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == appPreferences.voiceAssistantCloudApiKey.backendName) {
                recomputeVoiceCapability()
            }
        }

    /** Direct reference to the current engine, null while nothing is ready. */
    val instance: VoiceAiEngine? get() = _engine.value

    /**
     * Legacy accessor returning the raw LiteRT [LlmModelInstance] if the
     * active backend is LiteRT. Lets existing XR voice callers that still
     * bind to [LlmModelInstance] keep working while they migrate.
     */
    @Deprecated("Use engine: StateFlow<VoiceAiEngine?> instead.", ReplaceWith("engine.value"))
    val literRtInstance: LlmModelInstance?
        get() = (_engine.value as? LiteRtEngine)?.rawInstance

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var aiCoreDownloadJob: Job? = null

    init {
        appPreferences.sharedPreferences.registerOnSharedPreferenceChangeListener(cloudKeyListener)
        scope.launch { probeAiCoreAndActivate() }
        scope.launch {
            downloadManager.downloadState.collect { downloadState ->
                // Only fall back to LiteRT init when AICore is not the active
                // engine. If AICore is ready we leave the LiteRT model file on
                // disk but skip engine init — saves memory + startup time.
                if (_engine.value?.backendKind == VoiceAiBackend.AICORE) return@collect
                if (downloadState is DownloadState.Ready &&
                    modelState.value.let { it is ModelState.Idle || it is ModelState.Error }) {
                    initializeLiteRtEngine(downloadState.file.absolutePath)
                }
            }
        }
    }

    private fun recomputeVoiceCapability() {
        val backend = _engine.value?.backendName
        val hasCloudKey = !appPreferences.getValue(appPreferences.voiceAssistantCloudApiKey).isNullOrBlank()
        val next = when {
            hasCloudKey -> VoiceCapability.CAPABLE
            backend == null -> VoiceCapability.UNKNOWN
            backend.equals("CPU", ignoreCase = true) -> VoiceCapability.CPU_ONLY
            else -> VoiceCapability.CAPABLE
        }
        if (_voiceCapability.value != next) {
            Timber.i(
                "LlmModelManager: voice capability %s -> %s (backend=%s cloudKey=%b)",
                _voiceCapability.value,
                next,
                backend,
                hasCloudKey,
            )
            _voiceCapability.value = next
        }
    }

    /**
     * Ensure *some* on-device engine is ready. Called by voice entry points
     * (e.g. `modelManager.ensureInitialized(); modelManager.instance?.runInference(...)`)
     * just before running a prompt. Returns promptly when a backend is
     * already active.
     */
    suspend fun ensureInitialized() {
        if (_engine.value != null) return
        // Prefer AICore — re-probe in case the app was backgrounded during
        // a previous probe or the device state changed.
        val aiCore = AICoreModelHelper.checkStatus(context)
        _aiCoreStatus.value = aiCore
        if (aiCore is AICoreStatus.Ready) {
            activateAiCoreEngine()
            return
        }
        val downloadState = downloadManager.downloadState.value
        if (downloadState is DownloadState.Ready &&
            modelState.value.let { it is ModelState.Ready || it is ModelState.Initializing }) {
            // LiteRT already initializing — wait on the existing collector.
            return
        }
        if (downloadState is DownloadState.Ready) {
            initializeLiteRtEngine(downloadState.file.absolutePath)
        }
    }

    /**
     * Kick an AICore feature-model download. Safe to call regardless of
     * current state: if AICore is already Ready this is a no-op.
     */
    fun downloadAiCoreFeature() {
        aiCoreDownloadJob?.cancel()
        aiCoreDownloadJob = scope.launch {
            var lastTotal = 0L
            AICoreModelHelper.downloadFeature(context).collect { status ->
                val normalised = when (status) {
                    is AICoreStatus.Downloading -> {
                        if (status.totalBytes > 0) lastTotal = status.totalBytes
                        val effectiveTotal = lastTotal.takeIf { it > 0 } ?: 0L
                        val progress = if (effectiveTotal > 0) {
                            (status.bytesDownloaded.toFloat() / effectiveTotal).coerceIn(0f, 1f)
                        } else 0f
                        AICoreStatus.Downloading(progress, status.bytesDownloaded, effectiveTotal)
                    }
                    else -> status
                }
                _aiCoreStatus.value = normalised
                if (normalised is AICoreStatus.Warming) {
                    val warmed = AICoreModelHelper.warmup(context)
                    _aiCoreStatus.value = warmed
                    if (warmed is AICoreStatus.Ready) activateAiCoreEngine()
                } else if (normalised is AICoreStatus.Error) {
                    Timber.w("AICore: download reached terminal error — %s", normalised.message)
                }
            }
        }
    }

    private suspend fun probeAiCoreAndActivate() {
        Timber.i("AICore: probe starting")
        val status = AICoreModelHelper.checkStatus(context)
        _aiCoreStatus.value = status
        Timber.i("AICore: probe returned status=%s", status)
        if (status is AICoreStatus.Ready) {
            activateAiCoreEngine()
        }
    }

    /**
     * Manual re-probe hook for the Settings UI. Lets a user retry after
     * Google Play Services finishes installing the AICore feature, or after
     * the device boots out of an error state, without restarting the app.
     */
    suspend fun reprobeAiCore() {
        probeAiCoreAndActivate()
    }

    private fun activateAiCoreEngine() {
        val previous = _engine.value
        if (previous?.backendKind == VoiceAiBackend.AICORE) return
        _engine.value = AICoreEngine(context)
        _aiCoreStatus.value = AICoreStatus.Ready
        // Push the LiteRT "backend name" preference so the UI reflects the
        // currently-active engine without having to reach for both flows.
        appPreferences.setValue(appPreferences.voiceAssistantGemmaBackend, "Gemini Nano (AICore)")
        // If a prior LiteRT engine was active, tear it down — we'd rather not
        // keep two on-device LLMs warm in memory.
        if (previous is LiteRtEngine) previous.close()
        recomputeVoiceCapability()
        Timber.i("AICore: engine activated")
    }

    private suspend fun initializeLiteRtEngine(modelPath: String) {
        downloadManager.updateModelState(ModelState.Initializing)
        Timber.i("LlmModelManager: initializing LiteRT engine from %s", modelPath)

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
                val previous = _engine.value
                if (previous is LiteRtEngine) previous.close()
                val litertEngine = LiteRtEngine(newInstance)
                _engine.value = litertEngine
                downloadManager.updateModelState(ModelState.Ready(newInstance.backendName))

                val previousBackend = appPreferences.getValue(appPreferences.voiceAssistantGemmaBackend)
                appPreferences.setValue(appPreferences.voiceAssistantGemmaBackend, newInstance.backendName)
                if (previousBackend == "Requires Model" || previousBackend == "Unknown" || previousBackend == "CPU") {
                    if (newInstance.backendName == "CPU") {
                        appPreferences.setValue(appPreferences.voiceAssistantGemmaEnabled, false)
                        Timber.i("LlmModelManager: only CPU available, disabling Gemma by default")
                    } else {
                        appPreferences.setValue(appPreferences.voiceAssistantGemmaEnabled, true)
                        Timber.i("LlmModelManager: GPU/NPU available, enabling Gemma")
                    }
                }
                recomputeVoiceCapability()
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
