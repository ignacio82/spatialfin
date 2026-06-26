package dev.spatialfin.unified

import android.content.Context
import dev.jdtech.jellyfin.data.musicassistant.api.ServiceClient
import dev.jdtech.jellyfin.data.musicassistant.data.model.server.SearchResult
import dev.jdtech.jellyfin.data.musicassistant.data.model.server.ServerMediaItem
import dev.jdtech.jellyfin.data.musicassistant.repository.MaSessionRepository
import dev.jdtech.jellyfin.data.musicassistant.repository.MusicAssistantRepository
import dev.jdtech.jellyfin.sendspin.receiver.SendspinReceiverService
import timber.log.Timber

/**
 * "Play music" voice path. Searches Music Assistant for [query] across the
 * common library media types and dispatches the first hit to play on this
 * device's SendSpin receiver.
 *
 * Routes through [SendspinReceiverService.playMusicAssistantMedia] (REST) for
 * the actual command — that service owns the protocol-link race handling
 * (SendSpin endpoints register with MA as `PlayerType.PROTOCOL` players whose
 * Universal Player wrapper takes up to ~15 s to materialise). [MaSessionRepository]
 * also receives an **optimistic hint** so the mini-player shows the just-tapped
 * track immediately, instead of waiting for MA's event stream to catch up.
 */
suspend fun launchMusicAssistantQuery(
    context: Context,
    repository: MusicAssistantRepository,
    session: MaSessionRepository,
    serviceClient: ServiceClient,
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
    dispatchMusicAssistantPlay(context, session, serviceClient, item, uri)
}

/**
 * Shared entry point for "play this MA URI on the SendSpin receiver" used by
 * the voice helper, the Beam card-tap, and the search-result tap. Surfaces an
 * optimistic hint into [MaSessionRepository] so every UI surface bound to
 * `session.session` shows the track instantly.
 *
 * Pass [item] when available so the mini-player can render real metadata while
 * the actual `play_media` RPC is in flight; otherwise fall back to URI-only.
 */
fun dispatchMusicAssistantPlay(
    context: Context,
    session: MaSessionRepository,
    serviceClient: ServiceClient,
    item: ServerMediaItem?,
    uri: String,
) {
    if (uri.isBlank()) return
    session.reportOptimisticPlay(
        uri = uri,
        title = item?.name ?: uri.substringAfterLast('/'),
        artist = item?.artists?.firstOrNull()?.name ?: item?.album?.name,
        artworkUrl = item?.preferredImagePath()?.let(serviceClient::rebaseServerImageUrl),
        targetPlayerId = null,
    )
    SendspinReceiverService.playMusicAssistantMedia(
        context,
        uri,
        dev.jdtech.jellyfin.api.JellyfinApi.getInstance(context).userId?.toString(),
    )
}

private fun ServerMediaItem.preferredImagePath(): String? =
    image?.path ?: metadata?.images?.firstOrNull()?.path

private fun SearchResult.pickPlayableHit(): ServerMediaItem? =
    tracks.firstOrNull()
        ?: albums.firstOrNull()
        ?: artists.firstOrNull()
        ?: playlists.firstOrNull()

private val MUSIC_MEDIA_TYPES = listOf("track", "album", "artist", "playlist")
private const val TAG = "MusicAssistantVoicePlay"
