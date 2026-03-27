package dev.spatialfin.beam

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.models.NetworkShareDto
import dev.jdtech.jellyfin.models.NetworkVideoItem
import dev.jdtech.jellyfin.repository.NetworkMediaRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class BeamNetworkState(
    val shares: List<NetworkShareDto> = emptyList(),
    val resumeItems: List<NetworkVideoItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: Throwable? = null,
)

internal fun dedupeNetworkShares(shares: List<NetworkShareDto>): List<NetworkShareDto> =
    shares.distinctBy { it.id }

internal fun dedupeNetworkVideos(videos: List<NetworkVideoItem>): List<NetworkVideoItem> =
    videos.distinctBy { it.networkVideoId }

@HiltViewModel
class BeamNetworkViewModel
@Inject
constructor(
    private val repository: NetworkMediaRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(BeamNetworkState())
    val state = _state.asStateFlow()

    fun loadShares() {
        viewModelScope.launch {
            _state.emit(_state.value.copy(isLoading = true, error = null))
            runCatching {
                val shares = dedupeNetworkShares(repository.getShares())
                BeamNetworkState(
                    shares = shares,
                    resumeItems = dedupeNetworkVideos(repository.getResumeItems()),
                    isLoading = false,
                )
            }.onSuccess { _state.emit(it) }
                .onFailure { _state.emit(BeamNetworkState(isLoading = false, error = it)) }
        }
    }

    fun removeShare(shareId: String) {
        viewModelScope.launch {
            repository.removeShare(shareId)
            loadShares()
        }
    }
}

data class BeamNetworkShareState(
    val share: NetworkShareDto? = null,
    val videos: List<NetworkVideoItem> = emptyList(),
    val isLoading: Boolean = false,
    val isScanning: Boolean = false,
    val error: Throwable? = null,
)

@HiltViewModel
class BeamNetworkShareViewModel
@Inject
constructor(
    private val repository: NetworkMediaRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(BeamNetworkShareState())
    val state = _state.asStateFlow()

    fun load(shareId: String) {
        viewModelScope.launch {
            _state.emit(_state.value.copy(isLoading = true, error = null))
            runCatching {
                val shares = dedupeNetworkShares(repository.getShares())
                BeamNetworkShareState(
                    share = shares.find { it.id == shareId },
                    videos = dedupeNetworkVideos(repository.getVideosByShare(shareId)),
                    isLoading = false,
                )
            }.onSuccess { _state.emit(it) }
                .onFailure { _state.emit(BeamNetworkShareState(isLoading = false, error = it)) }
        }
    }

    fun scanShare(shareId: String) {
        viewModelScope.launch {
            _state.emit(_state.value.copy(isScanning = true, error = null))
            runCatching {
                repository.scanShare(shareId)
                repository.enrichMetadata(shareId)
            }.onSuccess { load(shareId) }
                .onFailure { _state.emit(_state.value.copy(isScanning = false, error = it)) }
        }
    }

    fun removeShare(shareId: String) {
        viewModelScope.launch {
            repository.removeShare(shareId)
        }
    }
}

@Composable
fun BeamNetworkScreen(
    contentPadding: PaddingValues,
    onShareClick: (String) -> Unit,
    onItemClick: (NetworkVideoItem) -> Unit,
    viewModel: BeamNetworkViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.loadShares()
    }

    BeamScaffoldBody(contentPadding = contentPadding) {
        item {
            BeamScreenHeader(
                title = "Network Shares",
                body = "Browse network shares and continue watching.",
            )
        }

        when {
            state.isLoading -> {
                item {
                    LoadingCard("Loading network shares...")
                }
            }
            state.error != null -> {
                item {
                    ErrorCard(
                        title = "Failed to load network shares.",
                        body = state.error?.localizedMessage ?: "Unknown error",
                        onRetry = { viewModel.loadShares() },
                    )
                }
            }
            state.shares.isEmpty() -> {
                item {
                    BeamEmptyCard("No saved network shares yet. Add-share UI is still pending for Beam.")
                }
            }
            else -> {
                if (state.resumeItems.isNotEmpty()) {
                    item {
                        Text(
                            text = "Continue Watching",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    items(state.resumeItems, key = { it.networkVideoId }) { item ->
                        BeamNetworkVideoCard(
                            item = item,
                            onPlay = { onItemClick(item) },
                        )
                    }
                }

                item {
                    Text(
                        text = "Shares",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                items(state.shares, key = { it.id }) { share ->
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
                                OutlinedButton(onClick = { viewModel.removeShare(share.id) }) {
                                    Text("Remove")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun BeamNetworkShareScreen(
    contentPadding: PaddingValues,
    shareId: String,
    onBack: () -> Unit,
    onItemClick: (NetworkVideoItem) -> Unit,
    onShareRemoved: () -> Unit,
    viewModel: BeamNetworkShareViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(shareId) {
        viewModel.load(shareId)
    }

    BeamScaffoldBody(contentPadding = contentPadding) {
        item {
            BeamScreenHeader(
                title = state.share?.displayName ?: state.share?.shareName ?: "Share",
                body = "Browse videos found in this network share.",
            )
        }

        when {
            state.isLoading -> {
                item { LoadingCard("Loading share contents...") }
            }
            state.error != null -> {
                item {
                    ErrorCard(
                        title = "Failed to load share contents.",
                        body = state.error?.localizedMessage ?: "Unknown error",
                        onRetry = { viewModel.load(shareId) },
                    )
                }
            }
            state.videos.isEmpty() -> {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                text = "No videos found in this share.",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(
                                    onClick = { viewModel.scanShare(shareId) },
                                    enabled = !state.isScanning,
                                ) {
                                    if (state.isScanning) {
                                        Text("Scanning...")
                                    } else {
                                        Text("Scan Share")
                                    }
                                }
                                OutlinedButton(onClick = onBack) {
                                    Text("Back")
                                }
                            }
                        }
                    }
                }
            }
            else -> {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = { viewModel.scanShare(shareId) },
                            enabled = !state.isScanning,
                        ) {
                            Text(if (state.isScanning) "Scanning..." else "Scan Share")
                        }
                        OutlinedButton(
                            onClick = {
                                viewModel.removeShare(shareId)
                                onShareRemoved()
                            }
                        ) {
                            Text("Remove Share")
                        }
                        OutlinedButton(onClick = onBack) {
                            Text("Back")
                        }
                    }
                }
                items(state.videos, key = { it.networkVideoId }) { item ->
                    BeamNetworkVideoCard(
                        item = item,
                        onPlay = { onItemClick(item) },
                    )
                }
            }
        }
    }
}

@Composable
private fun BeamNetworkVideoCard(
    item: NetworkVideoItem,
    onPlay: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.86f)),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(999.dp),
            ) {
                Text(
                    text = "Network",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    color = MaterialTheme.colorScheme.secondary,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Text(
                text = item.name,
                style = MaterialTheme.typography.titleLarge,
            )
            val metadata = remember(item) {
                listOfNotNull(
                    item.releaseYear?.toString(),
                    item.seasonNumber?.let { season ->
                        item.episodeNumber?.let { episode -> "S${season}E${episode}" } ?: "Season $season"
                    },
                    item.filePath.substringAfterLast('/').takeIf { it.isNotBlank() },
                ).joinToString(" • ")
            }
            if (metadata.isNotBlank()) {
                Text(
                    text = metadata,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onPlay) {
                    Text("Play")
                }
                OutlinedButton(onClick = onPlay) {
                    Text("Resume")
                }
            }
        }
    }
}

@Composable
internal fun LoadingCard(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.5.dp,
            )
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
internal fun ErrorCard(
    title: String,
    body: String,
    onRetry: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onRetry) {
                Text("Retry")
            }
        }
    }
}
