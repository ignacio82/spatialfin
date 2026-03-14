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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jdtech.jellyfin.core.R as CoreR
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
        onPlay = { startFromBeginning ->
            context.startActivity(
                XrPlayerActivity.createIntentForLocalMedia(
                    context = context,
                    mediaStoreId = mediaStoreId,
                    startFromBeginning = startFromBeginning,
                )
            )
        },
        onTogglePlayed = { played -> viewModel.markPlayed(mediaStoreId, played) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocalVideoScreenLayout(
    state: LocalVideoState,
    onBack: () -> Unit,
    onPlay: (Boolean) -> Unit,
    onTogglePlayed: (Boolean) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.item?.name ?: stringResource(CoreR.string.title_local)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(CoreR.drawable.ic_arrow_left),
                            contentDescription = null,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(),
            )
        }
    ) { innerPadding ->
        when {
            state.isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            state.item == null -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(CoreR.string.local_video_missing))
                }
            }
            else -> {
                val item = state.item
                Column(
                    modifier =
                        Modifier.fillMaxSize()
                            .padding(innerPadding)
                            .padding(24.dp)
                            .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.default),
                ) {
                    Text(text = item.name, style = MaterialTheme.typography.headlineMedium)
                    item.folderName?.let {
                        Text(text = it, style = MaterialTheme.typography.bodyMedium)
                    }
                    item.productionYear?.let {
                        Text(text = it.toString(), style = MaterialTheme.typography.bodyMedium)
                    }
                    if (item.seasonNumber != null && item.episodeNumber != null) {
                        Text(
                            text = "S${item.seasonNumber}:E${item.episodeNumber}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Text(
                        text = stringResource(
                            CoreR.string.runtime_minutes,
                            (item.durationMs / 60000L).toInt(),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { onPlay(false) }) {
                            Text(stringResource(CoreR.string.play))
                        }
                        if (item.playbackPositionTicks > 0) {
                            Button(onClick = { onPlay(true) }) {
                                Text(stringResource(CoreR.string.local_restart))
                            }
                        }
                        Button(onClick = { onTogglePlayed(!item.played) }) {
                            Text(
                                if (item.played) {
                                    stringResource(CoreR.string.unmark_as_played)
                                } else {
                                    stringResource(CoreR.string.mark_as_played)
                                }
                            )
                        }
                    }
                    if (item.overview.isNotBlank()) {
                        Text(text = item.overview, style = MaterialTheme.typography.bodyLarge)
                    }
                    Text(text = item.fileName, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
