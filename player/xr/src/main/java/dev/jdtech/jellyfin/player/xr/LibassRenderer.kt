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
    private var cachedBitmap: Bitmap? = null

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
        }
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

    /**
     * Render the subtitle frame at the given playback time.
     * Returns a RenderResult indicating whether content is visible.
     */
    fun renderFrame(timeMs: Long): RenderResult {
        if (destroyed) return RenderResult(hasContent = false)
        // Default to the last known content state so that when the native layer returns null
        // ("no change — reuse cached frame"), we preserve visibility instead of hiding the panel.
        var result = RenderResult(hasContent = hasActiveSubtitles)
        val latch = java.util.concurrent.CountDownLatch(1)
        // Subtitle tracks with many ASS events can enqueue thousands of chunk updates at start.
        // Rendering must preempt that backlog or the panel will freeze on the first visible line.
        val posted =
            handler.postAtFrontOfQueue {
                try {
                    if (!destroyed && nativeCtx != 0L) {
                        val nativeResult = nativeRenderFrame(nativeCtx, timeMs)
                        if (nativeResult != null) {
                            val hasContent = nativeResult[0] != 0
                            hasActiveSubtitles = hasContent
                            if (hasContent) {
                                val buffer = nativeGetBuffer(nativeCtx)
                                if (buffer != null) {
                                    buffer.rewind()
                                    val bitmap = cachedBitmap?.let {
                                        if (it.width == width && it.height == height) it
                                        else {
                                            Timber.w(
                                                "renderFrame: bitmap size mismatch %dx%d vs %dx%d — replacing without recycle",
                                                it.width,
                                                it.height,
                                                width,
                                                height,
                                            )
                                            null
                                        }
                                    } ?: Bitmap.createBitmap(
                                        width,
                                        height,
                                        Bitmap.Config.ARGB_8888,
                                    ).also {
                                        it.setHasAlpha(true)
                                        cachedBitmap = it
                                    }
                                    bitmap.copyPixelsFromBuffer(buffer)
                                    val dirtyW = nativeResult[3]
                                    val dirtyH = nativeResult[4]
                                    result = RenderResult(
                                        hasContent = true,
                                        bitmap = bitmap,
                                        dirtyX = nativeResult[1],
                                        dirtyY = nativeResult[2],
                                        dirtyW = dirtyW,
                                        dirtyH = dirtyH,
                                    )
                                }
                            } else {
                                result = RenderResult(hasContent = false)
                            }
                        }
                    }
                } finally {
                    latch.countDown()
                }
            }
        if (!posted) {
            hasActiveSubtitles = false
            Timber.w("renderFrame: skipped because renderer thread is unavailable")
            return RenderResult(hasContent = false)
        }
        latch.await()
        if (destroyed) return RenderResult(hasContent = false)
        return result
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
