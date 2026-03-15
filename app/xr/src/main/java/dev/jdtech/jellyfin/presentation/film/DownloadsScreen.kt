package dev.jdtech.jellyfin.presentation.film

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.presentation.dummy.dummyMovies
import dev.jdtech.jellyfin.film.presentation.collection.CollectionAction
import dev.jdtech.jellyfin.film.presentation.collection.CollectionState
import dev.jdtech.jellyfin.film.presentation.downloads.DownloadsViewModel
import dev.jdtech.jellyfin.models.CollectionSection
import dev.jdtech.jellyfin.models.SpatialFinItem
import dev.jdtech.jellyfin.models.UiText
import dev.jdtech.jellyfin.presentation.film.components.CollectionGrid
import dev.jdtech.jellyfin.presentation.film.components.DeleteDownloadDialog
import dev.jdtech.jellyfin.presentation.film.components.XrBrowseHeader
import dev.jdtech.jellyfin.presentation.utils.rememberSafePadding
import dev.spatialfin.presentation.theme.SpatialFinTheme
import dev.spatialfin.presentation.theme.spacings

@Composable
fun DownloadsScreen(
    onItemClick: (item: SpatialFinItem) -> Unit,
    viewModel: DownloadsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pendingDeleteItem by remember { mutableStateOf<SpatialFinItem?>(null) }

    LaunchedEffect(true) { viewModel.loadItems() }

    DownloadsScreenLayout(
        state = state,
        onDeleteItem = { pendingDeleteItem = it },
        onAction = { action ->
            when (action) {
                is CollectionAction.OnItemClick -> onItemClick(action.item)
                is CollectionAction.OnBackClick -> Unit
            }
        },
    )

    pendingDeleteItem?.let { item ->
        DeleteDownloadDialog(
            onDelete = {
                viewModel.deleteItem(item)
                pendingDeleteItem = null
            },
            onDismiss = { pendingDeleteItem = null },
        )
    }
}

@Composable
private fun DownloadsScreenLayout(
    state: CollectionState,
    onDeleteItem: ((SpatialFinItem) -> Unit)? = null,
    onAction: (CollectionAction) -> Unit,
) {
    val safePadding = rememberSafePadding()

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .padding(
                        start = safePadding.start + MaterialTheme.spacings.default,
                        top = safePadding.top + MaterialTheme.spacings.default,
                        end = safePadding.end + MaterialTheme.spacings.default,
                    ),
        ) {
            XrBrowseHeader(
                title = stringResource(CoreR.string.title_download),
            )
        }

        if (state.sections.isEmpty()) {
            Text(
                text = stringResource(CoreR.string.no_downloads),
                modifier = Modifier.align(Alignment.Center),
                style = MaterialTheme.typography.headlineSmall,
            )
        } else {
            CollectionGrid(
                sections = state.sections,
                innerPadding =
                    PaddingValues(
                        top = safePadding.top + 96.dp,
                        bottom = safePadding.bottom,
                    ),
                onAction = onAction,
                onDeleteItem = onDeleteItem,
            )
        }
    }
}

@PreviewScreenSizes
@Composable
private fun DownloadsScreenLayoutPreview() {
    SpatialFinTheme {
        DownloadsScreenLayout(
            state =
                CollectionState(
                    sections =
                        listOf(
                            CollectionSection(
                                id = 0,
                                name = UiText.StringResource(CoreR.string.movies_label),
                                items = dummyMovies,
                            )
                        )
                ),
            onDeleteItem = {},
            onAction = {},
        )
    }
}
