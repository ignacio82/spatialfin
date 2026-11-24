package dev.jdtech.jellyfin.data.musicassistant.repository

import dev.jdtech.jellyfin.data.musicassistant.api.Answer
import dev.jdtech.jellyfin.data.musicassistant.api.ConnectionInfo
import dev.jdtech.jellyfin.data.musicassistant.api.Request
import dev.jdtech.jellyfin.data.musicassistant.api.ScopedServiceEvent
import dev.jdtech.jellyfin.data.musicassistant.api.ServiceClient
import dev.jdtech.jellyfin.data.musicassistant.data.model.server.EventType
import dev.jdtech.jellyfin.data.musicassistant.data.model.server.PlayerState
import dev.jdtech.jellyfin.data.musicassistant.data.model.server.ServerPlayer
import dev.jdtech.jellyfin.data.musicassistant.data.model.server.events.PlayerAddedEvent
import dev.jdtech.jellyfin.data.musicassistant.utils.SessionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MaSessionRepositoryTest {
    private class FakeServiceClient : ServiceClient {
        override val sessionState = MutableStateFlow<SessionState>(SessionState.Disconnected.Initial)
        override val activeSocketGeneration = MutableStateFlow(1L)
        override val isReadyForCommands = MutableStateFlow(false)
        override val serverBaseUrl = MutableStateFlow<String?>("http://192.168.1.50:8095")
        val mutableEvents = MutableSharedFlow<ScopedServiceEvent>(extraBufferCapacity = 16)
        override val events: Flow<ScopedServiceEvent> = mutableEvents
        override val foregroundEvents: Flow<Unit> = MutableSharedFlow()

        override suspend fun sendRequest(request: Request): Result<Answer> =
            Result.success(Answer(buildJsonObject { put("result", JsonArray(emptyList())) }))
        override suspend fun login(username: String, password: String) = Unit
        override suspend fun authorize(token: String, isAutoLogin: Boolean) = Unit
        override fun logout() = Unit
        override fun resolveImageUrl(path: String, provider: String, isRemotelyAccessible: Boolean): String? = null
        override fun rebaseServerImageUrl(rawUrl: String): String? = rawUrl
        override fun onAppForeground() = Unit
        override fun onAppBackground() = Unit
        override fun disconnectByUser() {
            activeSocketGeneration.value += 1L
            isReadyForCommands.value = false
            sessionState.value = SessionState.Disconnected.ByUser
        }
        override fun disconnectForRebind() {
            activeSocketGeneration.value += 1L
            isReadyForCommands.value = false
            sessionState.value = SessionState.Disconnected.Initial
        }
        override fun connect(connection: ConnectionInfo) = Unit
        override fun onExternalConsumerActive() = Unit
        override fun onPlaybackActive() = Unit
        override fun onExternalConsumerInactive() = Unit
        override fun onPlaybackInactive() = Unit
    }

    @Test
    fun `stale socket generation events are ignored and resetSession clears state`() = runBlocking {
        val client = FakeServiceClient()
        val repo = MaSessionRepository(client)

        client.isReadyForCommands.value = true
        repo.session.first { it.hydrationState == HydrationState.Loaded }

        val player = ServerPlayer(
            playerId = "p-1",
            provider = "sendspin",
            type = "player",
            displayName = "Bedroom",
            available = true,
            enabled = true,
            state = PlayerState.PLAYING,
        )

        // Emit event from stale generation (0 != 1) -> must be dropped
        client.mutableEvents.emit(
            ScopedServiceEvent(
                socketGeneration = 0L,
                event = PlayerAddedEvent(event = EventType.PLAYER_ADDED, data = player),
            )
        )
        delay(100)
        assertTrue(repo.session.value.visiblePlayers.isEmpty())

        // Emit event from current generation (1 == 1) -> accepted
        client.mutableEvents.emit(
            ScopedServiceEvent(
                socketGeneration = 1L,
                event = PlayerAddedEvent(event = EventType.PLAYER_ADDED, data = player),
            )
        )
        val withPlayer = repo.session.first { it.visiblePlayers.any { p -> p.id == "p-1" } }
        assertEquals(1, withPlayer.visiblePlayers.size)

        // resetSession clears players and resets hydration to Idle
        repo.resetSession()
        val cleared = repo.session.first { it.visiblePlayers.isEmpty() && it.hydrationState == HydrationState.Idle }
        assertTrue(cleared.visiblePlayers.isEmpty())
        assertEquals(HydrationState.Idle, cleared.hydrationState)
    }
}
