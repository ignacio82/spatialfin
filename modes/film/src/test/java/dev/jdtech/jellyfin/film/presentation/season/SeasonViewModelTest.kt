package dev.jdtech.jellyfin.film.presentation.season

import dev.jdtech.jellyfin.core.presentation.dummy.dummyEpisode
import dev.jdtech.jellyfin.models.BulkDownloadSettings
import dev.jdtech.jellyfin.models.DownloadMode
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.settings.domain.models.Preference
import dev.jdtech.jellyfin.utils.BulkDownloadResult
import dev.jdtech.jellyfin.utils.Downloader
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SeasonViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val repository = mockk<JellyfinRepository>(relaxed = true)
    private val downloader = mockk<Downloader>(relaxed = true)
    private val appPreferences = mockk<AppPreferences>(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { repository.observeRealtimeEvents() } returns emptyFlow()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun downloadEpisodesUsesTranscodedModeWhenPreferenceEnabled() = runTest(testDispatcher) {
        val preferTranscodedPref = Preference("pref_download_prefer_transcoded", false)
        val transcodeBitratePref = Preference("pref_download_transcode_bitrate", 8_000_000)

        every { appPreferences.downloadPreferTranscoded } returns preferTranscodedPref
        every { appPreferences.downloadTranscodeBitrate } returns transcodeBitratePref
        every { appPreferences.sharedPreferences.getBoolean("pref_download_prefer_transcoded", false) } returns true
        every { appPreferences.sharedPreferences.getInt("pref_download_transcode_bitrate", 8_000_000) } returns 6_000_000

        coEvery { downloader.downloadItems(any(), any()) } returns BulkDownloadResult(queued = 1, skipped = 0, failed = 0)

        val viewModel = SeasonViewModel(repository, downloader, appPreferences)
        val episode = dummyEpisode

        viewModel.onAction(SeasonAction.DownloadEpisodes(listOf(episode)))
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify {
            downloader.downloadItems(
                listOf(episode),
                match { it.mode == DownloadMode.TRANSCODED && it.videoBitrate == 6_000_000 },
            )
        }
    }

    @Test
    fun downloadEpisodesUsesOriginalModeWhenPreferenceDisabled() = runTest(testDispatcher) {
        val preferTranscodedPref = Preference("pref_download_prefer_transcoded", false)
        val transcodeBitratePref = Preference("pref_download_transcode_bitrate", 8_000_000)

        every { appPreferences.downloadPreferTranscoded } returns preferTranscodedPref
        every { appPreferences.downloadTranscodeBitrate } returns transcodeBitratePref
        every { appPreferences.sharedPreferences.getBoolean("pref_download_prefer_transcoded", false) } returns false

        coEvery { downloader.downloadItems(any(), any()) } returns BulkDownloadResult(queued = 1, skipped = 0, failed = 0)

        val viewModel = SeasonViewModel(repository, downloader, appPreferences)
        val episode = dummyEpisode

        viewModel.onAction(SeasonAction.DownloadEpisodes(listOf(episode)))
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify {
            downloader.downloadItems(
                listOf(episode),
                match { it.mode == DownloadMode.ORIGINAL && it.videoBitrate == null },
            )
        }
    }

    @Test
    fun downloadEpisodesRespectsCustomSettingsOverride() = runTest(testDispatcher) {
        coEvery { downloader.downloadItems(any(), any()) } returns BulkDownloadResult(queued = 1, skipped = 0, failed = 0)

        val viewModel = SeasonViewModel(repository, downloader, appPreferences)
        val episode = dummyEpisode
        val customSettings = BulkDownloadSettings(mode = DownloadMode.TRANSCODED, videoBitrate = 4_000_000)

        viewModel.onAction(SeasonAction.DownloadEpisodes(listOf(episode), customSettings))
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify {
            downloader.downloadItems(
                listOf(episode),
                customSettings,
            )
        }
    }
}
