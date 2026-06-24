package dev.jdtech.jellyfin.player.xr

import java.util.Locale

/**
 * Detects the spatial *projection* of a video — flat, 180° (hemisphere) or 360°
 * (sphere) — from its title / file names. This is the geometry axis and is
 * orthogonal to [StereoModeDetector] (which decides the per-eye layout: mono,
 * side-by-side, top-bottom, multiview). A VR180 clip, for example, is usually
 * also side-by-side, so the two detectors compose.
 *
 * Jellyfin exposes no native "spherical" flag, so — like [StereoModeDetector] —
 * this leans on file-name / title conventions. It is deliberately conservative
 * (word-boundary matches, explicit VR/360/equirect markers) so that incidental
 * digits such as resolutions ("1080") or years ("2018") never trip a false
 * positive. Container-level metadata (media3 `Format.projectionData`) is the
 * authoritative override and is applied at runtime in `SpatialPlayerScreen`; a
 * manual player-menu override has the final say.
 */
object ProjectionModeDetector {
    enum class ProjectionMode {
        FLAT,
        VR180,
        VR360,
    }

    fun detect(
        title: String?,
        sourceNames: List<String>,
    ): ProjectionMode {
        val haystack =
            buildString {
                append(title.orEmpty())
                append(' ')
                append(sourceNames.joinToString(separator = " "))
            }.lowercase(Locale.ROOT)

        return when {
            VR360_REGEX.containsMatchIn(haystack) -> ProjectionMode.VR360
            VR180_REGEX.containsMatchIn(haystack) -> ProjectionMode.VR180
            else -> ProjectionMode.FLAT
        }
    }

    /** Maps a [ProjectionMode] to the player's `"projection"` intent-extra string. */
    fun asExtra(mode: ProjectionMode): String = when (mode) {
        ProjectionMode.VR180 -> PROJECTION_180
        ProjectionMode.VR360 -> PROJECTION_360
        ProjectionMode.FLAT -> PROJECTION_FLAT
    }

    const val PROJECTION_FLAT = "flat"
    const val PROJECTION_180 = "180"
    const val PROJECTION_360 = "360"
}

// 360: explicit "360" only when paired with a VR/sphere/video marker (or an
// explicit equirectangular / spherical / omnidirectional tag), so a stray "360"
// (resolutions like "360p", numbers like "13600") can't promote a flat movie.
// `(?<!\d)` / `(?!\d)` keep the numeric token from being part of a larger number;
// note `\b` is unusable here because filename separators ('_', '.') are word
// chars, so "concert_360_video" would have no boundary before the digits.
private val VR360_REGEX = Regex(
    """vr[\s._-]?360(?!\d)|(?<!\d)360[\s._-]?(?:vr|video|sphere|mono|3d|sbs|tb)|(?:vr|mono|3d|sbs|tb|over[\s._-]?under)[\s._-]?360(?!\d)|equirect(?:angular)?|spherical|omnidirectional|360x180|\.insv""",
)

// 180: VR180 / 180° markers and common VR180 fisheye lens tags. A bare "180"
// must be qualified by vr / 3d / sbs / degree to avoid matching bitrates, years,
// or episode numbers ("Episode 180").
private val VR180_REGEX = Regex(
    """vr[\s._-]?180(?!\d)|(?<!\d)180[\s._-]?(?:vr|3d|sbs|degree|deg)|(?:vr|3d|sbs)[\s._-]?180(?!\d)|fisheye|mkx200|rf52|180x180""",
)
