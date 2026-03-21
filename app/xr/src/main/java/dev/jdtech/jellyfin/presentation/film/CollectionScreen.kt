package dev.jdtech.jellyfin.presentation.film

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.presentation.dummy.dummyMovies
import dev.jdtech.jellyfin.film.presentation.collection.CollectionAction
import dev.jdtech.jellyfin.film.presentation.collection.CollectionState
import dev.jdtech.jellyfin.film.presentation.collection.CollectionViewModel
import dev.jdtech.jellyfin.models.CollectionSection
import dev.jdtech.jellyfin.models.SpatialFinItem
import dev.jdtech.jellyfin.models.UiText
import dev.jdtech.jellyfin.presentation.film.components.CollectionGrid
import dev.jdtech.jellyfin.presentation.film.components.XrBrowseHeader
import dev.jdtech.jellyfin.presentation.utils.rememberSafePadding
import dev.spatialfin.presentation.theme.SpatialFinTheme
import dev.spatialfin.presentation.theme.spacings
import java.util.UUID

@Composable
fun CollectionScreen(
    collectionId: UUID,
    collectionName: String,
    onItemClick: (item: SpatialFinItem) -> Unit,
    navigateBack: () -> Unit,
    viewModel: CollectionViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(true) { viewModel.loadItems(collectionId) }

    CollectionScreenLayout(
        collectionName = collectionName,
        state = state,
        onAction = { action ->
            when (action) {
                is CollectionAction.OnItemClick -> onItemClick(action.item)
                is CollectionAction.OnBackClick -> navigateBack()
            }
        },
    )
}

@Composable
fun CollectionScreenLayout(
    collectionName: String,
    state: CollectionState,
    onAction: (CollectionAction) -> Unit,
) {
    val safePadding = rememberSafePadding()

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .padding(
                        start = safePadding.start + androidx.compose.material3.MaterialTheme.spacings.default,
                        top = safePadding.top + androidx.compose.material3.MaterialTheme.spacings.default,
                        end = safePadding.end + androidx.compose.material3.MaterialTheme.spacings.default,
                    ),
        ) {
            XrBrowseHeader(
                title = collectionName,
                onBackClick = { onAction(CollectionAction.OnBackClick) },
            )
        }

        CollectionGrid(
            sections = state.sections,
            displayRatings = state.displayRatings,
            innerPadding =
                PaddingValues(
                    top = safePadding.top + 96.dp,
                    bottom = safePadding.bottom,
                ),
            onAction = onAction,
        )
    }
}

@PreviewScreenSizes
@Composable
private fun CollectionScreenLayoutPreview() {
    SpatialFinTheme {
        CollectionScreenLayout(
            collectionName = "Marvel",
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
            onAction = {},
        )
    }
}
