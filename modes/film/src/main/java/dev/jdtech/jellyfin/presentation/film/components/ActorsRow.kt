package dev.jdtech.jellyfin.presentation.film.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.models.SpatialFinItemPerson
import dev.spatialfin.presentation.theme.spacings
import java.util.UUID

@Composable
fun ActorsRow(
    actors: List<SpatialFinItemPerson>,
    onActorClick: (personId: UUID) -> Unit,
    contentPadding: PaddingValues,
) {
    Column(modifier = Modifier.padding(contentPadding)) {
        DetailSectionHeader(title = stringResource(CoreR.string.cast_amp_crew))
        Spacer(Modifier.height(MaterialTheme.spacings.small))
    }
    LazyRow(
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
    ) {
        itemsIndexed(items = actors, key = { index, person -> "${person.id}_$index" }) { _, person ->
            PersonItem(person = person, onClick = { onActorClick(person.id) })
        }
    }
}
