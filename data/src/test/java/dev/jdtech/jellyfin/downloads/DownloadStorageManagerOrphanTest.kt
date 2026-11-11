package dev.jdtech.jellyfin.downloads

import android.app.DownloadManager
import android.content.Context
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.DownloadTaskDto
import dev.jdtech.jellyfin.models.DownloadTaskKind
import dev.jdtech.jellyfin.models.SpatialFinEpisode
import dev.jdtech.jellyfin.models.SpatialFinImages
import dev.jdtech.jellyfin.models.SpatialFinSource
import dev.jdtech.jellyfin.models.SpatialFinSourceDto
import dev.jdtech.jellyfin.models.SpatialFinSourceType
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DownloadStorageManagerOrphanTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val database = mockk<ServerDatabaseDao>(relaxed = true)
    private lateinit var manager: DownloadStorageManager

    @Before
    fun setUp() {
        manager = DownloadStorageManager(context, database)
        manager.downloadsRoot().deleteRecursively()
        manager.ensureDownloadsRoot()
    }

    @After
    fun tearDown() {
        manager.downloadsRoot().deleteRecursively()
    }

    @Test
    fun `scanOrphanedFiles and cleanOrphanedFiles remove untracked files while preserving active downloads`() = runTest {
        val activeItemId = UUID.randomUUID()
        val root = manager.ensureDownloadsRoot()
        val activeFile = File(root, "Active_Episode_${activeItemId}_1080p_original.mkv").apply {
            writeBytes(ByteArray(4096))
        }
        val orphanFile = File(root, "Orphan_Movie_${UUID.randomUUID()}_1080p_original.mkv").apply {
            writeBytes(ByteArray(8192))
        }

        val activeSource = SpatialFinSourceDto(
            id = "src-active",
            itemId = activeItemId,
            name = "1080p",
            type = SpatialFinSourceType.LOCAL,
            path = activeFile.absolutePath,
        )
        every { database.getAllSources() } returns listOf(activeSource)
        every { database.getAllMediaStreams() } returns emptyList()
        every { database.getAllDownloadTasks() } returns emptyList()

        // computeTotalDownloadsSize counts only active tracked files
        assertEquals(4096L, manager.computeTotalDownloadsSize())

        val scanReport = manager.scanOrphanedFiles()
        assertEquals(1, scanReport.totalCount)
        assertEquals(8192L, scanReport.totalBytes)
        assertEquals(orphanFile.name, scanReport.entries.first().fileName)

        val cleanupResult = manager.cleanOrphanedFiles()
        assertEquals(1, cleanupResult.deletedCount)
        assertEquals(8192L, cleanupResult.freedBytes)
        assertFalse(orphanFile.exists())
        assertTrue(activeFile.exists())
    }

    @Test
    fun `deleteItem preserves unsynced userdata by calling deleteSyncedUserData`() = runTest {
        val episodeId = UUID.randomUUID()
        val seasonId = UUID.randomUUID()
        val seriesId = UUID.randomUUID()
        val source = SpatialFinSource(
            id = "src-ep",
            name = "1080p",
            type = SpatialFinSourceType.LOCAL,
            path = File(manager.ensureDownloadsRoot(), "ep.mp4").absolutePath,
            size = 1024L,
            mediaStreams = emptyList(),
        )
        val episode = SpatialFinEpisode(
            id = episodeId,
            name = "Episode 1",
            originalTitle = null,
            overview = "",
            sources = listOf(source),
            played = true,
            favorite = false,
            canPlay = true,
            canDownload = true,
            runtimeTicks = 1000L,
            playbackPositionTicks = 0L,
            premiereDate = null,
            seriesName = "Series",
            seriesId = seriesId,
            seasonId = seasonId,
            seasonName = "Season 1",
            indexNumber = 1,
            indexNumberEnd = null,
            parentIndexNumber = 1,
            communityRating = null,
            people = emptyList(),
            chapters = emptyList(),
            images = SpatialFinImages(),
            trickplayInfo = null,
        )

        manager.deleteItem(episode, source, deletePhysicalFile = true)

        verify { database.deleteSyncedUserData(episodeId) }
        verify(exactly = 0) { database.deleteUserData(episodeId) }
    }
}
