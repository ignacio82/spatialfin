package dev.spatialfin.unified.music

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.data.musicassistant.data.model.server.ServerQueueItem
import dev.jdtech.jellyfin.data.musicassistant.repository.MaSessionRepository
import dev.jdtech.jellyfin.data.musicassistant.repository.MusicAssistantRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Drives the queue panel inside [MaNowPlayingScreen]. Two inputs feed it:
 *
 *  - [MaSessionRepository.session] for the resolved `activeQueueId` and the
 *    currently-playing `queue_item_id` (highlight).
 *  - [MaSessionRepository.queueEvents] to refetch the ordered item list when
 *    the server reports a mutation — the session only carries the *current*
 *    item, not the full list, so a reorder/removal of upcoming tracks (by us
 *    or by another client) only surfaces through a refetch.
 *
 * Mutations are optimistic: the local list updates immediately, the RPC fires,
 * and the subsequent `QUEUE_UPDATED` reconciles. A failed RPC is logged; the
 * refetch (or next event) corrects the drift.
 */
@HiltViewModel
class MaQueueViewModel @Inject constructor(
    private val sessionRepo: MaSessionRepository,
    private val repository: MusicAssistantRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(MaQueueState())
    val state: StateFlow<MaQueueState> = _state.asStateFlow()

    private val _partyJoin = MutableStateFlow(PartyJoinUiState())
    /** Share-QR state: the join URL plus enough context to message the user when it's absent. */
    val partyJoin: StateFlow<PartyJoinUiState> = _partyJoin.asStateFlow()

    /** Tracks the queue we're currently bound to, so we only full-reload on change. */
    private var boundQueueId: String? = null

    init {
        viewModelScope.launch {
            sessionRepo.session
                .map { Triple(it.activeQueueId, it.currentQueueItemId, it.partySource?.active == true) }
                .distinctUntilChanged()
                .collect { (queueId, currentItemId, partyActive) ->
                    val queueChanged = queueId != boundQueueId
                    boundQueueId = queueId
                    _state.update {
                        it.copy(queueId = queueId, currentItemId = currentItemId, partyActive = partyActive)
                    }
                    when {
                        queueId == null -> _state.update { it.copy(items = emptyList(), loading = false) }
                        queueChanged -> reload(queueId)
                    }
                }
        }
        viewModelScope.launch {
            sessionRepo.queueEvents.collect { changedId ->
                if (changedId == boundQueueId) reload(changedId)
            }
        }
    }

    private suspend fun reload(queueId: String) {
        _state.update { it.copy(loading = it.items.isEmpty()) }
        val items = repository.getQueueItems(queueId)
        // Guard against a queue switch that raced the fetch.
        if (queueId != boundQueueId) return
        _state.update { it.copy(items = items, loading = false) }
    }

    /** Jump playback to [item]. */
    fun jumpTo(item: ServerQueueItem) {
        val queueId = boundQueueId ?: return
        viewModelScope.launch {
            if (!repository.playQueueIndex(queueId, item.queueItemId)) {
                Timber.tag(TAG).w("play_index failed for %s", item.queueItemId)
            }
        }
    }

    fun moveEarlier(item: ServerQueueItem) = move(item, -1)

    fun moveLater(item: ServerQueueItem) = move(item, 1)

    private fun move(item: ServerQueueItem, shift: Int) {
        val queueId = boundQueueId ?: return
        val items = _state.value.items
        val index = items.indexOfFirst { it.queueItemId == item.queueItemId }
        val target = index + shift
        if (index < 0 || target !in items.indices) return
        // Optimistic swap.
        val reordered = items.toMutableList().apply { add(target, removeAt(index)) }
        _state.update { it.copy(items = reordered) }
        viewModelScope.launch {
            if (!repository.moveQueueItem(queueId, item.queueItemId, shift)) {
                Timber.tag(TAG).w("move_item failed for %s", item.queueItemId)
                reload(queueId)
            }
        }
    }

    /**
     * Fetch the Party plugin guest join URL (for the share QR). Safe to call
     * repeatedly; flips to [PartyJoinUiState.loading] while in flight so the UI
     * can show a spinner instead of a premature "unavailable" message.
     */
    fun loadPartyUrl() {
        viewModelScope.launch {
            _partyJoin.update { it.copy(loading = true) }
            val info = repository.getPartyJoinInfo()
            _partyJoin.value = PartyJoinUiState(
                url = info.url,
                pluginAvailable = info.pluginAvailable,
                loading = false,
                loaded = true,
            )
        }
    }

    fun remove(item: ServerQueueItem) {
        val queueId = boundQueueId ?: return
        // Optimistic removal.
        _state.update { s -> s.copy(items = s.items.filterNot { it.queueItemId == item.queueItemId }) }
        viewModelScope.launch {
            if (!repository.removeQueueItem(queueId, item.queueItemId)) {
                Timber.tag(TAG).w("delete_item failed for %s", item.queueItemId)
                reload(queueId)
            }
        }
    }

    private companion object {
        const val TAG = "MaQueue"
    }
}

/**
 * UI state for the party share-QR.
 *
 *  - [url] non-null → render the QR.
 *  - [loaded] && url == null && [pluginAvailable] → plugin loaded but guest
 *    access is off → prompt the user to enable it.
 *  - [loaded] && url == null && !pluginAvailable → Party plugin not installed.
 */
data class PartyJoinUiState(
    val url: String? = null,
    val pluginAvailable: Boolean = false,
    val loading: Boolean = false,
    val loaded: Boolean = false,
)

data class MaQueueState(
    val queueId: String? = null,
    val items: List<ServerQueueItem> = emptyList(),
    val currentItemId: String? = null,
    val loading: Boolean = false,
    /** True when the active player is on the Party-plugin source — this is the party queue. */
    val partyActive: Boolean = false,
)
