package dev.jdtech.jellyfin.setup.presentation.users

import dev.jdtech.jellyfin.companion.CompanionCandidate
import dev.jdtech.jellyfin.companion.CompanionConfigImporter
import dev.jdtech.jellyfin.companion.CompanionImportOutcome
import dev.jdtech.jellyfin.companion.CompanionLiveSyncClient
import dev.jdtech.jellyfin.companion.ImportMode
import dev.jdtech.jellyfin.companion.SelectedCompanionUser
import dev.jdtech.jellyfin.models.User
import dev.jdtech.jellyfin.onboarding.OnboardingFailureCode
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.settings.domain.models.Preference
import dev.jdtech.jellyfin.setup.domain.SetCurrentUserResult
import dev.jdtech.jellyfin.setup.domain.SetupRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
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

    private fun mockPrefs(
        companionUrl: String = "",
        companionToken: String = if (companionUrl.isNotBlank()) "sf-setup-0123456789abcdef0123456789abcdef" else "",
    ): AppPreferences {
        val urlPref = Preference("pref_companion_url", "")
        val tokenPref = Preference("pref_companion_token", "")
        return mockk(relaxed = true) {
            every { this@mockk.companionUrl } returns urlPref
            every { this@mockk.companionToken } returns tokenPref
            every { getValue(urlPref) } returns companionUrl
            every { getValue(tokenPref) } returns companionToken
        }
    }

    private fun mockLiveSync(
        pushFlow: MutableSharedFlow<Unit> = MutableSharedFlow(extraBufferCapacity = 4),
    ): CompanionLiveSyncClient =
        mockk(relaxed = true) {
            every { configChangedEvents } returns pushFlow
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

            val vm =
                UsersViewModel(
                    repository = repo,
                    appPreferences = mockPrefs(),
                    companionConfigImporter = mockk(relaxed = true),
                    companionLiveSyncClient = mockLiveSync(),
                )
            vm.loadUsers("srv-42", "Home", "http://192.168.1.50:8096")
            advanceUntilIdle()

            vm.onAction(UsersAction.OnUserClick(u1))
            advanceUntilIdle()
            assertEquals(UsersEvent.UserActivated, vm.events.first())

            vm.onAction(UsersAction.OnUserClick(u2))
            advanceUntilIdle()
            assertEquals(UsersEvent.NeedsLogin(u2, "Bob", null), vm.events.first())

            vm.onAction(UsersAction.OnPublicUserClick("Charlie"))
            advanceUntilIdle()
            assertEquals(UsersEvent.NeedsLogin(null, "Charlie", null), vm.events.first())

            vm.onAction(UsersAction.OnUserClick(u3))
            advanceUntilIdle()
            assertEquals(UsersEvent.Failed(OnboardingFailureCode.STORAGE_ERROR), vm.events.first())

            vm.onAction(UsersAction.OnDeleteUser(u1))
            advanceUntilIdle()
            coVerify { repo.deleteUser(u1) }
        }

    @Test
    fun `loadUsers exposes needs_relink and pending Companion users deduplicated against local and public users`() =
        runTest(testDispatcher) {
            val ignacioId = UUID.randomUUID()
            val emilieId = UUID.randomUUID()
            val danielleId = UUID.randomUUID()
            val frankPublicId = UUID.randomUUID()

            val repo = mockk<SetupRepository>(relaxed = true)
            coEvery { repo.getUsers("srv-home") } returns listOf(User(ignacioId, "ignacio", "srv-home", "tok-ignacio"))
            coEvery { repo.getPublicUsers("srv-home", "http://192.168.1.90:8096") } returns
                listOf(
                    User(ignacioId, "ignacio", "srv-home"),
                    User(emilieId, "emilie", "srv-home"),
                    User(frankPublicId, "frank", "srv-home"),
                )

            val importer = mockk<CompanionConfigImporter>()
            val ignacioCandidate = CompanionCandidate("srv-home", "Jellyfin", ignacioId, "ignacio", isVerified = true)
            val emilieCandidate = CompanionCandidate("srv-home", "Jellyfin", emilieId, "emilie", isVerified = false, needsRelink = true)
            val spatialfinCandidate = CompanionCandidate("srv-home", "Jellyfin", null, "spatialfin", isVerified = false, needsRelink = false)
            val danielleCandidate = CompanionCandidate("srv-home", "Jellyfin", danielleId, "danielle", isVerified = false, needsRelink = true)
            coEvery {
                importer.fetchCompanionUsersForServer(any(), "srv-home", "http://192.168.1.90:8096", any())
            } returns listOf(ignacioCandidate, emilieCandidate, spatialfinCandidate, danielleCandidate)

            val pushFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
            val vm =
                UsersViewModel(
                    repository = repo,
                    appPreferences = mockPrefs("http://192.168.1.90:1982"),
                    companionConfigImporter = importer,
                    companionLiveSyncClient = mockLiveSync(pushFlow),
                )
            vm.loadUsers("srv-home", "Jellyfin", "http://192.168.1.90:8096")
            advanceUntilIdle()

            assertEquals(listOf("emilie", "spatialfin", "danielle"), vm.state.value.companionUsers.map { it.username })
            assertEquals(listOf("frank"), vm.state.value.publicUsers.map { it.name })
            assertEquals(ignacioCandidate, vm.state.value.companionStatusFor(vm.state.value.users.single()))

            // Tapping needs_relink or pending Companion user opens Sign In with no_verified_user
            vm.onAction(UsersAction.OnCompanionUserClick(emilieCandidate))
            advanceUntilIdle()
            assertEquals(
                UsersEvent.NeedsLogin(emilieId, "emilie", OnboardingFailureCode.PAIRING_NO_VERIFIED_USER),
                vm.events.first(),
            )

            // Tapping broker-disabled verified user opens Sign In with qc_broker_rejected
            val brokerOff = CompanionCandidate("srv-home", "Jellyfin", danielleId, "danielle", isVerified = true, quickConnectBrokerEnabled = false)
            vm.onAction(UsersAction.OnCompanionUserClick(brokerOff))
            advanceUntilIdle()
            assertEquals(
                UsersEvent.NeedsLogin(danielleId, "danielle", OnboardingFailureCode.QC_BROKER_REJECTED),
                vm.events.first(),
            )

            // Config push triggers live reload of Companion users
            pushFlow.tryEmit(Unit)
            advanceUntilIdle()
            coVerify(exactly = 2) {
                importer.fetchCompanionUsersForServer(any(), "srv-home", "http://192.168.1.90:8096", any())
            }
        }

    @Test
    fun `tapping verified Companion user mints session via importer or falls back to NeedsLogin`() =
        runTest(testDispatcher) {
            val emilieId = UUID.randomUUID()
            val emilieReady = CompanionCandidate("srv-home", "Jellyfin", emilieId, "emilie", isVerified = true)
            val repo = mockk<SetupRepository>(relaxed = true)
            coEvery { repo.getUsers("srv-home") } returns emptyList()
            coEvery { repo.getPublicUsers("srv-home", "http://192.168.1.90:8096") } returns emptyList()

            val importer = mockk<CompanionConfigImporter>()
            coEvery {
                importer.fetchCompanionUsersForServer(any(), "srv-home", "http://192.168.1.90:8096", any())
            } returns listOf(emilieReady)
            coEvery {
                importer.importFromEndpoint(
                    endpoint = any(),
                    mode = ImportMode.PAIRING,
                    policy = any(),
                    selectedUser = SelectedCompanionUser("srv-home", emilieId, "emilie"),
                    shareOnly = false,
                    handoffDeadlineElapsedMs = null,
                    beforeCommit = null,
                )
            } returnsMany listOf(
                CompanionImportOutcome.Activated("http://192.168.1.90:8096"),
                CompanionImportOutcome.BrokerFallbackToLogin(
                    serverId = "srv-home",
                    serverName = "Jellyfin",
                    serverAddress = "http://192.168.1.90:8096",
                    prefilledUsername = "emilie",
                    failureCode = OnboardingFailureCode.QC_BROKER_REJECTED,
                ),
            )

            val vm =
                UsersViewModel(
                    repository = repo,
                    appPreferences = mockPrefs("http://192.168.1.90:1982"),
                    companionConfigImporter = importer,
                    companionLiveSyncClient = mockLiveSync(),
                )
            vm.loadUsers("srv-home", "Jellyfin", "http://192.168.1.90:8096")
            advanceUntilIdle()

            vm.onAction(UsersAction.OnCompanionUserClick(emilieReady))
            advanceUntilIdle()
            assertEquals(UsersEvent.UserActivated, vm.events.first())

            vm.onAction(UsersAction.OnCompanionUserClick(emilieReady))
            advanceUntilIdle()
            assertEquals(
                UsersEvent.NeedsLogin(emilieId, "emilie", OnboardingFailureCode.QC_BROKER_REJECTED),
                vm.events.first(),
            )
        }
}
