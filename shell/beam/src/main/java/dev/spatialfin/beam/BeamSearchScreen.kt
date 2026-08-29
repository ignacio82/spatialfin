package dev.spatialfin.beam

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jdtech.jellyfin.api.SeerrSearchResult
import dev.jdtech.jellyfin.models.CollectionType
import dev.spatialfin.unified.audio.JellyfinAudioDetailType
import dev.spatialfin.unified.audio.LocalAudioPlaybackDispatcher
import java.util.UUID

/**
 * Jellyfin search with the Seerr request fallback.
 *
 * Split out of BeamJellyfinScreens.kt, which had grown past 4000 lines. Same
 * package, so nothing here changed except its file.
 */
@Composable
fun BeamSearchScreen(
    contentPadding: PaddingValues,
    voiceQuery: String? = null,
    onVoiceQueryConsumed: () -> Unit = {},
    onOpenLibrary: (UUID, String, CollectionType) -> Unit,
    onOpenShow: (UUID) -> Unit,
    onOpenSeason: (UUID) -> Unit,
    onOpenItem: (UUID) -> Unit,
    onOpenJellyfinAudioDetail: (UUID, String, JellyfinAudioDetailType) -> Unit,
    onOpenMaSearch: () -> Unit = {},
    viewModel: BeamSearchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val fcastSession = dev.spatialfin.fcast.session.LocalFCastSession.current
    val jellyfinAudioDispatcher = LocalAudioPlaybackDispatcher.current
    val scope = rememberCoroutineScope()
    var submittedInitialSearch by rememberSaveable { mutableStateOf(false) }
    var pendingSeerrRequest by remember { mutableStateOf<SeerrSearchResult?>(null) }

    LaunchedEffect(voiceQuery) {
        if (!voiceQuery.isNullOrBlank()) {
            viewModel.setQuery(voiceQuery)
            viewModel.search()
            onVoiceQueryConsumed()
        }
    }

    LaunchedEffect(submittedInitialSearch) {
        if (!submittedInitialSearch && state.query.isBlank()) {
            submittedInitialSearch = true
        }
    }

    pendingSeerrRequest?.let { item ->
        BeamSeerrRequestDialog(
            item = item,
            onConfirm = { is4k ->
                viewModel.requestSeerrItem(item, is4k)
                pendingSeerrRequest = null
            },
            onDismiss = { pendingSeerrRequest = null },
        )
    }

    BeamScaffoldBody(contentPadding = contentPadding) {
        item {
            BeamScreenHeader(
                title = "Search",
                body = "Find movies, shows, and more.",
            )
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = viewModel::setQuery,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Search Jellyfin") },
                        singleLine = true,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = viewModel::search) {
                            Text("Search")
                        }
                        if (state.query.isNotBlank()) {
                            OutlinedButton(onClick = { viewModel.setQuery("") }) {
                                Text("Clear")
                            }
                        }
                        OutlinedButton(onClick = onOpenMaSearch) {
                            Text("Search Music Assistant")
                        }
                    }
                }
            }
        }
        when {
            state.isLoading -> item { LoadingCard("Searching Jellyfin and Jellyseerr...") }
            state.error != null -> item {
                ErrorCard(
                    title = "Search failed.",
                    body = state.error?.localizedMessage ?: "Unknown error",
                    onRetry = viewModel::search,
                )
            }
            state.hasSearched && state.items.isEmpty() && state.seerrItems.isEmpty() -> item {
                BeamEmptyCard("No items matched your search.")
            }
            state.items.isNotEmpty() -> {
                item {
                    Text("In Your Library", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                }
                items(state.items, key = { it.id }) { item ->
                    BeamServerItemCard(
                        item = item,
                        onPlay = {
                            if (!playNativeAudioItem(item, jellyfinAudioDispatcher)) {
                                launchServerItem(context, fcastSession, scope, item)
                            }
                        },
                        onOpen = {
                            openServerItem(
                                context,
                                item,
                                onOpenLibrary,
                                onOpenShow,
                                onOpenSeason,
                                onOpenItem,
                                audioDispatcher = jellyfinAudioDispatcher,
                                onOpenJellyfinAudioDetail = onOpenJellyfinAudioDetail,
                            )
                        },
                    )
                }
            }
            else -> Unit
        }
        state.seerrError?.let { seerrError ->
            item {
                ErrorCard(
                    title = "Jellyseerr unavailable",
                    body = seerrError,
                    onRetry = viewModel::search,
                )
            }
        }
        if (state.seerrItems.isNotEmpty()) {
            item {
                Text("Request From Jellyseerr", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
            items(
                items = state.seerrItems,
                key = { "${it.mediaType}-${it.mediaId ?: it.id ?: it.title ?: it.name}" },
            ) { item ->
                BeamSeerrItemCard(
                    item = item,
                    onRequestClick = { pendingSeerrRequest = item },
                )
            }
        }
    }
}

@Composable
internal fun BeamSeerrItemCard(
    item: SeerrSearchResult,
    onRequestClick: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.title ?: item.name ?: "Unknown title",
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    buildBeamSeerrSubtitle(item)?.let { subtitle ->
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                BeamSeerrStatusBadge(item.mediaInfo?.status)
            }
            item.overview?.takeIf { it.isNotBlank() }?.let { overview ->
                Text(
                    text = overview,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            when (item.mediaInfo?.status ?: 1) {
                1 -> Button(onClick = onRequestClick) { Text("Request") }
                2 -> Text("Request pending", color = MaterialTheme.colorScheme.primary)
                3 -> Text("Request processing", color = MaterialTheme.colorScheme.primary)
                4 -> Text("Partially available", color = MaterialTheme.colorScheme.primary)
                5 -> Text("Already available", color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
internal fun BeamSeerrRequestDialog(
    item: SeerrSearchResult,
    onConfirm: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Request From Jellyseerr") },
        text = {
            BeamScrollableDialogBody {
                Text("Choose request quality for ${item.title ?: item.name ?: "this item"}.")
                FilledTonalButton(onClick = { onConfirm(false) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Standard")
                }
                FilledTonalButton(onClick = { onConfirm(true) }, modifier = Modifier.fillMaxWidth()) {
                    Text("4K")
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
