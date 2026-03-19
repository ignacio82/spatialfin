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
    ): StereoMode {
        val haystack =
            buildString {
                append(title.orEmpty())
                append(' ')
                append(video3DFormat.orEmpty())
                append(' ')
                append(sourceNames.joinToString(separator = " "))
            }.lowercase(Locale.ROOT)

        return when {
            haystack.contains("mvc") || haystack.contains("multiview") -> StereoMode.MULTIVIEW
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
