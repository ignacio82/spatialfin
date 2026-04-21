package dev.jdtech.jellyfin.presentation.film.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.core.R as CoreR

@Composable
fun XrBrowseHeader(
    title: String,
    onBackClick: (() -> Unit)? = null,
    primaryAction: BrowseHeaderAction? = null,
    secondaryAction: BrowseHeaderAction? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            onBackClick?.let {
                BrowseHeaderButton(
                    label = "Back",
                    icon = CoreR.drawable.ic_arrow_left,
                    onClick = it,
                )
            }
            FilledTonalButton(
                onClick = {},
                enabled = false,
                modifier = Modifier.height(68.dp),
            ) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            secondaryAction?.let {
                BrowseHeaderButton(
                    label = it.label,
                    icon = it.icon,
                    onClick = it.onClick,
                )
            }
            primaryAction?.let {
                BrowseHeaderButton(
                    label = it.label,
                    icon = it.icon,
                    onClick = it.onClick,
                )
            }
        }
    }
}

@Composable
private fun BrowseHeaderButton(
    label: String,
    @DrawableRes icon: Int,
    onClick: () -> Unit,
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier.height(68.dp),
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
        )
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}

data class BrowseHeaderAction(
    val label: String,
    @param:DrawableRes val icon: Int,
    val onClick: () -> Unit,
)
