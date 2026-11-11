package dev.jdtech.jellyfin.utils

import android.content.Context
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.BulkDownloadSettings
import dev.jdtech.jellyfin.models.DownloadMode
import dev.jdtech.jellyfin.models.SpatialFinEpisode
import dev.jdtech.jellyfin.models.SpatialFinImages
import dev.jdtech.jellyfin.models.SpatialFinSeason
import dev.jdtech.jellyfin.models.SpatialFinSource
import dev.jdtech.jellyfin.models.SpatialFinSourceDto
import dev.jdtech.jellyfin.models.SpatialFinSourceType
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.util.UUID
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
class EpisodeDownloadHygieneCoordinatorTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val database = mockk<ServerDatabaseDao>(relaxed = true)
    private val repository = mockk<JellyfinRepository>(relaxed = true)
    private val downloader = mockk<Downloader>(relaxed = true)
    private lateinit var appPreferences: AppPreferences
    private lateinit var coordinator: EpisodeDownloadHygieneCoordinator

    @Before
    fun setUp() {
        val prefs = context.getSharedPreferences("test_episode_hygiene", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        appPreferences = AppPreferences(prefs)
        coordinator = EpisodeDownloadHygieneCoordinator(
            database = database,
            repository = repository,
            downloader = downloader,
            appPreferences = appPreferences,
        )
    }

    @Test
    fun `deletes watched downloaded episode and queues next unwatched episodes across season boundary`() = runTest {
        appPreferences.setValue(appPreferences.autoDeleteWatchedDownloads, true)
        appPreferences.setValue(appPreferences.autoDownloadNextEpisodesCount, 2)

        val userId = UUID.randomUUID()
        val seriesId = UUID.randomUUID()
        val season1Id = UUID.randomUUID()
        val season2Id = UUID.randomUUID()
        val ep1Id = UUID.randomUUID()
        val ep2Id = UUID.randomUUID()
        val s2Ep1Id = UUID.randomUUID()

        val localSource = SpatialFinSource(
            id = "src-local-1",
            name = "1080p",
            type = SpatialFinSourceType.LOCAL,
            path = "/storage/emulated/0/Download/SpatialFin/Ep1_${ep1Id}_1080p_transcoded.mp4",
            size = 1024L,
            mediaStreams = emptyList(),
        )
        val remoteSource = SpatialFinSource(
            id = "src-remote",
            name = "1080p",
            type = SpatialFinSourceType.REMOTE,
            path = "https://example.com/stream",
            size = 2048L,
            mediaStreams = emptyList(),
        )

        val finishedEp = buildEpisode(
            id = ep1Id,
            seriesId = seriesId,
            seasonId = season1Id,
            seasonNumber = 1,
            episodeNumber = 1,
            played = true,
            sources = listOf(localSource),
        )
        val nextEpInSeason1 = buildEpisode(
            id = ep2Id,
            seriesId = seriesId,
            seasonId = season1Id,
            seasonNumber = 1,
            episodeNumber = 2,
            played = false,
            sources = listOf(remoteSource),
        )
        val firstEpInSeason2 = buildEpisode(
            id = s2Ep1Id,
            seriesId = seriesId,
            seasonId = season2Id,
            seasonNumber = 2,
            episodeNumber = 1,
            played = false,
            sources = listOf(remoteSource),
        )

        every { repository.getUserId() } returns userId
        every { database.getEpisodeOrNull(ep1Id) } returns null
        every { database.getSources(ep1Id) } returns listOf(
            SpatialFinSourceDto(
                id = localSource.id,
                itemId = ep1Id,
                name = localSource.name,
                type = SpatialFinSourceType.LOCAL,
                path = localSource.path,
            )
        )
        every { database.getDownloadTasksByItemId(ep1Id) } returns emptyList()
        every { database.getAllDownloadTasks() } returns emptyList()
        coEvery { repository.getItem(ep1Id) } returns finishedEp
        coEvery { repository.getEpisodes(seriesId = seriesId, seasonId = season1Id, offline = false) } returns listOf(
            finishedEp,
            nextEpInSeason1,
        )
        coEvery { repository.getSeasons(seriesId = seriesId, offline = false) } returns listOf(
            SpatialFinSeason(
                id = season2Id,
                name = "Season 2",
                originalTitle = null,
                overview = "",
                sources = emptyList(),
                indexNumber = 2,
                episodes = emptyList(),
                played = false,
                favorite = false,
                canPlay = true,
                canDownload = true,
                unplayedItemCount = 1,
                seriesName = "Series",
                seriesId = seriesId,
                images = SpatialFinImages(),
            )
        )
        coEvery { repository.getEpisodes(seriesId = seriesId, seasonId = season2Id, offline = false) } returns listOf(
            firstEpInSeason2,
        )

        coordinator.onEpisodePlaybackStopped(
            itemId = ep1Id,
            playedPercentage = 95,
            markedPlayed = true,
        )

        coVerify { downloader.deleteItem(finishedEp, localSource) }
        val episodesSlot = slot<List<SpatialFinEpisode>>()
        val settingsSlot = slot<BulkDownloadSettings>()
        coVerify { downloader.downloadItems(capture(episodesSlot), capture(settingsSlot)) }
        assertEquals(listOf(ep2Id, s2Ep1Id), episodesSlot.captured.map { it.id })
        assertEquals(DownloadMode.TRANSCODED, settingsSlot.captured.mode)
    }

    @Test
    fun `skips hygiene when playback stopped below 90 percent and not marked played`() = runTest {
        appPreferences.setValue(appPreferences.autoDeleteWatchedDownloads, true)
        appPreferences.setValue(appPreferences.autoDownloadNextEpisodesCount, 2)

        coordinator.onEpisodePlaybackStopped(
            itemId = UUID.randomUUID(),
            playedPercentage = 45,
            markedPlayed = false,
        )

        coVerify(exactly = 0) { downloader.deleteItem(any(), any()) }
        coVerify(exactly = 0) { downloader.downloadItems(any(), any()) }
    }

    private fun buildEpisode(
        id: UUID,
        seriesId: UUID,
        seasonId: UUID,
        seasonNumber: Int,
        episodeNumber: Int,
        played: Boolean,
        sources: List<SpatialFinSource>,
    ): SpatialFinEpisode = SpatialFinEpisode(
        id = id,
        name = "S${seasonNumber}E${episodeNumber}",
        originalTitle = null,
        overview = "",
        sources = sources,
        played = played,
        favorite = false,
        canPlay = true,
        canDownload = true,
        runtimeTicks = 1000L,
        playbackPositionTicks = 0L,
        premiereDate = null,
        seriesName = "Series",
        seriesId = seriesId,
        seasonId = seasonId,
        seasonName = "Season $seasonNumber",
        indexNumber = episodeNumber,
        indexNumberEnd = null,
        parentIndexNumber = seasonNumber,
        communityRating = null,
        people = emptyList(),
        chapters = emptyList(),
        images = SpatialFinImages(),
        trickplayInfo = null,
    )
}
