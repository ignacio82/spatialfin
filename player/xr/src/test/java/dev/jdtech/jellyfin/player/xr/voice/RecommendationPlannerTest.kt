package dev.jdtech.jellyfin.player.xr.voice

import dev.jdtech.jellyfin.models.SpatialFinChapter
import dev.jdtech.jellyfin.models.SpatialFinImages
import dev.jdtech.jellyfin.models.SpatialFinMediaStream
import dev.jdtech.jellyfin.models.SpatialFinMovie
import dev.jdtech.jellyfin.models.SpatialFinSource
import dev.jdtech.jellyfin.models.SpatialFinSourceType
import dev.jdtech.jellyfin.models.SpatialFinShow
import dev.jdtech.jellyfin.player.session.voice.PlayerStateSnapshot
import java.util.UUID
import org.jellyfin.sdk.model.api.MediaStreamType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendationPlannerTest {
    @Test
    fun `movie only follow up favors movies`() {
        val movie = movie(name = "Quick Laughs", genres = listOf("Comedy"), englishAudio = true)
        val show = show(name = "Long Mystery", genres = listOf("Drama"))
        val context = RecommendationContext(query = "recommend something", items = listOf(movie, show))
        val analysis = RecommendationPlanner.analyzeQuestion("movie only", context)

        val ranked =
            RecommendationPlanner.rankCandidates(
                candidates = listOf(movie, show),
                sourceWeights = mapOf(movie.id to 4, show.id to 4),
                playerState = PlayerStateSnapshot(),
                question = "movie only",
                analysis = analysis!!,
                previousContext = context,
            )

        assertEquals(movie.id, ranked.first().id)
    }

    @Test
    fun `english non anime filter penalizes anime picks`() {
        val englishMovie = movie(name = "Space Patrol", genres = listOf("Sci-Fi"), englishAudio = true)
        val animeShow = show(name = "Anime Nights", genres = listOf("Anime", "Comedy"))
        val analysis = RecommendationPlanner.analyzeQuestion("recommend something not anime with english audio", null)

        val ranked =
            RecommendationPlanner.rankCandidates(
                candidates = listOf(animeShow, englishMovie),
                sourceWeights = mapOf(englishMovie.id to 3, animeShow.id to 5),
                playerState = PlayerStateSnapshot(currentGenres = listOf("Sci-Fi")),
                question = "recommend something not anime with english audio",
                analysis = analysis!!,
                previousContext = null,
            )

        assertEquals(englishMovie.id, ranked.first().id)
    }

    @Test
    fun `more like second one resolves reference item`() {
        val comedyMovie = movie(name = "Laugh Riot", genres = listOf("Comedy"), englishAudio = true)
        val spaceMovie = movie(name = "Star Wake", genres = listOf("Sci-Fi"), englishAudio = true)
        val similarSpaceMovie = movie(name = "Galactic Run", genres = listOf("Sci-Fi"), englishAudio = true)
        val context = RecommendationContext(query = "what can i watch next", items = listOf(comedyMovie, spaceMovie))
        val analysis = RecommendationPlanner.analyzeQuestion("more like the second one", context)

        assertNotNull(analysis)
        val ranked =
            RecommendationPlanner.rankCandidates(
                candidates = listOf(comedyMovie, spaceMovie, similarSpaceMovie),
                sourceWeights = mapOf(comedyMovie.id to 3, spaceMovie.id to 3, similarSpaceMovie.id to 3),
                playerState = PlayerStateSnapshot(),
                question = "more like the second one",
                analysis = analysis!!,
                previousContext = context,
            )

        assertTrue(ranked.take(2).any { it.id == similarSpaceMovie.id })
    }

    @Test
    fun `under ninety minute mood query favors shorter picks`() {
        val shortComedy = movie(name = "Night Snack", genres = listOf("Comedy"), englishAudio = true, runtimeMinutes = 84)
        val longComedy = movie(name = "Epic Evening", genres = listOf("Comedy"), englishAudio = true, runtimeMinutes = 128)
        val analysis = RecommendationPlanner.analyzeQuestion("surprise me with something funny under 90 minutes", null)

        val ranked =
            RecommendationPlanner.rankCandidates(
                candidates = listOf(longComedy, shortComedy),
                sourceWeights = mapOf(shortComedy.id to 4, longComedy.id to 4),
                playerState = PlayerStateSnapshot(),
                question = "surprise me with something funny under 90 minutes",
                analysis = analysis!!,
                previousContext = null,
            )

        assertEquals(shortComedy.id, ranked.first().id)
        assertEquals(90, analysis.filters.maxRuntimeMinutes)
    }

    @Test
    fun `direct recommendation reply names titles without justification`() {
        val quickMovie = movie(name = "Quick Laughs", genres = listOf("Comedy"), englishAudio = true, runtimeMinutes = 88)
        val backupMovie = movie(name = "Space Patrol", genres = listOf("Sci-Fi"), englishAudio = true, runtimeMinutes = 104)
        val analysis = RecommendationPlanner.analyzeQuestion("recommend something funny with english audio", null)

        val reply =
            RecommendationPlanner.buildRecommendationReply(
                items = listOf(quickMovie, backupMovie),
                analysis = analysis!!,
            )

        assertNotNull(reply)
        assertTrue(reply!!.contains("Quick Laughs"))
        assertTrue(reply.contains("Space Patrol"))
        assertFalse(reply.contains("because"))
        assertFalse(reply.contains("good fit"))
    }

    private fun movie(
        name: String,
        genres: List<String>,
        englishAudio: Boolean,
        runtimeMinutes: Long = 45L,
    ): SpatialFinMovie =
        SpatialFinMovie(
            id = UUID.randomUUID(),
            name = name,
            originalTitle = null,
            overview = "$name overview",
            sources = listOf(source(englishAudio)),
            played = false,
            favorite = false,
            canPlay = true,
            canDownload = false,
            runtimeTicks = runtimeMinutes * 60L * 10_000_000L,
            playbackPositionTicks = 0L,
            premiereDate = null,
            people = emptyList(),
            genres = genres,
            communityRating = 8.0f,
            officialRating = "PG-13",
            status = "Released",
            productionYear = 2024,
            endDate = null,
            trailer = null,
            images = SpatialFinImages(),
            chapters = listOf(SpatialFinChapter(startPosition = 0L, name = "Start")),
            ratings = emptyList(),
            trickplayInfo = null,
        )

    private fun show(
        name: String,
        genres: List<String>,
    ): SpatialFinShow =
        SpatialFinShow(
            id = UUID.randomUUID(),
            name = name,
            originalTitle = null,
            overview = "$name overview",
            sources = listOf(source(englishAudio = false)),
            seasons = emptyList(),
            played = false,
            favorite = false,
            canPlay = true,
            canDownload = false,
            playbackPositionTicks = 0L,
            unplayedItemCount = 8,
            genres = genres,
            people = emptyList(),
            runtimeTicks = 50L * 60L * 10_000_000L,
            communityRating = 7.8f,
            officialRating = "TV-14",
            status = "Continuing",
            productionYear = 2023,
            endDate = null,
            trailer = null,
            images = SpatialFinImages(),
            chapters = emptyList(),
            ratings = emptyList(),
        )

    private fun source(englishAudio: Boolean): SpatialFinSource =
        SpatialFinSource(
            id = UUID.randomUUID().toString(),
            name = "remote",
            type = SpatialFinSourceType.REMOTE,
            path = "remote",
            size = 0L,
            mediaStreams =
                listOf(
                    SpatialFinMediaStream(
                        index = 0,
                        title = "audio",
                        displayTitle = null,
                        language = if (englishAudio) "en" else "ja",
                        type = MediaStreamType.AUDIO,
                        codec = "aac",
                        isExternal = false,
                        path = null,
                        channelLayout = null,
                        videoRangeType = null,
                        height = null,
                        width = null,
                        videoDoViTitle = null,
                    ),
                ),
        )
}
