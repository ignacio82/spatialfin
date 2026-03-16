package dev.jdtech.jellyfin.core.presentation.dummy

import dev.jdtech.jellyfin.models.CollectionType
import dev.jdtech.jellyfin.models.SpatialFinCollection
import dev.jdtech.jellyfin.models.SpatialFinImages
import java.util.UUID

private val dummyMoviesCollection =
    SpatialFinCollection(
        id = UUID.randomUUID(),
        name = "Movies",
        type = CollectionType.Movies,
        images = SpatialFinImages(),
    )

private val dummyShowsCollection =
    SpatialFinCollection(
        id = UUID.randomUUID(),
        name = "Shows",
        type = CollectionType.TvShows,
        images = SpatialFinImages(),
    )

val dummyCollections = listOf(dummyMoviesCollection, dummyShowsCollection)
