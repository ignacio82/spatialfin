package dev.jdtech.jellyfin.player.xr

import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import java.nio.ByteBuffer
import timber.log.Timber

data class RenderResult(
    val hasContent: Boolean,
    val bitmap: Bitmap? = null,
    val dirtyX: Int = 0,
    val dirtyY: Int = 0,
    val dirtyW: Int = 0,
    val dirtyH: Int = 0,
)

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
            } catch (error: UnsatisfiedLinkError) {
                Timber.w(error, "subtitle: failed to load libass shared library")
                false
            }
        }
    }

    private var nativeCtx: Long = 0L
    private val thread = HandlerThread("beam-libass-renderer").also { it.start() }
    private val handler = Handler(thread.looper)
    private var cachedBitmap: Bitmap? = null

    @Volatile
    var hasActiveSubtitles: Boolean = false
        private set

    fun init() {
        if (!isAvailable()) return
        Timber.i("subtitle: init native libass size=%dx%d", width, height)
        handler.post {
            nativeCtx = nativeInit(width, height)
            Timber.i("subtitle: nativeCtx=%d", nativeCtx)
        }
    }

    fun addFont(name: String, data: ByteArray) {
        handler.post {
            if (nativeCtx != 0L) nativeAddFont(nativeCtx, name, data)
        }
    }

    fun setTrackData(codecPrivate: ByteArray) {
        handler.post {
            if (nativeCtx != 0L) nativeSetTrackData(nativeCtx, codecPrivate)
        }
    }

    fun processChunk(data: ByteArray, startTimeMs: Long, durationMs: Long) {
        handler.post {
            if (nativeCtx != 0L) nativeProcessChunk(nativeCtx, data, startTimeMs, durationMs)
        }
    }

    fun clearCache() {
        handler.post {
            if (nativeCtx != 0L) nativeClearCache(nativeCtx)
        }
    }

    fun resize(newWidth: Int, newHeight: Int, storageW: Int = 0, storageH: Int = 0) {
        width = newWidth
        height = newHeight
        handler.post {
            if (nativeCtx != 0L) nativeResize(nativeCtx, newWidth, newHeight, storageW, storageH)
            cachedBitmap = null
        }
    }

    fun renderFrame(timeMs: Long): RenderResult {
        var result = RenderResult(hasContent = hasActiveSubtitles)
        val latch = java.util.concurrent.CountDownLatch(1)
        handler.postAtFrontOfQueue {
            if (nativeCtx != 0L) {
                val nativeResult = nativeRenderFrame(nativeCtx, timeMs)
                if (nativeResult != null) {
                    val hasContent = nativeResult[0] != 0
                    hasActiveSubtitles = hasContent
                    if (hasContent) {
                        val buffer = nativeGetBuffer(nativeCtx)
                        if (buffer != null) {
                            buffer.rewind()
                            val bitmap =
                                cachedBitmap?.let {
                                    if (it.width == width && it.height == height) {
                                        it
                                    } else {
                                        null
                                    }
                                } ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                                    it.setHasAlpha(true)
                                    cachedBitmap = it
                                }
                            bitmap.copyPixelsFromBuffer(buffer)
                            result =
                                RenderResult(
                                    hasContent = true,
                                    bitmap = bitmap,
                                    dirtyX = nativeResult[1],
                                    dirtyY = nativeResult[2],
                                    dirtyW = nativeResult[3],
                                    dirtyH = nativeResult[4],
                                )
                        }
                    } else {
                        result = RenderResult(hasContent = false)
                    }
                }
            }
            latch.countDown()
        }
        latch.await()
        return result
    }

    fun destroy() {
        handler.post {
            if (nativeCtx != 0L) {
                nativeDestroy(nativeCtx)
                nativeCtx = 0L
            }
            thread.quitSafely()
        }
        cachedBitmap = null
    }

    private external fun nativeInit(width: Int, height: Int): Long
    private external fun nativeSetTrackData(ctx: Long, codecPrivate: ByteArray)
    private external fun nativeProcessChunk(ctx: Long, data: ByteArray, startMs: Long, durationMs: Long)
    private external fun nativeRenderFrame(ctx: Long, timeMs: Long): IntArray?
    private external fun nativeGetBuffer(ctx: Long): ByteBuffer?
    private external fun nativeAddFont(ctx: Long, name: String, data: ByteArray)
    private external fun nativeResize(ctx: Long, width: Int, height: Int, storageW: Int, storageH: Int)
    private external fun nativeClearCache(ctx: Long)
    private external fun nativeDestroy(ctx: Long)
}
