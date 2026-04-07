package dev.jdtech.jellyfin.core.llm

import android.content.Context
import android.graphics.Bitmap
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

data class LlmModelInstance(val engine: Engine, var conversation: Conversation, val backendName: String)

object LlmChatModelHelper {
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
                Timber.w(e, "LiteRT: %s backend failed after %d ms", name, System.currentTimeMillis() - t0)
                null
            }
        }

        // NPU requires nativeLibraryDir so LiteRT can locate the vendor delegate .so.
        // Without it the NPU backend throws "TF_LITE_AUX not found in the model".
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val (engine, backendName) = tryBackend("NPU", Backend.NPU(nativeLibraryDir = nativeLibDir))?.let { it to "NPU" }
            ?: tryBackend("GPU", Backend.GPU())?.let { it to "GPU" }
            ?: tryBackend("CPU", Backend.CPU())?.let { it to "CPU" }
            ?: run {
                Timber.e("LiteRT: all backends failed after %d ms", System.currentTimeMillis() - totalStart)
                return null
            }

        val conversation = engine.createConversation(
            ConversationConfig(
                samplerConfig = SamplerConfig(
                    topK = 40,
                    topP = 0.95,
                    temperature = 0.8,
                ),
            ),
        )
        Timber.i(
            "LiteRT: engine ready — backend=%s total=%d ms",
            backendName,
            System.currentTimeMillis() - totalStart,
        )
        return LlmModelInstance(engine, conversation, backendName)
    }

    @OptIn(ExperimentalApi::class)
    fun runInference(
        instance: LlmModelInstance,
        prompt: String,
        images: List<Bitmap> = emptyList(),
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

        instance.conversation.sendMessageAsync(
            Contents.of(contents),
            object : MessageCallback {
                override fun onMessage(message: Message) {
                    onResult(message.toString(), false)
                }

                override fun onDone() {
                    onResult("", true)
                }

                override fun onError(throwable: Throwable) {
                    Timber.e(throwable, "Inference error")
                    onResult("Error: ${throwable.message}", true)
                }
            }
        )
    }

    fun close(instance: LlmModelInstance) {
        instance.conversation.close()
        instance.engine.close()
    }
}
