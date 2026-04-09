package dev.jdtech.jellyfin.player.xr.capture

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.media3.common.Player
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * Captures the best available still frame at the player's current position.
 *
 * Priority:
 * 1. [MediaMetadataRetriever] on the Jellyfin HTTP stream URI — full video resolution,
 *    seeks to the exact millisecond. Runs on [Dispatchers.IO] and is bounded by
 *    [timeoutMs] to avoid hanging the caller.
 * 2. Trickplay thumbnail — low-res pre-generated thumbnail from Jellyfin; instant but
 *    only available when the server has generated them.
 * 3. null — no frame available; caller should degrade gracefully.
 *
 * Memory contract:
 * - Bitmaps returned from [captureExact] are owned by the caller and MUST be recycled
 *   after use (call [Bitmap.recycle] once inference is complete).
 * - Bitmaps returned from [selectTrickplayFrame] are shared references into the
 *   trickplay image list; do NOT recycle them.
 */
object PlayerFrameCapture {

    private const val TAG = "PlayerFrameCapture"

    /**
     * Attempts to capture the exact current frame via [MediaMetadataRetriever].
     *
     * @param streamUri   The direct Jellyfin video stream URI (http://…/Videos/…/stream?…).
     * @param positionMs  The playback position in milliseconds.
     * @param targetWidth Desired output width; the retriever will scale proportionally.
     * @param timeoutMs   Maximum wall-clock time to wait; returns null on timeout.
     * @return A freshly-allocated [Bitmap] owned by the caller, or null on failure.
     */
    suspend fun captureExact(
        streamUri: String,
        positionMs: Long,
        targetWidth: Int = 640,
        timeoutMs: Long = 4_000,
    ): Bitmap? = withContext(Dispatchers.IO) {
        withTimeoutOrNull(timeoutMs) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(streamUri, emptyMap())
                // OPTION_CLOSEST_SYNC seeks to the nearest sync (keyframe) and is much
                // faster than OPTION_CLOSEST for HTTP streams; acceptable for character ID.
                val raw = retriever.getFrameAtTime(
                    positionMs * 1_000L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                ) ?: return@withTimeoutOrNull null

                // Scale down to targetWidth to keep the Bitmap small enough for the
                // LiteRT multimodal pipeline (which compresses it to PNG before inference).
                scaleBitmap(raw, targetWidth).also {
                    if (it !== raw) raw.recycle()
                    Timber.d(TAG, "captureExact: %dx%d @ %dms", it.width, it.height, positionMs)
                }
            } catch (e: Exception) {
                Timber.w(e, "$TAG: captureExact failed, will fall back to trickplay")
                null
            } finally {
                try { retriever.release() } catch (_: Exception) {}
            }
        }
    }

    /**
     * Selects the trickplay thumbnail frame closest to [positionMs].
     *
     * The returned bitmap is a shared reference — do NOT recycle it.
     *
     * @param positionMs       Current playback position in milliseconds.
     * @param images           The ordered list of trickplay thumbnails.
     * @param intervalSeconds  The server-reported interval between thumbnails, in seconds.
     */
    fun selectTrickplayFrame(
        positionMs: Long,
        images: List<Bitmap>,
        intervalSeconds: Long,
    ): Bitmap? {
        if (images.isEmpty() || intervalSeconds <= 0) return null
        val idx = (positionMs / 1_000L / intervalSeconds)
            .toInt()
            .coerceIn(0, images.size - 1)
        return images[idx]
    }

    /**
     * Returns the best single frame for character identification.
     *
     * [captureExact] is attempted first; if it fails or [streamUri] is null, falls back
     * to the trickplay thumbnail.  Pass [ownedBitmapOut] as a single-element list; the
     * caller should recycle its contents after use if [captureExact] succeeded.
     */
    suspend fun bestFrameForCharacterID(
        streamUri: String?,
        positionMs: Long,
        trickplayImages: List<Bitmap>,
        trickplayIntervalSeconds: Long,
        ownedBitmapOut: MutableList<Bitmap>,
    ): Bitmap? {
        if (!streamUri.isNullOrBlank()) {
            val exact = captureExact(streamUri, positionMs)
            if (exact != null) {
                ownedBitmapOut += exact   // caller must recycle this
                return exact
            }
        }
        return selectTrickplayFrame(positionMs, trickplayImages, trickplayIntervalSeconds)
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private fun scaleBitmap(src: Bitmap, targetWidth: Int): Bitmap {
        if (src.width <= targetWidth) return src
        val scale = targetWidth.toFloat() / src.width
        val h = (src.height * scale).toInt()
        return Bitmap.createScaledBitmap(src, targetWidth, h, true)
    }
}
