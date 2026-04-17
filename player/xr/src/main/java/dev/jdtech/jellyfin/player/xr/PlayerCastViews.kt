package dev.jdtech.jellyfin.player.xr

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.player.core.domain.models.PlayerPerson

/**
 * Cast & crew UI — leaf Composables extracted from SpatialPlayerScreen.kt.
 * No state or callbacks into the main screen: these are pure presentation
 * of [PlayerPerson] data produced by PlayerViewModel.
 */

@Composable
internal fun PersonPhoto(imageUri: String?, sizeDp: Int) {
    val shape = CircleShape
    if (imageUri != null) {
        AsyncImage(
            model = imageUri,
            contentDescription = null,
            modifier = Modifier.size(sizeDp.dp).clip(shape),
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            modifier = Modifier
                .size(sizeDp.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(CoreR.drawable.ic_user),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size((sizeDp * 0.55f).dp),
            )
        }
    }
}

@Composable
internal fun SectionHeader(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.headlineMedium,
        color = Color(0xFF90CAF9),
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 36.dp, bottom = 16.dp),
    )
}

/** Compact row used for Directors, Writers and other crew. */
@Composable
internal fun CrewRow(person: PlayerPerson) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        PersonPhoto(imageUri = person.imageUri, sizeDp = 96)
        Column {
            Text(
                text = person.name,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (person.role.isNotBlank()) {
                Text(
                    text = person.role,
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White.copy(alpha = 0.65f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Large card used for cast members — photo + name + character stacked. */
@Composable
internal fun ActorCard(person: PlayerPerson, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PersonPhoto(imageUri = person.imageUri, sizeDp = 160)
        Spacer(Modifier.height(14.dp))
        Text(
            text = person.name,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        if (person.role.isNotBlank()) {
            Text(
                text = person.role,
                style = MaterialTheme.typography.titleLarge,
                color = Color.White.copy(alpha = 0.65f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
internal fun CastCrewDialogContent(
    title: String,
    overview: String,
    people: List<PlayerPerson>,
    onDismiss: () -> Unit,
) {
    val directors = people.filter { it.type == "Director" }
    val writers = people.filter { it.type == "Writer" }
    val cast = people.filter { it.type == "Actor" }
    val crew = people.filter { it.type !in listOf("Director", "Writer", "Actor") }

    Surface(
        modifier = Modifier
            .width(800.dp)
            .heightIn(max = 700.dp),
        shape = RoundedCornerShape(32.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 12.dp,
    ) {
        Column(
            modifier = Modifier
                .padding(36.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

            if (overview.isNotBlank()) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = overview,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (directors.isNotEmpty()) {
                SectionHeader("Direction")
                directors.forEach { CrewRow(it) }
            }
            if (writers.isNotEmpty()) {
                SectionHeader("Writing")
                writers.forEach { CrewRow(it) }
            }
            if (crew.isNotEmpty()) {
                SectionHeader("Crew")
                crew.forEach { CrewRow(it) }
            }
            if (cast.isNotEmpty()) {
                SectionHeader("Cast")
                cast.chunked(2).forEach { pair ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        ActorCard(person = pair[0], modifier = Modifier.weight(1f))
                        if (pair.size > 1) {
                            ActorCard(person = pair[1], modifier = Modifier.weight(1f))
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }

            if (people.isEmpty() && overview.isBlank()) {
                Spacer(Modifier.height(24.dp))
                Text(
                    text = "No information available.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }

            Spacer(Modifier.height(24.dp))
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text("CLOSE", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
