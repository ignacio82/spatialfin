package dev.jdtech.jellyfin.models

/**
 * Tells a full-dialogue subtitle track apart from a forced / signs-only sibling,
 * using only the track's label.
 *
 * The distinction decides whether a track is a substitute for dialogue you
 * can't follow (full) or just a translation of on-screen text and the odd
 * foreign line (forced/signs). Landing on a forced track when the viewer
 * doesn't speak the audio language leaves most of the conversation
 * un-subtitled, so the two must never be confused.
 *
 * Lives in `:data` because both the in-player track selector and the detail
 * screen's "what will play" chips have to reach the same verdict — a chip that
 * disagrees with the player is worse than no chip.
 */
object SubtitleTrackRole {

    /**
     * Forced / signs-only keywords, matched as whole words so "Designer's Cut"
     * and "Assigned" don't read as forced. Covers the common muxes: "Forced",
     * "English (Forced)", "Foreign Dialogue", "Foreign Parts", "Non-English",
     * "Signs", "Signs & Songs", "Narrative", "Partly Foreign", "S&S".
     */
    private val FORCED_LABEL = Regex(
        """\b(forced?|foreign|narrative|non[-_\s]?english|signs?|songs?|short|partly)\b|s[&+/]s""",
        RegexOption.IGNORE_CASE,
    )

    /** Keywords that positively mark a track as the full dialogue track. */
    private val FULL_DIALOGUE_LABEL = Regex(
        """\b(full|dialogue?|subtitles?)\b""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * True when [label] reads as a forced / signs-only track.
     *
     * An explicit "Full Dialogue" marking wins over an incidental forced
     * keyword, so "Full Dialogue (Foreign Parts Included)" stays full.
     */
    fun isForcedOrSignsOnlyLabel(label: String?): Boolean {
        val normalized = label.orEmpty()
        if (normalized.isBlank()) return false
        if (
            FULL_DIALOGUE_LABEL.containsMatchIn(normalized) &&
            !FORCED_LABEL.containsMatchIn(normalized)
        ) {
            return false
        }
        return FORCED_LABEL.containsMatchIn(normalized)
    }

    /** True when [label] positively marks the track as full dialogue. */
    fun isFullDialogueLabel(label: String?): Boolean {
        val normalized = label.orEmpty()
        return normalized.isNotBlank() &&
            FULL_DIALOGUE_LABEL.containsMatchIn(normalized) &&
            !FORCED_LABEL.containsMatchIn(normalized)
    }
}

/**
 * True when this subtitle stream is a forced / signs-only track, judged from
 * the labels Jellyfin reports. Unlike the in-player check there is no
 * container selection flag to consult here — the detail screen only ever sees
 * server metadata.
 */
fun SpatialFinMediaStream.isForcedOrSignsOnly(): Boolean =
    SubtitleTrackRole.isForcedOrSignsOnlyLabel(title) ||
        SubtitleTrackRole.isForcedOrSignsOnlyLabel(displayTitle)
