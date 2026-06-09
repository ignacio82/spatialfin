package dev.jdtech.jellyfin.deeplink

import android.net.Uri

/**
 * `spatialfin://ma/<action>` deep links for Music Assistant playback control.
 * Distinct host from [PlayDeepLink] (`spatialfin://play`, the Jellyfin video
 * link already routed to `TvPlayerActivity`) so the two never collide.
 *
 * Used by app shortcuts ("Resume", "Open queue", "Party mode"), and reusable
 * by future share-to-queue QR codes (Phase 4) and Assistant hand-offs.
 *
 * Shapes:
 *  - `spatialfin://ma/play?uri=<ma-uri>` — play an MA media uri now.
 *  - `spatialfin://ma/resume`            — toggle play/pause on the selected player.
 *  - `spatialfin://ma/queue`             — open Now Playing → queue.
 *  - `spatialfin://ma/party`             — open the party screen.
 */
object MaDeepLink {
    const val SCHEME = "spatialfin"
    const val HOST = "ma"

    const val ACTION_PLAY = "play"
    const val ACTION_RESUME = "resume"
    const val ACTION_QUEUE = "queue"
    const val ACTION_PARTY = "party"

    const val PARAM_URI = "uri"

    sealed interface Parsed {
        data class Play(val uri: String) : Parsed
        data object Resume : Parsed
        data object Queue : Parsed
        data object Party : Parsed
    }

    fun play(uri: String): Uri = base(ACTION_PLAY).appendQueryParameter(PARAM_URI, uri).build()
    fun resume(): Uri = base(ACTION_RESUME).build()
    fun queue(): Uri = base(ACTION_QUEUE).build()
    fun party(): Uri = base(ACTION_PARTY).build()

    private fun base(action: String): Uri.Builder =
        Uri.Builder().scheme(SCHEME).authority(HOST).appendPath(action)

    fun parse(uri: Uri?): Parsed? {
        if (uri == null) return null
        if (!uri.scheme.equals(SCHEME, ignoreCase = true)) return null
        if (!uri.host.equals(HOST, ignoreCase = true)) return null
        return when (uri.pathSegments.firstOrNull()) {
            ACTION_PLAY -> uri.getQueryParameter(PARAM_URI)
                ?.takeIf { it.isNotBlank() }
                ?.let { Parsed.Play(it) }
            ACTION_RESUME -> Parsed.Resume
            ACTION_QUEUE -> Parsed.Queue
            ACTION_PARTY -> Parsed.Party
            else -> null
        }
    }
}
