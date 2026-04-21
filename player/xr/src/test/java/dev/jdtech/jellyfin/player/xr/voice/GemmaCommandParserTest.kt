package dev.jdtech.jellyfin.player.xr.voice

import dev.jdtech.jellyfin.core.llm.ParsedToolCall
import dev.jdtech.jellyfin.core.llm.VoiceAiEngine
import dev.jdtech.jellyfin.player.session.voice.PlayerStateSnapshot
import dev.jdtech.jellyfin.player.session.voice.VoiceScreenContext
import dev.jdtech.jellyfin.player.session.voice.XrPlayerAction
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Locks in the two parser paths — typed tool call (LITERT) and JSON fallback (AICore).
 *
 * Robolectric is required because the JSON fallback uses `org.json.JSONObject`, which
 * lives in the Android framework (no JVM stub). `@Config(sdk = [35])` pins the SDK —
 * Robolectric 4.15 resource jars top out at API 35 while compileSdk is 36.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GemmaCommandParserTest {

    // --- Tool-call (LITERT) path -----------------------------------------------

    @Test fun `tool call seek_forward carries seconds into SeekForward action`() = runTest {
        val engine = mockk<VoiceAiEngine>(relaxed = true)
        coEvery { engine.runToolCall(any(), any(), any()) } returns ParsedToolCall(
            name = "interpret_command",
            arguments = mapOf("action" to "seek_forward", "seconds" to 42),
        )

        val action = GemmaCommandParser(engine).parse(
            transcript = "skip ahead 42 seconds",
            playerState = PlayerStateSnapshot(screenContext = VoiceScreenContext.PLAYER),
        )

        assertEquals(XrPlayerAction.SeekForward(42), action)
        coVerify(exactly = 0) { engine.runInference(any(), any(), any(), any()) }
    }

    @Test fun `tool call select_audio with subtitles secondary_action populates both`() = runTest {
        val engine = mockk<VoiceAiEngine>(relaxed = true)
        coEvery { engine.runToolCall(any(), any(), any()) } returns ParsedToolCall(
            name = "interpret_command",
            arguments = mapOf(
                "action" to "select_audio",
                "language" to "japanese",
                "secondary_action" to mapOf(
                    "action" to "select_subtitles",
                    "language" to "english",
                ),
            ),
        )

        val action = GemmaCommandParser(engine).parse(
            transcript = "japanese audio with english subtitles",
            playerState = PlayerStateSnapshot(screenContext = VoiceScreenContext.PLAYER),
        )

        val audio = action as XrPlayerAction.SelectAudioTrack
        assertEquals("japanese", audio.language)
        val secondary = audio.secondaryAction as XrPlayerAction.SelectSubtitleTrack
        assertEquals("english", secondary.language)
    }

    @Test fun `tool call select_version routes to ResolveDisambiguation`() = runTest {
        val engine = mockk<VoiceAiEngine>(relaxed = true)
        coEvery { engine.runToolCall(any(), any(), any()) } returns ParsedToolCall(
            name = "interpret_command",
            arguments = mapOf("action" to "select_version", "query" to "3d"),
        )

        val action = GemmaCommandParser(engine).parse(
            transcript = "play the 3D one",
            playerState = PlayerStateSnapshot(),
        )

        val resolve = action as XrPlayerAction.ResolveDisambiguation
        assertEquals("3d", resolve.query)
        assertEquals("play the 3D one", resolve.originalTranscript)
    }

    @Test fun `tool call with unknown action falls through to Unrecognized`() = runTest {
        val engine = mockk<VoiceAiEngine>(relaxed = true)
        coEvery { engine.runToolCall(any(), any(), any()) } returns ParsedToolCall(
            name = "interpret_command",
            arguments = mapOf("action" to "do_a_barrel_roll"),
        )

        val action = GemmaCommandParser(engine).parse(
            transcript = "do a barrel roll",
            playerState = PlayerStateSnapshot(),
        )

        assertTrue(action is XrPlayerAction.Unrecognized)
    }

    // --- JSON fallback (AICore) path ------------------------------------------

    @Test fun `when tool call unavailable parser reads action from free-text JSON`() = runTest {
        val engine = mockk<VoiceAiEngine>(relaxed = true)
        coEvery { engine.runToolCall(any(), any(), any()) } returns null
        coEvery { engine.runInference(any(), any(), any(), any()) } returns """{"action":"pause"}"""

        val action = GemmaCommandParser(engine).parse(
            transcript = "pause",
            playerState = PlayerStateSnapshot(),
        )

        assertEquals(XrPlayerAction.Pause, action)
    }

    @Test fun `JSON fallback retries once on unparseable output`() = runTest {
        val engine = mockk<VoiceAiEngine>(relaxed = true)
        coEvery { engine.runToolCall(any(), any(), any()) } returns null
        coEvery {
            engine.runInference(
                prompt = any(),
                images = any(),
                profile = any(),
                onToken = any(),
            )
        } returnsMany listOf(
            "sure i can help with that", // first attempt — unparseable.
            """{"action":"chat","query":"help me"}""",
        )

        val action = GemmaCommandParser(engine).parse(
            transcript = "help me",
            playerState = PlayerStateSnapshot(),
        )

        val chat = action as XrPlayerAction.ChatQuery
        assertEquals("help me", chat.query)
    }

    @Test fun `tool call throw does not crash — fallback takes over`() = runTest {
        val engine = mockk<VoiceAiEngine>(relaxed = true)
        coEvery { engine.runToolCall(any(), any(), any()) } throws RuntimeException("tool call exploded")
        coEvery {
            engine.runInference(
                prompt = any(),
                images = any(),
                profile = any(),
                onToken = any(),
            )
        } returns """{"action":"play"}"""

        val action = GemmaCommandParser(engine).parse(
            transcript = "play",
            playerState = PlayerStateSnapshot(),
        )

        assertEquals(XrPlayerAction.Play, action)
    }
}
