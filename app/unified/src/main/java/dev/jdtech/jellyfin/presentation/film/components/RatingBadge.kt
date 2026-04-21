package dev.jdtech.jellyfin.presentation.film.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.models.Rating
import dev.jdtech.jellyfin.models.RatingType
import dev.jdtech.jellyfin.core.R as CoreR

@Composable
fun RatingBadge(rating: Rating, modifier: Modifier = Modifier) {
    // Use a neutral dark background for all badges to let the colorful brand icons pop
    val backgroundColor = Color.Black.copy(alpha = 0.5f)
    val contentColor = Color.White

    val iconRes = when (rating.type) {
        RatingType.IMDB -> CoreR.drawable.ic_imdb
        RatingType.ROTTEN_TOMATOES_CRITICS -> {
            val score = rating.value.removeSuffix("%").toIntOrNull() ?: 0
            if (score >= 60) CoreR.drawable.ic_rt_fresh else CoreR.drawable.ic_rt_rotten
        }
        RatingType.ROTTEN_TOMATOES_AUDIENCE -> CoreR.drawable.ic_rt_audience
        RatingType.METACRITIC, RatingType.METACRITIC_USER -> CoreR.drawable.ic_metacritic
        RatingType.TRAKT -> CoreR.drawable.ic_trakt
        RatingType.LETTERBOXD -> CoreR.drawable.ic_letterboxd
        RatingType.TMDB -> CoreR.drawable.ic_tmdb
        RatingType.MYANIMELIST -> CoreR.drawable.ic_mal
        RatingType.ANILIST -> CoreR.drawable.ic_anilist
        else -> null
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(backgroundColor)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (iconRes != null) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = rating.type.label,
                    tint = Color.Unspecified, // Keep original brand colors from the SVG
                    modifier = Modifier.size(16.dp)
                )
            } else {
                Text(
                    text = rating.type.label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = contentColor,
                )
            }
            Text(
                text = rating.value,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Black,
                color = contentColor
            )
        }
    }
}
