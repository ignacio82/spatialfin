package dev.spatialfin.unified

import android.content.Context
import dev.jdtech.jellyfin.data.musicassistant.data.model.server.ServerMediaItem
import dev.jdtech.jellyfin.data.musicassistant.repository.MusicAssistantRepository
import dev.jdtech.jellyfin.sendspin.receiver.SendspinReceiverService
import timber.log.Timber

/**
 * "Play music" voice path. Searches Music Assistant for [query] across the
 * common library media types and dispatches the first hit to play on this
 * device's SendSpin receiver.
 *
 * We deliberately route through [SendspinReceiverService.playMusicAssistantMedia]
 * instead of the WebSocket [MusicAssistantRepository.playMedia] because that
 * service owns the protocol-link race handling: SendSpin endpoints register
 * with MA as `PlayerType.PROTOCOL` players, which never get their own queue —
 * MA wraps them in a Universal Player that owns the queue, and the wrapper
 * id only becomes available a fraction of a second after `players/register`.
 * The service polls until the wrapper appears, then sends `play_media` against
 * the wrapper id. Calling the WebSocket repo with the raw `ServerMediaItem`
 * would hit `PlayerUnavailableError` until that race resolves.
 *
 * The WebSocket repo is still the right call when targeting an explicit
 * non-receiver player (different room, group, etc.) — pass an explicit
 * `queueOrPlayerId` there. This helper only covers the "play it here" intent.
 */
suspend fun launchMusicAssistantQuery(
    context: Context,
    repository: MusicAssistantRepository,
    query: String,
) {
    if (query.isBlank()) return
    val results = try {
        repository.search(
            query = query,
            mediaTypes = MUSIC_MEDIA_TYPES,
            limit = 1,
        )
    } catch (e: Exception) {
        Timber.tag(TAG).w(e, "search failed for '%s'", query)
        null
    }
    val item = results?.pickPlayableHit()
    val uri = item?.uri
    if (uri.isNullOrBlank()) {
        Timber.tag(TAG).w("no MA match for '%s'", query)
        return
    }
    Timber.tag(TAG).i("dispatching MA play for '%s' → %s", query, uri)
    SendspinReceiverService.playMusicAssistantMedia(context, uri)
}

private fun dev.jdtech.jellyfin.data.musicassistant.data.model.server.SearchResult.pickPlayableHit(): ServerMediaItem? =
    tracks.firstOrNull()
        ?: albums.firstOrNull()
        ?: artists.firstOrNull()
        ?: playlists.firstOrNull()

private val MUSIC_MEDIA_TYPES = listOf("track", "album", "artist", "playlist")
private const val TAG = "MusicAssistantVoicePlay"
