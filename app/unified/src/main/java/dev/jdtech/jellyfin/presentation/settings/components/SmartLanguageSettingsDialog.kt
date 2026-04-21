package dev.jdtech.jellyfin.presentation.settings.components

import android.content.Context
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
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

@OptIn(ExperimentalLayoutApi::class)
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
    var statusMessage by remember { mutableStateOf<String?>(null) }
    val defaultLanguageCode = remember(context) { LanguageCatalog.defaultDeviceLanguageCode(context) }
    val suggestionCodes =
        remember(defaultLanguageCode) {
            listOf(defaultLanguageCode, "eng", "spa", "jpn", "fra", "deu", "ita", "por", "kor", "zho")
                .distinct()
        }

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
    val quickSuggestions =
        remember(query, filteredLanguages, suggestionCodes, allLanguages, selectedCodes) {
            if (query.isBlank()) {
                val byCode = allLanguages.associateBy { it.code }
                suggestionCodes.mapNotNull(byCode::get).filter { it.code !in selectedCodes }
            } else {
                filteredLanguages
            }.take(10)
        }

    BaseDialog(
        title = stringResource(R.string.settings_smart_language_dialog_title),
        onDismiss = onDismissRequest,
        modifier = Modifier.widthIn(min = 720.dp, max = 980.dp).heightIn(max = 900.dp),
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
            modifier = Modifier.padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
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
                            style = MaterialTheme.typography.titleSmall,
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
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.settings_smart_language_list_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.settings_smart_language_picker_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )

            if (selectedCodes.isEmpty()) {
                Text(
                    text = stringResource(R.string.settings_smart_language_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            statusMessage?.let { message ->
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.8f),
                ) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacings.medium),
                    )
                }
            }

            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacings.medium),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
                ) {
                    Text(
                        text = stringResource(R.string.settings_smart_language_selected_preview_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
                    ) {
                        selectedCodes.take(4).forEach { code ->
                            SelectedLanguagePill(label = languageDisplayName(context, code))
                        }
                    }
                    if (selectedCodes.size > 4) {
                        Text(
                            text = stringResource(R.string.settings_smart_language_more_selected, selectedCodes.size - 4),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            OutlinedTextField(
                value = query,
                onValueChange = {
                    query = it
                    statusMessage = null
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.settings_smart_language_search)) },
                placeholder = {
                    Text(stringResource(R.string.settings_smart_language_search_placeholder))
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                singleLine = true,
            )

            Text(
                text = stringResource(R.string.settings_smart_language_quick_add_title),
                style = MaterialTheme.typography.titleSmall,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
            ) {
                quickSuggestions.forEach { option ->
                    OutlinedLanguageButton(
                        label = option.displayName,
                        onClick = {
                            selectedCodes = selectedCodes + option.code
                            query = ""
                            statusMessage =
                                context.getString(
                                    R.string.settings_smart_language_added_feedback,
                                    option.displayName,
                                )
                        },
                    )
                }
            }

            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacings.medium),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
                ) {
                    Text(
                        text = stringResource(R.string.settings_smart_language_selected_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = stringResource(R.string.settings_smart_language_reorder_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    selectedCodes.take(4).forEachIndexed { index, code ->
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacings.medium),
                                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = "${index + 1}. ${languageDisplayName(context, code)}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (index == 0) {
                                        SelectedLanguagePill(
                                            label = stringResource(R.string.settings_smart_language_top_priority)
                                        )
                                    }
                                }
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.extraSmall),
                                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.extraSmall),
                                ) {
                                    if (index > 0) {
                                        TextButton(
                                            onClick = {
                                                val name = languageDisplayName(context, code)
                                                selectedCodes =
                                                    selectedCodes.toMutableList().apply {
                                                        add(0, removeAt(index))
                                                    }
                                                statusMessage =
                                                    context.getString(
                                                        R.string.settings_smart_language_moved_first,
                                                        name,
                                                    )
                                            },
                                        ) {
                                            Text(stringResource(R.string.settings_smart_language_first))
                                        }
                                        TextButton(
                                            onClick = {
                                                val name = languageDisplayName(context, code)
                                                selectedCodes =
                                                    selectedCodes.toMutableList().apply {
                                                        add(index - 1, removeAt(index))
                                                    }
                                                statusMessage =
                                                    context.getString(
                                                        R.string.settings_smart_language_moved_earlier,
                                                        name,
                                                    )
                                            },
                                        ) {
                                            Text(stringResource(R.string.settings_smart_language_earlier))
                                        }
                                    }
                                    if (index < selectedCodes.lastIndex) {
                                        TextButton(
                                            onClick = {
                                                val name = languageDisplayName(context, code)
                                                selectedCodes =
                                                    selectedCodes.toMutableList().apply {
                                                        add(index + 1, removeAt(index))
                                                    }
                                                statusMessage =
                                                    context.getString(
                                                        R.string.settings_smart_language_moved_later,
                                                        name,
                                                    )
                                            },
                                        ) {
                                            Text(stringResource(R.string.settings_smart_language_later))
                                        }
                                    }
                                    TextButton(
                                        onClick = {
                                            val name = languageDisplayName(context, code)
                                            selectedCodes =
                                                selectedCodes.toMutableList().apply { removeAt(index) }
                                            statusMessage =
                                                context.getString(
                                                    R.string.settings_smart_language_removed_feedback,
                                                    name,
                                                )
                                        },
                                    ) {
                                        Text(stringResource(R.string.settings_smart_language_remove))
                                    }
                                }
                            }
                        }
                    }
                    if (selectedCodes.size > 4) {
                        Text(
                            text = stringResource(R.string.settings_smart_language_more_selected, selectedCodes.size - 4),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            filteredLanguages.take(if (query.isBlank()) 0 else 12).forEach { option ->
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
                                statusMessage =
                                    context.getString(
                                        R.string.settings_smart_language_added_feedback,
                                        option.displayName,
                                    )
                            },
                        ) {
                            Text(stringResource(R.string.settings_smart_language_add))
                        }
                    }
                }
            }

            Text(
                text = stringResource(R.string.settings_smart_language_subtitle_rule),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun OutlinedLanguageButton(
    label: String,
    onClick: () -> Unit,
) {
    Button(onClick = onClick) {
        Text(label)
    }
}

@Composable
private fun SelectedLanguagePill(label: String) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.75f),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

private fun languageDisplayName(context: Context, code: String): String {
    return LanguageCatalog.displayName(context, code)
}
