package dev.jdtech.jellyfin.presentation.film.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jdtech.jellyfin.presentation.components.BaseDialog
import dev.spatialfin.presentation.theme.spacings
import java.util.UUID

/**
 * "Edit external IDs" dialog. Given an [itemId], lets the user either paste a
 * correct IMDb ID directly or look one up by title via OMDb. On save the
 * ViewModel writes back to Jellyfin and kicks a metadata refresh; the dialog
 * auto-dismisses on success.
 *
 * Used by the 3-dots overflow on the movie / show hero card when a user wants
 * to correct metadata that's wrong or missing. Composable is form-factor
 * agnostic — looks the same on XR, Beam, and TV because [BaseDialog] handles
 * the shell.
 */
@Composable
fun EditExternalIdsDialog(
    itemId: UUID,
    initialTitle: String,
    initialYear: Int?,
    onDismiss: () -> Unit,
    onSaved: () -> Unit = {},
    viewModel: MetadataEditorViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(itemId) {
        viewModel.load(itemId, initialTitle, initialYear)
    }

    LaunchedEffect(state.saved) {
        if (state.saved) {
            viewModel.acknowledgeSaved()
            // onSaved fires first so the parent can start its delayed-reload
            // timer before the dialog tears down. Jellyfin's metadata refresh
            // is async on the server side, so the parent shouldn't assume
            // fresh data is available the instant this callback runs — see
            // the delay used at call sites.
            onSaved()
            onDismiss()
        }
    }

    BaseDialog(
        title = "Edit external IDs",
        onDismiss = onDismiss,
        negativeButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
        positiveButton = {
            Button(
                enabled = !state.isSaving && !state.isLoading,
                onClick = { viewModel.save() },
            ) {
                Text(if (state.isSaving) "Saving…" else "Save")
            }
        },
    ) { _ ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
        ) {
            // ---- Current IMDb ID ----------------------------------------
            // Shown even when null so first-time-set items look the same as
            // edits. Treating "None set" as first-class makes the dialog a
            // sensible surface for items imported from a filesystem where
            // Jellyfin couldn't match anything.
            val currentLine = state.currentImdbId
                ?.takeIf { it.isNotBlank() }
                ?.let { "Currently: $it" }
                ?: "No IMDb ID set on Jellyfin yet."
            Text(currentLine, style = MaterialTheme.typography.bodyMedium)

            OutlinedTextField(
                value = state.editedImdbId,
                onValueChange = { viewModel.updateEditedImdbId(it) },
                singleLine = true,
                label = { Text("IMDb ID") },
                placeholder = { Text("tt0133093") },
                supportingText = {
                    Text(
                        "Paste directly if you know it, or use title search below.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )

            HorizontalDivider()

            // ---- Search by title or IMDb ID ----------------------------
            Text("Search OMDb by title or IMDb ID", style = MaterialTheme.typography.titleSmall)
            if (!state.omdbConfigured) {
                Text(
                    "OMDb API key isn't set — search is disabled, but you can still paste an IMDb ID above.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = { viewModel.updateSearchQuery(it) },
                singleLine = true,
                label = { Text("Title with year, or IMDb ID") },
                placeholder = { Text("The Matrix 1999  •  tt0133093") },
                enabled = state.omdbConfigured,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { viewModel.searchOmdb() }),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedButton(
                    onClick = { viewModel.searchOmdb() },
                    enabled = state.omdbConfigured && !state.isSearching && state.searchQuery.isNotBlank(),
                ) {
                    Text(if (state.isSearching) "Searching…" else "Search")
                }
                if (state.isSearching) {
                    Spacer(Modifier.height(8.dp))
                    CircularProgressIndicator(Modifier.height(24.dp))
                }
            }

            state.searchResult?.let { hit ->
                HorizontalDivider()
                Column(
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.extraSmall),
                ) {
                    Text(
                        "${hit.title}${hit.year.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()}",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (hit.genre.isNotBlank() || hit.director.isNotBlank()) {
                        Text(
                            listOf(hit.genre, hit.director).filter { it.isNotBlank() }.joinToString(" — "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (hit.plot.isNotBlank()) {
                        Text(
                            hit.plot,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 3,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
                    ) {
                        Text(
                            "IMDb: ${hit.imdbId.ifBlank { "—" }}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 6.dp),
                        )
                        Spacer(Modifier.fillMaxWidth().padding(0.dp))
                    }
                    Button(
                        onClick = { viewModel.acceptSearchResult() },
                        enabled = hit.imdbId.isNotBlank() && hit.imdbId != state.editedImdbId,
                    ) {
                        Text("Use this IMDb ID")
                    }
                }
            }

            state.error?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Red,
                )
            }
        }
    }
}
