package dev.jdtech.jellyfin.core.llm

import android.content.Context
import android.graphics.Bitmap

/**
 * Backend-agnostic handle onto the currently-active on-device voice AI engine.
 * [LlmModelManager] produces one of these depending on which runtime is
 * available and preferred: [AICoreEngine] wraps Google's system Gemini Nano
 * (fast path on Pixel), [LiteRtEngine] wraps the bundled LiteRT-LM path
 * (universal fallback). Voice callers depend only on this interface so the
 * routing decision lives in one place.
 */
sealed interface VoiceAiEngine {
    /** Short, user-facing backend label — e.g. "Gemini Nano (AICore)", "GPU". */
    val backendName: String

    /** Identifier used in preferences and telemetry. */
    val backendKind: VoiceAiBackend

    suspend fun runInference(
        prompt: String,
        images: List<Bitmap> = emptyList(),
        profile: LlmInferenceProfile = LlmInferenceProfile.CHAT,
        onToken: ((String) -> Unit)? = null,
    ): String

    fun close()
}

enum class VoiceAiBackend { AICORE, LITERT }

/**
 * AICore / ML Kit GenAI wrapper. Stateless — the [AICoreModelHelper] singleton
 * already caches the [com.google.mlkit.genai.prompt.GenerativeModel], so this
 * engine is essentially a typed adapter.
 */
class AICoreEngine(private val context: Context) : VoiceAiEngine {
    override val backendName: String = "Gemini Nano (AICore)"
    override val backendKind: VoiceAiBackend = VoiceAiBackend.AICORE

    override suspend fun runInference(
        prompt: String,
        images: List<Bitmap>,
        profile: LlmInferenceProfile,
        onToken: ((String) -> Unit)?,
    ): String = AICoreModelHelper.runInference(context, prompt, images, profile, onToken)

    override fun close() {
        // Do not close the shared AICore GenerativeModel here — it's process-scoped
        // and LlmModelManager owns its lifecycle. A close() call from the router's
        // swap logic should transition to another engine without tearing down
        // Gemini Nano for the next consumer.
    }
}

/**
 * LiteRT-LM wrapper. Owns a concrete [LlmModelInstance] that was initialized
 * via [LlmChatModelHelper.initializeEngine].
 */
class LiteRtEngine(private val instance: LlmModelInstance) : VoiceAiEngine {
    override val backendName: String = instance.backendName
    override val backendKind: VoiceAiBackend = VoiceAiBackend.LITERT

    val rawInstance: LlmModelInstance get() = instance

    override suspend fun runInference(
        prompt: String,
        images: List<Bitmap>,
        profile: LlmInferenceProfile,
        onToken: ((String) -> Unit)?,
    ): String = LlmChatModelHelper.runInference(instance, prompt, images, profile, onToken)

    override fun close() {
        LlmChatModelHelper.close(instance)
    }
}
