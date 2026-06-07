package dev.spatialfin.sendspin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.data.musicassistant.api.Request
import dev.jdtech.jellyfin.data.musicassistant.api.ServiceClient
import dev.jdtech.jellyfin.data.musicassistant.data.model.server.ServerQueueItem
import dev.jdtech.jellyfin.data.musicassistant.data.model.server.events.QueueItemsUpdatedEvent
import dev.jdtech.jellyfin.data.musicassistant.data.model.server.events.QueueUpdatedEvent
import dev.jdtech.jellyfin.data.musicassistant.utils.myJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.decodeFromJsonElement
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class SendspinQueueViewModel @Inject constructor(
    private val serviceClient: ServiceClient
) : ViewModel() {

    private val _queueItems = MutableStateFlow<List<ServerQueueItem>>(emptyList())
    val queueItems: StateFlow<List<ServerQueueItem>> = _queueItems.asStateFlow()

    private var currentQueueId: String? = null

    init {
        viewModelScope.launch {
            serviceClient.events.collect { event ->
                if (event is QueueItemsUpdatedEvent) {
                    if (event.objectId == currentQueueId) {
                        refreshQueue()
                    }
                } else if (event is QueueUpdatedEvent) {
                    if (event.objectId == currentQueueId) {
                        refreshQueue()
                    }
                }
            }
        }
    }

    fun setQueueId(queueId: String?) {
        if (currentQueueId == queueId) return
        currentQueueId = queueId
        if (queueId != null) {
            refreshQueue()
        } else {
            _queueItems.value = emptyList()
        }
    }

    fun refreshQueue() {
        val queueId = currentQueueId ?: return
        viewModelScope.launch {
            try {
                val result = serviceClient.sendRequest(Request.Queue.items(queueId))
                if (result.isSuccess) {
                    val items = result.getOrNull()?.resultAs<List<ServerQueueItem>>() ?: emptyList()
                    _queueItems.value = items
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to refresh queue")
            }
        }
    }
    
    fun playItem(queueItemId: String) {
        val queueId = currentQueueId ?: return
        viewModelScope.launch {
            serviceClient.sendRequest(Request.Queue.playIndex(queueId, queueItemId))
        }
    }

    fun moveItem(queueItemId: String, positionShift: Int) {
        val queueId = currentQueueId ?: return
        viewModelScope.launch {
            serviceClient.sendRequest(Request.Queue.moveItem(queueId, queueItemId, positionShift))
        }
    }

    fun removeItem(queueItemId: String) {
        val queueId = currentQueueId ?: return
        viewModelScope.launch {
            serviceClient.sendRequest(Request.Queue.removeItem(queueId, queueItemId))
        }
    }
    
    fun clearQueue() {
        val queueId = currentQueueId ?: return
        viewModelScope.launch {
            serviceClient.sendRequest(Request.Queue.clear(queueId))
        }
    }
}
