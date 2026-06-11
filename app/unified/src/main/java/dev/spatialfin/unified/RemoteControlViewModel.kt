package dev.spatialfin.unified

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.fcast.discovery.FCastDiscovery
import dev.jdtech.jellyfin.models.SpatialFinMediaStream
import dev.jdtech.jellyfin.models.toSpatialFinMediaStream
import dev.jdtech.jellyfin.repository.JellyfinRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.GeneralCommandType
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.PlaystateCommand
import org.jellyfin.sdk.model.api.SessionInfoDto

@HiltViewModel
class RemoteControlViewModel @Inject constructor(
    application: Application,
    private val repository: JellyfinRepository,
) : AndroidViewModel(application) {

    private val fcastDiscovery = FCastDiscovery(application)

    private val activeSessions = repository.observeSessions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val localReceivers = fcastDiscovery.browseFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val selectedRemoteSessionId = MutableStateFlow<String?>(null)

    val activeRemoteSessions: StateFlow<List<SessionInfoDto>> = combine(
        activeSessions,
        localReceivers,
    ) { sessions, receivers ->
        // localReceivers automatically filters out the current device in FCastDiscovery.kt
        val localDeviceNames = receivers.mapNotNull { it.name }.toSet()

        sessions
            .filter { session ->
                !session.id.isNullOrBlank() &&
                    session.client?.contains("SpatialFin", ignoreCase = true) == true &&
                    session.nowPlayingItem != null &&
                    session.deviceName?.let { deviceName ->
                        localDeviceNames.any { localName ->
                            localName.contains(deviceName, ignoreCase = true) ||
                                deviceName.contains(localName, ignoreCase = true)
                        }
                    } == true
            }
            .sortedWith(
                compareBy<SessionInfoDto> { it.deviceName.orEmpty().lowercase() }
                    .thenBy { it.nowPlayingItem?.name.orEmpty().lowercase() }
            )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeRemoteSession: StateFlow<SessionInfoDto?> = combine(
        activeRemoteSessions,
        selectedRemoteSessionId,
    ) { sessions, selectedId ->
        selectedId
            ?.let { id -> sessions.firstOrNull { it.id == id } }
            ?: sessions.firstOrNull()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val activeMediaStreams: StateFlow<List<SpatialFinMediaStream>> = activeRemoteSession.mapLatest { session ->
        val nowPlaying = session?.nowPlayingItem ?: return@mapLatest emptyList()
        val sessionStreams =
            nowPlaying.mediaStreams
                .orEmpty()
                .map { it.toSpatialFinMediaStream(repository) }
                .filterSelectableRemoteStreams()
        if (sessionStreams.isNotEmpty()) return@mapLatest sessionStreams

        val itemId = nowPlaying.id
        try {
            val mediaSourceId = session.playState?.mediaSourceId
            val sources = repository.getMediaSources(itemId)
            val selectedSource = mediaSourceId
                ?.let { sourceId -> sources.firstOrNull { it.id == sourceId } }
                ?: sources.firstOrNull()
            selectedSource?.mediaStreams.orEmpty().filterSelectableRemoteStreams()
        } catch (e: Exception) {
            emptyList()
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun sendCommand(sessionId: String, command: PlaystateCommand) {
        viewModelScope.launch {
            runCatching { repository.sendPlaystateCommand(sessionId, command) }
        }
    }

    fun sendGeneralCommand(
        sessionId: String,
        command: GeneralCommandType,
        args: Map<String, String>? = null,
    ) {
        viewModelScope.launch {
            runCatching { repository.sendGeneralCommand(sessionId, command, args) }
        }
    }

    fun selectRemoteSession(sessionId: String) {
        selectedRemoteSessionId.value = sessionId
    }

    private fun List<SpatialFinMediaStream>.filterSelectableRemoteStreams(): List<SpatialFinMediaStream> =
        filter { stream ->
            stream.index != null &&
                (stream.type == MediaStreamType.AUDIO || stream.type == MediaStreamType.SUBTITLE)
        }.sortedWith(
            compareBy<SpatialFinMediaStream> { it.type.name }
                .thenBy { it.index ?: Int.MAX_VALUE }
        )
}
