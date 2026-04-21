package dev.jdtech.jellyfin.presentation.local

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.presentation.film.components.XrBrowseHeader
import dev.jdtech.jellyfin.presentation.utils.rememberSafePadding
import dev.jdtech.jellyfin.player.xr.StereoModeDetector
import dev.jdtech.jellyfin.player.xr.XrPlayerActivity
import dev.spatialfin.presentation.theme.spacings

@Composable
fun LocalVideoScreen(
    mediaStoreId: Long,
    navigateBack: () -> Unit,
    viewModel: LocalVideoViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(mediaStoreId) { viewModel.load(mediaStoreId) }

    LocalVideoScreenLayout(
        state = state,
        onBack = navigateBack,
        onPlay = { startFromBeginning, multitask ->
            val item = state.item
            val stereoMode = if (item != null) {
                StereoModeDetector.detect(item.name, null, listOf(item.fileName))
            } else {
                StereoModeDetector.StereoMode.MONO
            }
            val stereoModeStr = when (stereoMode) {
                StereoModeDetector.StereoMode.SIDE_BY_SIDE -> "sbs"
                StereoModeDetector.StereoMode.TOP_BOTTOM -> "top_bottom"
                StereoModeDetector.StereoMode.MULTIVIEW -> "multiview"
                else -> "mono"
            }
            if (multitask) {
                context.startActivity(
                    dev.jdtech.jellyfin.player.xr.MultitaskPlayerActivity.createIntentForLocalMedia(
                        context = context,
                        mediaStoreId = mediaStoreId,
                        startFromBeginning = startFromBeginning,
                    )
                )
            } else {
                context.startActivity(
                    XrPlayerActivity.createIntentForLocalMedia(
                        context = context,
                        mediaStoreId = mediaStoreId,
                        startFromBeginning = startFromBeginning,
                        stereoMode = stereoModeStr,
                    )
                )
            }
        },
        onTogglePlayed = { played -> viewModel.markPlayed(mediaStoreId, played) },
    )
}

@Composable
private fun LocalVideoScreenLayout(
    state: LocalVideoState,
    onBack: () -> Unit,
    onPlay: (Boolean, Boolean) -> Unit,
    onTogglePlayed: (Boolean) -> Unit,
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
                title = state.item?.name ?: stringResource(CoreR.string.title_local),
                onBackClick = onBack,
            )
        }

        when {
            state.isLoading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            state.item == null -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(CoreR.string.local_video_missing),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                }
            }
            else -> {
                val item = state.item
                Column(
                    modifier =
                        Modifier.fillMaxSize()
                            .padding(
                                start = safePadding.start + 24.dp,
                                top = safePadding.top + 112.dp,
                                end = safePadding.end + 24.dp,
                                bottom = safePadding.bottom + 24.dp,
                            )
                            .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.large),
                ) {
                    Text(text = item.name, style = MaterialTheme.typography.displaySmall)
                    item.folderName?.let {
                        Text(text = it, style = MaterialTheme.typography.titleLarge)
                    }
                    item.productionYear?.let {
                        Text(text = it.toString(), style = MaterialTheme.typography.titleMedium)
                    }
                    if (item.seasonNumber != null && item.episodeNumber != null) {
                        Text(
                            text = "S${item.seasonNumber}:E${item.episodeNumber}",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    Text(
                        text = stringResource(
                            CoreR.string.runtime_minutes,
                            (item.durationMs / 60000L).toInt(),
                        ),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FilledTonalButton(onClick = { onPlay(false, false) }) {
                            Text(
                                stringResource(CoreR.string.play),
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                        FilledTonalButton(onClick = { onPlay(false, true) }) {
                            Text(
                                "Multitask",
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                        if (item.playbackPositionTicks > 0) {
                            FilledTonalButton(onClick = { onPlay(true, false) }) {
                                Text(
                                    stringResource(CoreR.string.local_restart),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }
                        }
                        Button(onClick = { onTogglePlayed(!item.played) }) {
                            Text(
                                if (item.played) {
                                    stringResource(CoreR.string.unmark_as_played)
                                } else {
                                    stringResource(CoreR.string.mark_as_played)
                                },
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    }
                    if (item.overview.isNotBlank()) {
                        Text(text = item.overview, style = MaterialTheme.typography.bodyLarge)
                    }
                    Text(text = item.fileName, style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}
