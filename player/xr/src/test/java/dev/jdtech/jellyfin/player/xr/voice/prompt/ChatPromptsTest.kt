package dev.jdtech.jellyfin.player.xr.voice.prompt

import androidx.compose.ui.geometry.Offset
import dev.jdtech.jellyfin.player.session.voice.PlayerStateSnapshot
import dev.jdtech.jellyfin.player.xr.voice.AssistantPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Snapshot-style tests for the prompt pipeline. No LLM is involved — these tests
 * exercise the composition rules in [chatPrompt] / [recapPrompt] /
 * [characterIdentificationPrompt] so changes to skill prompts surface as diffable
 * section-level deltas instead of "prompt is slightly different" regressions.
 */
class ChatPromptsTest {

    // ---- Chat prompt structure ------------------------------------------------

    @Test fun `chat prompt omits blank metadata lines`() {
        val sections = chatPrompt(
            PromptContext(
                question = "what happens next",
                playerState = PlayerStateSnapshot(
                    currentItemTitle = "Alien",
                    productionYear = 1979,
                    currentGenres = listOf("Horror", "Sci-Fi"),
                ),
            ),
        )
        val rendered = sections.render()
        // Populated fields make it through.
        assertTrue("title should render", rendered.contains("Current title: Alien"))
        assertTrue("year should render", rendered.contains("Year: 1979"))
        assertTrue("genres should render", rendered.contains("Genres: Horror, Sci-Fi"))
        // Empty fields never emit a dangling `Rating: ` line — proof that the facts()
        // builder drops null/blank pairs.
        assertFalse("rating line should not render when empty", rendered.contains("Rating: \n"))
        assertFalse("series line should not render when empty", rendered.contains("Series: \n"))
        assertFalse("directors line should not render when empty", rendered.contains("Directors: \n"))
    }

    @Test fun `chat prompt includes conversation history only when populated`() {
        val withHistory = chatPrompt(
            PromptContext(
                question = "pick one",
                conversationHistory = listOf("q1" to "a1", "q2" to "a2"),
            ),
        )
        assertTrue(withHistory.hasSection("Conversation history:"))
        val body = withHistory.sectionBody("Conversation history:") ?: ""
        assertTrue(body.contains("User: q1\nAssistant: a1"))
        assertTrue(body.contains("User: q2\nAssistant: a2"))

        val without = chatPrompt(PromptContext(question = "pick one"))
        assertFalse(without.hasSection("Conversation history:"))
    }

    @Test fun `chat prompt renders pointer position as percentage`() {
        val sections = chatPrompt(
            PromptContext(
                question = "who's this",
                pointerPosition = Offset(0.25f, 0.75f),
            ),
        )
        val rendered = sections.render()
        assertTrue(
            "pointer section should state x=25%% y=75%%",
            rendered.contains("x=25%, y=75% of the video screen."),
        )
    }

    @Test fun `chat prompt question is always the last section`() {
        val sections = chatPrompt(
            PromptContext(
                question = "summarize",
                playerState = PlayerStateSnapshot(currentItemTitle = "Alien"),
            ),
        )
        val last = sections.sections.last()
        assertEquals("User question:", last.header)
        assertEquals("summarize", last.body)
    }

    @Test fun `chat prompt respects overview cap at 1000 characters`() {
        val longOverview = "X".repeat(1500)
        val sections = chatPrompt(
            PromptContext(playerState = PlayerStateSnapshot(currentOverview = longOverview)),
        )
        val rendered = sections.render()
        // Exactly 1000 Xs plus the "Overview: " prefix once.
        assertTrue(rendered.contains("Overview: " + "X".repeat(1000)))
        assertFalse(rendered.contains("X".repeat(1001)))
    }

    @Test fun `chat prompt respects verbosity and spoiler policy from preferences`() {
        val rendered = chatPrompt(
            PromptContext(
                assistantPreferences = AssistantPreferences(
                    verbosity = "terse",
                    spoilerPolicy = "strict",
                ),
            ),
        ).render()
        assertTrue(rendered.contains("Respect the spoiler policy: strict."))
        assertTrue(rendered.contains("Verbosity: terse."))
    }

    // ---- Recap prompt ---------------------------------------------------------

    @Test fun `recap prompt assembles show info with season episode and title`() {
        val sections = recapPrompt(
            PromptContext(
                question = "what just happened",
                playerState = PlayerStateSnapshot(
                    currentSeriesName = "Severance",
                    currentSeasonNumber = 2,
                    currentEpisodeNumber = 5,
                    currentItemTitle = "Trojan's Horse",
                    currentChapterName = "Chapter 3",
                ),
                subtitleContext = "MARK: are you innie\nHELLY: no",
            ),
        )
        val rendered = sections.render()
        assertTrue(rendered.contains("Severance S2E5 \"Trojan's Horse\""))
        assertTrue(rendered.contains("(chapter: Chapter 3)"))
        assertTrue(rendered.contains("MARK: are you innie"))
        assertTrue(rendered.contains("In 2-3 natural sentences"))
    }

    @Test fun `recap prompt falls back to 'this content' when title is blank`() {
        val rendered = recapPrompt(
            PromptContext(
                question = "summary please",
                subtitleContext = "line",
            ),
        ).render()
        assertTrue(rendered.contains("The user is watching this content"))
    }

    @Test fun `recap prompt caps subtitles at 60 lines`() {
        val subs = (1..100).joinToString("\n") { "line $it" }
        val rendered = recapPrompt(
            PromptContext(question = "recap", subtitleContext = subs),
        ).render()
        assertFalse("should drop line 40", rendered.contains("line 40\n"))
        assertTrue("should keep line 41 (1-indexed: last 60 = 41..100)", rendered.contains("line 41"))
        assertTrue("should keep line 100", rendered.contains("line 100"))
    }

    // ---- Character identification --------------------------------------------

    @Test fun `character id instructions list cast pairs verbatim when present`() {
        val instructions = characterIdentificationInstructions(
            titleLine = "Alien",
            castPairs = listOf(
                "Sigourney Weaver" to "Ripley",
                "Tom Skerritt" to "Dallas",
            ),
        )
        assertTrue(instructions.contains("  Character: Ripley — Actor: Sigourney Weaver"))
        assertTrue(instructions.contains("  Character: Dallas — Actor: Tom Skerritt"))
        assertTrue(instructions.contains("Cast for Alien:"))
    }

    @Test fun `character id instructions use no-metadata fallback when cast is empty`() {
        val instructions = characterIdentificationInstructions(
            titleLine = "Unknown",
            castPairs = emptyList(),
        )
        assertTrue(instructions.contains("No cast metadata available"))
        assertFalse(instructions.contains("Cast for Unknown:"))
    }

    @Test fun `character id vision prompt orders image-intro cast task final-instruction`() {
        val sections = characterIdentificationPrompt(
            PromptContext(
                playerState = PlayerStateSnapshot(
                    castWithCharacters = listOf("Sigourney Weaver" to "Ripley"),
                ),
                taskInstructions = "Task: identify the character",
            ),
        )
        val bodies = sections.sections.map { it.body }
        assertEquals(4, bodies.size)
        assertTrue(bodies[0].startsWith("You are SpatialFin"))
        assertEquals("Task: identify the character", bodies[1])
        assertTrue(bodies[2].contains("Ripley"))
        assertEquals("Analyze the video frame above and respond.", bodies[3])
    }
}
