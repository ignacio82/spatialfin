package dev.jdtech.jellyfin.player.xr.voice.prompt

import dev.jdtech.jellyfin.player.session.voice.PlayerStateSnapshot

/**
 * Builders for the three LLM prompt shapes the XR voice pipeline emits. Each
 * returns a [PromptSections] structure so tests can assert *which* sections
 * show up without booting the LLM, and `render()` produces the byte-for-byte
 * string the engine would previously have hand-concatenated.
 *
 * All inputs come through [PromptContext]; no skill-specific state leaks into
 * these functions.
 */

/**
 * Main chat prompt. Mirrors the legacy `SmartChatEngine.buildPrompt`
 * output — same section ordering, same truncation rules, same `.trimIndent()`
 * shape — so swapping callers is a no-op for the model.
 */
fun chatPrompt(ctx: PromptContext): PromptSections = promptSections {
    val prefs = ctx.assistantPreferences
    val state = ctx.playerState

    section(
        header = null,
        body = buildString {
            // Persona first — the old preamble read like an API spec and the
            // on-device model dutifully reproduced that register. "Friend who
            // loves movies" reframes the whole answer toward conversation,
            // while the concrete "no preamble / no bullets / 2-4 sentences"
            // lines keep small models (Gemini Nano, Gemma 4B) from defaulting
            // to "Based on the research notes, here are the facts:" style.
            appendLine("You are SpatialFin — a friend who loves movies and TV, chatting with the user about what they're watching. Warm, specific, a little opinionated. Not a Wikipedia narrator, not a search engine.")
            appendLine()
            appendLine("Speak in 2-4 short spoken sentences. No headers, no bullet lists, no preamble like \"based on my research\" or \"here's what I found\" — just start the answer.")
            appendLine("Only cite a source (TMDB, OMDb, Wikipedia) when you quote a specific number or direct claim from it. Weave the source name into the sentence, don't label each line.")
            appendLine("Respect the spoiler policy: ${prefs.spoilerPolicy}.")
            appendLine("Verbosity: ${prefs.verbosity}.")
            appendLine("Do not invent details beyond the supplied context. If you don't know, say so briefly.")
            if (ctx.taskInstructions.isNotBlank()) append(ctx.taskInstructions)
        },
    )

    // Metadata pairs — blank fields drop out automatically.
    facts(
        header = null,
        "Current title" to state.currentItemTitle,
        "Series" to state.currentSeriesName,
        "Season" to state.currentSeasonNumber,
        "Episode" to state.currentEpisodeNumber,
        "Year" to state.productionYear,
        "Rating" to state.officialRating,
        "Overview" to state.currentOverview.take(OVERVIEW_CAP).takeIf { it.isNotBlank() },
        "Genres" to state.currentGenres.joinToString(", "),
        "Community ratings" to state.currentRatings.joinToString(", "),
        "Cast" to state.castNames.joinToString(", "),
        "Directors" to state.directors.joinToString(", "),
        "Writers" to state.writers.joinToString(", "),
        "Current chapter" to state.currentChapterName,
        "Story so far" to ctx.storySoFar?.take(STORY_CAP),
        "Recent subtitles" to ctx.subtitleContext.takeLast(SUBTITLE_CAP).takeIf { it.isNotBlank() },
        "Audio track" to state.currentAudioTrack,
        "Subtitle track" to state.currentSubtitleTrack,
    )

    sectionIf(ctx.conversationHistory.isNotEmpty(), header = "Conversation history:") {
        ctx.conversationHistory.joinToString("\n") { (u, a) -> "User: $u\nAssistant: $a" }
    }

    sectionIf(!ctx.researchNotes.isNullOrBlank(), header = "Research notes (verified facts — ground your answer in these):") {
        ctx.researchNotes.orEmpty()
    }

    sectionIf(!ctx.relatedItems.isNullOrBlank(), header = "Items available in library that may be relevant:") {
        ctx.relatedItems.orEmpty()
    }

    sectionIf(ctx.pointerPosition != null, header = null) {
        val p = ctx.pointerPosition!!
        "User is currently pointing/looking at coordinates: " +
            "x=${(p.x * 100).toInt()}%, y=${(p.y * 100).toInt()}% of the video screen."
    }

    section(header = "User question:", body = ctx.question)
}

/**
 * Focused cloud-side recap summariser. Intentionally short — long prompts
 * waste Gemini Flash tokens and dilute the summary.
 */
fun recapPrompt(ctx: PromptContext): PromptSections = promptSections {
    val state = ctx.playerState
    val showInfo = buildShowInfo(state)
    val chapterHint = state.currentChapterName
        ?.takeIf { it.isNotBlank() }
        ?.let { " (chapter: $it)" }
        .orEmpty()
    val cappedSubtitles = ctx.subtitleContext.lines().takeLast(RECAP_SUBTITLE_LINE_CAP).joinToString("\n")

    section(body = "The user is watching $showInfo$chapterHint and asked: \"${ctx.question}\"")
    section(header = "Subtitles from that moment:", body = cappedSubtitles)
    section(
        body = "In 2-3 natural sentences, summarize what just happened. " +
            "Be conversational — do not read the lines back verbatim.",
    )
}

/**
 * Vision prompt for CHARACTER_IDENTIFICATION. The `Analyze the video frame
 * above and respond.` sentence is rendered last so Gemma sees the image → cast
 * → task → final instruction order it was trained with.
 *
 * Recent subtitles are included when present: if a character was just
 * addressed by name in the last few seconds of dialogue ("Jon, wait!") the
 * model can correlate that with whoever is centred in the frame, which is
 * a much stronger signal than face recognition alone on a 2-4B model.
 */
fun characterIdentificationPrompt(ctx: PromptContext): PromptSections = promptSections {
    section(body = "You are SpatialFin, an on-device XR assistant identifying characters in a video frame.")
    section(body = ctx.taskInstructions)

    val castBlock = ctx.playerState.castWithCharacters.take(CAST_PAIR_CAP)
        .joinToString("\n") { (actor, character) -> "Character: $character — Actor: $actor" }
        .ifBlank {
            ctx.playerState.castNames.take(CAST_NAME_CAP).joinToString("\n") { "Actor: $it" }
        }
    sectionIf(castBlock.isNotBlank(), header = "Reference cast:") { castBlock }

    // Take the tail so the most recent dialogue (the line most likely to
    // contain the name the user wants identified) is always included. Capping
    // at SUBTITLE_CAP mirrors the chat-prompt budget so we don't blow the
    // on-device context window on older dialogue.
    sectionIf(ctx.subtitleContext.isNotBlank(), header = "Recent dialogue (the frame's moment in time):") {
        ctx.subtitleContext.takeLast(SUBTITLE_CAP)
    }

    section(body = "Analyze the video frame above and respond.")
}

/**
 * Task-instruction block for CHARACTER_IDENTIFICATION produced by
 * [dev.jdtech.jellyfin.player.xr.voice.MediaSkillRegistry]. Extracted here so
 * the instruction prompt is diffable and testable alongside the chat prompt.
 *
 * Note: this function does **not** cap [castPairs]. Callers cap upstream — the
 * legacy site in `MediaSkillRegistry.characterIdentificationPlan` takes 12
 * pairs from `playerState.castWithCharacters` and passes them straight in.
 */
fun characterIdentificationInstructions(
    titleLine: String,
    castPairs: List<Pair<String, String>>,
): String {
    val castLines = castPairs.joinToString("\n") { (actor, character) ->
        "  Character: $character — Actor: $actor"
    }
    // Commit-or-admit: a tight single-paragraph instruction that forces the
    // model to either name a cast entry right away or bail in one sentence.
    // The old multi-step "state 1. name, 2. actor, 3. one sentence" version
    // encouraged the model to fill all three slots even when uncertain,
    // producing 15-20s of generic description ("a dark-haired man in
    // armour..."). The new phrasing explicitly treats a generic description
    // as failure and short-circuits it.
    return buildString {
        appendLine("Task: Identify the character centred in the frame from the cast list below.")
        appendLine("Reply in ONE short spoken sentence.")
        appendLine("- If you are confident: name the character, then the actor, then one clause about their role. Pull names ONLY from the cast list — do not invent.")
        appendLine("- If you are not confident, or the answer would only be a generic description of their appearance, say exactly: \"I can't tell which character this is from the frame.\" — do NOT describe what they look like, do NOT list the cast.")
        appendLine("- Recent dialogue may mention a name right before the frame — weight that heavily when matching.")
        if (castLines.isNotBlank()) {
            appendLine()
            appendLine("Cast for $titleLine:")
            append(castLines)
        } else {
            appendLine()
            append("No cast metadata available — admit you can't identify anyone instead of describing the frame.")
        }
    }.trimEnd()
}

private fun buildShowInfo(state: PlayerStateSnapshot): String = buildString {
    if (!state.currentSeriesName.isNullOrBlank()) {
        append(state.currentSeriesName)
        if (state.currentSeasonNumber != null && state.currentEpisodeNumber != null) {
            append(" S${state.currentSeasonNumber}E${state.currentEpisodeNumber}")
        }
        if (state.currentItemTitle.isNotBlank()) {
            append(" \"${state.currentItemTitle}\"")
        }
    } else {
        append(state.currentItemTitle.ifBlank { "this content" })
    }
}

// Matching the hard-coded caps the legacy builders used verbatim so swapping
// callers never changes the rendered prompt byte-for-byte.
private const val OVERVIEW_CAP = 1000
private const val STORY_CAP = 1200
private const val SUBTITLE_CAP = 1200
private const val RECAP_SUBTITLE_LINE_CAP = 60
private const val CAST_PAIR_CAP = 12
private const val CAST_NAME_CAP = 8
