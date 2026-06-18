package dev.spatialfin.unified

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.models.SpatialFinMediaStream
import dev.jdtech.jellyfin.models.toSpatialFinMediaStream
import dev.jdtech.jellyfin.repository.JellyfinRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
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

    /** Our own Jellyfin device id, so the controlling instance is never listed as a target. */
    private val ownDeviceId: String? = repository.getDeviceId()

    /** Base URL of the connected Jellyfin server, for building remote cover-art URLs. */
    val baseUrl: String get() = repository.getBaseUrl()

    /** Access token for the connected Jellyfin server, for authenticated cover-art requests. */
    val accessToken: String? get() = repository.getAccessToken()

    private val selectedRemoteSessionId = MutableStateFlow<String?>(null)

    /**
     * Every SpatialFin instance on the network that is currently playing something and is
     * remotely controllable, reported authoritatively by the Jellyfin server's live session
     * feed. The previous implementation additionally gated on a fuzzy name-match against
     * FCast mDNS discovery, which silently hid controllable sessions whenever discovery did
     * not see the device — the most common cause of an "invisible" remote. Commands reach the
     * target through the Jellyfin WebSocket relay (handled by SyncPlayCoordinator), so the
     * server's `supportsRemoteControl` flag is the correct, reliable signal.
     */
    val activeRemoteSessions: StateFlow<List<SessionInfoDto>> = repository.observeSessions()
        .map { sessions ->
            sessions
                .filter { session ->
                    !session.id.isNullOrBlank() &&
                        session.supportsRemoteControl &&
                        session.nowPlayingItem != null &&
                        session.client?.contains("SpatialFin", ignoreCase = true) == true &&
                        (ownDeviceId == null || session.deviceId != ownDeviceId)
                }
                .sortedWith(
                    compareBy<SessionInfoDto> { it.deviceName.orEmpty().lowercase() }
                        .thenBy { it.nowPlayingItem?.name.orEmpty().lowercase() }
                )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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
