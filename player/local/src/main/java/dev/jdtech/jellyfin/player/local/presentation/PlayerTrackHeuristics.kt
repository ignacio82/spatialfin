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
        if ((selectionFlags and C.SELECTION_FLAG_FORCED) != 0) return true
        val normalized = label.orEmpty()
        if (normalized.isEmpty()) return false
        return FORCED_LABEL_PATTERN.containsMatchIn(normalized)
    }

    fun isForcedOrSignsOnly(group: Tracks.Group): Boolean {
        val format = group.getTrackFormat(0)
        return isForcedOrSignsOnly(format.label, format.selectionFlags)
    }
}
