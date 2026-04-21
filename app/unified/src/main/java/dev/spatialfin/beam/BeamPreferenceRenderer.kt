package dev.spatialfin.beam

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.settings.domain.models.Preference as PreferenceBackend
import dev.jdtech.jellyfin.settings.presentation.models.Preference
import dev.jdtech.jellyfin.settings.presentation.models.PreferenceCategory
import dev.jdtech.jellyfin.settings.presentation.models.PreferenceInfo
import dev.jdtech.jellyfin.settings.presentation.models.PreferenceIntInput
import dev.jdtech.jellyfin.settings.presentation.models.PreferenceIntSelect
import dev.jdtech.jellyfin.settings.presentation.models.PreferenceLongInput
import dev.jdtech.jellyfin.settings.presentation.models.PreferenceSelect
import dev.jdtech.jellyfin.settings.presentation.models.PreferenceStringInput
import dev.jdtech.jellyfin.settings.presentation.models.PreferenceSwitch

/**
 * Renders a single [Preference] using Beam phone-appropriate controls. Writes
 * go straight to [AppPreferences] — there is no Beam [SettingsViewModel] to
 * round-trip through — so each row's state is held in a local [rememberSaveable]
 * seeded from the current stored value and kept in sync on change.
 *
 * Preference types that don't translate to a simple phone row (PreferenceAppLanguage,
 * PreferenceVoicePicker, PreferenceSmartLanguage, PreferenceMultiSelect,
 * PreferenceFloatInput, PreferenceInfo) are deliberately ignored here. Categories
 * that need them fall back to hand-rolled composables in BeamSettingsScreen.kt.
 */
@Composable
internal fun BeamPreferenceRow(
    preference: Preference,
    appPreferences: AppPreferences,
) {
    if (!preference.enabled) return
    when (preference) {
        is PreferenceSwitch -> BeamRenderSwitch(preference, appPreferences)
        is PreferenceSelect -> BeamRenderSelect(preference, appPreferences)
        is PreferenceIntSelect -> BeamRenderIntSelect(preference, appPreferences)
        is PreferenceIntInput -> BeamRenderIntInput(preference, appPreferences)
        is PreferenceLongInput -> BeamRenderLongInput(preference, appPreferences)
        is PreferenceStringInput -> BeamRenderStringInput(preference, appPreferences)
        is PreferenceInfo -> BeamRenderInfo(preference)
        is PreferenceCategory -> BeamRenderCategoryAction(preference)
        else -> Unit
    }
}

@Composable
private fun BeamRenderInfo(preference: PreferenceInfo) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(preference.title, style = MaterialTheme.typography.bodyLarge)
        Text(
            preference.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BeamRenderSwitch(preference: PreferenceSwitch, appPreferences: AppPreferences) {
    var checked by rememberSaveable(preference.backendPreference.backendName) {
        mutableStateOf(appPreferences.getValue(preference.backendPreference))
    }
    Row(
        modifier = Modifier.fillMaxWidth().wrapContentHeight(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(preference.nameStringResource), style = MaterialTheme.typography.bodyLarge)
            preference.descriptionStringRes?.let { descRes ->
                Text(
                    stringResource(descRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = { newValue ->
                checked = newValue
                appPreferences.setValue(preference.backendPreference, newValue)
                preference.onClick(preference.copy(value = newValue))
            },
        )
    }
}

/**
 * Select rows in the declarative tree carry a `Preference<String?>` backend —
 * even when the underlying stored value is a `Long` (e.g. `playerMaxBitrate`)
 * that was unsafe-cast at the declaration site. Mirrors the same Long-aware
 * branch used by SettingsViewModel.computePreferences() so Beam's renderer
 * handles both cases without crashing.
 */
@Composable
private fun BeamRenderSelect(preference: PreferenceSelect, appPreferences: AppPreferences) {
    val names = stringArrayResource(preference.options)
    val values = stringArrayResource(preference.optionValues)
    val shortNames = preference.shortOptionsRes?.let { stringArrayResource(it) }
    val isLongBacked = remember(preference.backendPreference) {
        (preference.backendPreference as PreferenceBackend<*>).defaultValue is Long
    }
    // Local fallback for the simple auto-persist flow. When the caller passes
    // a non-null `preference.value` (stateful flow — see app-lock) we use that
    // instead so external rollbacks reflect in the UI on the next recomposition.
    var internalRaw by rememberSaveable(preference.backendPreference.backendName) {
        mutableStateOf(
            if (isLongBacked) {
                @Suppress("UNCHECKED_CAST")
                appPreferences.getValue(preference.backendPreference as PreferenceBackend<Long>).toString()
            } else {
                appPreferences.getValue(preference.backendPreference)
            }
        )
    }
    val currentRaw = preference.value ?: internalRaw
    val currentLabel = values.indexOf(currentRaw).takeIf { it >= 0 }?.let { names[it] }
        ?: currentRaw.orEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "${stringResource(preference.nameStringResource)} · $currentLabel",
            style = MaterialTheme.typography.bodyLarge,
        )
        preference.descriptionStringRes?.let { descRes ->
            Text(
                stringResource(descRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            names.forEachIndexed { index, name ->
                val optionValue = values.getOrNull(index) ?: return@forEachIndexed
                val buttonLabel = shortNames?.getOrNull(index) ?: name
                Button(
                    onClick = {
                        internalRaw = optionValue
                        if (preference.autoPersist) {
                            if (isLongBacked) {
                                @Suppress("UNCHECKED_CAST")
                                appPreferences.setValue(
                                    preference.backendPreference as PreferenceBackend<Long>,
                                    optionValue.toLongOrNull() ?: 0L,
                                )
                            } else {
                                appPreferences.setValue(preference.backendPreference, optionValue)
                            }
                        }
                        preference.onUpdate(optionValue)
                    },
                ) {
                    Text(if (optionValue == currentRaw) "• $buttonLabel" else buttonLabel)
                }
            }
        }
    }
}

@Composable
private fun BeamRenderIntSelect(preference: PreferenceIntSelect, appPreferences: AppPreferences) {
    var internalValue by rememberSaveable(preference.backendPreference.backendName) {
        mutableIntStateOf(appPreferences.getValue(preference.backendPreference))
    }
    val currentValue = preference.value ?: internalValue
    val currentLabel = preference.options.firstOrNull { it.value == currentValue }
        ?.labelRes?.let { stringResource(it) } ?: currentValue.toString()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "${stringResource(preference.nameStringResource)} · $currentLabel",
            style = MaterialTheme.typography.bodyLarge,
        )
        preference.descriptionStringRes?.let { descRes ->
            Text(
                stringResource(descRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            preference.options.forEach { option ->
                val label = stringResource(option.labelRes)
                Button(
                    onClick = {
                        internalValue = option.value
                        appPreferences.setValue(preference.backendPreference, option.value)
                        preference.onUpdate(option.value)
                    },
                ) {
                    Text(if (option.value == currentValue) "• $label" else label)
                }
            }
        }
    }
}

@Composable
private fun BeamRenderIntInput(preference: PreferenceIntInput, appPreferences: AppPreferences) {
    var value by rememberSaveable(preference.backendPreference.backendName) {
        mutableIntStateOf(appPreferences.getValue(preference.backendPreference))
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(preference.nameStringResource), style = MaterialTheme.typography.bodyLarge)
        preference.descriptionStringRes?.let { descRes ->
            Text(
                stringResource(descRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedTextField(
            value = value.toString(),
            onValueChange = { raw ->
                val v = raw.toIntOrNull() ?: 0
                value = v
                appPreferences.setValue(preference.backendPreference, v)
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Numeric value") },
            singleLine = true,
        )
    }
}

@Composable
private fun BeamRenderLongInput(preference: PreferenceLongInput, appPreferences: AppPreferences) {
    val divisor = preference.displayDivisor.coerceAtLeast(1L)
    var displayValue by rememberSaveable(preference.backendPreference.backendName) {
        mutableLongStateOf(appPreferences.getValue(preference.backendPreference) / divisor)
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(preference.nameStringResource), style = MaterialTheme.typography.bodyLarge)
        preference.descriptionStringRes?.let { descRes ->
            Text(
                stringResource(descRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedTextField(
            value = displayValue.toString(),
            onValueChange = { raw ->
                val v = raw.toLongOrNull() ?: 0L
                displayValue = v
                appPreferences.setValue(preference.backendPreference, v * divisor)
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Numeric value") },
            singleLine = true,
        )
    }
}

@Composable
private fun BeamRenderStringInput(preference: PreferenceStringInput, appPreferences: AppPreferences) {
    var value by rememberSaveable(preference.backendPreference.backendName) {
        mutableStateOf(appPreferences.getValue(preference.backendPreference).orEmpty())
    }
    var revealed by rememberSaveable(preference.backendPreference.backendName) { mutableStateOf(false) }
    val placeholder = preference.placeholderRes?.let { stringResource(it) } ?: preference.placeholder.orEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(preference.nameStringResource), style = MaterialTheme.typography.bodyLarge)
        preference.descriptionStringRes?.let { descRes ->
            Text(
                stringResource(descRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedTextField(
            value = value,
            onValueChange = { new ->
                value = new
                appPreferences.setValue(preference.backendPreference, new.ifBlank { null })
            },
            modifier = Modifier.fillMaxWidth(),
            label = if (placeholder.isNotBlank()) { { Text(placeholder) } } else null,
            singleLine = true,
            visualTransformation = if (preference.secret && !revealed) PasswordVisualTransformation() else VisualTransformation.None,
            trailingIcon = if (preference.secret) {
                {
                    TextButton(onClick = { revealed = !revealed }) {
                        Text(if (revealed) "Hide" else "Show")
                    }
                }
            } else null,
        )
    }
}

/**
 * Categories are used in the declarative tree for navigation drill-downs, but
 * here we use them as action rows: clicking invokes `onClick(preference)` which
 * dispatches to whatever the construction site wired (open dialog, fire event,
 * etc). Beam maps this to a full-width button so the row reads as "action"
 * rather than "value you can change."
 */
@Composable
private fun BeamRenderCategoryAction(preference: PreferenceCategory) {
    Column(
        modifier = Modifier.fillMaxWidth().clickable { preference.onClick(preference) }.padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(stringResource(preference.nameStringResource), style = MaterialTheme.typography.bodyLarge)
        preference.descriptionStringRes?.let { descRes ->
            Text(
                stringResource(descRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
