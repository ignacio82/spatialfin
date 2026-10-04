package dev.jdtech.jellyfin.presentation.film

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.jdtech.jellyfin.core.presentation.downloader.DownloaderState
import dev.jdtech.jellyfin.core.presentation.dummy.dummyEpisode
import dev.jdtech.jellyfin.film.presentation.episode.EpisodeAction
import dev.jdtech.jellyfin.film.presentation.episode.EpisodeState
import dev.jdtech.jellyfin.models.SpatialFinSource
import dev.jdtech.jellyfin.models.SpatialFinSourceType
import dev.spatialfin.presentation.theme.SpatialFinTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EpisodeScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `streaming and downloaded copies with the same source ID remain selectable`() {
        val remote = source("cb924b279f5917082117b861dbd775de", "Streaming")
        val downloaded = remote.copy(
            name = "Downloaded",
            type = SpatialFinSourceType.LOCAL,
            path = "/downloads/episode.mkv",
        )

        assertBothSourcesSelectable(remote, downloaded)
    }

    @Test
    fun `repeated remote source IDs do not crash the episode screen`() {
        assertBothSourcesSelectable(source("same-id", "First"), source("same-id", "Second"))
    }

    @Test
    fun `empty source ID does not collide with another sources numeric ID`() {
        assertBothSourcesSelectable(source("", "First"), source("0", "Second"))
    }

    private fun assertBothSourcesSelectable(first: SpatialFinSource, second: SpatialFinSource) {
        var state by mutableStateOf(
            EpisodeState(episode = dummyEpisode.copy(sources = listOf(first, second)))
        )
        val selections = mutableListOf<Int>()
        compose.setContent {
            SpatialFinTheme {
                EpisodeScreenLayout(
                    state = state,
                    downloaderState = DownloaderState(),
                    initialMaxBitrate = 0L,
                    onAction = { action ->
                        if (action is EpisodeAction.SelectSource) {
                            selections += action.index
                            state = state.copy(selectedSourceIndex = action.index)
                        }
                    },
                    onDownloaderAction = {},
                    onPlay = {},
                )
            }
        }

        compose.onNodeWithText(first.name).performScrollTo().assertIsDisplayed().assertIsSelected()
        compose.onNodeWithText(second.name).assertIsDisplayed().assertIsNotSelected().performClick()
        compose.onNodeWithText(second.name).assertIsSelected()
        compose.onNodeWithText(first.name).assertIsNotSelected().performClick()
        compose.onNodeWithText(first.name).assertIsSelected()
        compose.runOnIdle { assertEquals(listOf(1, 0), selections) }
    }

    private fun source(id: String, name: String) = SpatialFinSource(
        id = id,
        name = name,
        type = SpatialFinSourceType.REMOTE,
        path = "episode.mkv",
        size = 0L,
        mediaStreams = emptyList(),
    )
}
