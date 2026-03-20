package dev.jdtech.jellyfin.core.presentation.downloader

import android.app.DownloadManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.models.SpatialFinItem
import dev.jdtech.jellyfin.models.SpatialFinSourceType
import dev.jdtech.jellyfin.utils.Downloader
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

@HiltViewModel
class DownloaderViewModel @Inject constructor(private val downloader: Downloader) : ViewModel() {
    private val _state = MutableStateFlow(DownloaderState())
    val state = _state.asStateFlow()

    private val eventsChannel = Channel<DownloaderEvent>()
    val events = eventsChannel.receiveAsFlow()

    var downloadId: Long? = null

    private var observeJob: Job? = null
    private var lastTerminalStatus: Int? = null

    fun update(item: SpatialFinItem) {
        observeJob?.cancel()
        lastTerminalStatus = null
        observeJob =
            viewModelScope.launch {
                downloader.observeDownloadStatus(item.id).collectLatest { snapshot ->
                    if (snapshot == null) {
                        downloadId = null
                        lastTerminalStatus = null
                        _state.emit(DownloaderState())
                        return@collectLatest
                    }

                    downloadId = snapshot.downloadId
                    _state.emit(snapshot.state)

                    if (snapshot.state.status != lastTerminalStatus &&
                        snapshot.state.status == DownloadManager.STATUS_SUCCESSFUL
                    ) {
                        lastTerminalStatus = snapshot.state.status
                        eventsChannel.trySend(DownloaderEvent.Successful)
                    }
                }
            }
    }

    private fun download(item: SpatialFinItem, request: dev.jdtech.jellyfin.models.DownloadRequest) {
        viewModelScope.launch {
            lastTerminalStatus = null
            _state.emit(DownloaderState(status = DownloadManager.STATUS_PENDING))
            val uiText = downloader.downloadItem(item = item, request = request)
            if (uiText != null) {
                _state.emit(
                    DownloaderState(status = DownloadManager.STATUS_FAILED, errorText = uiText)
                )
            }
        }
    }

    private fun cancelDownload(item: SpatialFinItem) {
        viewModelScope.launch {
            downloader.cancelDownload(item = item)

            _state.emit(DownloaderState())
        }
    }

    private fun deleteDownload(item: SpatialFinItem) {
        viewModelScope.launch {
            downloader.deleteItem(
                item = item,
                source = item.sources.first { it.type == SpatialFinSourceType.LOCAL },
            )
            eventsChannel.send(DownloaderEvent.Deleted)
        }
    }

    fun onAction(action: DownloaderAction) {
        when (action) {
            is DownloaderAction.Download -> download(action.item, action.request)
            is DownloaderAction.DeleteDownload -> deleteDownload(action.item)
            is DownloaderAction.CancelDownload -> cancelDownload(action.item)
        }
    }

    override fun onCleared() {
        super.onCleared()
        observeJob?.cancel()
    }
}
