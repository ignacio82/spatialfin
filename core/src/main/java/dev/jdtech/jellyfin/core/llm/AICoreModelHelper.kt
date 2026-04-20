package dev.jdtech.jellyfin.core.llm

import android.content.Context
import android.graphics.Bitmap
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.ImagePart
import com.google.mlkit.genai.prompt.ModelPreference
import com.google.mlkit.genai.prompt.ModelReleaseStage
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.google.mlkit.genai.prompt.generationConfig
import com.google.mlkit.genai.prompt.modelConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/**
 * Wrapper around ML Kit GenAI Prompt (`com.google.mlkit:genai-prompt`) — the
 * entry point into Google's on-device Gemini Nano via AICore. This is the fast
 * path that [Google AI Edge Gallery](https://github.com/google-ai-edge/gallery)
 * uses on Pixel devices, where AICore runs Gemini Nano on the Tensor hardware
 * at the OS level.
 *
 * Complements [LlmChatModelHelper], which owns the LiteRT-LM fallback path for
 * devices without AICore. The two helpers expose a deliberately similar
 * surface — `initializeEngine` → `runInference` → `close` — so a single
 * router ([LlmModelManager]) can swap between them based on
 * [AICoreStatus].
 *
 * One live [GenerativeModel] is cached per process. All inference goes through
 * [inferenceMutex] so overlapping requests serialise — mirrors LiteRT's
 * single-conversation-at-a-time constraint and avoids hammering the shared
 * AICore daemon with parallel sessions.
 */
sealed interface AICoreStatus {
    /** Initial state before any probe has run. */
    data object Unknown : AICoreStatus

    /** Device does not support AICore (unlock state, SKU, SDK, etc.). */
    data class Unavailable(val reason: String) : AICoreStatus

    /** AICore is present but the feature model needs to be downloaded first. */
    data object Downloadable : AICoreStatus

    /** Download in flight. `progress` is `[0f, 1f]` when the total size is known. */
    data class Downloading(val progress: Float, val bytesDownloaded: Long, val totalBytes: Long) : AICoreStatus

    /** Download succeeded, warm-up is in flight. */
    data object Warming : AICoreStatus

    /** Gemini Nano is ready for inference. */
    data object Ready : AICoreStatus

    /** Terminal failure from the AICore pipeline. */
    data class Error(val message: String) : AICoreStatus
}

object AICoreModelHelper {
    private val inferenceMutex = Mutex()

    @Volatile
    private var cachedModel: GenerativeModel? = null

    /**
     * Probe the device for AICore availability without side effects beyond
     * caching the [GenerativeModel] for later `runInference` calls.
     */
    suspend fun checkStatus(context: Context): AICoreStatus {
        val model = try {
            obtainModel(context)
        } catch (e: Exception) {
            Timber.w(e, "AICore: Generation.getClient failed — treating as Unavailable")
            return AICoreStatus.Unavailable(e.message ?: "getClient returned null")
        }
        model ?: return AICoreStatus.Unavailable("Generation.getClient returned null")

        return try {
            when (val s = model.checkStatus()) {
                FeatureStatus.AVAILABLE -> AICoreStatus.Ready
                FeatureStatus.DOWNLOADABLE -> AICoreStatus.Downloadable
                FeatureStatus.DOWNLOADING -> AICoreStatus.Downloading(
                    progress = 0f,
                    bytesDownloaded = 0L,
                    totalBytes = 0L,
                )
                FeatureStatus.UNAVAILABLE -> AICoreStatus.Unavailable("Device reported UNAVAILABLE")
                else -> AICoreStatus.Unavailable("Unknown FeatureStatus: $s")
            }
        } catch (e: Exception) {
            Timber.w(e, "AICore: checkStatus threw")
            AICoreStatus.Unavailable(e.message ?: "checkStatus threw")
        }
    }

    /**
     * Kicks the feature-model download and returns a [Flow] of intermediate
     * [AICoreStatus] updates driven by ML Kit's [DownloadStatus]. Completes
     * with [AICoreStatus.Warming] → [AICoreStatus.Ready] (the caller handles
     * warm-up separately via [warmup]). Errors surface as
     * [AICoreStatus.Error] before the flow completes.
     */
    fun downloadFeature(context: Context): Flow<AICoreStatus> {
        val model = try {
            obtainModel(context)
        } catch (e: Exception) {
            Timber.w(e, "AICore: downloadFeature getClient failed")
            return kotlinx.coroutines.flow.flowOf(
                AICoreStatus.Error(e.message ?: "getClient returned null"),
            )
        }
        if (model == null) {
            return kotlinx.coroutines.flow.flowOf(
                AICoreStatus.Error("Generation.getClient returned null"),
            )
        }
        return model.download()
            .map { ds: DownloadStatus ->
                when (ds) {
                    is DownloadStatus.DownloadStarted -> AICoreStatus.Downloading(
                        progress = 0f,
                        bytesDownloaded = 0L,
                        totalBytes = ds.bytesToDownload,
                    )
                    is DownloadStatus.DownloadProgress -> AICoreStatus.Downloading(
                        progress = 0f, // total not re-emitted per progress tick; UI layer stitches the last known total
                        bytesDownloaded = ds.totalBytesDownloaded,
                        totalBytes = 0L,
                    )
                    is DownloadStatus.DownloadCompleted -> AICoreStatus.Warming
                    is DownloadStatus.DownloadFailed -> AICoreStatus.Error(
                        ds.e.message ?: "download failed",
                    )
                }
            }
            .catch { throwable ->
                Timber.w(throwable, "AICore: download flow threw")
                emit(AICoreStatus.Error(throwable.message ?: "download flow failed"))
            }
    }

    suspend fun warmup(context: Context): AICoreStatus {
        val model = try {
            obtainModel(context) ?: return AICoreStatus.Unavailable("null model during warmup")
        } catch (e: Exception) {
            return AICoreStatus.Error(e.message ?: "warmup getClient failed")
        }
        return try {
            model.warmup()
            AICoreStatus.Ready
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "AICore: warmup failed")
            AICoreStatus.Error(e.message ?: "warmup failed")
        }
    }

    /**
     * Run a single-shot prompt. Streaming is supported via [onToken]; each
     * emission carries the accumulated response text so a UI consumer can
     * render it incrementally without re-joining partial chunks itself.
     */
    suspend fun runInference(
        context: Context,
        prompt: String,
        images: List<Bitmap> = emptyList(),
        profile: LlmInferenceProfile = LlmInferenceProfile.CHAT,
        onToken: ((String) -> Unit)? = null,
    ): String = inferenceMutex.withLock {
        val model = obtainModel(context) ?: error("AICore model not available")
        // ML Kit GenAI currently supports at most one image per request.
        val image = images.firstOrNull()
        val request = if (image != null) {
            generateContentRequest(ImagePart(image), TextPart(prompt)) {
                temperature = profile.temperature.coerceIn(0f, 1f)
                topK = profile.topK
                candidateCount = 1
            }
        } else {
            generateContentRequest(TextPart(prompt)) {
                temperature = profile.temperature.coerceIn(0f, 1f)
                topK = profile.topK
                candidateCount = 1
            }
        }
        val sb = StringBuilder()
        try {
            model.generateContentStream(request)
                .onEach { response ->
                    val candidate = response.candidates.firstOrNull() ?: return@onEach
                    val text = candidate.text
                    if (text.isNotEmpty()) {
                        sb.append(text)
                        onToken?.invoke(sb.toString())
                    }
                }
                .collect { /* consume */ }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "AICore: generateContentStream failed")
            throw e
        }
        sb.toString()
    }

    fun close() {
        cachedModel?.runCatching { close() }
        cachedModel = null
    }

    private fun obtainModel(context: Context): GenerativeModel? {
        cachedModel?.let { return it }
        // Use the parameterless overload so AICore picks whichever feature it
        // has provisioned on this device. The explicit
        // Generation.getClient(generationConfig { modelConfig { releaseStage=STABLE;
        // preference=FAST } }) overload asks for a specific feature id that
        // isn't always registered even on AICore-capable Pixels — on a Pixel 10
        // Pro it throws "ErrorCode 606 FEATURE_NOT_FOUND: Feature 645 is not
        // available." The no-arg client works (see BeamGeminiNanoService) and
        // uses AICore's default model selection, which is what Google AI Edge
        // Gallery does as well.
        val client = Generation.getClient()
        cachedModel = client
        return client
    }

    // Imported inline to avoid pulling the whole kotlinx.coroutines.flow
    // transformations namespace into top-level file scope.
    private fun <T, R> Flow<T>.map(transform: suspend (T) -> R): Flow<R> =
        kotlinx.coroutines.flow.flow { collect { value -> emit(transform(value)) } }
}
