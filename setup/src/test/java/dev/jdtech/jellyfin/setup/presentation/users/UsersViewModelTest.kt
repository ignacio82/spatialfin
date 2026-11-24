package dev.jdtech.jellyfin.setup.presentation.users

import dev.jdtech.jellyfin.models.User
import dev.jdtech.jellyfin.onboarding.OnboardingFailureCode
import dev.jdtech.jellyfin.setup.domain.SetCurrentUserResult
import dev.jdtech.jellyfin.setup.domain.SetupRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
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
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UsersViewModelTest {

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
    fun `UsersViewModel caches serverId and maps UserActivated, NeedsLogin, OnPublicUserClick, and Failed`() =
        runTest(testDispatcher) {
            val u1 = UUID.randomUUID()
            val u2 = UUID.randomUUID()
            val u3 = UUID.randomUUID()
            val repo = mockk<SetupRepository>(relaxed = true)
            coEvery { repo.getUsers("srv-42") } returns listOf(User(u1, "Alice", "srv-42", "tok"))
            coEvery { repo.getPublicUsers("srv-42", "http://192.168.1.50:8096") } returns emptyList()
            coEvery { repo.setCurrentUser("srv-42", u1) } returns SetCurrentUserResult.Activated
            coEvery { repo.setCurrentUser("srv-42", u2) } returns SetCurrentUserResult.NeedsLogin(u2, "Bob")
            coEvery { repo.setCurrentUser("srv-42", u3) } returns
                SetCurrentUserResult.Failed(OnboardingFailureCode.STORAGE_ERROR)

            val vm = UsersViewModel(repo)
            vm.loadUsers("srv-42", "Home", "http://192.168.1.50:8096")
            advanceUntilIdle()

            vm.onAction(UsersAction.OnUserClick(u1))
            advanceUntilIdle()
            assertEquals(UsersEvent.UserActivated, vm.events.first())

            vm.onAction(UsersAction.OnUserClick(u2))
            advanceUntilIdle()
            assertEquals(UsersEvent.NeedsLogin(u2, "Bob"), vm.events.first())

            vm.onAction(UsersAction.OnPublicUserClick("Charlie"))
            advanceUntilIdle()
            assertEquals(UsersEvent.NeedsLogin(null, "Charlie"), vm.events.first())

            vm.onAction(UsersAction.OnUserClick(u3))
            advanceUntilIdle()
            assertEquals(UsersEvent.Failed(OnboardingFailureCode.STORAGE_ERROR), vm.events.first())

            vm.onAction(UsersAction.OnDeleteUser(u1))
            advanceUntilIdle()
            coVerify { repo.deleteUser(u1) }
        }
}
