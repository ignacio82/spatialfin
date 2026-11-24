package dev.jdtech.jellyfin.utils

import android.content.Context
import androidx.work.WorkManager
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.downloads.ActiveDownloadRegistry
import dev.jdtech.jellyfin.downloads.DownloadStorageManager
import dev.jdtech.jellyfin.models.DownloadTaskDto
import dev.jdtech.jellyfin.models.DownloadTaskKind
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.security.ContentKeyManager
import dev.jdtech.jellyfin.session.SessionActivationCoordinator
import dev.jdtech.jellyfin.session.SessionMode
import dev.jdtech.jellyfin.session.SessionState
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.spatialfin.unified.applock.AppLockManager
import dev.spatialfin.unified.applock.AppLockObserver
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DownloaderUnencryptedScopeTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val database = mockk<ServerDatabaseDao>(relaxed = true)
    private val repository = mockk<JellyfinRepository>(relaxed = true)
    private val appPreferences = mockk<AppPreferences>(relaxed = true)
    private val workManager = mockk<WorkManager>(relaxed = true)
    private val storageManager = mockk<DownloadStorageManager>(relaxed = true)
    private val coordinator = mockk<SessionActivationCoordinator>(relaxed = true)
    private val activeDownloadRegistry = ActiveDownloadRegistry()
    private val contentKeyManager = mockk<ContentKeyManager>(relaxed = true)
    private val lockStateFlow = MutableStateFlow(AppLockManager.LockState.UNLOCKED)
    private val appLockObserver = object : AppLockObserver {
        override val lockState = lockStateFlow
        override val enrollmentVersion = MutableStateFlow(0L)
    }

    private val activeServerId = "srv-active"
    private val otherServerId = "srv-other"
    private val userId = UUID.randomUUID()
    private val activeSession = SessionState(
        isLoading = false,
        sessionMode = SessionMode.SERVER_AUTHENTICATED,
        sessionEpoch = 2L,
        activationEpoch = 1L,
        activeServerId = activeServerId,
        activeServerAddress = "https://example.com/",
        activeUserId = userId,
    )

    @Before
    fun setUp() {
        every { coordinator.sessionState } returns MutableStateFlow(activeSession)
        every { coordinator.isActivating } returns AtomicBoolean(false)
        coEvery { coordinator.ensureInitialized() } returns activeSession
    }

    private fun createDownloader() = DownloaderImpl(
        context = context,
        database = database,
        jellyfinRepository = repository,
        appPreferences = appPreferences,
        workManager = workManager,
        downloadStorageManager = storageManager,
        sessionActivationCoordinator = coordinator,
        appLockObserver = appLockObserver,
        activeDownloadRegistry = activeDownloadRegistry,
        contentKeyManager = contentKeyManager,
    )

    private fun unencryptedTask(id: String, finalPath: String) = DownloadTaskDto(
        id = id,
        itemId = UUID.randomUUID(),
        sourceId = "src-$id",
        kind = DownloadTaskKind.PRIMARY,
        mediaStreamId = null,
        downloadId = null,
        requestUrl = "https://example.com/stream.mkv",
        accessToken = null,
        tempPath = "",
        finalPath = finalPath,
        bytesDownloaded = 100L,
        totalBytes = 100L,
        eTag = null,
        lastModified = null,
        status = 8,
        progress = 100,
        errorMessage = null,
        updatedAt = 1_000L,
        isEncrypted = false,
        encryptionIv = null,
    )

    @Test
    fun `count and delete unencrypted downloads scope to active server and return zero when LOCKED`() = runTest {
        val activeTask = unencryptedTask("t-active", "/tmp/active.mkv")
        val otherTask = unencryptedTask("t-other", "/tmp/other.mkv")
        every { database.getUnencryptedDownloads() } returns listOf(activeTask, otherTask)
        every { database.resolveDownloadTaskServerId(activeTask) } returns activeServerId
        every { database.resolveDownloadTaskServerId(otherTask) } returns otherServerId

        val downloader = createDownloader()

        // Locked: both count and delete must return 0 and perform no deletions
        lockStateFlow.value = AppLockManager.LockState.LOCKED
        assertEquals(0, downloader.countUnencryptedDownloads())
        assertEquals(0, downloader.deleteUnencryptedDownloads())
        verify(exactly = 0) { database.deleteDownloadTasksByIds(any()) }

        // Unlocked: count and delete only touch activeServerId tasks
        lockStateFlow.value = AppLockManager.LockState.UNLOCKED
        assertEquals(1, downloader.countUnencryptedDownloads())
        assertEquals(1, downloader.deleteUnencryptedDownloads())
        verify(exactly = 1) { database.deleteDownloadTasksByIds(listOf("t-active")) }
        verify(exactly = 0) { database.deleteDownloadTasksByIds(listOf("t-other")) }
        verify(exactly = 1) { storageManager.deletePhysicalFileAndMediaStore("/tmp/active.mkv") }
        verify(exactly = 0) { storageManager.deletePhysicalFileAndMediaStore("/tmp/other.mkv") }
    }
}
