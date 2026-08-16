package dev.jdtech.jellyfin.player.core

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

    @Test
    fun treatsTitleWithYouAsMono() {
        assertEquals(
            StereoModeDetector.StereoMode.MONO,
            StereoModeDetector.detect(
                title = "Who Are You?",
                video3DFormat = null,
                sourceNames = listOf("Silo - S03E01 - Who Are You WEBDL-1080p.mkv"),
            ),
        )
    }

    @Test
    fun treatsTitleWithTabOrSbsWithout3dAsMono() {
        assertEquals(
            StereoModeDetector.StereoMode.MONO,
            StereoModeDetector.detect(
                title = "The Tab",
                video3DFormat = null,
                sourceNames = listOf("The.Tab.2022.1080p.mkv"),
            ),
        )
        assertEquals(
            StereoModeDetector.StereoMode.MONO,
            StereoModeDetector.detect(
                title = "SBS World News",
                video3DFormat = null,
                sourceNames = listOf("SBS.World.News.2024.mkv"),
            ),
        )
    }

    @Test
    fun detectsTopBottomAndOverUnderWith3dContextOrExplicitTags() {
        assertEquals(
            StereoModeDetector.StereoMode.TOP_BOTTOM,
            StereoModeDetector.detect(
                title = "Avatar 3D TAB",
                video3DFormat = null,
                sourceNames = listOf("Avatar.2009.3D.TAB.1080p.mkv"),
            ),
        )
        assertEquals(
            StereoModeDetector.StereoMode.TOP_BOTTOM,
            StereoModeDetector.detect(
                title = "Avatar 3D OU",
                video3DFormat = null,
                sourceNames = listOf("Avatar.2009.3D.OU.1080p.mkv"),
            ),
        )
        assertEquals(
            StereoModeDetector.StereoMode.TOP_BOTTOM,
            StereoModeDetector.detect(
                title = "Avatar Top-Bottom",
                video3DFormat = null,
                sourceNames = listOf("Avatar.2009.Top-Bottom.1080p.mkv"),
            ),
        )
        assertEquals(
            StereoModeDetector.StereoMode.TOP_BOTTOM,
            StereoModeDetector.detect(
                title = "Avatar HTAB",
                video3DFormat = null,
                sourceNames = listOf("Avatar.2009.HTAB.1080p.mkv"),
            ),
        )
    }
}

