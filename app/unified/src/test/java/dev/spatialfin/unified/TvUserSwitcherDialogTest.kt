package dev.spatialfin.unified

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelStore
import dev.jdtech.jellyfin.companion.CompanionCandidate
import dev.jdtech.jellyfin.companion.CompanionConfigImporter
import dev.jdtech.jellyfin.companion.CompanionLiveSyncClient
import dev.jdtech.jellyfin.models.Server
import dev.jdtech.jellyfin.models.User
import dev.jdtech.jellyfin.onboarding.OnboardingFailureCode
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.settings.domain.models.Preference
import dev.jdtech.jellyfin.setup.domain.SetCurrentUserResult
import dev.jdtech.jellyfin.setup.domain.SetupRepository
import dev.jdtech.jellyfin.setup.presentation.users.UsersViewModel
import dev.jdtech.jellyfin.viewmodels.MainState
import dev.spatialfin.test.SpatialFinTestApplication
import dev.spatialfin.tv.TvUserSwitcherDialog
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = SpatialFinTestApplication::class)
class TvUserSwitcherDialogTest {
    @get:Rule val compose = createComposeRule()
    private val viewModelStore = ViewModelStore()

    @After
    fun tearDown() {
        viewModelStore.clear()
    }

    private fun mockPrefs(companionUrl: String = ""): AppPreferences {
        val urlPref = Preference("pref_companion_url", "")
        val tokenPref = Preference("pref_companion_token", "")
        val token = if (companionUrl.isNotBlank()) "sf-setup-0123456789abcdef0123456789abcdef" else ""
        return mockk(relaxed = true) {
            every { this@mockk.companionUrl } returns urlPref
            every { this@mockk.companionToken } returns tokenPref
            every { getValue(urlPref) } returns companionUrl
            every { getValue(tokenPref) } returns token
        }
    }

    private fun mockLiveSync(): CompanionLiveSyncClient =
        mockk(relaxed = true) {
            every { configChangedEvents } returns MutableSharedFlow()
        }

    @Test
    fun userSwitchDismissesAfterAsynchronousActivationCompletes() {
        val user = User(UUID.randomUUID(), "Other user", "server")
        val completion = CompletableDeferred<SetCurrentUserResult>()
        val repository = mockk<SetupRepository>()
        coEvery { repository.getUsers("server") } returns listOf(user)
        coEvery { repository.getPublicUsers("server", "http://192.168.1.10:8096") } returns emptyList()
        coEvery { repository.setCurrentUser("server", user.id) } coAnswers { completion.await() }
        val viewModel =
            UsersViewModel(
                repository = repository,
                appPreferences = mockPrefs(),
                companionConfigImporter = mockk(relaxed = true),
                companionLiveSyncClient = mockLiveSync(),
            )
        viewModelStore.put("users", viewModel)
        var shown by mutableStateOf(true)
        compose.setContent {
            if (shown) {
                TvUserSwitcherDialog(
                    state = MainState(),
                    serverId = "server",
                    serverName = "Server",
                    serverAddress = "http://192.168.1.10:8096",
                    isServerAuthenticated = true,
                    onDismissRequest = { shown = false },
                    onNeedsLogin = { _, _ -> },
                    onAddUser = {},
                    onLogout = {},
                    viewModel = viewModel,
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Other user").performClick()
        compose.waitForIdle()

        coVerify(exactly = 1) { repository.setCurrentUser("server", user.id) }
        compose.runOnIdle {
            assertTrue("Keep the dialog open while activation is pending", shown)
            completion.complete(SetCurrentUserResult.Activated)
        }
        compose.waitForIdle()
        compose.runOnIdle { assertFalse("Dismiss dialog once UsersEvent.UserActivated emits", shown) }
    }

    @Test
    fun companionUserNeedingCredentialUpdateShowsAndRoutesToLoginWithNoVerifiedUser() {
        val ignacioId = UUID.randomUUID()
        val emilieId = UUID.randomUUID()
        val repository = mockk<SetupRepository>()
        coEvery { repository.getUsers("srv-home") } returns listOf(User(ignacioId, "ignacio", "srv-home", "tok"))
        coEvery { repository.getPublicUsers("srv-home", "http://192.168.1.90:8096") } returns emptyList()

        val importer = mockk<CompanionConfigImporter>()
        coEvery {
            importer.fetchCompanionUsersForServer(any(), "srv-home", "http://192.168.1.90:8096", any())
        } returns listOf(
            CompanionCandidate("srv-home", "Jellyfin", ignacioId, "ignacio", isVerified = true),
            CompanionCandidate("srv-home", "Jellyfin", emilieId, "emilie", isVerified = false, needsRelink = true),
        )

        val viewModel =
            UsersViewModel(
                repository = repository,
                appPreferences = mockPrefs("http://192.168.1.90:1982"),
                companionConfigImporter = importer,
                companionLiveSyncClient = mockLiveSync(),
            )
        viewModelStore.put("users-relink", viewModel)

        var shown by mutableStateOf(true)
        var capturedUser: String? = null
        var capturedCode: OnboardingFailureCode? = null
        compose.setContent {
            if (shown) {
                TvUserSwitcherDialog(
                    state = MainState(),
                    serverId = "srv-home",
                    serverName = "Jellyfin",
                    serverAddress = "http://192.168.1.90:8096",
                    isServerAuthenticated = true,
                    onDismissRequest = { shown = false },
                    onNeedsLogin = { username, code ->
                        capturedUser = username
                        capturedCode = code
                    },
                    onAddUser = {},
                    onLogout = {},
                    viewModel = viewModel,
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Needs credential update in Companion").assertExists()
        compose.onNodeWithText("emilie").performClick()
        compose.waitForIdle()

        assertEquals("emilie", capturedUser)
        assertEquals(OnboardingFailureCode.PAIRING_NO_VERIFIED_USER, capturedCode)
        assertFalse(shown)
    }
}
