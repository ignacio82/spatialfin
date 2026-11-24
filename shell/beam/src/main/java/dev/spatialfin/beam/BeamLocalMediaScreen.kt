package dev.spatialfin.beam

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.core.util.hasLocalVideoAccess
import dev.jdtech.jellyfin.core.util.localVideoPermissions
import dev.jdtech.jellyfin.models.LocalVideoItem
import dev.jdtech.jellyfin.models.NetworkVideoItem
import dev.jdtech.jellyfin.player.beam.BeamPlayerActivity
import dev.jdtech.jellyfin.presentation.network.NetworkViewModel
import dev.jdtech.jellyfin.repository.LocalMediaRepository
import dev.jdtech.jellyfin.setup.R as SetupR
import dev.spatialfin.unified.applock.LocalSystemModalGraceHook
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class BeamLocalMediaState(
    val items: List<LocalVideoItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: Throwable? = null,
)

@HiltViewModel
class BeamLocalMediaViewModel
@Inject
constructor(
    private val repository: LocalMediaRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(BeamLocalMediaState())
    val state = _state.asStateFlow()

    fun loadVideos() {
        viewModelScope.launch {
            _state.emit(_state.value.copy(isLoading = true, error = null))
            runCatching { repository.getVideos() }
                .onSuccess { videos ->
                    _state.emit(
                        BeamLocalMediaState(
                            items = videos.sortedByDescending { it.dateAddedEpochSeconds },
                            isLoading = false,
                        ),
                    )
                }
                .onFailure { error ->
                    _state.emit(BeamLocalMediaState(isLoading = false, error = error))
                }
        }
    }
}

@Composable
fun BeamLocalAndNetworkScreen(
    contentPadding: PaddingValues,
    hasServers: Boolean,
    onConnectToServer: () -> Unit,
    onShareClick: (String) -> Unit,
    onAddShareClick: () -> Unit,
    onNetworkVideoClick: (NetworkVideoItem) -> Unit,
    localViewModel: BeamLocalMediaViewModel = hiltViewModel(),
    networkViewModel: NetworkViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val graceHook = LocalSystemModalGraceHook.current
    val localState by localViewModel.state.collectAsStateWithLifecycle()
    val networkState by networkViewModel.state.collectAsStateWithLifecycle()
    var hasPermission by remember { mutableStateOf(hasLocalVideoAccess(context)) }
    var permissionRequestActive by remember { mutableStateOf(false) }

    DisposableEffect(graceHook) {
        onDispose {
            if (permissionRequestActive) {
                permissionRequestActive = false
                graceHook.setSystemModalActive(false)
            }
        }
    }

    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (permissionRequestActive) {
                permissionRequestActive = false
                graceHook.setSystemModalActive(false)
            }
            hasPermission = hasLocalVideoAccess(context)
            if (hasPermission) {
                localViewModel.loadVideos()
            }
        }

    LaunchedEffect(hasPermission) {
        if (hasPermission) {
            localViewModel.loadVideos()
        }
    }
    LaunchedEffect(Unit) {
        networkViewModel.loadShares()
    }

    BeamScaffoldBody(contentPadding = contentPadding) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Local & Network",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Browse videos on this device and your saved SMB/NFS network shares.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (!hasServers) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = stringResource(SetupR.string.connect_to_jellyfin_server),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = stringResource(SetupR.string.connect_to_jellyfin_server_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(onClick = onConnectToServer) {
                            Text(stringResource(SetupR.string.connect_to_jellyfin_server))
                        }
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Network Shares",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                OutlinedButton(onClick = onAddShareClick) {
                    Text("Add Share")
                }
            }
        }

        when {
            networkState.isLoading -> {
                item { LoadingCard("Loading network shares...") }
            }
            networkState.error != null -> {
                item {
                    ErrorCard(
                        title = "Failed to load network shares.",
                        body = networkState.error?.localizedMessage ?: "Unknown error",
                        onRetry = { networkViewModel.loadShares() },
                    )
                }
            }
            networkState.shares.isEmpty() -> {
                item {
                    BeamEmptyCard("No network shares added yet.")
                }
            }
            else -> {
                if (networkState.resumeItems.isNotEmpty()) {
                    items(networkState.resumeItems, key = { "resume:${it.networkVideoId}" }) { item ->
                        BeamNetworkVideoCard(
                            item = item,
                            onPlay = { onNetworkVideoClick(item) },
                        )
                    }
                }
                items(networkState.shares, key = { "share:${it.id}" }) { share ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                text = share.displayName ?: share.shareName,
                                style = MaterialTheme.typography.titleLarge,
                            )
                            Text(
                                text = "${share.protocol.uppercase()}://${share.host}/${share.shareName}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(onClick = { onShareClick(share.id) }) {
                                    Text("Open Share")
                                }
                                OutlinedButton(onClick = { networkViewModel.removeShare(share.id) }) {
                                    Text("Remove")
                                }
                            }
                        }
                    }
                }
            }
        }

        item {
            Text(
                text = "On This Device",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }

        when {
            !hasPermission -> {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                text = "Video access is required to show local files.",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Button(
                                onClick = {
                                    if (!permissionRequestActive) {
                                        permissionRequestActive = true
                                        graceHook.setSystemModalActive(true)
                                    }
                                    permissionLauncher.launch(localVideoPermissions())
                                },
                            ) {
                                Text("Grant Access")
                            }
                        }
                    }
                }
            }
            localState.isLoading -> {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.padding(20.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CircularProgressIndicator()
                            Text("Loading local videos...")
                        }
                    }
                }
            }
            localState.error != null -> {
                item {
                    ErrorCard(
                        title = "Failed to load local videos.",
                        body = localState.error?.localizedMessage ?: "Unknown error",
                        onRetry = { localViewModel.loadVideos() },
                    )
                }
            }
            localState.items.isEmpty() -> {
                item {
                    BeamEmptyCard("No local videos found.")
                }
            }
            else -> {
                items(localState.items, key = { it.mediaStoreId }) { item ->
                    BeamLocalVideoCard(
                        item = item,
                        onPlay = { startFromBeginning ->
                            context.startActivity(
                                BeamPlayerActivity.createIntentForLocalMedia(
                                    context = context,
                                    mediaStoreId = item.mediaStoreId,
                                    startFromBeginning = startFromBeginning,
                                ),
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
fun BeamLocalMediaScreen(
    contentPadding: PaddingValues,
    onConnectToServer: () -> Unit,
    viewModel: BeamLocalMediaViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val graceHook = LocalSystemModalGraceHook.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    var hasPermission by remember { mutableStateOf(hasLocalVideoAccess(context)) }
    var permissionRequestActive by remember { mutableStateOf(false) }

    DisposableEffect(graceHook) {
        onDispose {
            if (permissionRequestActive) {
                permissionRequestActive = false
                graceHook.setSystemModalActive(false)
            }
        }
    }

    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (permissionRequestActive) {
                permissionRequestActive = false
                graceHook.setSystemModalActive(false)
            }
            hasPermission = hasLocalVideoAccess(context)
            if (hasPermission) {
                viewModel.loadVideos()
            }
        }

    LaunchedEffect(hasPermission) {
        if (hasPermission) {
            viewModel.loadVideos()
        }
    }

    BeamScaffoldBody(contentPadding = contentPadding) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Local Media",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Browse videos stored on this device.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        when {
            !hasPermission -> {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                text = "Video access is required to show local files.",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(
                                    onClick = {
                                        if (!permissionRequestActive) {
                                            permissionRequestActive = true
                                            graceHook.setSystemModalActive(true)
                                        }
                                        permissionLauncher.launch(localVideoPermissions())
                                    },
                                ) {
                                    Text("Grant Access")
                                }
                                OutlinedButton(onClick = onConnectToServer) {
                                    Text(stringResource(SetupR.string.connect_to_jellyfin_server))
                                }
                            }
                        }
                    }
                }
            }
            state.isLoading -> {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.padding(20.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CircularProgressIndicator()
                            Text("Loading local videos...")
                        }
                    }
                }
            }
            state.error != null -> {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                text = "Failed to load local videos.",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = state.error?.localizedMessage ?: "Unknown error",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(onClick = { viewModel.loadVideos() }) {
                                    Text("Retry")
                                }
                                OutlinedButton(onClick = onConnectToServer) {
                                    Text(stringResource(SetupR.string.connect_to_jellyfin_server))
                                }
                            }
                        }
                    }
                }
            }
            state.items.isEmpty() -> {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                text = "No local videos found.",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            OutlinedButton(onClick = onConnectToServer) {
                                Text(stringResource(SetupR.string.connect_to_jellyfin_server))
                            }
                        }
                    }
                }
            }
            else -> {
                items(state.items, key = { it.mediaStoreId }) { item ->
                    BeamLocalVideoCard(
                        item = item,
                        onPlay = { startFromBeginning ->
                            context.startActivity(
                                BeamPlayerActivity.createIntentForLocalMedia(
                                    context = context,
                                    mediaStoreId = item.mediaStoreId,
                                    startFromBeginning = startFromBeginning,
                                ),
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun BeamLocalVideoCard(
    item: LocalVideoItem,
    onPlay: (startFromBeginning: Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.titleLarge,
            )
            val metadata =
                listOfNotNull(
                    item.folderName,
                    item.productionYear?.toString(),
                    item.durationMs.takeIf { it > 0 }?.let(::formatDuration),
                ).joinToString(" • ")
            if (metadata.isNotBlank()) {
                Text(
                    text = metadata,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledIconButton(onClick = { onPlay(false) }) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = "Play",
                    )
                }
                FilledTonalIconButton(onClick = { onPlay(true) }) {
                    Icon(
                        imageVector = Icons.Rounded.Replay,
                        contentDescription = "Play From Start",
                    )
                }
            }
        }
    }
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
