package dev.jdtech.jellyfin.setup.presentation.login

import androidx.lifecycle.SavedStateHandle
import dev.jdtech.jellyfin.companion.CompanionAdminRelinkClient
import dev.jdtech.jellyfin.companion.CompanionCandidate
import dev.jdtech.jellyfin.companion.CompanionConfigImporter
import dev.jdtech.jellyfin.companion.CompanionImportOutcome
import dev.jdtech.jellyfin.companion.CompanionLiveSyncClient
import dev.jdtech.jellyfin.companion.CompanionRelinkResult
import dev.jdtech.jellyfin.companion.ImportMode
import dev.jdtech.jellyfin.models.User
import dev.jdtech.jellyfin.models.companion.CompanionConfig
import dev.jdtech.jellyfin.models.companion.CompanionEndpoint
import dev.jdtech.jellyfin.network.UrlTargetPolicy
import dev.jdtech.jellyfin.onboarding.OnboardingFailureCode
import dev.jdtech.jellyfin.quickconnect.PollResult
import dev.jdtech.jellyfin.quickconnect.QuickConnectClient
import dev.jdtech.jellyfin.quickconnect.QuickConnectInitiated
import dev.jdtech.jellyfin.session.ActivationResult
import dev.jdtech.jellyfin.session.SessionActivationCoordinator
import dev.jdtech.jellyfin.session.SessionChangeReason
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.settings.domain.models.Preference
import dev.jdtech.jellyfin.setup.domain.AuthenticateUserResult
import dev.jdtech.jellyfin.setup.domain.SetupRepository
import dev.jdtech.jellyfin.setup.lan.LanSetupController
import dev.jdtech.jellyfin.setup.lan.LanSetupMode
import dev.jdtech.jellyfin.setup.lan.LanSetupUiState
import dev.jdtech.jellyfin.setup.lan.LeaseHandle
import dev.jdtech.jellyfin.setup.quickconnect.QuickConnectController
import dev.jdtech.jellyfin.setup.quickconnect.QuickConnectState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.jellyfin.sdk.model.api.AuthenticationResult
import org.jellyfin.sdk.model.api.UserDto
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun mockAppPrefs(
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
    fun `bindTarget is idempotent for same target, pins serverId on local QC, and canonicalizes srv- prefix`() =
        runTest(testDispatcher) {
            val userId = UUID.randomUUID()
            val qcClient = mockk<QuickConnectClient>()
            coEvery { qcClient.checkEnabled(any(), any()) } returns true
            coEvery { qcClient.initiate(any(), any()) } returns QuickConnectInitiated("111222", "sec-1")
            coEvery { qcClient.pollOnce(any(), "sec-1", any()) } returns PollResult.Authenticated

            val authMismatch = mockk<AuthenticationResult>(relaxed = true) {
                every { serverId } returns "different-server-id"
                every { accessToken } returns "tok"
                every { user } returns mockk<UserDto>(relaxed = true) {
                    every { id } returns userId
                    every { name } returns "Alice"
                }
            }
            coEvery { qcClient.authenticate(any(), "sec-1", any()) } returns authMismatch

            val repo = mockk<SetupRepository>(relaxed = true)
            val coordinator = mockk<SessionActivationCoordinator>(relaxed = true)
            val lanController = mockk<LanSetupController>(relaxed = true) {
                every { uiState } returns MutableStateFlow(LanSetupUiState())
                every { isActiveSession(any()) } returns false
                every { acquire(any(), any()) } answers { LeaseHandle(UUID.randomUUID(), firstArg()) }
            }

            val factory = QuickConnectController.Factory(
                quickConnectClient = qcClient,
                elapsedRealtimeMs = { 10_000L },
                clockEpochMs = { 1_700_000_000_000L },
                pollIntervalMs = 50L,
            )
            val vm =
                LoginViewModel(
                    SavedStateHandle(),
                    repo,
                    coordinator,
                    factory,
                    lanController,
                    mockAppPrefs(),
                    mockk(relaxed = true),
                    mockLiveSync(),
                    mockk(relaxed = true),
                )

            vm.bindTarget("canonical-id-1", "Home", "http://192.168.1.50:8096")
            vm.onAction(LoginAction.OnStartQuickConnect)
            runCurrent()
            // Re-binding identical target must NOT reset QC state
            vm.bindTarget("canonical-id-1", "Home", "http://192.168.1.50:8096")
            advanceTimeBy(100L)
            advanceUntilIdle()
            assertEquals(OnboardingFailureCode.SERVER_IDENTITY_MISMATCH, vm.state.value.failureCode)

            // Legacy srv-* placeholder canonicalizes to authResult.serverId
            val authCanonical = mockk<AuthenticationResult>(relaxed = true) {
                every { serverId } returns "canonical-real-id"
                every { accessToken } returns "tok-real"
                every { user } returns mockk<UserDto>(relaxed = true) {
                    every { id } returns userId
                    every { name } returns "Alice"
                }
            }
            coEvery { qcClient.authenticate(any(), "sec-1", any()) } returns authCanonical
            coEvery {
                coordinator.activateServer(
                    serverId = "canonical-real-id",
                    addressUrl = "http://192.168.1.50:8096",
                    reason = SessionChangeReason.QUICK_CONNECT,
                    user = any(),
                    serverName = "Home",
                    addresses = any(),
                    legacyServerIds = listOf("srv-legacy"),
                    handoffDeadlineElapsedMs = any(),
                    isDedicatedToken = true,
                    overwriteUserPreferences = false,
                    companionBaseline = any(),
                    deviceOverrodeSeerrUrl = any(),
                    companionCredentials = any(),
                    shares = any(),
                )
            } returns ActivationResult.Activated("canonical-real-id", userId, "http://192.168.1.50:8096", 1L, 1L)

            vm.bindTarget("srv-legacy", "Home", "http://192.168.1.50:8096")
            vm.onAction(LoginAction.OnStartQuickConnect)
            advanceTimeBy(100L)
            advanceUntilIdle()
            assertNull(vm.state.value.failureCode)
            coVerify(exactly = 1) {
                coordinator.activateServer(
                    serverId = "canonical-real-id",
                    addressUrl = any(),
                    reason = SessionChangeReason.QUICK_CONNECT,
                    user = any(),
                    serverName = any(),
                    addresses = any(),
                    legacyServerIds = listOf("srv-legacy"),
                    handoffDeadlineElapsedMs = any(),
                    isDedicatedToken = true,
                    overwriteUserPreferences = false,
                    companionBaseline = any(),
                    deviceOverrodeSeerrUrl = any(),
                    companionCredentials = any(),
                    shares = any(),
                )
            }
        }

    @Test
    fun `LAN session delegates QC actions, manual login, keep-alive lease, and degrades when session ends`() =
        runTest(testDispatcher) {
            val lanFlow = MutableStateFlow(
                LanSetupUiState(
                    activeSessionId = "sess-1",
                    quickConnectState = QuickConnectState.Active("123456", "s", 0, 1L),
                ),
            )
            val lanController = mockk<LanSetupController>(relaxed = true) {
                every { uiState } returns lanFlow
                every { isActiveSession("sess-1") } returns true
                every { acquire(LanSetupMode.BROWSER_URL_ENTRY, false) } returns
                    LeaseHandle(UUID.randomUUID(), LanSetupMode.BROWSER_URL_ENTRY)
                coEvery { performManualLogin("sess-1", any(), any(), any(), any(), any()) } returns
                    ActivationResult.Activated("srv-1", UUID.randomUUID(), "http://192.168.1.50:8096", 1L, 1L)
            }
            val qcFactory = QuickConnectController.Factory(mockk(relaxed = true))
            val vm = LoginViewModel(
                SavedStateHandle(),
                mockk(relaxed = true),
                mockk(relaxed = true),
                qcFactory,
                lanController,
                mockAppPrefs(),
                mockk(relaxed = true),
                mockLiveSync(),
                mockk(relaxed = true),
            )

            vm.bindTarget("srv-1", "Home", "http://192.168.1.50:8096", lanSetupSessionId = "sess-1")
            runCurrent()
            assertTrue(vm.state.value.quickConnectState is QuickConnectState.Active)

            // Keep-alive lease uses clearCompletedDrain = false
            vm.acquireBrowserKeepAliveLease()
            verify { lanController.acquire(LanSetupMode.BROWSER_URL_ENTRY, false) }

            // ON_STOP does NOT pause LAN session
            vm.onPauseOrStop()
            assertTrue(vm.state.value.quickConnectState is QuickConnectState.Active)

            vm.onAction(LoginAction.OnCancelQuickConnect)
            verify { lanController.cancelQuickConnect("sess-1") }
            vm.onAction(LoginAction.OnRetryQuickConnect)
            verify { lanController.retryQuickConnect("sess-1") }
            vm.onAction(LoginAction.OnLoginClick("alice", "pw"))
            advanceUntilIdle()
            coVerify { lanController.performManualLogin("sess-1", "srv-1", "Home", "http://192.168.1.50:8096", "alice", "pw") }

            // Reactive degradation when activeSessionId clears
            lanFlow.value = LanSetupUiState(activeSessionId = null)
            runCurrent()
            assertEquals(QuickConnectState.Idle, vm.state.value.quickConnectState)
        }

    @Test
    fun `Quick Connect sign-in with Companion relink enabled relinks then activates`() =
        runTest(testDispatcher) {
            val userId = UUID.randomUUID()
            val qcClient = mockk<QuickConnectClient>()
            coEvery { qcClient.checkEnabled(any(), any()) } returns true
            coEvery { qcClient.initiate(any(), any()) } returns QuickConnectInitiated("111222", "sec-1")
            coEvery { qcClient.pollOnce(any(), "sec-1", any()) } returns PollResult.Authenticated
            val authResult = mockk<AuthenticationResult>(relaxed = true) {
                every { serverId } returns "canon-srv-1"
                every { accessToken } returns "jf-tok"
                every { user } returns mockk<UserDto>(relaxed = true) {
                    every { id } returns userId
                    every { name } returns "ignacio"
                }
            }
            coEvery { qcClient.authenticate(any(), "sec-1", any()) } returns authResult

            val relinkClient = mockk<CompanionAdminRelinkClient>()
            coEvery {
                relinkClient.relinkUser(
                    companionBaseUrl = "http://192.168.1.90:1982",
                    adminPassword = "admin-pw",
                    serverId = "canon-srv-1",
                    jellyfinBaseUrl = "http://192.168.1.90:8096",
                    jellyfinAccessToken = "jf-tok",
                    jellyfinUsername = "ignacio",
                    jellyfinPassword = null,
                    enableQuickConnectBroker = false,
                )
            } returns CompanionRelinkResult.Success("ignacio")

            val coordinator = mockk<SessionActivationCoordinator>(relaxed = true)
            coEvery {
                coordinator.activateServer(
                    serverId = "canon-srv-1",
                    addressUrl = "http://192.168.1.90:8096",
                    reason = SessionChangeReason.QUICK_CONNECT,
                    user = any(),
                    serverName = "Jellyfin",
                    addresses = any(),
                    legacyServerIds = emptyList(),
                    handoffDeadlineElapsedMs = any(),
                    isDedicatedToken = true,
                    overwriteUserPreferences = false,
                    companionBaseline = any(),
                    deviceOverrodeSeerrUrl = any(),
                    companionCredentials = any(),
                    shares = any(),
                )
            } returns ActivationResult.Activated("canon-srv-1", userId, "http://192.168.1.90:8096", 1L, 1L)

            val lanController = mockk<LanSetupController>(relaxed = true) {
                every { uiState } returns MutableStateFlow(LanSetupUiState())
                every { isActiveSession(any()) } returns false
            }
            val factory = QuickConnectController.Factory(
                quickConnectClient = qcClient,
                elapsedRealtimeMs = { 10_000L },
                clockEpochMs = { 1_700_000_000_000L },
                pollIntervalMs = 50L,
            )
            val vm =
                LoginViewModel(
                    SavedStateHandle(),
                    mockk(relaxed = true),
                    coordinator,
                    factory,
                    lanController,
                    mockAppPrefs("http://192.168.1.90:1982"),
                    relinkClient,
                    mockLiveSync(),
                    mockk(relaxed = true),
                )

            vm.bindTarget(
                serverId = "canon-srv-1",
                serverName = "Jellyfin",
                serverAddress = "http://192.168.1.90:8096",
                prefilledUsername = "ignacio",
                initialFailureCode = OnboardingFailureCode.PAIRING_NO_VERIFIED_USER,
            )
            assertTrue(vm.state.value.showCompanionRelinkOption)
            vm.onAction(LoginAction.OnCompanionRelinkToggled(true))
            vm.onAction(LoginAction.OnCompanionAdminPasswordChanged("admin-pw"))
            vm.onAction(LoginAction.OnStartQuickConnect)
            advanceTimeBy(100L)
            advanceUntilIdle()

            assertEquals(LoginEvent.CompanionRelinked("ignacio"), vm.events.first())
            coVerify(exactly = 1) {
                coordinator.activateServer(
                    serverId = "canon-srv-1",
                    addressUrl = "http://192.168.1.90:8096",
                    reason = SessionChangeReason.QUICK_CONNECT,
                    user = any(),
                    serverName = "Jellyfin",
                    addresses = any(),
                    legacyServerIds = emptyList(),
                    handoffDeadlineElapsedMs = any(),
                    isDedicatedToken = true,
                    overwriteUserPreferences = false,
                    companionBaseline = any(),
                    deviceOverrodeSeerrUrl = any(),
                    companionCredentials = any(),
                    shares = any(),
                )
            }
        }

    @Test
    fun `Password sign-in with wrong Companion admin password pauses for retry then succeeds without re-authenticating Jellyfin`() =
        runTest(testDispatcher) {
            val userId = UUID.randomUUID()
            val jfUser = User(id = userId, name = "ignacio", serverId = "srv-1", accessToken = "jf-tok")
            val repo = mockk<SetupRepository>(relaxed = true)
            coEvery {
                repo.authenticate("srv-1", "Jellyfin", "http://192.168.1.90:8096", "ignacio", "jf-pw")
            } returns AuthenticateUserResult.Authenticated("srv-1", "Jellyfin", "http://192.168.1.90:8096", jfUser, emptyList())

            val relinkClient = mockk<CompanionAdminRelinkClient>()
            coEvery {
                relinkClient.relinkUser(
                    companionBaseUrl = "http://192.168.1.90:1982",
                    adminPassword = "wrong",
                    serverId = "srv-1",
                    jellyfinBaseUrl = "http://192.168.1.90:8096",
                    jellyfinAccessToken = "jf-tok",
                    jellyfinUsername = "ignacio",
                    jellyfinPassword = "jf-pw",
                    enableQuickConnectBroker = true,
                )
            } returns CompanionRelinkResult.InvalidAdminPassword("Companion admin password was rejected.")
            coEvery {
                relinkClient.relinkUser(
                    companionBaseUrl = "http://192.168.1.90:1982",
                    adminPassword = "correct",
                    serverId = "srv-1",
                    jellyfinBaseUrl = "http://192.168.1.90:8096",
                    jellyfinAccessToken = "jf-tok",
                    jellyfinUsername = "ignacio",
                    jellyfinPassword = "jf-pw",
                    enableQuickConnectBroker = true,
                )
            } returns CompanionRelinkResult.Success("ignacio")

            val coordinator = mockk<SessionActivationCoordinator>(relaxed = true)
            coEvery {
                coordinator.activateServer(
                    serverId = "srv-1",
                    addressUrl = "http://192.168.1.90:8096",
                    reason = SessionChangeReason.MANUAL_LOGIN,
                    user = jfUser,
                    serverName = "Jellyfin",
                    addresses = any(),
                    legacyServerIds = emptyList(),
                    handoffDeadlineElapsedMs = any(),
                    isDedicatedToken = true,
                    overwriteUserPreferences = false,
                    companionBaseline = any(),
                    deviceOverrodeSeerrUrl = any(),
                    companionCredentials = any(),
                    shares = any(),
                )
            } returns ActivationResult.Activated("srv-1", userId, "http://192.168.1.90:8096", 1L, 1L)

            val lanController = mockk<LanSetupController>(relaxed = true) {
                every { uiState } returns MutableStateFlow(LanSetupUiState())
                every { isActiveSession(any()) } returns false
            }
            val vm =
                LoginViewModel(
                    SavedStateHandle(),
                    repo,
                    coordinator,
                    QuickConnectController.Factory(mockk(relaxed = true)),
                    lanController,
                    mockAppPrefs("http://192.168.1.90:1982"),
                    relinkClient,
                    mockLiveSync(),
                    mockk(relaxed = true),
                )

            vm.bindTarget(
                serverId = "srv-1",
                serverName = "Jellyfin",
                serverAddress = "http://192.168.1.90:8096",
                prefilledUsername = "ignacio",
                initialFailureCode = OnboardingFailureCode.QC_BROKER_REJECTED,
            )
            vm.onAction(LoginAction.OnCompanionRelinkToggled(true))
            vm.onAction(LoginAction.OnCompanionAdminPasswordChanged("wrong"))
            vm.onAction(LoginAction.OnLoginClick("ignacio", "jf-pw"))
            advanceUntilIdle()

            assertEquals("ignacio", vm.state.value.pendingSignedInUsername)
            assertTrue(vm.state.value.companionRelinkState is CompanionRelinkState.Failed)
            coVerify(exactly = 0) { coordinator.activateServer(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }

            vm.onAction(LoginAction.OnRetryCompanionRelink("correct"))
            advanceUntilIdle()

            assertNull(vm.state.value.pendingSignedInUsername)
            assertEquals(LoginEvent.CompanionRelinked("ignacio"), vm.events.first())
            coVerify(exactly = 1) { repo.authenticate(any(), any(), any(), any(), any()) }
            coVerify(exactly = 1) {
                coordinator.activateServer(
                    serverId = "srv-1",
                    addressUrl = "http://192.168.1.90:8096",
                    reason = SessionChangeReason.MANUAL_LOGIN,
                    user = jfUser,
                    serverName = "Jellyfin",
                    addresses = any(),
                    legacyServerIds = emptyList(),
                    handoffDeadlineElapsedMs = any(),
                    isDedicatedToken = true,
                    overwriteUserPreferences = false,
                    companionBaseline = any(),
                    deviceOverrodeSeerrUrl = any(),
                    companionCredentials = any(),
                    shares = any(),
                )
            }
        }

    @Test
    fun `Companion relink failure allows Skip and continue to activate pending session immediately`() =
        runTest(testDispatcher) {
            val userId = UUID.randomUUID()
            val jfUser = User(id = userId, name = "ignacio", serverId = "srv-1", accessToken = "jf-tok")
            val repo = mockk<SetupRepository>(relaxed = true)
            coEvery {
                repo.authenticate("srv-1", "Jellyfin", "http://192.168.1.90:8096", "ignacio", "jf-pw")
            } returns AuthenticateUserResult.Authenticated("srv-1", "Jellyfin", "http://192.168.1.90:8096", jfUser, emptyList())

            val relinkClient = mockk<CompanionAdminRelinkClient>()
            coEvery {
                relinkClient.relinkUser(any(), any(), any(), any(), any(), any(), any(), any())
            } returns CompanionRelinkResult.Failed("Companion unreachable", "qc_broker_unreachable")

            val coordinator = mockk<SessionActivationCoordinator>(relaxed = true)
            coEvery {
                coordinator.activateServer(
                    serverId = "srv-1",
                    addressUrl = "http://192.168.1.90:8096",
                    reason = SessionChangeReason.MANUAL_LOGIN,
                    user = jfUser,
                    serverName = "Jellyfin",
                    addresses = any(),
                    legacyServerIds = emptyList(),
                    handoffDeadlineElapsedMs = any(),
                    isDedicatedToken = true,
                    overwriteUserPreferences = false,
                    companionBaseline = any(),
                    deviceOverrodeSeerrUrl = any(),
                    companionCredentials = any(),
                    shares = any(),
                )
            } returns ActivationResult.Activated("srv-1", userId, "http://192.168.1.90:8096", 1L, 1L)

            val lanController = mockk<LanSetupController>(relaxed = true) {
                every { uiState } returns MutableStateFlow(LanSetupUiState())
                every { isActiveSession(any()) } returns false
            }
            val vm =
                LoginViewModel(
                    SavedStateHandle(),
                    repo,
                    coordinator,
                    QuickConnectController.Factory(mockk(relaxed = true)),
                    lanController,
                    mockAppPrefs("http://192.168.1.90:1982"),
                    relinkClient,
                    mockLiveSync(),
                    mockk(relaxed = true),
                )

            vm.bindTarget(
                serverId = "srv-1",
                serverName = "Jellyfin",
                serverAddress = "http://192.168.1.90:8096",
                prefilledUsername = "ignacio",
                initialFailureCode = OnboardingFailureCode.PAIRING_NO_VERIFIED_USER,
            )
            vm.onAction(LoginAction.OnCompanionRelinkToggled(true))
            vm.onAction(LoginAction.OnLoginClick("ignacio", "jf-pw"))
            advanceUntilIdle()

            assertEquals("ignacio", vm.state.value.pendingSignedInUsername)
            vm.onAction(LoginAction.OnSkipCompanionRelinkAndContinue)
            advanceUntilIdle()

            assertNull(vm.state.value.pendingSignedInUsername)
            coVerify(exactly = 1) {
                coordinator.activateServer(
                    serverId = "srv-1",
                    addressUrl = "http://192.168.1.90:8096",
                    reason = SessionChangeReason.MANUAL_LOGIN,
                    user = jfUser,
                    serverName = "Jellyfin",
                    addresses = any(),
                    legacyServerIds = emptyList(),
                    handoffDeadlineElapsedMs = any(),
                    isDedicatedToken = true,
                    overwriteUserPreferences = false,
                    companionBaseline = any(),
                    deviceOverrodeSeerrUrl = any(),
                    companionCredentials = any(),
                    shares = any(),
                )
            }
        }

    @Test
    fun `Live configChangedEvents push while on no_verified_user auto-recovers and clears error`() =
        runTest(testDispatcher) {
            val pushFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
            val liveSync = mockLiveSync(pushFlow)
            val importer = mockk<CompanionConfigImporter>()
            coEvery {
                importer.importFromEndpoint(any(), ImportMode.PAIRING, any(), any(), any(), any(), any())
            } returns CompanionImportOutcome.Activated("http://192.168.1.90:8096")

            val lanController = mockk<LanSetupController>(relaxed = true) {
                every { uiState } returns MutableStateFlow(LanSetupUiState())
                every { isActiveSession(any()) } returns false
            }
            val vm =
                LoginViewModel(
                    SavedStateHandle(),
                    mockk(relaxed = true),
                    mockk(relaxed = true),
                    QuickConnectController.Factory(mockk(relaxed = true)),
                    lanController,
                    mockAppPrefs("http://192.168.1.90:1982"),
                    mockk(relaxed = true),
                    liveSync,
                    importer,
                )

            vm.bindTarget(
                serverId = "canon-srv-1",
                serverName = "Jellyfin",
                serverAddress = "http://192.168.1.90:8096",
                prefilledUsername = "ignacio",
                initialFailureCode = OnboardingFailureCode.PAIRING_NO_VERIFIED_USER,
            )
            runCurrent()
            verify(exactly = 1) { liveSync.ensureConnected() }

            pushFlow.tryEmit(Unit)
            advanceUntilIdle()

            assertNull(vm.state.value.failureCode)
            assertEquals(LoginEvent.CompanionAutoRecovered, vm.events.first())
            coVerify(exactly = 1) {
                importer.importFromEndpoint(any(), ImportMode.PAIRING, any(), any(), any(), any(), any())
            }
        }

    @Test
    fun `onResumeOrStart after pause rechecks Companion and auto-selects matching prefilledUsername`() =
        runTest(testDispatcher) {
            val ignacioId = UUID.randomUUID()
            val emilieId = UUID.randomUUID()
            val endpoint = CompanionEndpoint.parse("http://192.168.1.90:1982", "sf-setup-0123456789abcdef0123456789abcdef")
            val cfg = CompanionConfig(version = 1, setup_token = "sf-setup-0123456789abcdef0123456789abcdef")
            val candidates =
                listOf(
                    CompanionCandidate("canon-srv-1", "Jellyfin", ignacioId, "ignacio"),
                    CompanionCandidate("canon-srv-1", "Jellyfin", emilieId, "emilie"),
                )

            val importer = mockk<CompanionConfigImporter>()
            coEvery {
                importer.importFromEndpoint(any(), ImportMode.PAIRING, any(), any(), any(), any(), any())
            } returns CompanionImportOutcome.NeedsUserSelection(
                config = cfg,
                endpoint = endpoint,
                policy = UrlTargetPolicy.LAN_ONLY,
                candidates = candidates,
            )
            coEvery {
                importer.completeUserSelection(cfg, endpoint, UrlTargetPolicy.LAN_ONLY, "canon-srv-1", ignacioId, any(), "ignacio")
            } returns CompanionImportOutcome.Activated("http://192.168.1.90:8096")

            val lanController = mockk<LanSetupController>(relaxed = true) {
                every { uiState } returns MutableStateFlow(LanSetupUiState())
                every { isActiveSession(any()) } returns false
            }
            val vm =
                LoginViewModel(
                    SavedStateHandle(),
                    mockk(relaxed = true),
                    mockk(relaxed = true),
                    QuickConnectController.Factory(mockk(relaxed = true)),
                    lanController,
                    mockAppPrefs("http://192.168.1.90:1982"),
                    mockk(relaxed = true),
                    mockLiveSync(),
                    importer,
                )

            vm.bindTarget(
                serverId = "canon-srv-1",
                serverName = "Jellyfin",
                serverAddress = "http://192.168.1.90:8096",
                prefilledUsername = "ignacio",
                initialFailureCode = OnboardingFailureCode.PAIRING_NO_VERIFIED_USER,
            )
            vm.onPauseOrStop()
            vm.onResumeOrStart()
            advanceUntilIdle()

            assertNull(vm.state.value.failureCode)
            assertEquals(LoginEvent.CompanionAutoRecovered, vm.events.first())
            coVerify(exactly = 1) {
                importer.completeUserSelection(cfg, endpoint, UrlTargetPolicy.LAN_ONLY, "canon-srv-1", ignacioId, any(), "ignacio")
            }
        }

    @Test
    fun `unverified candidate matching prefilledUsername is not auto-selected and preserves PAIRING_NO_VERIFIED_USER`() =
        runTest(testDispatcher) {
            val ignacioId = UUID.randomUUID()
            val emilieId = UUID.randomUUID()
            val endpoint = CompanionEndpoint.parse("http://192.168.1.90:1982", "sf-setup-0123456789abcdef0123456789abcdef")
            val cfg = CompanionConfig(version = 1, setup_token = "sf-setup-0123456789abcdef0123456789abcdef")
            val candidates =
                listOf(
                    CompanionCandidate("canon-srv-1", "Jellyfin", ignacioId, "ignacio", isVerified = true),
                    CompanionCandidate("canon-srv-1", "Jellyfin", emilieId, "emilie", isVerified = false, needsRelink = true),
                    CompanionCandidate("canon-srv-1", "Jellyfin", null, "spatialfin", isVerified = false, needsRelink = false),
                )

            val importer = mockk<CompanionConfigImporter>()
            coEvery {
                importer.importFromEndpoint(any(), ImportMode.PAIRING, any(), any(), any(), any(), any())
            } returns CompanionImportOutcome.NeedsUserSelection(
                config = cfg,
                endpoint = endpoint,
                policy = UrlTargetPolicy.LAN_ONLY,
                candidates = candidates,
            )
            coEvery {
                importer.completeUserSelection(cfg, endpoint, UrlTargetPolicy.LAN_ONLY, "canon-srv-1", null, any(), "spatialfin")
            } returns CompanionImportOutcome.BrokerFallbackToLogin(
                serverId = "canon-srv-1",
                serverName = "Jellyfin",
                serverAddress = "http://192.168.1.90:8096",
                prefilledUsername = "spatialfin",
                failureCode = OnboardingFailureCode.PAIRING_NO_VERIFIED_USER,
            )

            val lanController = mockk<LanSetupController>(relaxed = true) {
                every { uiState } returns MutableStateFlow(LanSetupUiState())
                every { isActiveSession(any()) } returns false
            }
            val vm =
                LoginViewModel(
                    SavedStateHandle(),
                    mockk(relaxed = true),
                    mockk(relaxed = true),
                    QuickConnectController.Factory(mockk(relaxed = true)),
                    lanController,
                    mockAppPrefs("http://192.168.1.90:1982"),
                    mockk(relaxed = true),
                    mockLiveSync(),
                    importer,
                )

            vm.bindTarget(
                serverId = "canon-srv-1",
                serverName = "Jellyfin",
                serverAddress = "http://192.168.1.90:8096",
                prefilledUsername = "emilie",
                initialFailureCode = OnboardingFailureCode.PAIRING_NO_VERIFIED_USER,
            )
            vm.onAction(LoginAction.OnRetryCompanionPairing)
            advanceUntilIdle()

            // Must NOT auto-select emilie (unverified) and must keep no_verified_user while showing all candidates
            coVerify(exactly = 0) {
                importer.completeUserSelection(any(), any(), any(), any(), emilieId, any(), any())
            }
            assertEquals(OnboardingFailureCode.PAIRING_NO_VERIFIED_USER, vm.state.value.failureCode)
            assertEquals(listOf("ignacio", "emilie", "spatialfin"), vm.state.value.companionSelectionCandidates.map { it.username })

            // Selecting an unverified candidate updates suggestedUsername and preserves no_verified_user
            vm.onAction(LoginAction.OnSelectCompanionCandidate("canon-srv-1", null, "spatialfin"))
            advanceUntilIdle()
            assertEquals("spatialfin", vm.state.value.suggestedUsername)
            assertEquals(OnboardingFailureCode.PAIRING_NO_VERIFIED_USER, vm.state.value.failureCode)
        }

    @Test
    fun `Manual OnRetryCompanionPairing updates failureCode when Companion returns new fallback error`() =
        runTest(testDispatcher) {
            val importer = mockk<CompanionConfigImporter>()
            coEvery {
                importer.importFromEndpoint(any(), ImportMode.PAIRING, any(), any(), any(), any(), any())
            } returns CompanionImportOutcome.BrokerFallbackToLogin(
                serverId = "canon-srv-1",
                serverName = "Jellyfin",
                serverAddress = "http://192.168.1.90:8096",
                prefilledUsername = "ignacio",
                failureCode = OnboardingFailureCode.QC_BROKER_REJECTED,
            )

            val lanController = mockk<LanSetupController>(relaxed = true) {
                every { uiState } returns MutableStateFlow(LanSetupUiState())
                every { isActiveSession(any()) } returns false
            }
            val vm =
                LoginViewModel(
                    SavedStateHandle(),
                    mockk(relaxed = true),
                    mockk(relaxed = true),
                    QuickConnectController.Factory(mockk(relaxed = true)),
                    lanController,
                    mockAppPrefs("http://192.168.1.90:1982"),
                    mockk(relaxed = true),
                    mockLiveSync(),
                    importer,
                )

            vm.bindTarget(
                serverId = "canon-srv-1",
                serverName = "Jellyfin",
                serverAddress = "http://192.168.1.90:8096",
                prefilledUsername = "ignacio",
                initialFailureCode = OnboardingFailureCode.PAIRING_NO_VERIFIED_USER,
            )
            vm.onAction(LoginAction.OnRetryCompanionPairing)
            advanceUntilIdle()

            assertEquals(OnboardingFailureCode.QC_BROKER_REJECTED, vm.state.value.failureCode)
        }
}
