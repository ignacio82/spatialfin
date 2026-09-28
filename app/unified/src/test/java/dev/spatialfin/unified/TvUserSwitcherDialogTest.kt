package dev.spatialfin.unified

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelStore
import dev.jdtech.jellyfin.models.Server
import dev.jdtech.jellyfin.models.User
import dev.jdtech.jellyfin.setup.domain.SetupRepository
import dev.jdtech.jellyfin.setup.presentation.users.UsersViewModel
import dev.jdtech.jellyfin.viewmodels.MainState
import dev.spatialfin.test.SpatialFinTestApplication
import dev.spatialfin.tv.TvUserSwitcherDialog
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
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

    @Test
    fun userSwitchNavigatesAfterAsynchronousLoginCompletes() {
        val user = User(UUID.randomUUID(), "Other user", "server")
        val completion = CompletableDeferred<Unit>()
        val repository = mockk<SetupRepository>()
        coEvery { repository.getCurrentServer() } returns Server("server", "Server", null, null)
        coEvery { repository.getUsers("server") } returns listOf(user)
        coEvery { repository.getPublicUsers("server") } returns emptyList()
        coEvery { repository.getCurrentServerAddress() } returns null
        coEvery { repository.setCurrentUser(user.id) } coAnswers { completion.await() }
        val viewModel = UsersViewModel(repository)
        viewModelStore.put("users", viewModel)
        var shown by mutableStateOf(true)
        var navigations = 0
        compose.setContent {
            if (shown) {
                TvUserSwitcherDialog(
                    state = MainState(),
                    onDismissRequest = { shown = false },
                    onUserSwitched = {
                        navigations++
                        shown = false
                    },
                    onAddUser = {},
                    viewModel = viewModel,
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Other user").performClick()
        compose.waitForIdle()

        coVerify(exactly = 1) { repository.setCurrentUser(user.id) }
        compose.runOnIdle {
            assertTrue("Keep the completion listener alive while login is pending", shown)
            assertEquals(0, navigations)
            completion.complete(Unit)
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, navigations) }
    }
}
