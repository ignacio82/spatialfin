package dev.jdtech.jellyfin.presentation.settings.components

import android.speech.tts.Voice
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.player.xr.voice.SpatialVoiceSynthesizer
import dev.jdtech.jellyfin.presentation.components.BaseDialog
import dev.jdtech.jellyfin.settings.R
import dev.spatialfin.presentation.theme.spacings
import java.util.Locale

@Composable
fun VoicePickerDialog(
    initialVoiceName: String?,
    onSave: (String?) -> Unit,
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    var selection by remember { mutableStateOf(initialVoiceName) }

    val networkRequiredLabel = stringResource(R.string.voice_assistant_voice_dialog_network_required)
    val qualityLabelFormat = stringResource(R.string.voice_assistant_voice_dialog_quality_label)
    val previewText = stringResource(R.string.voice_assistant_voice_dialog_preview_text)

    val synthesizer = remember(context) { SpatialVoiceSynthesizer(context.applicationContext) }
    DisposableEffect(synthesizer) {
        onDispose { synthesizer.destroy() }
    }

    val ready by synthesizer.isReady.collectAsState()
    var voices by remember { mutableStateOf<List<VoicePickerEntry>>(emptyList()) }
    LaunchedEffect(ready, networkRequiredLabel, qualityLabelFormat) {
        if (ready) {
            voices = synthesizer.availableVoices().toPickerEntries(
                displayLocale = Locale.getDefault(),
                networkRequiredLabel = networkRequiredLabel,
                qualityLabelFormat = qualityLabelFormat,
            )
        }
    }

    BaseDialog(
        title = stringResource(R.string.voice_assistant_voice_dialog_title),
        onDismiss = onDismissRequest,
        modifier = Modifier.widthIn(min = 640.dp, max = 860.dp).heightIn(max = 780.dp),
        negativeButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(R.string.cancel))
            }
        },
        positiveButton = {
            TextButton(
                onClick = {
                    synthesizer.stop()
                    onSave(selection)
                },
            ) {
                Text(stringResource(R.string.save))
            }
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .padding(contentPadding)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
        ) {
            VoicePickerRow(
                title = stringResource(R.string.voice_assistant_voice_system_default),
                subtitle = stringResource(R.string.voice_assistant_voice_dialog_system_default_summary),
                selected = selection.isNullOrBlank(),
                onSelect = { selection = null },
                onPreview = null,
            )

            if (!ready) {
                Text(
                    text = stringResource(R.string.voice_assistant_voice_dialog_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (voices.isEmpty()) {
                Text(
                    text = stringResource(R.string.voice_assistant_voice_dialog_no_voices),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                voices.forEach { entry ->
                    VoicePickerRow(
                        title = entry.displayName,
                        subtitle = entry.subtitle,
                        selected = selection == entry.name,
                        onSelect = { selection = entry.name },
                        onPreview = {
                            synthesizer.speak(
                                text = previewText,
                                voiceName = entry.name,
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun VoicePickerRow(
    title: String,
    subtitle: String?,
    selected: Boolean,
    onSelect: () -> Unit,
    onPreview: (() -> Unit)?,
) {
    val background = if (selected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
    } else {
        MaterialTheme.colorScheme.surface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(background)
            .selectable(
                selected = selected,
                onClick = onSelect,
                role = Role.RadioButton,
            )
            .padding(MaterialTheme.spacings.medium),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
            )
            subtitle?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (onPreview != null) {
            TextButton(onClick = onPreview) {
                Text(stringResource(R.string.voice_assistant_voice_dialog_preview))
            }
        }
    }
}

internal data class VoicePickerEntry(
    val name: String,
    val displayName: String,
    val subtitle: String,
)

private fun List<Voice>.toPickerEntries(
    displayLocale: Locale,
    networkRequiredLabel: String,
    qualityLabelFormat: String,
): List<VoicePickerEntry> {
    return this
        .filter { it.locale?.language?.isNotBlank() == true }
        .sortedWith(
            compareByDescending<Voice> { isSameLocale(it.locale, displayLocale) }
                .thenByDescending { isSameLanguage(it.locale, displayLocale) }
                .thenBy { it.locale?.getDisplayName(displayLocale).orEmpty() }
                .thenBy { it.name }
        )
        .map { it.toEntry(displayLocale, networkRequiredLabel, qualityLabelFormat) }
}

private fun isSameLocale(a: Locale?, b: Locale): Boolean {
    if (a == null) return false
    return a.language.equals(b.language, ignoreCase = true) &&
        a.country.equals(b.country, ignoreCase = true)
}

private fun isSameLanguage(a: Locale?, b: Locale): Boolean {
    if (a == null) return false
    return a.language.equals(b.language, ignoreCase = true)
}

private fun Voice.toEntry(
    displayLocale: Locale,
    networkRequiredLabel: String,
    qualityLabelFormat: String,
): VoicePickerEntry {
    val localeLabel = this.locale?.getDisplayName(displayLocale)?.takeIf { it.isNotBlank() }
        ?: this.name
    val shortName = this.name.substringAfterLast('#').substringAfterLast('-').ifBlank { this.name }
    val display = if (localeLabel.equals(this.name, ignoreCase = true)) {
        this.name
    } else {
        "$localeLabel — $shortName"
    }
    val parts = mutableListOf<String>()
    if (this.isNetworkConnectionRequired) parts += networkRequiredLabel
    if (this.quality > 0) parts += qualityLabelFormat.format(this.quality)
    return VoicePickerEntry(
        name = this.name,
        displayName = display,
        subtitle = parts.joinToString(" · "),
    )
}
