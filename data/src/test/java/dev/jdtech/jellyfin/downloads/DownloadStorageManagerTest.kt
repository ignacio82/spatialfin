package dev.jdtech.jellyfin.downloads

import android.app.DownloadManager
import android.content.Context
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.DownloadTaskDto
import dev.jdtech.jellyfin.models.DownloadTaskKind
import dev.jdtech.jellyfin.models.SpatialFinEpisodeDto
import dev.jdtech.jellyfin.models.SpatialFinSeasonDto
import dev.jdtech.jellyfin.models.SpatialFinShowDto
import dev.jdtech.jellyfin.models.SpatialFinSourceDto
import dev.jdtech.jellyfin.models.SpatialFinSourceType
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.io.File
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DownloadStorageManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val database = mockk<ServerDatabaseDao>(relaxed = true)

    @Test
    fun reconcileHealsTempSourcePathWhenCompletedFileExistsOnDisk() = runTest {
        val itemId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val completedFile = tempFolder.newFile("Episode_One_${itemId}_1080p_transcoded.mp4").apply {
            writeBytes(ByteArray(1024))
        }
        val tempPath = "${completedFile.absolutePath}.download"
        val source = SpatialFinSourceDto(
            id = "src-1",
            itemId = itemId,
            name = "1080p",
            type = SpatialFinSourceType.LOCAL,
            path = tempPath,
        )
        val task = DownloadTaskDto(
            id = "task-1",
            itemId = itemId,
            sourceId = "src-1",
            kind = DownloadTaskKind.PRIMARY,
            mediaStreamId = null,
            downloadId = null,
            requestUrl = "https://example.com/Videos/$itemId/stream.mp4?Static=false",
            accessToken = null,
            tempPath = tempPath,
            finalPath = completedFile.absolutePath,
            bytesDownloaded = 1024L,
            totalBytes = 4096L,
            eTag = null,
            lastModified = null,
            status = DownloadManager.STATUS_PENDING,
            progress = 0,
            errorMessage = null,
            updatedAt = 0L,
        )

        every { database.getAllSources() } returns listOf(source)
        every { database.getDownloadTasksBySourceId("src-1") } returns listOf(task)
        every { database.getMovieOrNull(itemId) } returns null
        every { database.getEpisodeOrNull(itemId) } returns mockk(relaxed = true)
        every { database.getMoviesByServerId("srv-1") } returns emptyList()
        every { database.getEpisodesByServerId("srv-1") } returns emptyList()
        every { database.getShowsByServerId("srv-1") } returns emptyList()

        val manager = DownloadStorageManager(context, database)
        manager.reconcileCurrentServerDownloads("srv-1", userId)

        verify { database.setSourcePath("src-1", completedFile.absolutePath) }
        verify {
            database.updateDownloadTask(
                id = "task-1",
                downloadId = null,
                bytesDownloaded = 1024L,
                totalBytes = 1024L,
                eTag = null,
                lastModified = null,
                status = DownloadManager.STATUS_SUCCESSFUL,
                progress = 100,
                errorMessage = null,
                updatedAt = any(),
            )
        }
    }

    @Test
    fun reconcileReconstructsOrphanedEpisodeRowFromCompletedLocalSource() = runTest {
        val showId = UUID.randomUUID()
        val seasonId = UUID.randomUUID()
        val episodeId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val completedFile = tempFolder.newFile("Pilot_Episode_${episodeId}_1080p_original.mkv").apply {
            writeBytes(ByteArray(2048))
        }
        val source = SpatialFinSourceDto(
            id = "src-ep-1",
            itemId = episodeId,
            name = "1080p",
            type = SpatialFinSourceType.LOCAL,
            path = completedFile.absolutePath,
        )
        val show = SpatialFinShowDto(
            id = showId,
            serverId = "srv-1",
            name = "Severance",
            originalTitle = null,
            overview = "",
            runtimeTicks = 0L,
            communityRating = 8.7f,
            officialRating = "TV-MA",
            status = "Continuing",
            productionYear = 2022,
            endDate = null,
        )
        val season = SpatialFinSeasonDto(
            id = seasonId,
            seriesId = showId,
            name = "Season 1",
            seriesName = "Severance",
            overview = "",
            indexNumber = 1,
        )

        every { database.getAllSources() } returns listOf(source)
        every { database.getMovieOrNull(episodeId) } returns null
        every { database.getEpisodeOrNull(episodeId) } returns null
        every { database.getShowsByServerId("srv-1") } returns listOf(show)
        every { database.getSeasonsByShowId(showId) } returns listOf(season)
        every { database.getEpisodesBySeasonId(seasonId) } returns emptyList()
        every { database.getMoviesByServerId("srv-1") } returns emptyList()
        every { database.getEpisodesByServerId("srv-1") } returns emptyList()

        val episodeSlot = slot<SpatialFinEpisodeDto>()
        val manager = DownloadStorageManager(context, database)
        manager.reconcileCurrentServerDownloads("srv-1", userId)

        verify { database.upsertEpisode(capture(episodeSlot)) }
        assertEquals(episodeId, episodeSlot.captured.id)
        assertEquals(showId, episodeSlot.captured.seriesId)
        assertEquals(seasonId, episodeSlot.captured.seasonId)
        assertEquals("Pilot Episode", episodeSlot.captured.name)
    }
}
