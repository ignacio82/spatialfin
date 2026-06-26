package dev.spatialfin.fcast.session

/**
 * Small, testable rules for split-A/V receiver stream timelines.
 *
 * Jellyfin HLS transcodes are generated from the requested PlaybackInfo start offset, so the
 * receiver's stream timeline starts at 0 while the user-visible media timeline starts at the
 * requested resume position. Raw direct streams keep the original media timeline.
 */
internal object SplitAvStreamUrlPolicy {
    fun isJellyfinHlsUrl(url: String): Boolean =
        url.contains(".m3u8", ignoreCase = true) || url.contains("/hls", ignoreCase = true)

    fun receiverMediaStartOffsetMs(streamUrl: String, requestedStartMs: Long): Long =
        if (isJellyfinHlsUrl(streamUrl)) requestedStartMs.coerceAtLeast(0L) else 0L
}
