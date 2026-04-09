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
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File

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

    // Tracks the currently open conversation. LiteRT only allows one session at a time;
    // if a session leaks (e.g. coroutine cancellation before onDone fires) every subsequent
    // createConversation() call fails with FAILED_PRECONDITION. We close the stale session
    // before opening a new one to recover automatically.
    @Volatile
    private var activeConversation: Conversation? = null

    private data class BackendCacheState(
        val lastSuccessfulBackend: String? = null,
        val npuUnsupported: Boolean = false,
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
        val cacheState = readBackendCache(context, modelPath)
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

    @OptIn(ExperimentalApi::class)
    fun runInference(
        instance: LlmModelInstance,
        prompt: String,
        images: List<Bitmap> = emptyList(),
        profile: LlmInferenceProfile = LlmInferenceProfile.CHAT,
        onResult: (String, Boolean) -> Unit
    ) {
        val contents = mutableListOf<Content>()
        
        for (bitmap in images) {
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            contents.add(Content.ImageBytes(stream.toByteArray()))
        }
        
        if (prompt.isNotEmpty()) {
            contents.add(Content.Text(prompt))
        }

        Timber.d(
            "LiteRT: runInference profile=%s promptChars=%d imageCount=%d backend=%s",
            profile.name,
            prompt.length,
            images.size,
            instance.backendName,
        )

        // Close any session leaked by a previous call (e.g. coroutine cancelled before onDone).
        val conversation: Conversation
        synchronized(this) {
            val leaked = activeConversation
            if (leaked != null) {
                Timber.w("LiteRT: closing leaked session before new inference (profile=%s)", profile.name)
                try { leaked.close() } catch (ignored: Exception) {}
                activeConversation = null
            }
            conversation = createConversation(instance.engine, profile)
            activeConversation = conversation
        }

        try {
            conversation.sendMessageAsync(
                Contents.of(contents),
                object : MessageCallback {
                    override fun onMessage(message: Message) {
                        onResult(message.toString(), false)
                    }

                    override fun onDone() {
                        synchronized(this@LlmChatModelHelper) {
                            if (activeConversation === conversation) activeConversation = null
                        }
                        conversation.close()
                        onResult("", true)
                    }

                    override fun onError(throwable: Throwable) {
                        synchronized(this@LlmChatModelHelper) {
                            if (activeConversation === conversation) activeConversation = null
                        }
                        conversation.close()
                        Timber.e(throwable, "Inference error")
                        onResult("Error: ${throwable.message}", true)
                    }
                }
            )
        } catch (e: Exception) {
            synchronized(this@LlmChatModelHelper) {
                if (activeConversation === conversation) activeConversation = null
            }
            conversation.close()
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
                if (!cacheState.npuUnsupported) {
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
