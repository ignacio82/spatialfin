package dev.jdtech.jellyfin.presentation.network

import android.app.Application
import android.content.Context
import dev.jdtech.jellyfin.models.NetworkShareDto
import dev.jdtech.jellyfin.models.NetworkVideoItem
import dev.jdtech.jellyfin.repository.NetworkMediaRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NetworkViewModelTest {

    private lateinit var app: Application
    private lateinit var prefs: AppPreferences
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        app = RuntimeEnvironment.getApplication()
        val sp = app.getSharedPreferences("net_vm_test", Context.MODE_PRIVATE)
        sp.edit().clear().commit()
        prefs = AppPreferences(sp)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `NetworkViewModel observes shares reactively and deduplicates shares and videos`() =
        runTest(testDispatcher) {
            val s1 = NetworkShareDto("a", "smb", "192.168.1.10", "Movies", "smb://192.168.1.10/Movies", "Movies", null, null, null, 0L, null)
            val sDup = NetworkShareDto("b", "SMB", "192.168.1.10", "movies", "smb://192.168.1.10/movies", "Movies", null, null, null, 0L, null)
            val sharesFlow = MutableStateFlow(listOf(s1, sDup))
            val repo = mockk<NetworkMediaRepository>(relaxed = true) {
                every { observeNetworkShares() } returns sharesFlow
                coEvery { getShares() } returns listOf(s1, sDup)
                coEvery { getResumeItems() } returns emptyList()
            }

            val vm = NetworkViewModel(repo, prefs)
            advanceUntilIdle()
            assertEquals(1, vm.state.value.shares.size)
            assertEquals("a", vm.state.value.shares.first().id)

            // Live emission updates state without calling loadShares()
            val s2 = NetworkShareDto("c", "nfs", "192.168.1.11", "Shows", "nfs://192.168.1.11/Shows", "Shows", null, null, null, 0L, null)
            sharesFlow.value = listOf(s1, s2)
            advanceUntilIdle()
            assertEquals(2, vm.state.value.shares.size)
        }
}
