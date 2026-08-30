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
        index: Int = 0,
        title: String = "",
        displayTitle: String? = null,
        language: String = "",
        width: Int? = null,
        height: Int? = null,
        range: VideoRangeType? = null,
        doVi: String? = null,
        channels: String? = null,
    ) = SpatialFinMediaStream(
        index = index,
        title = title,
        displayTitle = displayTitle,
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

    /**
     * Silo S3:E2 — Portuguese-default audio, English audio track, and 44
     * subtitle tracks whose first entry is Portuguese. A viewer configured for
     * English must not be told Portuguese subtitles are about to play.
     */
    @Test
    fun `understood audio leaves subtitles off rather than picking the first track`() {
        val hero = movie(
            streams = listOf(
                stream(MediaStreamType.AUDIO, index = 1, language = "por", channels = "5.1"),
                stream(MediaStreamType.AUDIO, index = 2, language = "eng", channels = "5.1"),
                stream(MediaStreamType.SUBTITLE, index = 3, language = "por"),
                stream(MediaStreamType.SUBTITLE, index = 4, language = "por", displayTitle = "Brazilian (Forced) - Portuguese"),
                stream(MediaStreamType.SUBTITLE, index = 14, language = "eng"),
            )
        ).detailHeroMetadata(
            preferredAudioLanguage = "eng",
            preferredSubtitleLanguage = "eng",
            spokenLanguages = listOf("eng"),
        )

        assertEquals("ENG - 5.1", hero.audio)
        assertEquals(2, hero.audioStreamIndex)
        assertEquals("Off", hero.subtitle)
        assertNull(hero.subtitleStreamIndex)
    }

    @Test
    fun `foreign audio picks the full dialogue track in a language the viewer reads`() {
        val hero = movie(
            streams = listOf(
                stream(MediaStreamType.AUDIO, index = 1, language = "jpn", channels = "5.1"),
                stream(MediaStreamType.SUBTITLE, index = 2, language = "eng", displayTitle = "English (Signs & Songs)"),
                stream(MediaStreamType.SUBTITLE, index = 3, language = "eng", displayTitle = "English (Full Dialogue)"),
            )
        ).detailHeroMetadata(
            preferredSubtitleLanguage = "eng",
            spokenLanguages = listOf("eng"),
        )

        // Not the signs-only sibling, which would leave the dialogue untranslated.
        assertEquals(3, hero.subtitleStreamIndex)
    }

    @Test
    fun `understood audio still surfaces a forced track for foreign dialogue`() {
        val hero = movie(
            streams = listOf(
                stream(MediaStreamType.AUDIO, index = 1, language = "eng", channels = "5.1"),
                stream(MediaStreamType.SUBTITLE, index = 2, language = "eng", displayTitle = "English (Forced)"),
                stream(MediaStreamType.SUBTITLE, index = 3, language = "eng", displayTitle = "English"),
            )
        ).detailHeroMetadata(
            preferredAudioLanguage = "eng",
            spokenLanguages = listOf("eng"),
        )

        assertEquals(2, hero.subtitleStreamIndex)
    }

    @Test
    fun `an explicit subtitle pick overrides the smart default`() {
        val hero = movie(
            streams = listOf(
                stream(MediaStreamType.AUDIO, index = 1, language = "eng", channels = "5.1"),
                stream(MediaStreamType.SUBTITLE, index = 3, language = "por"),
            )
        ).detailHeroMetadata(
            preferredAudioLanguage = "eng",
            spokenLanguages = listOf("eng"),
            selectedSubtitleStreamIndex = 3,
        )

        assertEquals(3, hero.subtitleStreamIndex)
        assertEquals("POR", hero.subtitle)
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
    fun `preferred audio language picks matching audio stream over first stream`() {
        val hero = movie(
            streams = listOf(
                stream(MediaStreamType.AUDIO, index = 1, language = "por", channels = "5.1"),
                stream(MediaStreamType.AUDIO, index = 2, language = "eng", channels = "5.1"),
            )
        ).detailHeroMetadata(preferredAudioLanguage = "en")

        assertEquals("ENG - 5.1", hero.audio)
    }

    @Test
    fun `explicitly selected audio and subtitle stream indexes are respected`() {
        val hero = movie(
            streams = listOf(
                stream(MediaStreamType.AUDIO, index = 1, language = "por", channels = "5.1"),
                stream(MediaStreamType.AUDIO, index = 2, language = "eng", channels = "7.1"),
                stream(MediaStreamType.SUBTITLE, index = 3, language = "por"),
                stream(MediaStreamType.SUBTITLE, index = 4, language = "eng", title = "Forced"),
            )
        ).detailHeroMetadata(
            selectedAudioStreamIndex = 2,
            selectedSubtitleStreamIndex = 4,
        )

        assertEquals("ENG - 7.1", hero.audio)
        assertEquals("ENG (Forced)", hero.subtitle)
    }

    @Test
    fun `subtitlesDisabled displays Off`() {
        val hero = movie(
            streams = listOf(
                stream(MediaStreamType.AUDIO, index = 1, language = "eng", channels = "5.1"),
                stream(MediaStreamType.SUBTITLE, index = 2, language = "eng"),
            )
        ).detailHeroMetadata(subtitlesDisabled = true)

        assertEquals("Off", hero.subtitle)
    }

    @Test
    fun `ratings trim trailing zeros`() {
        assertEquals("8.1", formatRating(8.10f))
        assertEquals("9", formatRating(9.0f))
        assertNull(formatRating(null))
        assertNull(formatRating(0f))
    }
}
