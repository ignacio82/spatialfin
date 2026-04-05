package dev.jdtech.jellyfin.player.xr

import org.junit.Assert.assertEquals
import org.junit.Test

class StereoModeDetectorTest {
    @Test
    fun detectsExplicitMvHevcAsMultiview() {
        assertEquals(
            StereoModeDetector.StereoMode.MULTIVIEW,
            StereoModeDetector.detect(
                title = "Avatar",
                video3DFormat = "MV-HEVC",
                sourceNames = emptyList(),
            ),
        )
    }

    @Test
    fun keepsLegacyMvcAsMonoWithoutHevcSignal() {
        assertEquals(
            StereoModeDetector.StereoMode.MONO,
            StereoModeDetector.detect(
                title = "3D-full-MVC",
                video3DFormat = null,
                sourceNames = listOf("3D-full-MVC.mkv"),
            ),
        )
    }

    @Test
    fun allowsGenericMultiviewWhenHevcIsPresent() {
        assertEquals(
            StereoModeDetector.StereoMode.MULTIVIEW,
            StereoModeDetector.detect(
                title = "Movie",
                video3DFormat = null,
                sourceNames = listOf("movie.multiview.mkv"),
                videoCodecs = listOf("hevc"),
            ),
        )
    }

    @Test
    fun keepsSideBySideDetection() {
        assertEquals(
            StereoModeDetector.StereoMode.SIDE_BY_SIDE,
            StereoModeDetector.detect(
                title = "Movie HSBS",
                video3DFormat = null,
                sourceNames = emptyList(),
            ),
        )
    }
}
