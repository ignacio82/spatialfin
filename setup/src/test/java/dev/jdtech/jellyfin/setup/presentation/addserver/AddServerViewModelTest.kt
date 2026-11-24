package dev.jdtech.jellyfin.setup.presentation.addserver

import dev.jdtech.jellyfin.models.Server
import dev.jdtech.jellyfin.models.ServerAddress
import dev.jdtech.jellyfin.onboarding.OnboardingFailureCode
import dev.jdtech.jellyfin.onboarding.OnboardingFailureException
import dev.jdtech.jellyfin.setup.domain.AddedServer
import dev.jdtech.jellyfin.setup.domain.SetupRepository
import dev.jdtech.jellyfin.setup.lan.LanSetupController
import dev.jdtech.jellyfin.setup.lan.LanSetupMode
import dev.jdtech.jellyfin.setup.lan.LanSetupUiState
import dev.jdtech.jellyfin.setup.lan.LeaseHandle
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.jellyfin.sdk.model.api.ServerDiscoveryInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AddServerViewModelTest {

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
    fun `AddServerViewModel emits immutable discovery snapshots, one-shot lease drain clear, and typed errors`() =
        runTest(testDispatcher) {
            val repo = mockk<SetupRepository>(relaxed = true)
            every { repo.discoverServers() } returns flowOf(
                ServerDiscoveryInfo(id = "s1", name = "Living Room", address = "http://192.168.1.50:8096", endpointAddress = null),
                ServerDiscoveryInfo(id = "s1", name = "Living Room", address = "http://192.168.1.50:8096", endpointAddress = null),
            )
            val addrId = UUID.randomUUID()
            coEvery { repo.addServer("http://192.168.1.50:8096") } returns
                AddedServer(
                    server = Server("s1", "Living Room", addrId, null),
                    address = ServerAddress(addrId, "s1", "http://192.168.1.50:8096"),
                )
            coEvery { repo.addServer("http://bad") } throws
                OnboardingFailureException(OnboardingFailureCode.URL_DNS_UNRESOLVED)

            val lanController = mockk<LanSetupController>(relaxed = true) {
                every { uiState } returns MutableStateFlow(LanSetupUiState())
                every { acquire(any(), any()) } answers { LeaseHandle(UUID.randomUUID(), firstArg()) }
            }

            val vm = AddServerViewModel(repo, lanController)
            vm.discoverServers()
            advanceUntilIdle()
            assertEquals(1, vm.state.value.discoveredServers.size)

            // One-shot entryId clearCompletedDrain guard
            vm.acquireBrowserLease("entry-A", clearCompletedDrain = true)
            vm.acquireBrowserLease("entry-A", clearCompletedDrain = true)
            verify(exactly = 1) { lanController.acquire(LanSetupMode.BROWSER_URL_ENTRY, true) }
            verify(exactly = 1) { lanController.acquire(LanSetupMode.BROWSER_URL_ENTRY, false) }

            vm.onAction(AddServerAction.OnConnectClick("http://bad"))
            advanceUntilIdle()
            assertEquals(OnboardingFailureCode.URL_DNS_UNRESOLVED, vm.state.value.failureCode)

            vm.onAction(AddServerAction.OnConnectClick("http://192.168.1.50:8096"))
            advanceUntilIdle()
            val ev = vm.events.first() as AddServerEvent.Success
            assertEquals("s1", ev.serverId)
            assertEquals("Living Room", ev.serverName)
            assertEquals("http://192.168.1.50:8096", ev.serverAddress)
        }
}
