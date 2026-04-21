package dev.jdtech.jellyfin.deeplink

import android.net.Uri
import java.util.UUID

/**
 * Canonical `spatialfin://play` deep link for launching playback of a Jellyfin
 * item. Used by:
 *
 *  - `WatchNextSync` — builds URIs for Google TV Watch Next cards.
 *  - `TvPlayerActivity.onCreate` — parses `ACTION_VIEW` intents.
 *  - (future) Global Search ContentProvider, AppFunctions, share-sheet handling.
 *
 * URI shape: `spatialfin://play?id=<uuid>&kind=<Movie|Episode>[&startMs=<long>]`.
 *
 * Kept deliberately simple: opaque query params, no path hierarchy. Callers that
 * need extra context (like "open SyncPlay dialog on start") should pass that via
 * `Intent` extras after resolving the deep link — we don't overload the URI.
 */
object PlayDeepLink {
    const val SCHEME = "spatialfin"
    const val HOST = "play"

    const val PARAM_ID = "id"
    const val PARAM_KIND = "kind"
    const val PARAM_START_MS = "startMs"

    const val KIND_MOVIE = "Movie"
    const val KIND_EPISODE = "Episode"

    data class Parsed(val itemId: UUID, val kind: String, val startPositionMs: Long?)

    fun build(itemId: UUID, kind: String, startPositionMs: Long? = null): Uri {
        val builder =
            Uri.Builder()
                .scheme(SCHEME)
                .authority(HOST)
                .appendQueryParameter(PARAM_ID, itemId.toString())
                .appendQueryParameter(PARAM_KIND, kind)
        if (startPositionMs != null && startPositionMs > 0L) {
            builder.appendQueryParameter(PARAM_START_MS, startPositionMs.toString())
        }
        return builder.build()
    }

    fun parse(uri: Uri?): Parsed? {
        if (uri == null) return null
        if (!uri.scheme.equals(SCHEME, ignoreCase = true)) return null
        if (!uri.host.equals(HOST, ignoreCase = true)) return null
        val idString = uri.getQueryParameter(PARAM_ID) ?: return null
        val itemId = runCatching { UUID.fromString(idString) }.getOrNull() ?: return null
        val kind = uri.getQueryParameter(PARAM_KIND)?.takeIf { it.isNotBlank() } ?: return null
        val startMs = uri.getQueryParameter(PARAM_START_MS)?.toLongOrNull()?.takeIf { it > 0L }
        return Parsed(itemId = itemId, kind = kind, startPositionMs = startMs)
    }
}
