package dev.jdtech.jellyfin.player.local.presentation

import android.app.Application
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import dev.jdtech.jellyfin.models.SpatialFinSegment
import dev.jdtech.jellyfin.models.SpatialFinSegmentType
import dev.jdtech.jellyfin.player.core.domain.models.PlayerChapter
import dev.jdtech.jellyfin.player.core.domain.models.PlayerContentSource
import dev.jdtech.jellyfin.player.core.domain.models.PlayerItem
import dev.jdtech.jellyfin.player.local.domain.PlaylistManager
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.repository.LocalMediaRepository
import dev.jdtech.jellyfin.repository.NetworkMediaRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlayerSegmentResolutionTest {

    private val application: Application = RuntimeEnvironment.getApplication()
    private val sharedPreferences = application.getSharedPreferences("test_prefs_segments", Context.MODE_PRIVATE)
    private val appPreferences = AppPreferences(sharedPreferences)

    private val playlistManager = mockk<PlaylistManager>(relaxed = true)
    private val repository = mockk<JellyfinRepository>(relaxed = true)
    private val localMediaRepository = mockk<LocalMediaRepository>(relaxed = true)
    private val networkMediaRepository = mockk<NetworkMediaRepository>(relaxed = true)
    private val savedStateHandle = SavedStateHandle()

    @Before
    fun setUp() {
        sharedPreferences.edit().clear().commit()
        appPreferences.setValue(appPreferences.playerChapterIntroDetection, true)
        appPreferences.setValue(appPreferences.playerAniSkipEnabled, false)
    }

    private fun createViewModel(): PlayerViewModel {
        return PlayerViewModel(
            application = application,
            playlistManager = playlistManager,
            repository = repository,
            localMediaRepository = localMediaRepository,
            networkMediaRepository = networkMediaRepository,
            appPreferences = appPreferences,
            savedStateHandle = savedStateHandle,
        )
    }

    @Test
    fun `server segments take precedence over chapter heuristics`() = runBlocking {
        val itemId = UUID.randomUUID()
        val serverSegments = listOf(
            SpatialFinSegment(type = SpatialFinSegmentType.INTRO, startTicks = 85_000L, endTicks = 175_000L),
            SpatialFinSegment(type = SpatialFinSegmentType.OUTRO, startTicks = 1_300_000L, endTicks = 1_420_000L),
        )
        coEvery { repository.getSegments(itemId) } returns serverSegments

        val chapters = listOf(
            PlayerChapter(startPosition = 0L, name = "Cold Open"),
            // Different from server to verify server wins
            PlayerChapter(startPosition = 90_000L, name = "Intro"),
            PlayerChapter(startPosition = 180_000L, name = "Episode"),
        )
        val item = PlayerItem(
            name = "Episode 1",
            itemId = itemId,
            mediaSourceId = "src-1",
            playbackPosition = 0L,
            contentSource = PlayerContentSource.JELLYFIN,
            chapters = chapters,
        )

        val viewModel = createViewModel()
        viewModel.getSegments(item)

        assertEquals(2, viewModel.currentMediaItemSegments.size)
        // Exactly matches server timestamps (85s, not 90s)
        assertEquals(85_000L, viewModel.currentMediaItemSegments.first { it.type == SpatialFinSegmentType.INTRO }.startTicks)
    }

    @Test
    fun `falls back to chapters when server returns empty list`() = runBlocking {
        val itemId = UUID.randomUUID()
        coEvery { repository.getSegments(itemId) } returns emptyList()

        val chapters = listOf(
            PlayerChapter(startPosition = 0L, name = "Prologue"),
            PlayerChapter(startPosition = 60_000L, name = "Opening"),
            PlayerChapter(startPosition = 150_000L, name = "Main"),
            PlayerChapter(startPosition = 1_320_000L, name = "Ending"),
            PlayerChapter(startPosition = 1_410_000L, name = "Preview"),
        )
        val item = PlayerItem(
            name = "Anime Episode 1",
            itemId = itemId,
            mediaSourceId = "src-2",
            playbackPosition = 0L,
            contentSource = PlayerContentSource.JELLYFIN,
            chapters = chapters,
        )

        val viewModel = createViewModel()
        viewModel.getSegments(item)

        val segments = viewModel.currentMediaItemSegments
        // INTRO and OUTRO resolved from chapters
        assertTrue(segments.isNotEmpty())

        val intro = segments.first { it.type == SpatialFinSegmentType.INTRO }
        assertEquals(60_000L, intro.startTicks)
        assertEquals(150_000L, intro.endTicks)

        val outro = segments.first { it.type == SpatialFinSegmentType.OUTRO }
        assertEquals(1_320_000L, outro.startTicks)
        assertEquals(1_410_000L, outro.endTicks)
    }

    @Test
    fun `local media resolves chapters directly without querying server repository`() = runBlocking {
        val itemId = UUID.randomUUID()
        val chapters = listOf(
            PlayerChapter(startPosition = 0L, name = "Intro"),
            PlayerChapter(startPosition = 90_000L, name = "Feature"),
        )
        val item = PlayerItem(
            name = "Local Video",
            itemId = itemId,
            mediaSourceId = "local-1",
            playbackPosition = 0L,
            contentSource = PlayerContentSource.LOCAL,
            chapters = chapters,
        )

        val viewModel = createViewModel()
        viewModel.getSegments(item)

        val segments = viewModel.currentMediaItemSegments
        assertEquals(1, segments.size)
        assertEquals(SpatialFinSegmentType.INTRO, segments[0].type)
        assertEquals(0L, segments[0].startTicks)
        assertEquals(90_000L, segments[0].endTicks)
    }

    @Test
    fun `respects chapter detection disabled preference`() = runBlocking {
        appPreferences.setValue(appPreferences.playerChapterIntroDetection, false)

        val itemId = UUID.randomUUID()
        coEvery { repository.getSegments(itemId) } returns emptyList()

        val chapters = listOf(
            PlayerChapter(startPosition = 0L, name = "Intro"),
            PlayerChapter(startPosition = 90_000L, name = "Feature"),
        )
        val item = PlayerItem(
            name = "Episode",
            itemId = itemId,
            mediaSourceId = "src-3",
            playbackPosition = 0L,
            contentSource = PlayerContentSource.JELLYFIN,
            chapters = chapters,
        )

        val viewModel = createViewModel()
        viewModel.getSegments(item)

        assertTrue(viewModel.currentMediaItemSegments.isEmpty())
    }
}
