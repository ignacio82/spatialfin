package dev.jdtech.jellyfin.models

import org.jellyfin.sdk.model.api.BaseItemDto

fun BaseItemDto.toSpatialFinRatings(): List<Rating> {
    val ratings = mutableListOf<Rating>()
    
    // MDBList plugin stores ratings in ExternalUrls
    externalUrls?.forEach { externalUrl ->
        val name = externalUrl.name ?: return@forEach
        val url = externalUrl.url
        
        // MDBList format is usually "RatingName: Value"
        if (name.contains(":")) {
            val parts = name.split(":", limit = 2)
            val label = parts[0].trim()
            val value = parts[1].trim()
            
            val type = RatingType.fromLabel(label)
            if (type != RatingType.UNKNOWN) {
                ratings.add(Rating(type = type, value = value, url = url))
            }
        } else {
            // Some might not have colon, try direct match
            val type = RatingType.fromLabel(name)
            if (type != RatingType.UNKNOWN) {
                // If it's just the name, maybe the value is in the URL or we don't have it
                // But usually it has the value in the name.
                // We'll skip if no value for now.
            }
        }
    }
    
    // Also include standard CommunityRating as TMDb if no TMDb rating was found
    if (ratings.none { it.type == RatingType.TMDB } && communityRating != null) {
        ratings.add(Rating(type = RatingType.TMDB, value = "%.1f".format(communityRating)))
    }
    
    // Also include CriticRating as Rotten Tomatoes (Critics) if no RT Critics was found
    val critic = criticRating
    if (ratings.none { it.type == RatingType.ROTTEN_TOMATOES_CRITICS } && critic != null) {
        ratings.add(Rating(type = RatingType.ROTTEN_TOMATOES_CRITICS, value = "${critic.toInt()}%"))
    }

    return ratings
}
