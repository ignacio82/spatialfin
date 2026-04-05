package dev.jdtech.jellyfin.player.xr

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
        val haystack =
            buildString {
                append(title.orEmpty())
                append(' ')
                append(video3DFormat.orEmpty())
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

        return when {
            // Android XR multiview playback is MV-HEVC-based. Legacy MVC/H.264 titles may
            // still carry "mvc" or generic "multiview" tags, but routing those into the
            // multiview surface mode renders only the primary eye on-device.
            MV_HEVC_REGEX.containsMatchIn(haystack) -> StereoMode.MULTIVIEW
            MULTIVIEW_REGEX.containsMatchIn(haystack) && hasHevcSignal -> StereoMode.MULTIVIEW
            haystack.contains("tab") ||
                haystack.contains("top bottom") ||
                haystack.contains("top-bottom") ||
                haystack.contains("over under") ||
                haystack.contains("ou ") ||
                haystack.endsWith(" ou") -> StereoMode.TOP_BOTTOM
            haystack.contains("sbs") ||
                haystack.contains("side by side") ||
                haystack.contains("side-by-side") ||
                haystack.contains("hsbs") -> StereoMode.SIDE_BY_SIDE
            else -> StereoMode.MONO
        }
    }
}

private val MV_HEVC_REGEX = Regex("""\b(mv-hevc|mvhevc|spatial(?:[\s.-]?video)?)\b""")
private val MULTIVIEW_REGEX = Regex("""\bmultiview\b""")
private val HEVC_REGEX = Regex("""\b(hevc|h265|h\.265|x265)\b""")
