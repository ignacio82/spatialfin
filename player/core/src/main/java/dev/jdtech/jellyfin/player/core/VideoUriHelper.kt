package dev.jdtech.jellyfin.player.core

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

/**
 * Helpers for resolving external video URIs (e.g. from Android's "Open with" / ACTION_VIEW),
 * validating supported video formats and MIME types, and extracting readable display titles.
 */
object VideoUriHelper {

    private val VIDEO_EXTENSIONS = setOf(
        "mp4", "m4v", "mkv", "webm", "avi", "mov", "qt",
        "ts", "mts", "m2ts", "3gp", "3g2", "flv", "f4v",
        "wmv", "asf", "vob", "mpg", "mpeg", "m2v", "ogv",
        "ogg", "divx", "rm", "rmvb", "m3u8", "mpd"
    )

    private val VIDEO_MIME_TYPES = setOf(
        "application/x-matroska",
        "application/matroska",
        "application/mp4",
        "application/x-mp4",
        "application/webm",
        "application/ogg",
        "application/x-quicktimeplayer",
        "application/vnd.apple.mpegurl",
        "application/x-mpegurl",
        "application/dash+xml",
    )

    /**
     * Checks if [mimeType] represents a supported video format.
     */
    fun isSupportedVideoMimeType(mimeType: String?): Boolean {
        if (mimeType.isNullOrBlank()) return false
        val lower = mimeType.trim().lowercase()
        return lower.startsWith("video/") || lower in VIDEO_MIME_TYPES
    }

    /**
     * Checks if [nameOrPath] ends with a supported video extension.
     */
    fun isSupportedVideoExtension(nameOrPath: String?): Boolean {
        if (nameOrPath.isNullOrBlank()) return false
        val clean = nameOrPath.substringBefore('?').substringBefore('#')
        val extension = clean.substringAfterLast('.', "").lowercase()
        return extension in VIDEO_EXTENSIONS
    }

    /**
     * Checks whether [uri] points to a video file or stream supported by the player.
     * Checks the [mimeTypeHint] first, then uri path and filename extensions, and finally
     * probes [ContentResolver.getType] or display name when dealing with a content:// URI.
     */
    fun isSupportedVideoUri(context: Context, uri: Uri, mimeTypeHint: String? = null): Boolean {
        if (isSupportedVideoMimeType(mimeTypeHint)) return true
        if (isSupportedVideoExtension(uri.lastPathSegment)) return true
        if (isSupportedVideoExtension(uri.path)) return true

        if (uri.scheme.equals(ContentResolver.SCHEME_CONTENT, ignoreCase = true)) {
            val resolvedType = runCatching { context.contentResolver.getType(uri) }.getOrNull()
            if (isSupportedVideoMimeType(resolvedType)) return true

            val displayName = resolveDisplayName(context, uri)
            if (isSupportedVideoExtension(displayName)) return true
        }

        return false
    }

    /**
     * Resolves the human-readable display title for a video [uri].
     * Queries [OpenableColumns.DISPLAY_NAME] for content:// URIs and falls back to
     * the last segment of the path or a fallback string.
     */
    fun resolveDisplayName(context: Context, uri: Uri): String {
        if (uri.scheme.equals(ContentResolver.SCHEME_CONTENT, ignoreCase = true)) {
            val nameFromResolver = runCatching {
                context.contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) cursor.getString(idx) else null
                    } else {
                        null
                    }
                }
            }.getOrNull()
            if (!nameFromResolver.isNullOrBlank()) {
                return nameFromResolver
            }
        }

        val segment = uri.lastPathSegment?.substringAfterLast('/')
        if (!segment.isNullOrBlank()) {
            return segment
        }

        return "External Video"
    }
}
