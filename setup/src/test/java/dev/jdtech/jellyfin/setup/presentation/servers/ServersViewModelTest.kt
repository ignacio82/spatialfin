package dev.jdtech.jellyfin.setup.presentation.servers

import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.Server
import dev.jdtech.jellyfin.models.ServerAddress
import dev.jdtech.jellyfin.models.ServerWithAddresses
import dev.jdtech.jellyfin.onboarding.OnboardingFailureCode
import dev.jdtech.jellyfin.session.ActivationResult
import dev.jdtech.jellyfin.setup.domain.ProbedServer
import dev.jdtech.jellyfin.setup.domain.SetCurrentServerResult
import dev.jdtech.jellyfin.setup.domain.SetupRepository
import dev.jdtech.jellyfin.setup.presentation.addresses.ServerAddressesAction
import dev.jdtech.jellyfin.setup.presentation.addresses.ServerAddressesViewModel
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServersViewModelTest {

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
    fun `ServersViewModel maps setCurrentServer and setCurrentAddress outcomes to events`() =
        runTest(testDispatcher) {
            val addrId = UUID.randomUUID()
            val repo = mockk<SetupRepository>(relaxed = true)
            coEvery { repo.setCurrentServer("srv-auth") } returns SetCurrentServerResult.Activated
            coEvery { repo.setCurrentServer("srv-unauth") } returns
                SetCurrentServerResult.NeedsUserSelection("srv-unauth", "Home", "http://192.168.1.50:8096")
            coEvery { repo.setCurrentAddress("srv-1", addrId) } returns
                ActivationResult.Failed(OnboardingFailureCode.URL_TIMEOUT)

            val vm = ServersViewModel(repo)
            vm.onAction(ServersAction.OnServerClick("srv-auth"))
            advanceUntilIdle()
            assertEquals(ServersEvent.ServerChanged, vm.events.first())

            vm.onAction(ServersAction.OnServerClick("srv-unauth"))
            advanceUntilIdle()
            val nav = vm.events.first() as ServersEvent.NavigateToUsers
            assertEquals("srv-unauth", nav.serverId)

            vm.onAction(ServersAction.OnAddressClick("srv-1", addrId))
            advanceUntilIdle()
            assertEquals(ServersEvent.Failed(OnboardingFailureCode.URL_TIMEOUT), vm.events.first())
        }

    @Test
    fun `ServerAddressesViewModel delegates probeServer, rejects identity mismatch, and deduplicates addresses`() =
        runTest(testDispatcher) {
            val existingAddr = ServerAddress(UUID.randomUUID(), "abc-123", "http://192.168.1.50:8096/")
            val srv = Server("abc-123", "Home", existingAddr.id, null)
            val db = mockk<ServerDatabaseDao>(relaxed = true) {
                every { getServerWithAddresses("abc-123") } returns ServerWithAddresses(srv, listOf(existingAddr), null)
                every { getAllServersSync() } returns listOf(srv)
            }
            val repo = mockk<SetupRepository>(relaxed = true)
            coEvery { repo.probeServer("http://other:8096") } returns
                ProbedServer("different-id", "Other", "http://other:8096")
            coEvery { repo.probeServer("http://192.168.1.50:8096") } returns
                ProbedServer("abc123", "Home", "http://192.168.1.50:8096")

            val vm = ServerAddressesViewModel(repo, db)
            vm.loadAddresses("abc-123")
            vm.onAction(ServerAddressesAction.OpenAddAddressDialog)
            Thread.sleep(50L)

            // Mismatched serverId -> SERVER_IDENTITY_MISMATCH
            vm.onAction(ServerAddressesAction.AddAddress("http://other:8096"))
            Thread.sleep(100L)
            assertEquals(OnboardingFailureCode.SERVER_IDENTITY_MISMATCH, vm.state.value.addError)

            // Trailing-slash duplicate of existing address -> closes dialog without duplicate insert
            vm.onAction(ServerAddressesAction.AddAddress("http://192.168.1.50:8096"))
            Thread.sleep(100L)
            assertNull(vm.state.value.addError)
            assertFalse(vm.state.value.isAddDialogOpen)
            verify(exactly = 0) { db.insertServerAddress(any()) }
        }
}
