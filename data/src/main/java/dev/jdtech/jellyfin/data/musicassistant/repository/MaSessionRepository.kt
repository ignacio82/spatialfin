package dev.jdtech.jellyfin.data.musicassistant.repository

import dev.jdtech.jellyfin.data.musicassistant.api.APICommands
import dev.jdtech.jellyfin.data.musicassistant.api.Request
import dev.jdtech.jellyfin.data.musicassistant.api.ServiceClient
import dev.jdtech.jellyfin.data.musicassistant.data.model.server.PlayerState
import dev.jdtech.jellyfin.data.musicassistant.data.model.server.RepeatMode
import dev.jdtech.jellyfin.data.musicassistant.data.model.server.ServerPlayer
import dev.jdtech.jellyfin.data.musicassistant.data.model.server.ServerQueue
import dev.jdtech.jellyfin.data.musicassistant.data.model.server.events.MediaItemPlayedEvent
import dev.jdtech.jellyfin.data.musicassistant.data.model.server.events.PlayerAddedEvent
import dev.jdtech.jellyfin.data.musicassistant.data.model.server.events.PlayerRemovedEvent
import dev.jdtech.jellyfin.data.musicassistant.data.model.server.events.PlayerUpdatedEvent
import dev.jdtech.jellyfin.data.musicassistant.data.model.server.events.QueueAddedEvent
import dev.jdtech.jellyfin.data.musicassistant.data.model.server.events.QueueItemsUpdatedEvent
import dev.jdtech.jellyfin.data.musicassistant.data.model.server.events.QueueTimeUpdatedEvent
import dev.jdtech.jellyfin.data.musicassistant.data.model.server.events.QueueUpdatedEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Live Music Assistant session state, fed by the server's event stream.
 *
 * Subscribes to PLAYER_*, QUEUE_*, and MEDIA_ITEM_PLAYED events from
 * [ServiceClient.events] and reduces them into a single [StateFlow]<[MaSession]>
 * that every MA UI surface reads from. Mirrors the architecture of the official
 * mobile app's `MainDataSource` (commonMain/.../data/MainDataSource.kt) so we
 * stay structurally compatible with future MA event additions.
 *
 * Two responsibilities the events alone can't satisfy, both handled here:
 *
 *  1. **Cold-start hydration** — events only fire on _changes_, so we seed
 *     [players] and [queues] with a one-shot `players/all` + `player_queues/all`
 *     fetch the first time the WebSocket reports ready.
 *
 *  2. **Optimistic playback** — `play_media` blocks until MA has loaded the
 *     queue (frequently several seconds), and the UI needs to show the just-
 *     tapped track instantly. [reportOptimisticPlay] surfaces a pending track
 *     hint in [MaSession.pendingPlay] that the mini-player can render
 *     immediately. The hint clears automatically the moment a real
 *     QUEUE_UPDATED or PLAYER_UPDATED arrives whose current item matches the
 *     hinted URI.
 */
class MaSessionRepository(
    private val serviceClient: ServiceClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _players = MutableStateFlow<Map<String, ServerPlayer>>(emptyMap())
    private val _queues = MutableStateFlow<Map<String, ServerQueue>>(emptyMap())
    private val _selectedPlayerId = MutableStateFlow<String?>(null)
    private val _pendingPlay = MutableStateFlow<PendingPlay?>(null)
    private val _hydrationState = MutableStateFlow(HydrationState.Idle)

    /**
     * URI the user explicitly Stopped. While the active queue still holds this
     * item, the now-playing UI stays hidden (Stop = "I'm done", not "pause").
     * Cleared by a new play (optimistic hint) or once the queue moves to a
     * different track. Null means nothing is dismissed.
     */
    private val _dismissedUri = MutableStateFlow<String?>(null)

    /**
     * Fires the affected `queue_id` every time the server reports a queue
     * mutation (add / update / items-updated). The queue panel collects this
     * to refetch its item list — `MaSession` only carries the *current* item,
     * not the full ordered list, so reorders/removals of upcoming tracks don't
     * otherwise surface through [session].
     */
    private val _queueEvents = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val queueEvents: SharedFlow<String> = _queueEvents.asSharedFlow()

    @OptIn(FlowPreview::class)
    val session: StateFlow<MaSession> = combine(
        _players,
        _queues,
        _selectedPlayerId,
        _pendingPlay,
        _hydrationState,
    ) { players, queues, selectedId, pending, hydration ->
        SessionInputs(players, queues, selectedId, pending, hydration)
    }.combine(_dismissedUri) { inputs, dismissedUri ->
        buildSession(
            inputs.players,
            inputs.queues,
            inputs.selectedId,
            inputs.pending,
            inputs.hydration,
            dismissedUri,
        )
    }
        // Coalesce rapid bursts (QUEUE_TIME_UPDATED fires up to ~once/s per active
        // queue, PLAYER_UPDATED can fire several times during state transitions).
        // The UI doesn't need every intermediate frame.
        .debounce(SESSION_DEBOUNCE_MS)
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, MaSession.EMPTY)

    init {
        observe()
    }

    /**
     * Hint to the UI that the user just initiated playback of [uri] on
     * [targetPlayerId]. Call this from the play_media tap handler BEFORE
     * the RPC returns so the mini-player shows the track instantly.
     *
     * The hint is automatically dropped once the real state catches up, or
     * after [PENDING_PLAY_MAX_AGE_MS] as a safety net.
     */
    fun reportOptimisticPlay(
        uri: String,
        title: String?,
        artist: String?,
        artworkUrl: String?,
        targetPlayerId: String?,
    ) {
        val hint = PendingPlay(
            uri = uri,
            title = title,
            artist = artist,
            artworkUrl = artworkUrl,
            targetPlayerId = targetPlayerId,
            createdAtMs = System.currentTimeMillis(),
        )
        _pendingPlay.value = hint
        // A fresh play overrides any prior Stop dismissal.
        _dismissedUri.value = null
        targetPlayerId?.takeIf { it.isNotBlank() }?.let { _selectedPlayerId.value = it }
    }

    /**
     * The user pressed Stop. Hide the now-playing / mini-player for the current
     * track until something new plays — Stop means "I'm done", unlike Pause.
     * MA keeps the stopped item in the queue, so we remember the URI and the
     * session suppresses it until the queue advances or a new play arrives.
     */
    fun dismissPlayback() {
        _dismissedUri.value = session.value.nowPlaying?.uri?.takeIf { it.isNotBlank() }
    }

    /**
     * Persist the user's preferred playback target across surfaces. Independent
     * of [reportOptimisticPlay] for use by an explicit picker.
     */
    fun setSelectedPlayer(playerId: String?) {
        _selectedPlayerId.value = playerId
    }

    private fun observe() {
        scope.launch {
            // Hydrate as soon as the socket reports ready, AND every time it
            // re-becomes ready after a reconnect (otherwise events that fired
            // during the outage are missed forever).
            serviceClient.isReadyForCommands.collect { ready ->
                if (ready) hydrate()
                else _hydrationState.value = HydrationState.Idle
            }
        }
        scope.launch {
            serviceClient.events.collect { event ->
                try {
                    handle(event)
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "event handler threw")
                }
            }
        }
        scope.launch {
            // Drop stale optimistic hints — guards against a play_media that
            // truly never lands a queue update.
            _pendingPlay
                .filter { it != null }
                .collect { hint ->
                    val ageGuard = PENDING_PLAY_MAX_AGE_MS - (System.currentTimeMillis() - hint!!.createdAtMs)
                    if (ageGuard > 0) kotlinx.coroutines.delay(ageGuard)
                    _pendingPlay.update { current -> if (current === hint) null else current }
                }
        }
    }

    private suspend fun hydrate() {
        if (_hydrationState.value == HydrationState.InFlight) return
        _hydrationState.value = HydrationState.InFlight
        try {
            // Wait for auth to settle before issuing RPCs — server responds 401
            // until the auth handshake completes.
            serviceClient.isReadyForCommands.filter { it }.first()

            val players = fetchPlayers()
            if (players != null) {
                _players.value = players.associateBy { it.playerId }
            }
            val queues = fetchQueues()
            if (queues != null) {
                _queues.value = queues.associateBy { it.queueId }
            }
            _hydrationState.value = HydrationState.Loaded
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "initial hydration failed")
            _hydrationState.value = HydrationState.Error
        }
    }

    private suspend fun fetchPlayers(): List<ServerPlayer>? = runCatching {
        serviceClient
            .sendRequest(Request(command = APICommands.PLAYERS_ALL))
            .getOrThrow()
            .resultAs<List<ServerPlayer>>()
    }.onFailure { Timber.tag(TAG).w(it, "players/all failed") }.getOrNull()

    private suspend fun fetchQueues(): List<ServerQueue>? = runCatching {
        serviceClient
            .sendRequest(Request(command = APICommands.PLAYER_QUEUES_ALL))
            .getOrThrow()
            .resultAs<List<ServerQueue>>()
    }.onFailure { Timber.tag(TAG).w(it, "player_queues/all failed") }.getOrNull()

    private fun handle(event: dev.jdtech.jellyfin.data.musicassistant.data.model.server.events.Event<out Any>) {
        when (event) {
            is PlayerAddedEvent, is PlayerUpdatedEvent -> {
                val player = event.data as ServerPlayer
                _players.update { it + (player.playerId to player) }
                maybeClearPendingPlay()
            }
            is PlayerRemovedEvent -> {
                val id = event.objectId ?: return
                _players.update { it - id }
            }
            is QueueAddedEvent, is QueueUpdatedEvent, is QueueItemsUpdatedEvent -> {
                val queue = event.data as ServerQueue
                _queues.update { it + (queue.queueId to queue) }
                _queueEvents.tryEmit(queue.queueId)
                maybeClearPendingPlay()
            }
            is QueueTimeUpdatedEvent -> {
                // Server hands us only the elapsed seconds on the queue's
                // objectId. Patch our cached ServerQueue in place so the UI
                // scrubber re-derives without a full queue refetch.
                val queueId = event.objectId ?: return
                val elapsed = event.data
                _queues.update { current ->
                    val existing = current[queueId] ?: return@update current
                    current + (queueId to existing.copy(
                        elapsedTime = elapsed,
                        elapsedTimeLastUpdated = System.currentTimeMillis() / 1000.0,
                    ))
                }
            }
            is MediaItemPlayedEvent -> {
                // Best handled by the QUEUE_UPDATED that follows, but the
                // event arriving at all is a strong "playback just started"
                // signal — clear pending hint defensively.
                maybeClearPendingPlay()
            }
            else -> Unit
        }
    }

    private fun maybeClearPendingPlay() {
        val hint = _pendingPlay.value ?: return
        // Clear only once a player is actually PLAYING the hinted uri — not
        // merely when MA has *loaded* it. MA loads the media (currentMedia/queue
        // item set) a beat before the player transitions to PLAYING; clearing on
        // load alone dropped the optimistic hint into a gap where the real
        // now-playing wasn't resolvable yet, so the mini-player flickered off and
        // only came back when playback truly started. Requiring PLAYING keeps the
        // hint up through that window (the age-based timeout is the safety net for
        // a play that never reaches PLAYING). For local playback targetPlayerId is
        // null, so we match on any player, not a single known target.
        val queues = _queues.value
        val playing = _players.value.values.any {
            it.state == PlayerState.PLAYING && it.playsUri(hint.uri, queues)
        }
        if (playing) {
            _pendingPlay.value = null
        }
    }

    private fun Map<String, ServerPlayer>.findById(id: String?): ServerPlayer? =
        if (id == null) null else this[id]

    /** True if this player's current media (or its active queue's item) is [uri]. */
    private fun ServerPlayer.playsUri(uri: String, queues: Map<String, ServerQueue>): Boolean {
        if (currentMedia?.uri == uri) return true
        val queueId = activeSource ?: currentMedia?.queueId ?: playerId
        return queues[queueId]?.currentItem?.mediaItem?.uri == uri
    }

    private fun buildSession(
        players: Map<String, ServerPlayer>,
        queues: Map<String, ServerQueue>,
        selectedId: String?,
        pending: PendingPlay?,
        hydration: HydrationState,
        dismissedUri: String?,
    ): MaSession {
        // Visible-player filter mirrors the official app's PlayerFactory:
        // available && enabled && !hidden && !hide_in_ui.
        val visiblePlayers = players.values
            .filter { p ->
                p.available && p.enabled && p.hidden != true && p.hideInUi != true
            }
            .sortedBy { it.displayName.lowercase() }

        val selected = players.findById(selectedId)
            ?.takeIf { it.available && it.enabled }
            ?: players.findById(pending?.targetPlayerId)
            // Playing on THIS device casts to a Universal-Player wrapper whose id
            // we don't know at dispatch time (targetPlayerId is null), so follow
            // whichever player is actually playing the just-tapped track, then
            // any actively-playing visible player. Without this the session locks
            // onto an unrelated "first visible" player and the now-playing /
            // mini-player / Now Playing actions (queue, party, playlist) never
            // surface for local playback.
            ?: pending?.uri?.let { uri -> 
                players.values
                    .sortedBy { it.type.equals("protocol", ignoreCase = true) }
                    .firstOrNull { it.playsUri(uri, queues) } 
            }
            ?: players.values
                .sortedBy { it.type.equals("protocol", ignoreCase = true) }
                .firstOrNull {
                    it.state == PlayerState.PLAYING && it.available && it.enabled &&
                        it.hidden != true && it.hideInUi != true
                }
            // Last-resort before "first visible": follow ANY actively-playing
            // player even if it's hidden. When a player gets pulled into a sync
            // group MA can flip its hide_in_ui flag; without this clause control
            // would jump to an unrelated visible player and the user loses the
            // transport for the song that's actually playing.
            ?: players.values
                .sortedBy { it.type.equals("protocol", ignoreCase = true) }
                .firstOrNull {
                    it.state == PlayerState.PLAYING && it.available && it.enabled
                }
            ?: visiblePlayers.firstOrNull()

        val activeQueueId = selected?.let { p ->
            p.activeSource ?: p.currentMedia?.queueId ?: p.playerId
        }
        val activeQueue = activeQueueId?.let { queues[it] }

        // Party plugin: a non-passive source on the selected player whose
        // id/name marks it as the vote-driven party queue. `defaultSourceId` is
        // a non-party source we can switch back to when leaving party mode.
        val partySource = selected?.let { p ->
            val party = p.sourceList.firstOrNull {
                !it.passive && (it.id.contains("party", true) || it.name.contains("party", true))
            } ?: return@let null
            val fallback = p.sourceList.firstOrNull { it.id != party.id && !it.passive }?.id
            MaPartySource(
                id = party.id,
                name = party.name.ifBlank { "Party" },
                playerId = p.playerId,
                active = p.activeSource == party.id,
                defaultSourceId = fallback,
            )
        }
        val nowPlayingFromServer = activeQueue?.currentItem?.mediaItem
            ?.let { item ->
                NowPlayingTrack(
                    uri = item.uri ?: "",
                    title = item.name,
                    artist = item.artists?.firstOrNull()?.name ?: activeQueue.currentItem.mediaItem.album?.name,
                    artworkUrl = item.image?.path
                        ?: item.metadata?.images?.firstOrNull()?.path,
                    durationMs = (item.duration ?: activeQueue.currentItem.duration)
                        ?.let { (it * 1000.0).toLong() },
                )
            }
            ?: selected?.currentMedia?.let { media ->
                NowPlayingTrack(
                    uri = media.uri ?: "",
                    title = media.title,
                    artist = media.artist ?: media.album,
                    artworkUrl = media.imageUrl,
                    durationMs = media.duration?.let { (it * 1000.0).toLong() },
                )
            }

        // If MA hasn't echoed yet, the optimistic hint stands in.
        val resolvedNowPlaying = nowPlayingFromServer
            ?: pending?.let {
                NowPlayingTrack(
                    uri = it.uri,
                    title = it.title,
                    artist = it.artist,
                    artworkUrl = it.artworkUrl,
                    durationMs = null,
                )
            }

        // Stop dismissal: while the dismissed URI is still the current track and
        // the user hasn't started anything new, hide the now-playing entirely.
        val dismissed = dismissedUri != null &&
            pending == null &&
            resolvedNowPlaying?.uri == dismissedUri
        val nowPlaying = if (dismissed) null else resolvedNowPlaying

        val playbackState = when {
            dismissed -> PlaybackPhase.Stopped
            pending != null && nowPlayingFromServer == null -> PlaybackPhase.Preparing
            selected?.state == PlayerState.PLAYING -> PlaybackPhase.Playing
            selected?.state == PlayerState.PAUSED -> PlaybackPhase.Paused
            selected?.announcementInProgress == true -> PlaybackPhase.Playing
            nowPlaying != null -> PlaybackPhase.Idle
            else -> PlaybackPhase.Stopped
        }

        return MaSession(
            visiblePlayers = visiblePlayers.map { it.summary() },
            selectedPlayer = selected?.summary(),
            nowPlaying = nowPlaying,
            elapsedMs = activeQueue?.elapsedTime?.let { (it * 1000.0).toLong() },
            elapsedAsOfEpochMs = activeQueue?.elapsedTimeLastUpdated?.let { (it * 1000.0).toLong() },
            playbackPhase = playbackState,
            hydrationState = hydration,
            pendingPlayUri = pending?.uri,
            activeQueueId = activeQueueId,
            repeatMode = activeQueue?.repeatMode?.let(RepeatMode::fromServer) ?: RepeatMode.OFF,
            shuffleEnabled = activeQueue?.shuffleEnabled == true,
            dontStopTheMusic = activeQueue?.dontStopTheMusicEnabled == true,
            currentQueueItemId = activeQueue?.currentItem?.queueItemId,
            partySource = partySource,
        )
    }

    private fun ServerPlayer.summary(): MaPlayerSummary = MaPlayerSummary(
        id = playerId,
        name = displayName.ifBlank { playerId },
        provider = provider,
        isPlaying = state == PlayerState.PLAYING,
        volumeLevel = volumeLevel?.toInt(),
        volumeMuted = volumeMuted == true,
        // MA reports "none" (or empty) when the endpoint exposes no volume knob
        // — hide the slider then rather than send no-op RPCs.
        supportsVolume = volumeControl.isNotBlank() && volumeControl != "none",
        // MA's can_group_with holds player ids AND provider-instance ids; the
        // picker matches on both (mirrors the official frontend). Grouping is
        // only offered when the player advertises the set_members feature.
        canGroupWith = canGroupWith?.toSet() ?: emptySet(),
        supportsGrouping = supportedFeatures.any { it.equals("set_members", ignoreCase = true) },
        syncedToPlayerId = syncedTo,
        groupMemberIds = groupMembers ?: emptySet(),
        activeGroup = activeGroup,
    )

    private data class PendingPlay(
        val uri: String,
        val title: String?,
        val artist: String?,
        val artworkUrl: String?,
        val targetPlayerId: String?,
        val createdAtMs: Long,
    )

    /** The five event-derived inputs, bundled so a 6th (dismissal) can combine in. */
    private data class SessionInputs(
        val players: Map<String, ServerPlayer>,
        val queues: Map<String, ServerQueue>,
        val selectedId: String?,
        val pending: PendingPlay?,
        val hydration: HydrationState,
    )

    private companion object {
        const val TAG = "MaSession"
        const val SESSION_DEBOUNCE_MS = 50L
        const val PENDING_PLAY_MAX_AGE_MS = 30_000L
    }
}

/** UI-facing playback snapshot derived from the live MA event stream. */
data class MaSession(
    val visiblePlayers: List<MaPlayerSummary>,
    val selectedPlayer: MaPlayerSummary?,
    val nowPlaying: NowPlayingTrack?,
    val elapsedMs: Long?,
    /**
     * Server-side wall-clock (epoch ms) when [elapsedMs] was sampled. Clients
     * extrapolate `elapsedMs + (now - elapsedAsOfEpochMs)` for a smooth scrubber
     * without burning RPCs per second.
     */
    val elapsedAsOfEpochMs: Long?,
    val playbackPhase: PlaybackPhase,
    val hydrationState: HydrationState,
    /** When non-null, the user just tapped play and MA hasn't echoed yet. */
    val pendingPlayUri: String?,
    /**
     * Resolved queue id for the selected player — the target for queue
     * mutations (move / remove / play-index) and `play_media` enqueue
     * (next / add). Null when no player is selected or it has no queue.
     */
    val activeQueueId: String?,
    /** Active queue's repeat mode (off / one / all) — drives the repeat toggle. */
    val repeatMode: RepeatMode,
    /** Active queue's shuffle state — drives the shuffle toggle. */
    val shuffleEnabled: Boolean,
    /** Active queue's "Don't Stop The Music" auto-continuation state. */
    val dontStopTheMusic: Boolean,
    /** `queue_item_id` of the currently-playing item, for highlighting in the queue panel. */
    val currentQueueItemId: String?,
    /** The selected player's Party-plugin source, when one exists. Drives the party toggle. */
    val partySource: MaPartySource?,
) {
    companion object {
        val EMPTY = MaSession(
            visiblePlayers = emptyList(),
            selectedPlayer = null,
            nowPlaying = null,
            elapsedMs = null,
            elapsedAsOfEpochMs = null,
            playbackPhase = PlaybackPhase.Stopped,
            hydrationState = HydrationState.Idle,
            pendingPlayUri = null,
            activeQueueId = null,
            repeatMode = RepeatMode.OFF,
            shuffleEnabled = false,
            dontStopTheMusic = false,
            currentQueueItemId = null,
            partySource = null,
        )
    }
}

/**
 * The Party-plugin source on a player, plus a [defaultSourceId] to fall back to
 * when leaving party mode. [active] reflects whether the player is currently on
 * the party source.
 */
data class MaPartySource(
    val id: String,
    val name: String,
    val playerId: String,
    val active: Boolean,
    val defaultSourceId: String?,
)

data class MaPlayerSummary(
    val id: String,
    val name: String,
    val provider: String,
    val isPlaying: Boolean,
    /** Current volume 0–100, or null when the player reports none. */
    val volumeLevel: Int? = null,
    val volumeMuted: Boolean = false,
    /** Whether this player exposes a volume control (drives slider visibility). */
    val supportsVolume: Boolean = false,
    /** Player ids AND provider-instance ids this player can be sync-grouped with. */
    val canGroupWith: Set<String> = emptySet(),
    /** Whether MA advertises the `set_members` feature (i.e. it can lead/join a sync group). */
    val supportsGrouping: Boolean = false,
    /** The leader this player is currently synced to, if it's a follower. */
    val syncedToPlayerId: String? = null,
    /** Players currently synced under this one, if it's a group leader. */
    val groupMemberIds: Set<String> = emptySet(),
    /** The group this player is currently captured by, if any (MA `active_group`). */
    val activeGroup: String? = null,
)

data class NowPlayingTrack(
    val uri: String,
    val title: String?,
    val artist: String?,
    val artworkUrl: String?,
    val durationMs: Long?,
)

enum class PlaybackPhase {
    /** Track loaded, waiting to play. */
    Idle,

    /** User just tapped play, MA loading queue + dispatching audio. */
    Preparing,
    Playing,
    Paused,

    /** No track loaded at all. */
    Stopped,
}

enum class HydrationState { Idle, InFlight, Loaded, Error }
