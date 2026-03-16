package dev.jdtech.jellyfin.core.presentation.dummy

import dev.jdtech.jellyfin.models.SpatialFinImages
import dev.jdtech.jellyfin.models.SpatialFinMediaStream
import dev.jdtech.jellyfin.models.SpatialFinMovie
import dev.jdtech.jellyfin.models.SpatialFinSource
import dev.jdtech.jellyfin.models.SpatialFinSourceType
import java.time.LocalDateTime
import java.util.UUID
import org.jellyfin.sdk.model.api.MediaStreamType

val dummyMovie =
    SpatialFinMovie(
        id = UUID.randomUUID(),
        name = "Alita: Battle Angel",
        originalTitle = null,
        overview =
            "When Alita awakens with no memory of who she is in a future world she does not recognize, she is taken in by Ido, a compassionate doctor who realizes that somewhere in this abandoned cyborg shell is the heart and soul of a young woman with an extraordinary past.",
        sources =
            listOf(
                SpatialFinSource(
                    id = "",
                    name = "",
                    type = SpatialFinSourceType.REMOTE,
                    path = "",
                    size = 0L,
                    mediaStreams =
                        listOf(
                            SpatialFinMediaStream(
                                index = 0,
                                title = "",
                                displayTitle = "",
                                language = "en",
                                type = MediaStreamType.VIDEO,
                                codec = "hevc",
                                isExternal = false,
                                path = "",
                                channelLayout = null,
                                videoRangeType = null,
                                height = 1080,
                                width = 1920,
                                videoDoViTitle = null,
                            )
                        ),
                )
            ),
        played = false,
        favorite = true,
        canPlay = true,
        canDownload = true,
        runtimeTicks = 2000000000L,
        playbackPositionTicks = 0L,
        premiereDate = LocalDateTime.parse("2019-02-14T00:00:00"),
        people = emptyList(),
        genres = listOf("Action", "Sience Fiction", "Adventure"),
        communityRating = 7.2f,
        officialRating = "PG-13",
        status = "Ended",
        productionYear = 2019,
        endDate = null,
        trailer = "https://www.youtube.com/watch?v=puKWa8hrvA8",
        images = SpatialFinImages(),
        chapters = emptyList(),
        trickplayInfo = null,
    )

val dummyMovies = listOf(dummyMovie)
