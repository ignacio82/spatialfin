package dev.jdtech.jellyfin.settings.domain.llm

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class LlmDownloadManagerTest {

    private lateinit var context: Context
    private lateinit var okHttpClient: OkHttpClient
    private lateinit var manager: LlmDownloadManager

    @Before
    fun setup() {
        context = mockk()
        okHttpClient = mockk()

        // Mock context.filesDir to a temporary directory for tests
        val tempDir = kotlin.io.path.createTempDirectory("testDir").toFile()
        every { context.filesDir } returns tempDir

        manager = LlmDownloadManager(context, okHttpClient)
    }

    @Test
    fun `test updateModelState changes modelState to Ready`() = runTest {
        // Initially it should be Idle
        assertEquals(ModelState.Idle, manager.modelState.value)

        // Update to Ready state
        val readyState = ModelState.Ready("test-backend")
        manager.updateModelState(readyState)

        // Verify the state is updated
        assertEquals(readyState, manager.modelState.value)
    }

    @Test
    fun `test updateModelState changes modelState to Initializing`() = runTest {
        assertEquals(ModelState.Idle, manager.modelState.value)

        val initiaState = ModelState.Initializing
        manager.updateModelState(initiaState)

        assertEquals(initiaState, manager.modelState.value)
    }

    @Test
    fun `test updateModelState changes modelState to Error`() = runTest {
        assertEquals(ModelState.Idle, manager.modelState.value)

        val errorState = ModelState.Error("Test error")
        manager.updateModelState(errorState)

        assertEquals(errorState, manager.modelState.value)
    }
}
