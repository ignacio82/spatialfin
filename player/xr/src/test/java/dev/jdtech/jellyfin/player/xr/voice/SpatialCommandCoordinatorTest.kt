package dev.jdtech.jellyfin.player.xr.voice

import android.content.Context
import dev.jdtech.jellyfin.core.llm.LlmModelManager
import dev.jdtech.jellyfin.player.session.voice.PlayerStateSnapshot
import dev.jdtech.jellyfin.player.session.voice.XrPlayerAction
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import io.mockk.mockk
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
class SpatialCommandCoordinatorTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val geminiNano = mockk<GeminiNanoService>(relaxed = true)
    private val geminiCloud = mockk<GeminiCloudService>(relaxed = true)
    private val modelManager = mockk<LlmModelManager>(relaxed = true)
    private lateinit var appPreferences: AppPreferences
    private lateinit var coordinator: SpatialCommandCoordinator

    @Before
    fun setUp() {
        val prefs = context.getSharedPreferences("test_voice_coordinator", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        appPreferences = AppPreferences(prefs)
        coordinator = SpatialCommandCoordinator(
            appContext = context,
            geminiNanoService = geminiNano,
            geminiCloudService = geminiCloud,
            appPreferences = appPreferences,
            modelManager = modelManager,
        )
    }

    @Test
    fun `parses curvature preset voice commands deterministically`() = runTest {
        assertEquals(
            XrPlayerAction.SetScreenCurvePreset("flat"),
            coordinator.parse("flatten the screen").action,
        )
        assertEquals(
            XrPlayerAction.SetScreenCurvePreset("subtle"),
            coordinator.parse("set curve to subtle").action,
        )
        assertEquals(
            XrPlayerAction.SetScreenCurvePreset("cinema"),
            coordinator.parse("cinema curve").action,
        )
        assertEquals(
            XrPlayerAction.SetScreenCurvePreset("deep"),
            coordinator.parse("deep curve").action,
        )
        assertEquals(
            XrPlayerAction.SetCurvedScreen(true),
            coordinator.parse("curve the screen").action,
        )
    }

    @Test
    fun `parses room dimming and passthrough opacity commands`() = runTest {
        assertEquals(
            XrPlayerAction.SetPassthroughOpacity(0.35f),
            coordinator.parse("dim the room").action,
        )
        assertEquals(
            XrPlayerAction.SetPassthroughOpacity(0.75f),
            coordinator.parse("dim the room to 75%").action,
        )
        assertEquals(
            XrPlayerAction.SetPassthroughOpacity(0.0f),
            coordinator.parse("blackout mode").action,
        )
        assertEquals(
            XrPlayerAction.SetPassthroughOpacity(1.0f),
            coordinator.parse("brighten the room").action,
        )
    }

    @Test
    fun `parses half-second sync offsets and reset sync commands`() = runTest {
        assertEquals(
            XrPlayerAction.AdjustSubtitleOffset(500L),
            coordinator.parse("delay subtitles by half a second").action,
        )
        assertEquals(
            XrPlayerAction.AdjustAudioOffset(-500L),
            coordinator.parse("advance audio by half a second").action,
        )
        assertEquals(
            XrPlayerAction.ResetSyncOffsets,
            coordinator.parse("reset sync").action,
        )
    }
}
