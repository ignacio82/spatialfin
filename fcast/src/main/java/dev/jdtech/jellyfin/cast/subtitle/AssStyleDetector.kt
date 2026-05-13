package dev.jdtech.jellyfin.cast.subtitle

/**
 * Pure heuristic that classifies an ASS/SSA subtitle track as **styled** (contains override tags
 * that the default Media3 text renderer would silently strip) or **plain dialogue** (just text,
 * renders fine on every receiver). The cast sender uses this to decide whether to request a
 * burn-in transcode from Jellyfin when the receiver lacks [dev.jdtech.jellyfin.cast.CastCapability.NativeAss].
 *
 * Why this matters: burn-in transcodes are expensive for the Jellyfin server (re-encode the
 * whole video with libass-rendered pixels overlaid), and the user experiences a small startup
 * delay. We only want to pay that cost for tracks that would actually look wrong without it —
 * anime fansub releases with `\pos`, karaoke, signs etc. A pure-dialogue ASS track without
 * override tags renders identically through ExoPlayer's default renderer, so triggering a
 * burn-in for those is wasted server CPU.
 *
 * The detector is keyword-based and intentionally conservative: when in doubt (binary content,
 * non-UTF-8, parse failures), it returns `true` so the receiver doesn't render garbage.
 */
object AssStyleDetector {

    /**
     * Override tags that signal "this line is doing more than printing text." Each entry is a
     * fragment that, if found inside a `{...}` override block, marks the track as styled.
     *
     * The list comes from the spec in `cast-implementation.md` §13.7. New tags can be added
     * here without changing call sites — the detector treats them uniformly.
     */
    private val STYLED_TAGS = listOf(
        // Positioning
        "\\pos", "\\an", "\\move", "\\org",
        // Rotation
        "\\frx", "\\fry", "\\frz", "\\fr",
        // Karaoke
        "\\k", "\\kf", "\\ko", "\\K",
        // Clipping / masking / drawing
        "\\clip", "\\iclip", "\\p1",
        // Fades / animation
        "\\fad", "\\fade", "\\t(",
        // Per-character scaling / blur
        "\\fscx", "\\fscy", "\\blur", "\\be",
    )

    /**
     * Maximum number of dialogue lines to scan. ~50 is enough to catch most fansub typesetting
     * (intro signs land in the first few minutes; karaoke is typically in the first 90s). Any
     * styled tag in the file means we burn in, so we don't need to scan to the end.
     */
    private const val MAX_LINES_TO_SCAN = 50

    /**
     * Returns true when [content] contains at least one Dialogue line with an override tag from
     * [STYLED_TAGS]. Designed to run on the raw bytes (or string) of a `.ass` file or the
     * first few KB of one — the detector only looks at lines starting with `Dialogue:` and
     * stops after [MAX_LINES_TO_SCAN] of them.
     *
     * Empty / non-ASS content returns `false` (no styling, no transcode needed).
     */
    fun isStyled(content: String): Boolean {
        if (content.isBlank()) return false
        var scanned = 0
        for (rawLine in content.lineSequence()) {
            val line = rawLine.trimStart()
            if (!line.startsWith("Dialogue:", ignoreCase = true)) continue
            scanned++
            // Override tags only live inside `{...}` blocks per the ASS spec, but a faster
            // substring check is fine — false positives on a literal "\pos" in dialogue text
            // would burn in unnecessarily, not break anything.
            if (containsStyledTag(line)) return true
            if (scanned >= MAX_LINES_TO_SCAN) break
        }
        return false
    }

    private fun containsStyledTag(line: String): Boolean {
        for (tag in STYLED_TAGS) {
            if (line.contains(tag, ignoreCase = false)) return true
        }
        return false
    }
}
