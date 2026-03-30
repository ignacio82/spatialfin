package dev.jdtech.jellyfin.core.presentation.downloader

import android.app.DownloadManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.models.BulkDownloadSettings
import dev.jdtech.jellyfin.models.SpatialFinEpisode
import dev.jdtech.jellyfin.models.SpatialFinItem
import dev.jdtech.jellyfin.models.SpatialFinSourceType
import dev.jdtech.jellyfin.utils.BulkDownloadResult
import dev.jdtech.jellyfin.utils.Downloader
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

data class BulkDownloadState(
    val isQueuing: Boolean = false,
    val result: BulkDownloadResult? = null,
)

@HiltViewModel
class DownloaderViewModel @Inject constructor(private val downloader: Downloader) : ViewModel() {
    private val _state = MutableStateFlow(DownloaderState())
    val state = _state.asStateFlow()

    private val _bulkState = MutableStateFlow(BulkDownloadState())
    val bulkState = _bulkState.asStateFlow()

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

    private fun pauseDownload(item: SpatialFinItem) {
        viewModelScope.launch {
            downloader.pauseDownload(item = item)
        }
    }

    private fun resumeDownload(item: SpatialFinItem) {
        viewModelScope.launch {
            downloader.resumeDownload(item = item)
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

    private fun downloadEpisodes(episodes: List<SpatialFinEpisode>, settings: BulkDownloadSettings) {
        viewModelScope.launch {
            _bulkState.emit(BulkDownloadState(isQueuing = true))
            val result = downloader.downloadItems(episodes, settings)
            _bulkState.emit(BulkDownloadState(isQueuing = false, result = result))
        }
    }

    fun onAction(action: DownloaderAction) {
        when (action) {
            is DownloaderAction.Download -> download(action.item, action.request)
            is DownloaderAction.DownloadEpisodes -> downloadEpisodes(action.episodes, action.settings)
            is DownloaderAction.DeleteDownload -> deleteDownload(action.item)
            is DownloaderAction.CancelDownload -> cancelDownload(action.item)
            is DownloaderAction.PauseDownload -> pauseDownload(action.item)
            is DownloaderAction.ResumeDownload -> resumeDownload(action.item)
        }
    }

    override fun onCleared() {
        super.onCleared()
        observeJob?.cancel()
    }
}
