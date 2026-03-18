package dev.jdtech.jellyfin.presentation.settings.components

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.presentation.components.BaseDialog
import dev.jdtech.jellyfin.settings.R
import dev.jdtech.jellyfin.settings.language.LanguageCatalog
import dev.jdtech.jellyfin.settings.language.SmartLanguageSettings
import dev.spatialfin.presentation.theme.spacings

@Composable
fun SmartLanguageSettingsDialog(
    initialSettings: SmartLanguageSettings,
    onUpdate: (SmartLanguageSettings) -> Unit,
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    val allLanguages = remember(context) { LanguageCatalog.all(context) }
    var preferOriginalAudio by remember { mutableStateOf(initialSettings.preferOriginalAudio) }
    var selectedCodes by remember { mutableStateOf(initialSettings.spokenLanguageCodes.distinct()) }
    var query by remember { mutableStateOf("") }

    val filteredLanguages =
        remember(query, selectedCodes, allLanguages) {
            val normalizedQuery = query.trim().lowercase()
            allLanguages.filter { option ->
                option.code !in selectedCodes &&
                    (normalizedQuery.isBlank() ||
                        option.displayName.lowercase().contains(normalizedQuery) ||
                        option.aliases.any { alias -> alias.contains(normalizedQuery) })
            }.sortedWith(
                compareBy<dev.jdtech.jellyfin.settings.language.LanguageOption> {
                    val name = it.displayName.lowercase()
                    when {
                        normalizedQuery.isBlank() -> 2
                        name == normalizedQuery -> 0
                        name.startsWith(normalizedQuery) -> 1
                        else -> 2
                    }
                }.thenBy { it.displayName }
            )
        }

    BaseDialog(
        title = stringResource(R.string.settings_smart_language_dialog_title),
        onDismiss = onDismissRequest,
        negativeButton = {
            TextButton(onClick = onDismissRequest) { Text(stringResource(R.string.cancel)) }
        },
        positiveButton = {
            TextButton(
                onClick = {
                    onUpdate(
                        SmartLanguageSettings(
                            preferOriginalAudio = preferOriginalAudio,
                            spokenLanguageCodes =
                                selectedCodes.ifEmpty {
                                    listOf(LanguageCatalog.defaultDeviceLanguageCode(context))
                                },
                        )
                    )
                }
            ) {
                Text(stringResource(R.string.save))
            }
        },
    ) { contentPadding ->
        Column(
            modifier =
                Modifier.padding(contentPadding)
                    .heightIn(min = 820.dp)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
        ) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface,
            ) {
                Row(
                    modifier =
                        Modifier.fillMaxWidth().padding(MaterialTheme.spacings.large),
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_smart_language_prefer_original),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(modifier = Modifier.height(MaterialTheme.spacings.extraSmall))
                        Text(
                            text =
                                stringResource(
                                    R.string.settings_smart_language_prefer_original_summary
                                ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = preferOriginalAudio,
                        onCheckedChange = { preferOriginalAudio = it },
                    )
                }
            }

            Text(
                text = stringResource(R.string.settings_smart_language_list_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = stringResource(R.string.settings_smart_language_list_summary),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (selectedCodes.isEmpty()) {
                Text(
                    text = stringResource(R.string.settings_smart_language_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            selectedCodes.forEachIndexed { index, code ->
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacings.large),
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
                    ) {
                        Text(
                            text = languageDisplayName(context, code),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small)) {
                            TextButton(
                                onClick = {
                                    if (index > 0) {
                                        selectedCodes =
                                            selectedCodes.toMutableList().apply {
                                                add(index - 1, removeAt(index))
                                            }
                                    }
                                },
                                enabled = index > 0,
                            ) {
                                Text(stringResource(R.string.settings_smart_language_up))
                            }
                            TextButton(
                                onClick = {
                                    if (index < selectedCodes.lastIndex) {
                                        selectedCodes =
                                            selectedCodes.toMutableList().apply {
                                                add(index + 1, removeAt(index))
                                            }
                                    }
                                },
                                enabled = index < selectedCodes.lastIndex,
                            ) {
                                Text(stringResource(R.string.settings_smart_language_down))
                            }
                            TextButton(
                                onClick = {
                                    selectedCodes = selectedCodes.toMutableList().apply { removeAt(index) }
                                },
                            ) {
                                Text(stringResource(R.string.settings_smart_language_remove))
                            }
                        }
                    }
                }
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.settings_smart_language_search)) },
                placeholder = {
                    Text(stringResource(R.string.settings_smart_language_search_placeholder))
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                singleLine = true,
            )

            filteredLanguages.take(24).forEach { option ->
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacings.large),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = option.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Button(
                            onClick = {
                                selectedCodes = selectedCodes + option.code
                                query = ""
                            },
                        ) {
                            Text(stringResource(R.string.settings_smart_language_add))
                        }
                    }
                }
            }

            Text(
                text = stringResource(R.string.settings_smart_language_subtitle_rule),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun languageDisplayName(context: Context, code: String): String {
    return LanguageCatalog.displayName(context, code)
}
