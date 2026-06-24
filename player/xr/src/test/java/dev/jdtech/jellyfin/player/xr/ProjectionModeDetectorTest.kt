package dev.jdtech.jellyfin.player.xr

import dev.jdtech.jellyfin.player.xr.ProjectionModeDetector.ProjectionMode
import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectionModeDetectorTest {
    @Test
    fun detectsVr360FromTitle() {
        assertEquals(
            ProjectionMode.VR360,
            ProjectionModeDetector.detect(title = "Coral Reef VR360", sourceNames = emptyList()),
        )
    }

    @Test
    fun detectsVr360FromEquirectangularTag() {
        assertEquals(
            ProjectionMode.VR360,
            ProjectionModeDetector.detect(
                title = "Skydive",
                sourceNames = listOf("Skydive.equirectangular.mp4"),
            ),
        )
    }

    @Test
    fun detectsVr360FromUnderscore360Marker() {
        assertEquals(
            ProjectionMode.VR360,
            ProjectionModeDetector.detect(
                title = "Concert",
                sourceNames = listOf("concert_360_video.mkv"),
            ),
        )
    }

    @Test
    fun detectsVr180FromVr180Tag() {
        assertEquals(
            ProjectionMode.VR180,
            ProjectionModeDetector.detect(
                title = "Beach VR180 3D",
                sourceNames = emptyList(),
            ),
        )
    }

    @Test
    fun detectsVr180FromFisheyeLensTag() {
        assertEquals(
            ProjectionMode.VR180,
            ProjectionModeDetector.detect(
                title = "Hike",
                sourceNames = listOf("hike.mkx200.fisheye.mp4"),
            ),
        )
    }

    @Test
    fun plainTitleStaysFlat() {
        assertEquals(
            ProjectionMode.FLAT,
            ProjectionModeDetector.detect(title = "The Matrix", sourceNames = listOf("The.Matrix.1999.mkv")),
        )
    }

    @Test
    fun bareResolutionDigitsDoNotTripVr360() {
        // "2160" / "1080" / years must not be read as a 360/180 projection.
        assertEquals(
            ProjectionMode.FLAT,
            ProjectionModeDetector.detect(
                title = "Dune Part Two 2160p",
                sourceNames = listOf("Dune.Part.Two.2024.2160p.BluRay.x265.mkv"),
            ),
        )
    }

    @Test
    fun bare180InNameDoesNotTripVr180() {
        assertEquals(
            ProjectionMode.FLAT,
            ProjectionModeDetector.detect(
                title = "Episode 180",
                sourceNames = listOf("show.s01e180.mkv"),
            ),
        )
    }

    @Test
    fun asExtraMapsToIntentStrings() {
        assertEquals("flat", ProjectionModeDetector.asExtra(ProjectionMode.FLAT))
        assertEquals("180", ProjectionModeDetector.asExtra(ProjectionMode.VR180))
        assertEquals("360", ProjectionModeDetector.asExtra(ProjectionMode.VR360))
    }
}
