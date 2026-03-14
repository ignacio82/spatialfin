package dev.jdtech.jellyfin.presentation.settings.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import dev.jdtech.jellyfin.settings.presentation.models.PreferenceInfo
import dev.spatialfin.presentation.theme.spacings

@Composable
fun SettingsInfoCard(preference: PreferenceInfo, modifier: Modifier = Modifier) {
    SettingsBaseCard(preference = preference, onClick = {}, modifier = modifier) {
        Row(
            modifier = Modifier.padding(MaterialTheme.spacings.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            preference.iconDrawableId?.let {
                Icon(painter = painterResource(it), contentDescription = null)
                Spacer(modifier = Modifier.width(MaterialTheme.spacings.default))
            }
            Column {
                Text(text = preference.title, style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(MaterialTheme.spacings.extraSmall))
                Text(text = preference.description, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
