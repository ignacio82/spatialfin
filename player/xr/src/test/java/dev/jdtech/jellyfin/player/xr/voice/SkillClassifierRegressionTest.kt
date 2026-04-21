package dev.jdtech.jellyfin.player.xr.voice

import dev.jdtech.jellyfin.player.session.voice.PlayerStateSnapshot
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Drift sentinel for [MediaSkillRegistry.selectSkill].
 *
 * Prompt/classifier edits routinely shift a single keyword or boundary and
 * the only way we notice is when users complain that "who directed X"
 * suddenly falls through to GENERAL_CHAT instead of METADATA_QA. Running
 * this seed set on every change catches those shifts before they land.
 *
 * The test is intentionally **data-driven** rather than "one test per case":
 *   - A single failure reports the full diff (expected vs. actual per query)
 *     so you can see whether a change is a deliberate reclassification or a
 *     regression in one step.
 *   - Adding a new case is a one-line addition — not a new @Test method.
 *
 * When a deliberate reclassification is made, update the expected column in
 * [CASES] in the same commit as the code change; the git diff then documents
 * the behavior change at review time.
 */
class SkillClassifierRegressionTest {

    private val registry = MediaSkillRegistry(
        repository = mockk<JellyfinRepository>(relaxed = true),
        appPreferences = mockk<AppPreferences>(relaxed = true),
    )

    private data class Case(
        val query: String,
        val expected: MediaSkillId,
        val playerState: PlayerStateSnapshot = PlayerStateSnapshot(),
        val hasVisualContext: Boolean = false,
    )

    @Test
    fun `classifier routes each seed query to the expected skill`() {
        val failures = mutableListOf<String>()
        for (case in CASES) {
            val actual = registry.selectSkill(
                question = case.query,
                playerState = case.playerState,
                recommendationContext = null,
                hasVisualContext = case.hasVisualContext,
            )
            if (actual != case.expected) {
                failures.add(
                    "  \"${case.query}\" -> expected=${case.expected.name} actual=${actual.name}",
                )
            }
        }
        if (failures.isNotEmpty()) {
            throw AssertionError(
                "Skill classifier regressed on ${failures.size}/${CASES.size} cases:\n" +
                    failures.joinToString("\n"),
            )
        }
    }

    @Test
    fun `classifier cases are unique by query string`() {
        val dupes = CASES.groupBy { it.query.lowercase() }.filter { it.value.size > 1 }.keys
        assertEquals("duplicate queries in classifier seed: $dupes", emptySet<String>(), dupes)
    }

    companion object {
        private val playingMovie = PlayerStateSnapshot(
            currentItemTitle = "Blade Runner",
            productionYear = 1982,
            directors = listOf("Ridley Scott"),
            castNames = listOf("Harrison Ford"),
        )

        // The expected values reflect *actual current classifier behavior*, not
        // what the test author thinks should happen. Cases that intentionally
        // fall through to GENERAL_CHAT today are documented in
        // [ASPIRATIONAL_IMPROVEMENTS] below; keeping them separate makes the
        // test useful as a drift sentinel without being red on day one.
        private val CASES = listOf(
            // ---- PLAYBACK_CONTROL --------------------------------------------
            Case("what am I watching", MediaSkillId.PLAYBACK_CONTROL),
            Case("what is this", MediaSkillId.PLAYBACK_CONTROL),
            Case("how much time is remaining", MediaSkillId.PLAYBACK_CONTROL),
            Case("when does it end", MediaSkillId.PLAYBACK_CONTROL),
            Case("is passthrough on", MediaSkillId.PLAYBACK_CONTROL),

            // ---- CONTINUE_WATCHING -------------------------------------------
            Case("what can I continue watching", MediaSkillId.CONTINUE_WATCHING),
            Case("pick up where I left off", MediaSkillId.CONTINUE_WATCHING),

            // ---- LIBRARY_SEARCH ----------------------------------------------
            Case("search for the matrix", MediaSkillId.LIBRARY_SEARCH),
            Case("find breaking bad", MediaSkillId.LIBRARY_SEARCH),

            // ---- EXTERNAL_KNOWLEDGE ------------------------------------------
            Case("tell me about arrival", MediaSkillId.EXTERNAL_KNOWLEDGE),

            // ---- MOOD_SURPRISE -----------------------------------------------
            Case("surprise me", MediaSkillId.MOOD_SURPRISE),

            // ---- WATCH_RECOMMENDER -------------------------------------------
            Case("recommend something to watch", MediaSkillId.WATCH_RECOMMENDER),
            Case("what should I watch tonight", MediaSkillId.WATCH_RECOMMENDER),
            Case("recommend something funny with english audio", MediaSkillId.WATCH_RECOMMENDER),

            // ---- CHARACTER_IDENTIFICATION (requires visual context) ----------
            Case(
                query = "who is that",
                expected = MediaSkillId.CHARACTER_IDENTIFICATION,
                playerState = playingMovie,
                hasVisualContext = true,
            ),

            // ---- RECAP -------------------------------------------------------
            Case("recap the last five minutes", MediaSkillId.RECAP),
            Case("what happened in the last minute", MediaSkillId.RECAP),
            Case("summarize the story so far", MediaSkillId.RECAP),

            // ---- METADATA_QA -------------------------------------------------
            Case("who directed this", MediaSkillId.METADATA_QA, playerState = playingMovie),
            Case("who wrote this episode", MediaSkillId.METADATA_QA, playerState = playingMovie),
            Case("what genre is this", MediaSkillId.METADATA_QA, playerState = playingMovie),
            Case("what year did this come out", MediaSkillId.METADATA_QA, playerState = playingMovie),
            Case("who's in the cast", MediaSkillId.METADATA_QA, playerState = playingMovie),

            // ---- GENERAL_CHAT (the fall-through bucket) ----------------------
            Case("hello there", MediaSkillId.GENERAL_CHAT),
            Case("how are you", MediaSkillId.GENERAL_CHAT),
            Case("tell me a joke", MediaSkillId.GENERAL_CHAT),
        )

        /**
         * Queries that we *wish* the classifier would route to a specific
         * skill but currently fall through to GENERAL_CHAT (or a related
         * skill). Not asserted — the test would be red without classifier
         * changes that are out of scope for the current commit. Use this list
         * as the starter set when rewriting the classifier heuristics; when
         * one moves to its intended routing, migrate the case into [CASES]
         * in the same commit.
         *
         * Present state of each, as observed by the sentinel test:
         *   - "resume something"                    -> GENERAL_CHAT (want CONTINUE_WATCHING)
         *   - "do I have interstellar"              -> GENERAL_CHAT (want LIBRARY_SEARCH)
         *   - "who is Denis Villeneuve"             -> GENERAL_CHAT (want EXTERNAL_KNOWLEDGE)
         *   - "what else has ridley scott done"     -> WATCH_RECOMMENDER (want EXTERNAL_KNOWLEDGE)
         *   - "pick something for me"               -> GENERAL_CHAT (want MOOD_SURPRISE)
         *   - "recommend a movie under 90 minutes"  -> MOOD_SURPRISE (want WATCH_RECOMMENDER)
         *   - "who is that guy" (with visual)       -> EXTERNAL_KNOWLEDGE (want CHARACTER_IDENTIFICATION)
         *   - "what did they just say"              -> GENERAL_CHAT (want DIALOGUE_EXPLAINER)
         *   - "what does that mean"                 -> GENERAL_CHAT (want DIALOGUE_EXPLAINER)
         *   - "what's the rating"                   -> GENERAL_CHAT (want METADATA_QA)
         */
        @Suppress("unused")
        private val ASPIRATIONAL_IMPROVEMENTS = Unit
    }
}
