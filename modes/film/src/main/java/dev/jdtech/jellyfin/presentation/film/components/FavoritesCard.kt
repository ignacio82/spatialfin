package dev.jdtech.jellyfin.presentation.film.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.core.R as CoreR
import dev.spatialfin.presentation.theme.SpatialFinTheme
import dev.spatialfin.presentation.theme.spacings

@Composable
fun FavoritesCard(onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedCard(
        onClick = onClick,
        modifier =
            modifier.fillMaxWidth()
                .heightIn(min = 108.dp)
                .defaultMinSize(minHeight = 108.dp),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(MaterialTheme.spacings.large),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painter = painterResource(CoreR.drawable.ic_star), contentDescription = null)
                Text(
                    text = stringResource(CoreR.string.title_favorite),
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
            Text(
                text = "Quick access to starred titles",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier =
                    Modifier.padding(
                        start = MaterialTheme.spacings.large,
                        top = 66.dp,
                        end = MaterialTheme.spacings.large,
                        bottom = MaterialTheme.spacings.large,
                    ),
            )
        }
    }
}

@Preview
@Composable
private fun FavoritesCardPreview() {
    SpatialFinTheme { FavoritesCard(onClick = {}, modifier = Modifier.width(320.dp)) }
}
