package dev.jdtech.jellyfin.utils

import android.content.Context
import androidx.work.WorkManager
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.downloads.DownloadStorageManager
import dev.jdtech.jellyfin.models.DownloadTaskDto
import dev.jdtech.jellyfin.models.DownloadTaskKind
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.settings.domain.models.Preference
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DownloadIntegrityVerificationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val database = mockk<ServerDatabaseDao>(relaxed = true)
    private val repository = mockk<JellyfinRepository>(relaxed = true)
    private val appPreferences = mockk<AppPreferences>(relaxed = true)
    private val workManager = mockk<WorkManager>(relaxed = true)
    private val storageManager = mockk<DownloadStorageManager>(relaxed = true)

    @Before
    fun setUp() {
        val pref = Preference("pref_download_over_mobile_data", false)
        every { appPreferences.downloadOverMobileData } returns pref
        every { appPreferences.sharedPreferences } returns mockk(relaxed = true)
    }

    @Test
    fun reportCalculatesAllHealthyCorrectly() {
        val healthyReport = DownloadVerificationReport(
            totalChecked = 5,
            intactCount = 5,
            missingOrCorruptedCount = 0,
            requeuedForRepairCount = 0,
        )
        assertTrue(healthyReport.allHealthy)

        val brokenReport = DownloadVerificationReport(
            totalChecked = 5,
            intactCount = 4,
            missingOrCorruptedCount = 1,
            requeuedForRepairCount = 1,
        )
        assertFalse(brokenReport.allHealthy)

        val emptyReport = DownloadVerificationReport(
            totalChecked = 0,
            intactCount = 0,
            missingOrCorruptedCount = 0,
            requeuedForRepairCount = 0,
        )
        assertFalse(emptyReport.allHealthy)
    }

    @Test
    fun verifyDownloadIntegrityDetectsMissingFileAndRequeues() = runTest {
        val missingFile = File(tempFolder.root, "missing_movie.mp4")
        val task = DownloadTaskDto(
            id = "task-1",
            itemId = UUID.randomUUID(),
            sourceId = "source-1",
            kind = DownloadTaskKind.PRIMARY,
            mediaStreamId = null,
            downloadId = null,
            requestUrl = "https://example.com/video",
            accessToken = null,
            tempPath = File(tempFolder.root, "temp_movie.mp4").absolutePath,
            finalPath = missingFile.absolutePath,
            bytesDownloaded = 1000L,
            totalBytes = 1000L,
            eTag = null,
            lastModified = null,
            status = android.app.DownloadManager.STATUS_SUCCESSFUL,
            progress = 100,
            errorMessage = null,
            updatedAt = System.currentTimeMillis(),
        )

        every { database.getCompletedDownloadTasks(DownloadTaskKind.PRIMARY) } returns listOf(task)
        every { database.getCompletedDownloadTasks(DownloadTaskKind.SUBTITLE) } returns emptyList()

        val downloader = DownloaderImpl(
            context = context,
            database = database,
            jellyfinRepository = repository,
            appPreferences = appPreferences,
            workManager = workManager,
            downloadStorageManager = storageManager,
        )

        val report = downloader.verifyDownloadIntegrity()

        assertEquals(1, report.totalChecked)
        assertEquals(0, report.intactCount)
        assertEquals(1, report.missingOrCorruptedCount)
        assertEquals(1, report.requeuedForRepairCount)
        verify { database.setSourcePath("source-1", task.tempPath) }
        verify {
            database.updateDownloadTask(
                id = "task-1",
                downloadId = any(),
                bytesDownloaded = 0L,
                totalBytes = 1000L,
                eTag = any(),
                lastModified = any(),
                status = android.app.DownloadManager.STATUS_PENDING,
                progress = 0,
                errorMessage = any(),
                updatedAt = any(),
            )
        }
    }

    @Test
    fun verifyDownloadIntegrityDetectsSizeMismatchAndRequeues() = runTest {
        val truncatedFile = tempFolder.newFile("truncated.mp4").apply {
            writeBytes(ByteArray(500))
        }
        val task = DownloadTaskDto(
            id = "task-2",
            itemId = UUID.randomUUID(),
            sourceId = "source-2",
            kind = DownloadTaskKind.PRIMARY,
            mediaStreamId = null,
            downloadId = null,
            requestUrl = "https://example.com/video",
            accessToken = null,
            tempPath = File(tempFolder.root, "temp_truncated.mp4").absolutePath,
            finalPath = truncatedFile.absolutePath,
            bytesDownloaded = 500L,
            totalBytes = 1000L, // Expected 1000, actual 500
            eTag = null,
            lastModified = null,
            status = android.app.DownloadManager.STATUS_SUCCESSFUL,
            progress = 100,
            errorMessage = null,
            updatedAt = System.currentTimeMillis(),
        )

        every { database.getCompletedDownloadTasks(DownloadTaskKind.PRIMARY) } returns listOf(task)
        every { database.getCompletedDownloadTasks(DownloadTaskKind.SUBTITLE) } returns emptyList()

        val downloader = DownloaderImpl(
            context = context,
            database = database,
            jellyfinRepository = repository,
            appPreferences = appPreferences,
            workManager = workManager,
            downloadStorageManager = storageManager,
        )

        val report = downloader.verifyDownloadIntegrity()

        assertEquals(1, report.totalChecked)
        assertEquals(0, report.intactCount)
        assertEquals(1, report.missingOrCorruptedCount)
        assertEquals(1, report.requeuedForRepairCount)
        verify { database.setSourcePath("source-2", task.tempPath) }
        assertFalse(truncatedFile.exists()) // Should be deleted upon requeue
    }

    @Test
    fun verifyDownloadIntegrityIdentifiesIntactFiles() = runTest {
        val intactFile = tempFolder.newFile("intact.srt").apply {
            writeBytes(ByteArray(300))
        }
        val streamId = UUID.randomUUID()
        val task = DownloadTaskDto(
            id = "task-3",
            itemId = UUID.randomUUID(),
            sourceId = "source-3",
            kind = DownloadTaskKind.SUBTITLE,
            mediaStreamId = streamId,
            downloadId = null,
            requestUrl = "https://example.com/sub",
            accessToken = null,
            tempPath = File(tempFolder.root, "temp_sub.srt").absolutePath,
            finalPath = intactFile.absolutePath,
            bytesDownloaded = 300L,
            totalBytes = 300L,
            eTag = null,
            lastModified = null,
            status = android.app.DownloadManager.STATUS_SUCCESSFUL,
            progress = 100,
            errorMessage = null,
            updatedAt = System.currentTimeMillis(),
        )

        every { database.getCompletedDownloadTasks(DownloadTaskKind.PRIMARY) } returns emptyList()
        every { database.getCompletedDownloadTasks(DownloadTaskKind.SUBTITLE) } returns listOf(task)

        val downloader = DownloaderImpl(
            context = context,
            database = database,
            jellyfinRepository = repository,
            appPreferences = appPreferences,
            workManager = workManager,
            downloadStorageManager = storageManager,
        )

        val report = downloader.verifyDownloadIntegrity()

        assertEquals(1, report.totalChecked)
        assertEquals(1, report.intactCount)
        assertEquals(0, report.missingOrCorruptedCount)
        assertEquals(0, report.requeuedForRepairCount)
        assertTrue(intactFile.exists())
    }

    @Test
    fun verifyDownloadIntegrityHealsTranscodedSizeDifferenceWhenBytesDownloadedMatches() = runTest {
        val transcodedFile = tempFolder.newFile("Episode_1_transcoded.mp4").apply {
            writeBytes(ByteArray(640))
        }
        val task = DownloadTaskDto(
            id = "task-transcoded",
            itemId = UUID.randomUUID(),
            sourceId = "source-transcoded",
            kind = DownloadTaskKind.PRIMARY,
            mediaStreamId = null,
            downloadId = null,
            requestUrl = "https://example.com/Videos/123/stream.mp4?Static=false",
            accessToken = null,
            tempPath = File(tempFolder.root, "Episode_1_transcoded.mp4.download").absolutePath,
            finalPath = transcodedFile.absolutePath,
            bytesDownloaded = 640L,
            totalBytes = 2048L, // Original server source size before transcoding
            eTag = null,
            lastModified = null,
            status = android.app.DownloadManager.STATUS_SUCCESSFUL,
            progress = 100,
            errorMessage = null,
            updatedAt = System.currentTimeMillis(),
            isEncrypted = true, // Skip Robolectric MediaExtractor stub check while testing size healing
        )

        every { database.getCompletedDownloadTasks(DownloadTaskKind.PRIMARY) } returns listOf(task)
        every { database.getCompletedDownloadTasks(DownloadTaskKind.SUBTITLE) } returns emptyList()

        val downloader = DownloaderImpl(
            context = context,
            database = database,
            jellyfinRepository = repository,
            appPreferences = appPreferences,
            workManager = workManager,
            downloadStorageManager = storageManager,
        )

        val report = downloader.verifyDownloadIntegrity()

        assertEquals(1, report.totalChecked)
        assertEquals(1, report.intactCount)
        assertEquals(0, report.missingOrCorruptedCount)
        assertEquals(0, report.requeuedForRepairCount)
        assertTrue(transcodedFile.exists())
        verify {
            database.updateDownloadTask(
                id = "task-transcoded",
                downloadId = null,
                bytesDownloaded = 640L,
                totalBytes = 640L,
                eTag = null,
                lastModified = null,
                status = android.app.DownloadManager.STATUS_SUCCESSFUL,
                progress = 100,
                errorMessage = null,
                updatedAt = any(),
            )
        }
        verify { database.setSourcePath("source-transcoded", transcodedFile.absolutePath) }
    }
}

