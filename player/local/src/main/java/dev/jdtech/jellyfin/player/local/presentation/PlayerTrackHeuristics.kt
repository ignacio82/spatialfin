package dev.jdtech.jellyfin.player.local.presentation

import androidx.media3.common.C
import androidx.media3.common.Tracks
import dev.jdtech.jellyfin.models.SubtitleTrackRole

/**
 * Pure, unit-testable predicates over track formats used by [PlayerTrackSelector].
 *
 * Each predicate ships in two forms:
 * - a primitive-typed overload (so the rule can be JVM-tested without constructing a
 *   Media3 [Tracks.Group]); and
 * - a `Tracks.Group` overload that pulls `format[0]`'s fields and delegates.
 *
 * Lives in `:player:local`; made public so the FCast inbound receiver player in
 * `:player:xr` (which depends on `:player:local`) can reuse the exact same
 * forced/signs detection when applying the smart subtitle default on a cast
 * session — keeping the policy identical across direct playback and casting.
 */
object PlayerTrackHeuristics {

    /**
     * Returns true when a subtitle track is marked forced or is clearly a signs/songs/foreign-dialogue
     * sibling track (based on its label).
     *
     * These tracks are useful to viewers who understand the audio and only need foreign
     * on-screen text, title cards, or foreign-language dialogue scenes translated. They are NOT
     * a substitute for full dialogue, so the smart selector must not auto-land on one when the
     * viewer doesn't speak the audio language — doing so leaves most of the conversation un-subtitled.
     *
     * Triggers:
     * - Media3's [C.SELECTION_FLAG_FORCED] bit is set, OR
     * - the format `label` matches forced / foreign / signs / songs keywords case-insensitively.
     *
     * The label heuristic is necessary because many MKV muxes ship sibling tracks as
     * Full Dialogue / Forced / Foreign Dialogue without setting the selection flag correctly.
     */
    fun isForcedOrSignsOnly(label: String?, selectionFlags: Int): Boolean {
        // An explicit "Full Dialogue" marking outranks even the container's
        // forced flag: muxes set that bit wrong far more often than they
        // mislabel the track.
        if (SubtitleTrackRole.isFullDialogueLabel(label)) return false
        if ((selectionFlags and C.SELECTION_FLAG_FORCED) != 0) return true
        return SubtitleTrackRole.isForcedOrSignsOnlyLabel(label)
    }

    fun isForcedOrSignsOnly(group: Tracks.Group): Boolean {
        val format = group.getTrackFormat(0)
        return isForcedOrSignsOnly(format.label, format.selectionFlags)
    }

    /**
     * Ranks forced / signs-only candidate tracks for the "viewer understands the audio but
     * wants the foreign-language parts translated" case. Higher is better.
     *
     * A track tagged [C.SELECTION_FLAG_FORCED] or labelled "Forced" / "Foreign" is the canonical
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
        if (Regex("""\b(forced?|foreign|narrative|non[-_\s]?english|partly)\b""", RegexOption.IGNORE_CASE).containsMatchIn(normalized)) score += 50
        if (Regex("""\b(signs?|songs?)\b|s[&+/]s""", RegexOption.IGNORE_CASE).containsMatchIn(normalized)) score += 25
        if ((selectionFlags and C.SELECTION_FLAG_DEFAULT) != 0) score += 10
        return score
    }

    fun forcedSubtitlePriority(group: Tracks.Group): Int {
        val format = group.getTrackFormat(0)
        return forcedSubtitlePriority(format.label, format.selectionFlags)
    }
}
