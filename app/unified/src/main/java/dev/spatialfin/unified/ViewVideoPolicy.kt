package dev.spatialfin.unified

import android.content.Context
import android.content.Intent
import dev.jdtech.jellyfin.network.SafeUrlPolicy
import dev.jdtech.jellyfin.network.UrlTargetPolicy
import dev.jdtech.jellyfin.player.core.ProjectionModeDetector
import dev.jdtech.jellyfin.player.core.StereoModeDetector
import dev.jdtech.jellyfin.player.core.VideoUriHelper
import java.io.File

/**
 * Pure policy helpers for handling external video intents (e.g. Android "Open with" / ACTION_VIEW).
 */
object ViewVideoPolicy {

    private val ALLOWED_SCHEMES = setOf("content", "file", "http", "https")

    /**
     * Determines whether the given [intent] is a safe, supported ACTION_VIEW request for video content.
     */
    fun shouldHandleIntent(context: Context, intent: Intent?): Boolean {
        if (intent == null) return false
        if (intent.action != Intent.ACTION_VIEW) return false

        val uri = intent.data ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme !in ALLOWED_SCHEMES) return false

        if (scheme == "http" || scheme == "https") {
            val allowed = runCatching {
                SafeUrlPolicy.validateInitialUrl(uri.toString(), UrlTargetPolicy.PUBLIC_OR_LAN)
            }.isSuccess
            if (!allowed) return false
        } else if (scheme == "file") {
            val path = uri.path ?: return false
            if (isAppPrivateFile(context, File(path))) return false
        }

        return VideoUriHelper.isSupportedVideoUri(context, uri, intent.type)
    }

    internal fun isAppPrivateFile(context: Context, file: File): Boolean {
        val canonicalTarget = runCatching { file.canonicalFile }.getOrElse { return true }
        val privateRoots = buildList {
            context.dataDir?.let(::add)
            context.filesDir?.let(::add)
            context.cacheDir?.let(::add)
            context.getExternalFilesDirs(null)?.filterNotNull()?.forEach(::add)
            context.externalCacheDirs?.filterNotNull()?.forEach(::add)
        }
        return privateRoots.any { root ->
            val canonicalRoot = runCatching { root.canonicalFile }.getOrNull() ?: return@any false
            val rootPath = canonicalRoot.path.trimEnd(File.separatorChar)
            val targetPath = canonicalTarget.path
            targetPath == rootPath || targetPath.startsWith("$rootPath${File.separatorChar}")
        }
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
            ProjectionModeDetector.detect(displayName, listOf(displayName)),
        )
    }
}
