package dev.jdtech.jellyfin.presentation.network

import dev.jdtech.jellyfin.models.NetworkShareDto
import dev.jdtech.jellyfin.network.NetworkFileClientFactory
import dev.jdtech.jellyfin.repository.NetworkMediaRepository
import dev.jdtech.jellyfin.session.SessionActivationCoordinator
import dev.jdtech.jellyfin.session.SessionChangeReason
import dev.jdtech.jellyfin.session.SessionMode
import dev.jdtech.jellyfin.session.SessionState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AddShareViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `first-run saveShare activates standalone even if addShare flips sessionMode synchronously`() =
        runTest(testDispatcher) {
            val sessionFlow = MutableStateFlow(SessionState(isLoading = false, sessionMode = SessionMode.UNCONFIGURED))
            val coordinator = mockk<SessionActivationCoordinator>(relaxed = true) {
                every { sessionState } returns sessionFlow
            }
            val repo = mockk<NetworkMediaRepository>(relaxed = true)
            coEvery { repo.addShare(any(), any(), any(), any(), any(), any(), any()) } coAnswers {
                // Simulate Room observeNetworkShareCount() flipping sessionMode mid-addShare
                sessionFlow.value = SessionState(isLoading = false, sessionMode = SessionMode.STANDALONE_SOURCES)
                NetworkShareDto(
                    id = "share-1",
                    protocol = "smb",
                    host = "192.168.1.10",
                    shareName = "Movies",
                    path = "smb://192.168.1.10/Movies",
                    displayName = "Movies",
                    username = null,
                    password = null,
                    domain = null,
                    addedAtEpochMs = 0L,
                    lastScannedAtEpochMs = null,
                )
            }

            val vm = AddShareViewModel(repo, mockk<NetworkFileClientFactory>(relaxed = true), coordinator)
            vm.saveShare("smb", "192.168.1.10", "Movies", null, null, null, "Movies", activateStandaloneIfUnconfigured = true)
            advanceUntilIdle()

            verify { repo.triggerBackgroundScanAndEnrich("share-1") }
            coVerify(exactly = 1) { coordinator.activateStandalone(SessionChangeReason.STANDALONE_ONBOARDING) }
        }

    @Test
    fun `configured-session saveShare emits one-shot Saved event`() = runTest(testDispatcher) {
        val sessionFlow = MutableStateFlow(SessionState(isLoading = false, sessionMode = SessionMode.STANDALONE_SOURCES))
        val coordinator = mockk<SessionActivationCoordinator>(relaxed = true) {
            every { sessionState } returns sessionFlow
        }
        val repo = mockk<NetworkMediaRepository>(relaxed = true)
        coEvery { repo.addShare(any(), any(), any(), any(), any(), any(), any()) } returns
            NetworkShareDto(
                id = "share-2",
                protocol = "smb",
                host = "192.168.1.10",
                shareName = "TV",
                path = "smb://192.168.1.10/TV",
                displayName = "TV",
                username = null,
                password = null,
                domain = null,
                addedAtEpochMs = 0L,
                lastScannedAtEpochMs = null,
            )

        val vm = AddShareViewModel(repo, mockk(relaxed = true), coordinator)
        vm.saveShare("smb", "192.168.1.10", "TV", null, null, null, "TV", activateStandaloneIfUnconfigured = true)
        advanceUntilIdle()

        assertEquals(AddShareEvent.Saved, vm.events.first())
        coVerify(exactly = 0) { coordinator.activateStandalone(any()) }
    }
}
