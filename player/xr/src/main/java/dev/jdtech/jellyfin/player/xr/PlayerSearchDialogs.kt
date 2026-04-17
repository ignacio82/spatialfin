package dev.jdtech.jellyfin.player.xr

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jdtech.jellyfin.models.SpatialFinItem
import dev.jdtech.jellyfin.player.local.presentation.PlayerViewModel
import java.util.Locale

/**
 * Search-related dialog content Composables.
 *
 * VoiceSearchDialogContent: results from "hey assistant, find X" queries —
 * stateless, just renders search output with Play / Trailer / Favorite actions.
 *
 * SubtitleSearchDialogContent: online subtitle search UI — binds directly to
 * PlayerViewModel.subtitleSearchState, since the ViewModel owns the entire
 * search lifecycle (throttling, network, download, switch).
 */

@Composable
internal fun VoiceSearchDialogContent(
    query: String,
    loading: Boolean,
    error: String?,
    results: List<SpatialFinItem>,
    currentItemTitle: String,
    onWatchTrailer: (SpatialFinItem) -> Unit,
    onMoreLikeThis: (SpatialFinItem) -> Unit,
    onToggleFavorite: (SpatialFinItem) -> Unit,
    onPlayResult: (SpatialFinItem) -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(36.dp),
        color = Color.Black.copy(alpha = 0.92f),
        modifier = Modifier.width(900.dp).height(760.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Voice Search",
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White,
                )
                TextButton(onClick = onDismiss) { Text("Close") }
            }
            Text(
                text = "Query: $query",
                style = MaterialTheme.typography.titleLarge,
                color = Color.White.copy(alpha = 0.9f),
            )
            Text(
                text = "Current playback stays active. Close this panel to continue $currentItemTitle.",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.7f),
            )
            if (results.isNotEmpty()) {
                Text(
                    text = "Say \u201cplay the first one\u201d or \u201cmore like the second one.\u201d",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFB3E5FC),
                )
            }
            when {
                loading -> Text("Searching...", color = Color.White)
                error != null -> Text(error, color = Color(0xFFEF5350))
                results.isEmpty() -> Text("No results found", color = Color.White.copy(alpha = 0.75f))
                else -> {
                    Column(
                        modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        results.forEachIndexed { index, item ->
                            Surface(
                                shape = RoundedCornerShape(24.dp),
                                color = Color.White.copy(alpha = 0.08f),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(18.dp),
                                    verticalAlignment = Alignment.Top,
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(14.dp),
                                        color = Color(0xFF4FC3F7).copy(alpha = 0.2f),
                                    ) {
                                        Text(
                                            text = "${index + 1}",
                                            color = Color.White,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                            style = MaterialTheme.typography.titleMedium,
                                        )
                                    }
                                    SearchResultPoster(item = item)
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = item.name,
                                            color = Color.White,
                                            style = MaterialTheme.typography.titleMedium,
                                        )
                                        Text(
                                            text = searchResultTypeLabel(item),
                                            color = Color.White.copy(alpha = 0.7f),
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                        item.overview.take(120).takeIf { it.isNotBlank() }?.let { overview ->
                                            Text(
                                                text = overview,
                                                color = Color.White.copy(alpha = 0.7f),
                                                style = MaterialTheme.typography.bodySmall,
                                                modifier = Modifier.padding(top = 4.dp),
                                            )
                                        }
                                        Row(
                                            modifier = Modifier.padding(top = 12.dp),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            if (canPlayFromVoiceSearch(item)) {
                                                Button(onClick = { onPlayResult(item) }) {
                                                    Text("Play")
                                                }
                                            }
                                            TextButton(onClick = { onMoreLikeThis(item) }) {
                                                Text("More Like This")
                                            }
                                            if (itemTrailerUrl(item) != null) {
                                                TextButton(onClick = { onWatchTrailer(item) }) {
                                                    Text("Trailer")
                                                }
                                            }
                                            TextButton(onClick = { onToggleFavorite(item) }) {
                                                Text(if (item.favorite) "Saved" else "Save")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = onDismiss) { Text("Resume Current Video") }
            }
        }
    }
}

@Composable
internal fun SubtitleSearchDialogContent(
    viewModel: PlayerViewModel,
    onDismiss: () -> Unit,
) {
    val searchState by viewModel.subtitleSearchState.collectAsStateWithLifecycle()
    var language by remember { mutableStateOf(Locale.getDefault().language) }

    Surface(
        modifier = Modifier
            .width(600.dp)
            .heightIn(max = 560.dp),
        shape = RoundedCornerShape(32.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 12.dp,
    ) {
        Column(modifier = Modifier.padding(32.dp)) {
            Text("Search Subtitles", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = language,
                onValueChange = { language = it },
                label = { Text("Language Code (e.g. eng, spa, fre)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardActions = KeyboardActions(
                    onSearch = { viewModel.searchForSubtitles(language) }
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            )

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = { viewModel.searchForSubtitles(language) },
                modifier = Modifier.align(Alignment.End),
            ) { Text("Search") }

            Spacer(Modifier.height(16.dp))

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (val state = searchState) {
                    is PlayerViewModel.SubtitleSearchState.Idle -> {
                        Text("Enter a language and click search.", modifier = Modifier.align(Alignment.Center))
                    }
                    is PlayerViewModel.SubtitleSearchState.Searching -> {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    }
                    is PlayerViewModel.SubtitleSearchState.Downloading -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.align(Alignment.Center),
                        ) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(8.dp))
                            Text("Downloading...")
                        }
                    }
                    is PlayerViewModel.SubtitleSearchState.Error -> {
                        Text("Error: ${state.message}", color = MaterialTheme.colorScheme.error)
                    }
                    is PlayerViewModel.SubtitleSearchState.Success -> {
                        if (state.options.isEmpty()) {
                            Text("No subtitles found.", modifier = Modifier.align(Alignment.Center))
                        } else {
                            LazyColumn(modifier = Modifier.fillMaxSize()) {
                                items(state.options) { option ->
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { viewModel.downloadAndSwitchSubtitles(option) }
                                            .padding(vertical = 12.dp, horizontal = 8.dp),
                                    ) {
                                        Text(option.name ?: "Unknown", style = MaterialTheme.typography.titleMedium)
                                        Text(
                                            "Format: ${option.format} \u2022 Rating: ${option.communityRating ?: 0}",
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                    }
                                    HorizontalDivider()
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text("CLOSE", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
