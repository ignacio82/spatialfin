package dev.jdtech.jellyfin.player.xr.voice

import android.content.Context
import dev.jdtech.jellyfin.core.llm.LlmModelManager
import dev.jdtech.jellyfin.player.session.voice.PlayerStateSnapshot
import dev.jdtech.jellyfin.player.session.voice.VoiceScreenContext
import dev.jdtech.jellyfin.player.session.voice.XrPlayerAction
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SpatialCommandCoordinatorExactMatchTest {

    private lateinit var coordinator: SpatialCommandCoordinator

    @Before
    fun setUp() {
        val context = mockk<Context>(relaxed = true)
        val nano = mockk<GeminiNanoService>(relaxed = true)
        val cloud = mockk<GeminiCloudService>(relaxed = true)
        val prefs = mockk<AppPreferences>(relaxed = true)
        val modelManager = mockk<LlmModelManager>(relaxed = true)

        every { prefs.getValue(prefs.voiceAssistantGemmaEnabled) } returns false
        every { prefs.getValue(prefs.voiceAssistantCloudApiKey) } returns ""

        coordinator = SpatialCommandCoordinator(
            appContext = context,
            geminiNanoService = nano,
            geminiCloudService = cloud,
            appPreferences = prefs,
            modelManager = modelManager,
        )
    }

    @Test
    fun `fast forward voice commands match keyword before model`() = runTest {
        val playerState = PlayerStateSnapshot(screenContext = VoiceScreenContext.PLAYER)

        val variations = listOf(
            "fast forward" to 15,
            "fast foward" to 15,
            "fast-forward" to 15,
            "fastforward" to 15,
            "fast forward 30 seconds" to 30,
            "fast foward 45 seconds" to 45,
            "skip forward 20 seconds" to 20,
            "skip ahead 10 seconds" to 10,
            "go forward 1 minute" to 60,
        )

        for ((transcript, expectedSeconds) in variations) {
            val result = coordinator.parse(transcript, playerState)
            assertEquals("Testing '$transcript'", XrPlayerAction.SeekForward(expectedSeconds), result.action)
            assertEquals("Strategy for '$transcript'", VoiceParseStrategy.KEYWORD, result.strategy)
            assertEquals("DebugInfo for '$transcript'", "exact keyword matched before model", result.debugInfo)
        }
    }

    @Test
    fun `rewind and seek commands match keyword before model`() = runTest {
        val playerState = PlayerStateSnapshot(screenContext = VoiceScreenContext.PLAYER)

        val rewindResult = coordinator.parse("rewind", playerState)
        assertEquals(XrPlayerAction.SeekBackward(10), rewindResult.action)
        assertEquals(VoiceParseStrategy.KEYWORD, rewindResult.strategy)
        assertEquals("exact keyword matched before model", rewindResult.debugInfo)

        val rewind30Result = coordinator.parse("rewind 30 seconds", playerState)
        assertEquals(XrPlayerAction.SeekBackward(30), rewind30Result.action)
        assertEquals(VoiceParseStrategy.KEYWORD, rewind30Result.strategy)

        val goBackResult = coordinator.parse("go back 15 seconds", playerState)
        assertEquals(XrPlayerAction.SeekBackward(15), goBackResult.action)

        val seekToResult = coordinator.parse("seek to 10 minutes", playerState)
        assertEquals(XrPlayerAction.SeekTo(600L), seekToResult.action)
        assertEquals(VoiceParseStrategy.KEYWORD, seekToResult.strategy)
    }
}
