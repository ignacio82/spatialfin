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
import dev.jdtech.jellyfin.session.SessionActivationCoordinator
import dev.jdtech.jellyfin.session.SessionMode
import dev.jdtech.jellyfin.session.SessionState
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.spatialfin.unified.applock.AppLockManager
import dev.spatialfin.unified.applock.AppLockObserver
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
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
class EpisodeDownloadHygieneCoordinatorTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val database = mockk<ServerDatabaseDao>(relaxed = true)
    private val repository = mockk<JellyfinRepository>(relaxed = true)
    private val downloader = mockk<Downloader>(relaxed = true)
    private val sessionCoordinator = mockk<SessionActivationCoordinator>(relaxed = true)
    private val lockStateFlow = MutableStateFlow(AppLockManager.LockState.UNLOCKED)
    private val appLockObserver = object : AppLockObserver {
        override val lockState = lockStateFlow
        override val enrollmentVersion = MutableStateFlow(0L)
    }
    private lateinit var appPreferences: AppPreferences
    private lateinit var coordinator: EpisodeDownloadHygieneCoordinator

    private val activeServerId = "srv-1"
    private val activeUserId = UUID.randomUUID()
    private val activeEpoch = 7L

    @Before
    fun setUp() {
        val prefs = context.getSharedPreferences("test_episode_hygiene", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        appPreferences = AppPreferences(prefs)
        val session = SessionState(
            isLoading = false,
            sessionMode = SessionMode.SERVER_AUTHENTICATED,
            sessionEpoch = activeEpoch,
            activationEpoch = 1L,
            activeServerId = activeServerId,
            activeUserId = activeUserId,
        )
        every { sessionCoordinator.sessionState } returns MutableStateFlow(session)
        every { sessionCoordinator.sessionEpoch } returns AtomicLong(activeEpoch)
        every { sessionCoordinator.isActivating } returns AtomicBoolean(false)
        coEvery { sessionCoordinator.ensureInitialized() } returns session

        coordinator = EpisodeDownloadHygieneCoordinator(
            database = database,
            repository = repository,
            downloader = downloader,
            appPreferences = appPreferences,
            sessionActivationCoordinator = sessionCoordinator,
            appLockObserver = appLockObserver,
        )
    }

    @Test
    fun `deletes watched downloaded episode and queues next unwatched episodes across season boundary`() = runTest {
        appPreferences.setValue(appPreferences.autoDeleteWatchedDownloads, true)
        appPreferences.setValue(appPreferences.autoDownloadNextEpisodesCount, 2)

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

        val finishedEp = buildEpisode(ep1Id, seriesId, season1Id, 1, 1, true, listOf(localSource))
        val nextEpInSeason1 = buildEpisode(ep2Id, seriesId, season1Id, 1, 2, false, listOf(remoteSource))
        val firstEpInSeason2 = buildEpisode(s2Ep1Id, seriesId, season2Id, 2, 1, false, listOf(remoteSource))

        every { repository.getUserId() } returns activeUserId
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
            serverId = activeServerId,
            userId = activeUserId,
            sessionEpoch = activeEpoch,
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
            serverId = activeServerId,
            userId = activeUserId,
            sessionEpoch = activeEpoch,
        )

        coVerify(exactly = 0) { downloader.deleteItem(any(), any()) }
        coVerify(exactly = 0) { downloader.downloadItems(any(), any()) }
    }

    @Test
    fun `skips hygiene when App Lock is LOCKED or session epoch mismatches`() = runTest {
        appPreferences.setValue(appPreferences.autoDeleteWatchedDownloads, true)
        appPreferences.setValue(appPreferences.autoDownloadNextEpisodesCount, 2)

        lockStateFlow.value = AppLockManager.LockState.LOCKED
        coordinator.onEpisodePlaybackStopped(
            itemId = UUID.randomUUID(),
            playedPercentage = 100,
            markedPlayed = true,
            serverId = activeServerId,
            userId = activeUserId,
            sessionEpoch = activeEpoch,
        )

        lockStateFlow.value = AppLockManager.LockState.UNLOCKED
        coordinator.onEpisodePlaybackStopped(
            itemId = UUID.randomUUID(),
            playedPercentage = 100,
            markedPlayed = true,
            serverId = activeServerId,
            userId = activeUserId,
            sessionEpoch = activeEpoch - 1L,
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
