package dev.jdtech.jellyfin.player.local.presentation

import androidx.media3.common.C
import androidx.media3.common.Tracks

/**
 * Pure, unit-testable predicates over track formats used by [PlayerTrackSelector].
 *
 * Each predicate ships in two forms:
 * - a primitive-typed overload (so the rule can be JVM-tested without constructing a
 *   Media3 [Tracks.Group]); and
 * - a `Tracks.Group` overload that pulls `format[0]`'s fields and delegates.
 *
 * Lives in this package (as `internal`) so tests in the same module can reach it
 * without exposing it to `:app:unified`.
 */
internal object PlayerTrackHeuristics {

    /**
     * Matches labels of forced / signs-only sibling subtitle tracks as whole words, so
     * "Designer's Cut" or "Assigned" don't accidentally look forced. Covers common muxes:
     * "Forced", "English (Forced)", "Signs", "Signs & Songs", "Songs".
     */
    private val FORCED_LABEL_PATTERN = Regex("""\b(forced|signs?|songs?)\b""", RegexOption.IGNORE_CASE)
    private val FULL_DIALOGUE_LABEL_PATTERN = Regex("""\b(full|dialogue?|subtitles?)\b""", RegexOption.IGNORE_CASE)

    /**
     * Returns true when a subtitle track is marked forced or is clearly a signs/songs-only
     * sibling track (based on its label).
     *
     * These tracks are useful to viewers who understand the audio and only need foreign
     * on-screen text or title cards translated. They are NOT a substitute for full
     * dialogue, so the smart selector must not auto-land on one when the viewer doesn't
     * speak the audio language — doing so leaves most of the conversation un-subtitled.
     *
     * Triggers:
     * - Media3's [C.SELECTION_FLAG_FORCED] bit is set, OR
     * - the format `label` matches `\b(forced|signs?|songs?)\b` case-insensitively.
     *
     * The label heuristic is necessary because many MKV muxes ship sibling tracks as
     * Full Dialogue / Forced / Signs without setting the selection flag correctly.
     */
    fun isForcedOrSignsOnly(label: String?, selectionFlags: Int): Boolean {
        val normalized = label.orEmpty()
        if (
            normalized.isNotBlank() &&
                FULL_DIALOGUE_LABEL_PATTERN.containsMatchIn(normalized) &&
                !FORCED_LABEL_PATTERN.containsMatchIn(normalized)
        ) {
            return false
        }
        if ((selectionFlags and C.SELECTION_FLAG_FORCED) != 0) return true
        if (normalized.isEmpty()) return false
        return FORCED_LABEL_PATTERN.containsMatchIn(normalized)
    }

    fun isForcedOrSignsOnly(group: Tracks.Group): Boolean {
        val format = group.getTrackFormat(0)
        return isForcedOrSignsOnly(format.label, format.selectionFlags)
    }

    /**
     * Ranks forced / signs-only candidate tracks for the "viewer understands the audio but
     * wants the foreign-language parts translated" case. Higher is better.
     *
     * A track tagged [C.SELECTION_FLAG_FORCED] or labelled "Forced" is the canonical
     * foreign-dialogue overlay, so it outranks a "Signs & Songs" sibling (which mostly
     * translates on-screen text and karaoke). [C.SELECTION_FLAG_DEFAULT] breaks ties.
     *
     * Only meaningful for tracks that already passed [isForcedOrSignsOnly]; on a full
     * dialogue track the score is still defined but the caller should never feed one in.
     */
    fun forcedSubtitlePriority(label: String?, selectionFlags: Int): Int {
        val normalized = label.orEmpty()
        var score = 0
        if ((selectionFlags and C.SELECTION_FLAG_FORCED) != 0) score += 100
        if (Regex("""\bforced\b""", RegexOption.IGNORE_CASE).containsMatchIn(normalized)) score += 50
        if ((selectionFlags and C.SELECTION_FLAG_DEFAULT) != 0) score += 10
        return score
    }

    fun forcedSubtitlePriority(group: Tracks.Group): Int {
        val format = group.getTrackFormat(0)
        return forcedSubtitlePriority(format.label, format.selectionFlags)
    }
}
