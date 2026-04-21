package dev.jdtech.jellyfin.player.xr.voice.prompt

import androidx.compose.ui.geometry.Offset
import dev.jdtech.jellyfin.player.session.voice.PlayerStateSnapshot
import dev.jdtech.jellyfin.player.xr.voice.AssistantPreferences

/**
 * Typed input bundle for the chat-prompt pipeline.
 *
 * [SmartChatEngine] used to assemble prompts inline via chains of `buildString`
 * and `trimIndent()` — the template text was scattered across the engine and
 * the skill registry, there was no way to diff rendered prompts against a
 * baseline without booting the LLM, and every field had to be threaded through
 * a 10-argument private method. Moving the raw inputs into this value class
 * (and the composed output into [PromptSections]) lets the prompt pipeline
 * stay a pure function of typed data — easy to snapshot-test, easy to add new
 * optional sections to without breaking callers.
 */
data class PromptContext(
    /** The user's raw spoken/typed question. */
    val question: String = "",
    /** Snapshot of what's on screen. */
    val playerState: PlayerStateSnapshot = PlayerStateSnapshot(),
    /** Verbosity / spoiler policy / speech preferences. */
    val assistantPreferences: AssistantPreferences = AssistantPreferences(),
    /** Long-form recap of prior episodes/scenes — see PlaylistManager.getStorySoFarContext. */
    val storySoFar: String? = null,
    /** Recent subtitle lines joined with newlines; truncated to the tail inside renderers. */
    val subtitleContext: String = "",
    /** Prior (user, assistant) turns for the current conversation. */
    val conversationHistory: List<Pair<String, String>> = emptyList(),
    /** Pre-formatted related-items block when a skill surfaces library hits (WATCH_RECOMMENDER etc.). */
    val relatedItems: String? = null,
    /**
     * Pre-formatted external knowledge (TMDB, Wikipedia, current-item digest) gathered
     * by [dev.jdtech.jellyfin.player.xr.voice.ChatToolRegistry] before the main
     * inference. Rendered as its own section ahead of [relatedItems] so the model
     * sees grounded facts distinctly from "items from the user's library".
     */
    val researchNotes: String? = null,
    /** Pointer / gaze fraction of the video panel (0..1), used by CHARACTER_IDENTIFICATION. */
    val pointerPosition: Offset? = null,
    /** Per-skill instruction block produced by [MediaSkillRegistry]. */
    val taskInstructions: String = "",
)

/** One labelled block in the rendered prompt. Empty sections are skipped by the builder. */
data class PromptSection(
    val header: String?,
    val body: String,
)

/**
 * Ordered list of sections ready to render. Keeping this as a value class (rather
 * than a plain String) means tests can assert against *structure* — "the Cast section
 * is absent when castNames is empty" — instead of grepping the final blob.
 */
data class PromptSections(val sections: List<PromptSection>) {
    fun render(): String = sections.joinToString(separator = "\n\n") { section ->
        val header = section.header
        if (header.isNullOrBlank()) section.body else "$header\n${section.body}"
    }

    /** Render the sections as a single trimmed block, matching the legacy `.trimIndent()` output. */
    fun renderTrimmed(): String = render().trimEnd()

    /** True when [header]'s section exists in the composed output. Useful for tests. */
    fun hasSection(header: String): Boolean = sections.any { it.header == header }

    fun sectionBody(header: String): String? =
        sections.firstOrNull { it.header == header }?.body
}

@DslMarker annotation class PromptDsl

/** Builder — mutations happen via [section] / [sectionIf]. */
@PromptDsl
class PromptBuilder {
    private val _sections = mutableListOf<PromptSection>()

    /** Append a section if [body] has content. Trailing whitespace is trimmed. */
    fun section(header: String? = null, body: String) {
        val trimmed = body.trimEnd()
        if (trimmed.isNotBlank()) _sections.add(PromptSection(header, trimmed))
    }

    /** Append a section if [condition] is true. Short-circuits the body lambda otherwise. */
    inline fun sectionIf(
        condition: Boolean,
        header: String? = null,
        body: () -> String,
    ) {
        if (condition) section(header, body())
    }

    /**
     * Append a section whose body is composed from ordered key-value lines
     * (`"Title: Alien"`). Pairs with blank values drop out automatically, so the
     * rendered prompt never shows `"Rating: "` on a line by itself.
     */
    fun facts(header: String? = null, vararg lines: Pair<String, Any?>) {
        val body = lines.mapNotNull { (label, value) ->
            val str = value?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            "$label: $str"
        }.joinToString("\n")
        section(header, body)
    }

    fun build(): PromptSections = PromptSections(_sections.toList())
}

/** Entry point for the DSL. */
fun promptSections(block: PromptBuilder.() -> Unit): PromptSections =
    PromptBuilder().apply(block).build()
