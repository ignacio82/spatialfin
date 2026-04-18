package dev.jdtech.jellyfin.player.xr

import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import java.nio.ByteBuffer
import timber.log.Timber

/**
 * Result of a single subtitle render call.
 *
 * @param hasContent true if there are visible subtitles at this timestamp.
 *   When false, the caller MUST remove the subtitle SpatialPanel from the
 *   Subspace composition to avoid blocking raycasts (XR-critical).
 * @param bitmap the composited ARGB_8888 subtitle overlay, or null if unchanged / empty.
 * @param dirtyX/dirtyY/dirtyW/dirtyH bounding box of changed pixels (for partial update optimization).
 */
data class RenderResult(
    val hasContent: Boolean,
    val bitmap: Bitmap? = null,
    val dirtyX: Int = 0,
    val dirtyY: Int = 0,
    val dirtyW: Int = 0,
    val dirtyH: Int = 0,
)

/**
 * Kotlin bridge to the native libass subtitle renderer.
 * All native calls are dispatched to a single background thread for thread safety.
 *
 * The render dimensions should be computed from the SpatialPanel's dp size
 * multiplied by the display density — do NOT hardcode 1080p. Example:
 *   val renderWidth = (panelWidthDp * density.density).toInt().coerceIn(1280, 7680)
 *   val renderHeight = (panelHeightDp * density.density).toInt().coerceIn(720, 4320)
 */
class LibassRenderer(
    private var width: Int,
    private var height: Int,
) {
    companion object {
        private var libraryLoaded = false

        fun isAvailable(): Boolean {
            if (libraryLoaded) return true
            return try {
                System.loadLibrary("ass_jni")
                libraryLoaded = true
                true
            } catch (e: UnsatisfiedLinkError) {
                false
            }
        }
    }

    private var nativeCtx: Long = 0L
    private val thread = HandlerThread("libass-renderer").also { it.start() }
    private val handler = Handler(thread.looper)
    @Volatile private var destroyed = false

    // Reusable bitmap for the current frame. Never recycle eagerly: Compose may still
    // be drawing a previously published bitmap on the UI thread when resize/destroy
    // happens, and recycling it here will crash with "trying to use a recycled bitmap".
    // @Volatile so renderFrame (caller-thread blocked on latch) observes any value
    // written by the render-handler thread without torn references.
    @Volatile private var cachedBitmap: Bitmap? = null

    // Track whether the last render had content — used by callers to decide
    // whether to compose the SpatialPanel (raycast passthrough when false)
    @Volatile var hasActiveSubtitles: Boolean = false
        private set

    private fun postWork(name: String, block: () -> Unit): Boolean {
        if (destroyed) return false
        val posted =
            handler.post {
                if (destroyed) return@post
                block()
            }
        if (!posted) {
            Timber.w("%s: skipped because renderer thread is unavailable", name)
        }
        return posted
    }

    fun init() {
        if (!isAvailable() || destroyed) return
        Timber.i("init: %dx%d", width, height)
        postWork("init") {
            nativeCtx = nativeInit(width, height)
            Timber.i("init: nativeCtx=%d", nativeCtx)
            if (nativeCtx != 0L) registerSystemFonts()
        }
    }

    /**
     * Register every TTF/OTF/TTC under /system/fonts/ with libass. Without this,
     * `Fontname:` directives in ASS styles resolve only to the single fallback font
     * we passed to ass_set_fonts — every anime renders in Noto CJK regardless of
     * the author's intent. MKV-embedded fonts are registered later in
     * LibassTextRenderer.ensureFontsLoaded(); those take priority automatically
     * because libass matches by family name.
     */
    private fun registerSystemFonts() {
        val dir = java.io.File("/system/fonts")
        val files = dir.listFiles { f ->
            val lower = f.name.lowercase()
            lower.endsWith(".ttf") || lower.endsWith(".otf") || lower.endsWith(".ttc")
        } ?: return
        var ok = 0
        var bytes = 0L
        for (file in files) {
            runCatching {
                val data = file.readBytes()
                nativeAddFont(nativeCtx, file.nameWithoutExtension, data)
                ok++
                bytes += data.size
            }.onFailure {
                Timber.w(it, "registerSystemFonts: failed %s", file.name)
            }
        }
        Timber.i("registerSystemFonts: registered %d fonts (%d KB)", ok, bytes / 1024)
    }

    /**
     * Register an embedded font (from MKV attachments) BEFORE loading the track.
     */
    fun addFont(name: String, data: ByteArray) {
        postWork("addFont") {
            if (nativeCtx != 0L) nativeAddFont(nativeCtx, name, data)
        }
    }

    /**
     * Load the ASS header (codec private / initialization data).
     * Call this after addFont() calls are complete.
     */
    fun setTrackData(codecPrivate: ByteArray) {
        postWork("setTrackData") {
            if (nativeCtx != 0L) nativeSetTrackData(nativeCtx, codecPrivate)
        }
    }

    /**
     * Feed a subtitle event (dialogue chunk) with its timing.
     */
    fun processChunk(data: ByteArray, startTimeMs: Long, durationMs: Long) {
        postWork("processChunk") {
            if (nativeCtx != 0L) nativeProcessChunk(nativeCtx, data, startTimeMs, durationMs)
        }
    }

    /**
     * Clear libass internal caches. MUST be called on seek to prevent
     * ghost subtitles or missed events after large time jumps.
     */
    fun clearCache() {
        postWork("clearCache") {
            if (nativeCtx != 0L) nativeClearCache(nativeCtx)
        }
    }

    /**
     * Update render resolution and storage size.
     * @param newWidth physical pixels width of the display panel.
     * @param newHeight physical pixels height of the display panel.
     * @param storageW pixel width of the original video (for correct positioning).
     * @param storageH pixel height of the original video.
     */
    fun resize(newWidth: Int, newHeight: Int, storageW: Int = 0, storageH: Int = 0) {
        if (destroyed) return
        Timber.d("subtitle: resize %dx%d → %dx%d storage=%dx%d", width, height, newWidth, newHeight, storageW, storageH)
        width = newWidth
        height = newHeight
        postWork("resize") {
            if (nativeCtx != 0L) nativeResize(nativeCtx, newWidth, newHeight, storageW, storageH)
            cachedBitmap = null
        }
    }

    // Latest async render output. Updated by the renderer thread, read by callers on
    // the UI thread. @Volatile gives a torn-free reference read across threads.
    @Volatile private var lastResult: RenderResult = RenderResult(hasContent = false)
    // Single-slot dedup: if a render is already in flight, skip posting another. Without
    // this, heavy karaoke frames (50–70 ms native) queue up faster than they complete
    // and the handler backlog grows unboundedly.
    @Volatile private var renderInFlight: Boolean = false

    /**
     * Request a subtitle frame render at the given playback time.
     *
     * Non-blocking: posts work to the renderer thread and returns the most recently
     * completed frame. A 16 ms caller-side budget is not viable — heavy anime karaoke
     * and sign lines can produce 200–900 image blits and take 40–70 ms to composite
     * on the CPU, which would cause every single frame to be dropped. Callers poll
     * this method on a loop; the cached [lastResult] is updated by the render thread
     * as new frames complete.
     */
    fun renderFrame(timeMs: Long): RenderResult {
        if (destroyed) return RenderResult(hasContent = false)
        if (!renderInFlight) {
            renderInFlight = true
            val posted = handler.postAtFrontOfQueue {
                try {
                    if (destroyed || nativeCtx == 0L) return@postAtFrontOfQueue
                    val nativeResult = nativeRenderFrame(nativeCtx, timeMs) ?: return@postAtFrontOfQueue
                    val hasContent = nativeResult[0] != 0
                    hasActiveSubtitles = hasContent
                    if (!hasContent) {
                        lastResult = RenderResult(hasContent = false)
                        return@postAtFrontOfQueue
                    }
                    val buffer = nativeGetBuffer(nativeCtx) ?: return@postAtFrontOfQueue
                    buffer.rewind()
                    val bitmap = cachedBitmap?.takeIf { it.width == width && it.height == height }
                        ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                            it.setHasAlpha(true)
                            cachedBitmap = it
                        }
                    bitmap.copyPixelsFromBuffer(buffer)
                    lastResult = RenderResult(
                        hasContent = true,
                        bitmap = bitmap,
                        dirtyX = nativeResult[1],
                        dirtyY = nativeResult[2],
                        dirtyW = nativeResult[3],
                        dirtyH = nativeResult[4],
                    )
                } finally {
                    renderInFlight = false
                }
            }
            if (!posted) {
                renderInFlight = false
                Timber.w("renderFrame: skipped because renderer thread is unavailable")
            }
        }
        return lastResult
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        hasActiveSubtitles = false
        cachedBitmap = null
        val posted =
            handler.post {
                if (nativeCtx != 0L) {
                    nativeDestroy(nativeCtx)
                    nativeCtx = 0L
                }
                thread.quitSafely()
            }
        if (!posted) {
            Timber.w("destroy: renderer thread already unavailable; destroying context on caller thread")
            if (nativeCtx != 0L) {
                nativeDestroy(nativeCtx)
                nativeCtx = 0L
            }
            thread.quitSafely()
        }
    }

    // --- Native methods ---
    private external fun nativeInit(width: Int, height: Int): Long
    private external fun nativeSetTrackData(ctx: Long, codecPrivate: ByteArray)
    private external fun nativeProcessChunk(ctx: Long, data: ByteArray, startMs: Long, durationMs: Long)
    // Returns [hasContent, dirtyX, dirtyY, dirtyW, dirtyH] or null on error
    private external fun nativeRenderFrame(ctx: Long, timeMs: Long): IntArray?
    private external fun nativeGetBuffer(ctx: Long): ByteBuffer?
    private external fun nativeAddFont(ctx: Long, name: String, data: ByteArray)
    private external fun nativeResize(ctx: Long, width: Int, height: Int, storageW: Int, storageH: Int)
    private external fun nativeClearCache(ctx: Long)
    private external fun nativeDestroy(ctx: Long)
}
