package dev.spatialfin.unified

import android.content.Context
import android.content.Intent
import dev.jdtech.jellyfin.player.core.ProjectionModeDetector
import dev.jdtech.jellyfin.player.core.StereoModeDetector
import dev.jdtech.jellyfin.player.core.VideoUriHelper

/**
 * Pure policy helpers for handling external video intents (e.g. Android "Open with" / ACTION_VIEW).
 */
object ViewVideoPolicy {

    /**
     * Determines whether the given [intent] is an ACTION_VIEW request for video content.
     */
    fun shouldHandleIntent(context: Context, intent: Intent?): Boolean {
        if (intent == null) return false
        if (intent.action != Intent.ACTION_VIEW) return false

        val uri = intent.data ?: return false
        // Exclude internal deep links (e.g. spatialfin://ma/..., spatialfin://play)
        if (uri.scheme.equals("spatialfin", ignoreCase = true)) return false

        return VideoUriHelper.isSupportedVideoUri(context, uri, intent.type)
    }

    /**
     * Resolves the stereo mode string ("mono", "sbs", "top_bottom", "multiview")
     * for a video with [displayName].
     */
    fun resolveStereoMode(displayName: String): String {
        return when (StereoModeDetector.detect(displayName, null, listOf(displayName))) {
            StereoModeDetector.StereoMode.SIDE_BY_SIDE -> "sbs"
            StereoModeDetector.StereoMode.TOP_BOTTOM -> "top_bottom"
            StereoModeDetector.StereoMode.MULTIVIEW -> "multiview"
            else -> "mono"
        }
    }

    /**
     * Resolves the projection string ("flat", "180", "360", etc.)
     * for a video with [displayName].
     */
    fun resolveProjection(displayName: String): String {
        return ProjectionModeDetector.asExtra(
            ProjectionModeDetector.detect(displayName, listOf(displayName))
        )
    }
}
