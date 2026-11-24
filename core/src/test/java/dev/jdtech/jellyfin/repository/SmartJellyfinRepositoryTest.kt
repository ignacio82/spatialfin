package dev.jdtech.jellyfin.repository

import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.offline.ServerConnectionMonitor
import dev.jdtech.jellyfin.session.SessionActivationCoordinator
import dev.jdtech.jellyfin.session.SessionMode
import dev.jdtech.jellyfin.session.SessionState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartJellyfinRepositoryTest {

    @Test
    fun `falls back to offline repository on connection failure and passes expectedEpoch to monitor`() = runTest {
        val online = mockk<JellyfinRepositoryImpl>(relaxed = true)
        val offline = mockk<JellyfinRepositoryOfflineImpl>(relaxed = true)
        val monitor = mockk<ServerConnectionMonitor>(relaxed = true)
        val jellyfinApi = mockk<JellyfinApi>(relaxed = true)
        val coordinator = mockk<SessionActivationCoordinator>(relaxed = true)

        val userId = UUID.randomUUID()
        val sessionFlow = MutableStateFlow(
            SessionState(
                isLoading = false,
                sessionMode = SessionMode.SERVER_AUTHENTICATED,
                sessionEpoch = 11L,
                activeServerId = "srv-1",
                activeServerAddress = "http://192.168.1.10:8096/",
                activeUserId = userId,
            ),
        )
        every { coordinator.sessionState } returns sessionFlow
        every { coordinator.sessionEpoch } returns AtomicLong(11L)
        every { coordinator.isActivating } returns AtomicBoolean(false)
        every { monitor.shouldUseOfflineRepository() } returns false
        every { monitor.isConnectionFailure(any()) } returns true
        every { jellyfinApi.userId } returns userId
        every { jellyfinApi.api.accessToken } returns "tok-1"

        coEvery { offline.getResumeItems() } returns emptyList()
        coEvery { online.getResumeItems() } throws IOException("Network down")

        val smart = SmartJellyfinRepository(
            onlineRepository = online,
            offlineRepository = offline,
            connectionMonitor = monitor,
            jellyfinApi = jellyfinApi,
            sessionActivationCoordinator = coordinator,
        )

        val result = smart.fetchResumeItems()
        assertTrue(result.isOffline)
        verify(exactly = 1) { monitor.markServerInaccessible(expectedEpoch = 11L) }
        coVerify(exactly = 1) { offline.getResumeItems() }
    }

    @Test
    fun `uses offline repository directly when jellyfinApi has no active user or token`() = runTest {
        val online = mockk<JellyfinRepositoryImpl>(relaxed = true)
        val offline = mockk<JellyfinRepositoryOfflineImpl>(relaxed = true)
        val monitor = mockk<ServerConnectionMonitor>(relaxed = true)
        val jellyfinApi = mockk<JellyfinApi>(relaxed = true)
        val coordinator = mockk<SessionActivationCoordinator>(relaxed = true)

        every { coordinator.sessionState } returns MutableStateFlow(
            SessionState(
                isLoading = false,
                sessionMode = SessionMode.SERVER_NEEDS_AUTH,
                sessionEpoch = 2L,
                activeServerId = "srv-1",
            ),
        )
        every { coordinator.sessionEpoch } returns AtomicLong(2L)
        every { coordinator.isActivating } returns AtomicBoolean(false)
        every { monitor.shouldUseOfflineRepository() } returns false
        every { jellyfinApi.userId } returns null
        every { jellyfinApi.api.accessToken } returns null
        coEvery { offline.getResumeItems() } returns emptyList()

        val smart = SmartJellyfinRepository(
            onlineRepository = online,
            offlineRepository = offline,
            connectionMonitor = monitor,
            jellyfinApi = jellyfinApi,
            sessionActivationCoordinator = coordinator,
        )

        val result = smart.fetchResumeItems()
        assertTrue(result.isOffline)
        coVerify(exactly = 0) { online.getResumeItems() }
        coVerify(exactly = 1) { offline.getResumeItems() }
    }
}
