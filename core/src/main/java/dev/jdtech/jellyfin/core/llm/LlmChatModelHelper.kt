package dev.jdtech.jellyfin.core.llm

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class LlmModelInstance(val engine: Engine, val backendName: String)

data class LlmInferenceProfile(
    val name: String,
    val topK: Int,
    val topP: Float,
    val temperature: Float,
) {
    companion object {
        val COMMAND =
            LlmInferenceProfile(
                name = "command",
                topK = 1,
                topP = 0.1f,
                temperature = 0.0f,
            )

        val CHAT =
            LlmInferenceProfile(
                name = "chat",
                topK = 40,
                topP = 0.95f,
                temperature = 0.8f,
            )
    }
}

object LlmChatModelHelper {
    private const val BACKEND_CACHE_PREFS = "llm_backend_cache"
    private const val KEY_LAST_SUCCESSFUL = "last_successful"
    private const val KEY_NPU_UNSUPPORTED = "npu_unsupported"

    // LiteRT only allows one active Conversation (GPU session) at a time. Closing a
    // Conversation does NOT interrupt an in-flight GPU operation — the native session
    // stays alive until sendMessageAsync completes. This Mutex serialises all inference:
    // it is acquired before sendMessageAsync and released only in onDone/onError, so a
    // cancelled coroutine still holds the lock until the GPU finishes.
    private val inferenceMutex = Mutex()

    private data class BackendCacheState(
        val lastSuccessfulBackend: String? = null,
        val npuUnsupported: Boolean = false,
        val skipNpuForModel: Boolean = false,
    )

    @OptIn(ExperimentalApi::class)
    private fun createConversation(
        engine: Engine,
        profile: LlmInferenceProfile = LlmInferenceProfile.CHAT,
    ): Conversation =
        engine.createConversation(
            ConversationConfig(
                samplerConfig = SamplerConfig(
                    topK = profile.topK,
                    topP = profile.topP.toDouble(),
                    temperature = profile.temperature.toDouble(),
                ),
            ),
        )

    @OptIn(ExperimentalApi::class)
    fun initializeEngine(
        context: Context,
        modelPath: String,
        maxTokens: Int = 1024,
    ): LlmModelInstance? {
        val totalStart = System.currentTimeMillis()
        Timber.i("LiteRT: starting engine init — model=%s maxTokens=%d", modelPath, maxTokens)

        // Gallery passes cacheDir=null for app-internal model paths. A stale GPU kernel
        // cache at cacheDir can cause the GPU backend to fail with a cryptic internal error.
        val cacheDir = if (modelPath.startsWith("/data/local/tmp")) context.cacheDir.absolutePath else null

        // The gemma-4-E2B model has vision + audio subgraphs. Gallery always passes
        // visionBackend=GPU and audioBackend=CPU when initializing with GPU, matching
        // the model's expected backend routing. Without these, LiteRT may try to compile
        // all subgraphs onto the main backend and fail.
        fun tryBackend(name: String, backend: Backend): Engine? {
            val t0 = System.currentTimeMillis()
            Timber.i("LiteRT: trying %s backend…", name)
            val isGpu = backend is Backend.GPU
            val isNpu = backend is Backend.NPU
            return try {
                val config = EngineConfig(
                    modelPath = modelPath,
                    backend = backend,
                    visionBackend = if (isGpu) Backend.GPU() else null,
                    audioBackend = if (isGpu || isNpu) Backend.CPU() else null,
                    maxNumTokens = maxTokens,
                    cacheDir = cacheDir,
                )
                val eng = Engine(config)
                eng.initialize()
                Timber.i("LiteRT: %s backend ready in %d ms", name, System.currentTimeMillis() - t0)
                eng
            } catch (e: Exception) {
                if (name == "NPU" && isUnsupportedNpuFailure(e)) {
                    val updatedCache = readBackendCache(context, modelPath).copy(npuUnsupported = true)
                    writeBackendCache(context, modelPath, updatedCache)
                }
                Timber.w(e, "LiteRT: %s backend failed after %d ms", name, System.currentTimeMillis() - t0)
                null
            }
        }

        // NPU requires nativeLibraryDir so LiteRT can locate the vendor delegate .so.
        // Without it the NPU backend throws "TF_LITE_AUX not found in the model".
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        // Models we know don't ship NPU subgraphs — skip the attempt entirely.
        // Today that's every Gemma 4 variant on the litert-community bucket; the
        // LiteRT-LM NPU path is Gemma3-1B-IT only + Qualcomm/MediaTek SoCs.
        val modelName = File(modelPath).name
        val skipNpu = modelName.contains("gemma-4", ignoreCase = true) ||
            modelName.contains("gemma4", ignoreCase = true)
        val cacheState = readBackendCache(context, modelPath).copy(skipNpuForModel = skipNpu)
        val backends =
            buildBackendOrder(cacheState, nativeLibDir).map { (name, backend) ->
                name to { tryBackend(name, backend) }
            }

        var resolved: Pair<Engine, String>? = null
        for ((name, loader) in backends) {
            val engine = loader()
            if (engine != null) {
                resolved = engine to name
                writeBackendCache(
                    context = context,
                    modelPath = modelPath,
                    state = cacheState.copy(lastSuccessfulBackend = name),
                )
                break
            }
        }

        val (engine, backendName) = resolved ?: run {
            Timber.e("LiteRT: all backends failed after %d ms", System.currentTimeMillis() - totalStart)
            return null
        }

        Timber.i(
            "LiteRT: engine ready — backend=%s total=%d ms",
            backendName,
            System.currentTimeMillis() - totalStart,
        )
        return LlmModelInstance(engine, backendName)
    }

    /**
     * Runs one inference call and returns the full response text.
     *
     * Acquires [inferenceMutex] before calling sendMessageAsync and releases it only
     * inside [onDone]/[onError] (i.e. when the GPU has finished). A cancelled coroutine
     * keeps the lock held until the GPU completes, preventing a FAILED_PRECONDITION on
     * the next call.
     *
     * @param onToken Called on the LiteRT thread with the accumulated response after
     *   each token arrives. Useful for streaming UI updates.
     */
    @OptIn(ExperimentalApi::class)
    suspend fun runInference(
        instance: LlmModelInstance,
        prompt: String,
        images: List<Bitmap> = emptyList(),
        profile: LlmInferenceProfile = LlmInferenceProfile.CHAT,
        onToken: ((String) -> Unit)? = null,
    ): String {
        // Suspend here until any prior GPU session finishes.
        inferenceMutex.lock()

        val contents = mutableListOf<Content>()
        for (bitmap in images) {
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            contents.add(Content.ImageBytes(stream.toByteArray()))
        }
        if (prompt.isNotEmpty()) contents.add(Content.Text(prompt))

        Timber.d(
            "LiteRT: runInference profile=%s promptChars=%d imageCount=%d backend=%s",
            profile.name,
            prompt.length,
            images.size,
            instance.backendName,
        )

        val conversation = createConversation(instance.engine, profile)

        return try {
            suspendCancellableCoroutine { continuation ->
                // Cancellation must NOT release the mutex — the GPU is still running.
                // inferenceMutex.unlock() happens only in onDone/onError below.
                val sb = StringBuilder()
                try {
                    conversation.sendMessageAsync(
                        Contents.of(contents),
                        object : MessageCallback {
                            override fun onMessage(message: Message) {
                                sb.append(message.toString())
                                onToken?.invoke(sb.toString())
                            }

                            override fun onDone() {
                                conversation.close()
                                inferenceMutex.unlock()
                                continuation.resume(sb.toString())
                            }

                            override fun onError(throwable: Throwable) {
                                conversation.close()
                                inferenceMutex.unlock()
                                Timber.e(throwable, "LiteRT: inference error")
                                continuation.resumeWithException(throwable)
                            }
                        }
                    )
                } catch (e: Exception) {
                    // sendMessageAsync threw synchronously — no callback will fire.
                    conversation.close()
                    inferenceMutex.unlock()
                    continuation.resumeWithException(e)
                }
            }
        } catch (e: CancellationException) {
            // The coroutine was cancelled. The mutex is still locked and will be
            // released when onDone/onError fires. Re-throw so the caller sees cancellation.
            throw e
        }
    }

    fun close(instance: LlmModelInstance) {
        instance.engine.close()
    }

    private fun buildBackendOrder(
        cacheState: BackendCacheState,
        nativeLibDir: String,
    ): List<Pair<String, Backend>> {
        val defaults =
            buildList {
                // Only attempt NPU for models that carry an NPU subgraph. The
                // `gemma-4-E2B-it.litertlm` file we ship from litert-community
                // was built with only GPU/CPU delegate graphs (LiteRT-LM
                // issue #1915), so attempting NPU always fails with
                // "TF_LITE_AUX not found in the model" and we pay the cost
                // of a failed init. Skip entirely. On Pixel devices the
                // fast path is AICore (Gemini Nano) through
                // AICoreModelHelper, not LiteRT NPU.
                if (!cacheState.npuUnsupported && !cacheState.skipNpuForModel) {
                    add("NPU" to Backend.NPU(nativeLibraryDir = nativeLibDir))
                }
                add("GPU" to Backend.GPU())
                add("CPU" to Backend.CPU())
            }

        val preferred = cacheState.lastSuccessfulBackend ?: return defaults
        return defaults.sortedBy { if (it.first == preferred) 0 else 1 }
    }

    private fun readBackendCache(context: Context, modelPath: String): BackendCacheState {
        val prefs = context.getSharedPreferences(BACKEND_CACHE_PREFS, Context.MODE_PRIVATE)
        val key = backendCacheKey(modelPath)
        return BackendCacheState(
            lastSuccessfulBackend = prefs.getString("${key}_$KEY_LAST_SUCCESSFUL", null),
            npuUnsupported = prefs.getBoolean("${key}_$KEY_NPU_UNSUPPORTED", false),
        )
    }

    private fun writeBackendCache(
        context: Context,
        modelPath: String,
        state: BackendCacheState,
    ) {
        val prefs = context.getSharedPreferences(BACKEND_CACHE_PREFS, Context.MODE_PRIVATE)
        val key = backendCacheKey(modelPath)
        prefs.edit()
            .putString("${key}_$KEY_LAST_SUCCESSFUL", state.lastSuccessfulBackend)
            .putBoolean("${key}_$KEY_NPU_UNSUPPORTED", state.npuUnsupported)
            .apply()
    }

    private fun isUnsupportedNpuFailure(error: Throwable): Boolean {
        val message = error.message.orEmpty()
        return message.contains("TF_LITE_AUX", ignoreCase = true) ||
            message.contains("not found in the model", ignoreCase = true)
    }

    private fun backendCacheKey(modelPath: String): String {
        val modelName = File(modelPath).name
        val deviceKey =
            listOf(Build.MANUFACTURER, Build.MODEL, Build.DEVICE, Build.VERSION.SDK_INT.toString())
                .joinToString("_")
                .lowercase()
                .replace(Regex("[^a-z0-9_]+"), "_")
        return "${modelName}_$deviceKey"
    }
}
