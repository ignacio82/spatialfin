package dev.jdtech.jellyfin.player.core

import java.util.Locale

object StereoModeDetector {
    enum class StereoMode {
        MONO,
        SIDE_BY_SIDE,
        TOP_BOTTOM,
        MULTIVIEW,
    }

    fun detect(
        title: String?,
        video3DFormat: String?,
        sourceNames: List<String>,
        videoCodecs: List<String> = emptyList(),
    ): StereoMode {
        if (!video3DFormat.isNullOrBlank()) {
            val fmt = video3DFormat.lowercase(Locale.ROOT)
            when {
                MV_HEVC_REGEX.containsMatchIn(fmt) -> return StereoMode.MULTIVIEW
                EXPLICIT_TOP_BOTTOM_REGEX.containsMatchIn(fmt) || CONTEXTUAL_TOP_BOTTOM_REGEX.containsMatchIn(fmt) -> return StereoMode.TOP_BOTTOM
                EXPLICIT_SIDE_BY_SIDE_REGEX.containsMatchIn(fmt) || CONTEXTUAL_SIDE_BY_SIDE_REGEX.containsMatchIn(fmt) -> return StereoMode.SIDE_BY_SIDE
            }
        }

        val haystack =
            buildString {
                append(title.orEmpty())
                append(' ')
                append(sourceNames.joinToString(separator = " "))
            }.lowercase(Locale.ROOT)

        val hasHevcSignal =
            HEVC_REGEX.containsMatchIn(haystack) ||
                videoCodecs.any { codec ->
                    val normalized = codec.lowercase(Locale.ROOT)
                    normalized.contains("hevc") ||
                        normalized.contains("h265") ||
                        normalized.contains("h.265") ||
                        normalized.contains("x265")
                }

        val has3dTag = GENERIC_3D_REGEX.containsMatchIn(haystack)

        return when {
            // Android XR multiview playback is MV-HEVC-based. Legacy MVC/H.264 titles may
            // still carry "mvc" or generic "multiview" tags, but routing those into the
            // multiview surface mode renders only the primary eye on-device.
            MV_HEVC_REGEX.containsMatchIn(haystack) -> StereoMode.MULTIVIEW
            MULTIVIEW_REGEX.containsMatchIn(haystack) && hasHevcSignal -> StereoMode.MULTIVIEW
            EXPLICIT_TOP_BOTTOM_REGEX.containsMatchIn(haystack) ||
                (has3dTag && CONTEXTUAL_TOP_BOTTOM_REGEX.containsMatchIn(haystack)) -> StereoMode.TOP_BOTTOM
            EXPLICIT_SIDE_BY_SIDE_REGEX.containsMatchIn(haystack) ||
                (has3dTag && CONTEXTUAL_SIDE_BY_SIDE_REGEX.containsMatchIn(haystack)) -> StereoMode.SIDE_BY_SIDE
            else -> StereoMode.MONO
        }
    }
}

private val MV_HEVC_REGEX = Regex("""\b(mv-hevc|mvhevc|spatial(?:[\s.-]?video)?)\b""")
private val MULTIVIEW_REGEX = Regex("""\bmultiview\b""")
private val HEVC_REGEX = Regex("""\b(hevc|h265|h\.265|x265)\b""")
private val GENERIC_3D_REGEX = Regex("""\b3d\b""")

// Unambiguous 3D indicators (do not require explicit "3d" token)
private val EXPLICIT_SIDE_BY_SIDE_REGEX =
    Regex("""\b(hsbs|half[\s.-]?sbs|fsbs|full[\s.-]?sbs|side[\s.-]?by[\s.-]?side|side[\s.-]?and[\s.-]?side)\b""")

private val EXPLICIT_TOP_BOTTOM_REGEX =
    Regex("""\b(htab|half[\s.-]?tab|ftab|full[\s.-]?tab|hou|half[\s.-]?ou|fou|full[\s.-]?ou|top[\s.-]?bottom|top[\s.-]?and[\s.-]?bottom|over[\s.-]?under)\b""")

// Ambiguous short tokens (tab, ou, tb, sbs) that require a "3d" context tag or "3d-" prefix
private val CONTEXTUAL_SIDE_BY_SIDE_REGEX =
    Regex("""\b(3d[\s.-]?h?sbs|sbs)\b""")

private val CONTEXTUAL_TOP_BOTTOM_REGEX =
    Regex("""\b(3d[\s.-]?(tab|tb|ou)|tab|tb|ou)\b""")

