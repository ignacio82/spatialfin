package dev.jdtech.jellyfin.data.musicassistant.api

import dev.jdtech.jellyfin.data.musicassistant.data.model.server.events.Event
import dev.jdtech.jellyfin.data.musicassistant.utils.SessionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface ServiceClient {
    val sessionState: StateFlow<SessionState>

    suspend fun sendRequest(request: Request): Result<Answer>
    suspend fun login(username: String, password: String)
    suspend fun authorize(token: String, isAutoLogin: Boolean = false)
    fun logout()
    val isReadyForCommands: StateFlow<Boolean>
    val serverBaseUrl: StateFlow<String?>
    fun resolveImageUrl(path: String, provider: String, isRemotelyAccessible: Boolean): String?
    fun rebaseServerImageUrl(rawUrl: String): String?
    val events: Flow<Event<out Any>>

    fun onAppForeground()
    fun onAppBackground()
    val foregroundEvents: Flow<Unit>
    fun disconnectByUser()
    fun connect(connection: ConnectionInfo)
    fun onExternalConsumerActive()
    fun onPlaybackActive()
    fun onExternalConsumerInactive()
    fun onPlaybackInactive()
}
