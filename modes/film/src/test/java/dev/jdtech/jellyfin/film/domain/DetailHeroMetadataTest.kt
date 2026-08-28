package dev.jdtech.jellyfin.film.domain

import dev.jdtech.jellyfin.models.SpatialFinImages
import dev.jdtech.jellyfin.models.SpatialFinMediaStream
import dev.jdtech.jellyfin.models.SpatialFinMovie
import dev.jdtech.jellyfin.models.SpatialFinSource
import dev.jdtech.jellyfin.models.SpatialFinSourceType
import java.util.UUID
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.VideoRangeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DetailHeroMetadataTest {
    private fun stream(
        type: MediaStreamType,
        language: String = "",
        width: Int? = null,
        height: Int? = null,
        range: VideoRangeType? = null,
        doVi: String? = null,
        channels: String? = null,
    ) = SpatialFinMediaStream(
        index = 0,
        title = "",
        displayTitle = null,
        language = language,
        type = type,
        codec = "hevc",
        isExternal = false,
        path = null,
        channelLayout = channels,
        videoRangeType = range,
        height = height,
        width = width,
        videoDoViTitle = doVi,
    )

    private fun movie(
        streams: List<SpatialFinMediaStream> = emptyList(),
        runtimeTicks: Long = 0L,
        rating: Float? = null,
        official: String? = null,
        year: Int? = null,
        genres: List<String> = emptyList(),
    ) = SpatialFinMovie(
        id = UUID.randomUUID(),
        name = "The Invite",
        originalTitle = null,
        overview = "",
        sources = listOf(
            SpatialFinSource(
                id = "s",
                name = "s",
                type = SpatialFinSourceType.REMOTE,
                path = "",
                size = 0L,
                mediaStreams = streams,
            )
        ),
        played = false,
        favorite = false,
        canPlay = true,
        canDownload = true,
        runtimeTicks = runtimeTicks,
        playbackPositionTicks = 0L,
        premiereDate = null,
        people = emptyList(),
        genres = genres,
        communityRating = rating,
        officialRating = official,
        status = "",
        productionYear = year,
        endDate = null,
        trailer = null,
        images = SpatialFinImages(),
        chapters = emptyList(),
        trickplayInfo = null,
    )

    @Test
    fun `facts mirror the certification, year, runtime and rating chips`() {
        val hero = movie(runtimeTicks = 107L * 600_000_000L, rating = 7.32f, official = "R", year = 2026)
            .detailHeroMetadata()

        assertEquals(
            listOf("R", "2026", "1h 47m", "7.32"),
            hero.facts.map { it.label },
        )
        assertEquals(
            listOf(
                HeroFactKind.CERTIFICATION,
                HeroFactKind.YEAR,
                HeroFactKind.RUNTIME,
                HeroFactKind.RATING,
            ),
            hero.facts.map { it.kind },
        )
    }

    @Test
    fun `a dolby vision file with an HDR10+ base layer reports both`() {
        val hero = movie(
            streams = listOf(
                stream(
                    MediaStreamType.VIDEO,
                    width = 3840,
                    height = 2160,
                    range = VideoRangeType.DOVI_WITH_HDR10_PLUS,
                    doVi = "DV Profile 8",
                )
            )
        ).detailHeroMetadata()

        assertEquals("4K DoVi/HDR10+", hero.video)
    }

    @Test
    fun `audio and subtitle chips use language and channel layout`() {
        val hero = movie(
            streams = listOf(
                stream(MediaStreamType.AUDIO, language = "eng", channels = "5.1"),
                stream(MediaStreamType.SUBTITLE, language = "eng"),
            )
        ).detailHeroMetadata()

        assertEquals("ENG - 5.1", hero.audio)
        assertEquals("ENG", hero.subtitle)
    }

    @Test
    fun `stereo is normalised to 2 point 0`() {
        val hero = movie(
            streams = listOf(stream(MediaStreamType.AUDIO, language = "spa", channels = "stereo"))
        ).detailHeroMetadata()

        assertEquals("SPA - 2.0", hero.audio)
    }

    @Test
    fun `an item with no streams yields no stream chips`() {
        val hero = movie().detailHeroMetadata()

        assertNull(hero.video)
        assertNull(hero.audio)
        assertNull(hero.subtitle)
    }

    @Test
    fun `genres are capped so the hero cannot wrap forever`() {
        val hero = movie(genres = listOf("Comedy", "Drama", "Romance", "Thriller"))
            .detailHeroMetadata()

        assertEquals(listOf("Comedy", "Drama", "Romance"), hero.genres)
    }

    @Test
    fun `runtime under an hour drops the hour part`() {
        assertEquals("47m", formatRuntime(47L * 600_000_000L))
        assertNull(formatRuntime(0L))
    }

    @Test
    fun `ratings trim trailing zeros`() {
        assertEquals("8.1", formatRating(8.10f))
        assertEquals("9", formatRating(9.0f))
        assertNull(formatRating(null))
        assertNull(formatRating(0f))
    }
}
