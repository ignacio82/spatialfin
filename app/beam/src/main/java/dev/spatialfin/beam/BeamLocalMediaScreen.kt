package dev.spatialfin.beam

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.models.LocalVideoItem
import dev.jdtech.jellyfin.player.beam.BeamPlayerActivity
import dev.jdtech.jellyfin.repository.LocalMediaRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope

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
                        )
                    )
                }
                .onFailure { error ->
                    _state.emit(BeamLocalMediaState(isLoading = false, error = error))
                }
        }
    }
}

@Composable
fun BeamLocalMediaScreen(
    contentPadding: PaddingValues,
    onResetOnboarding: () -> Unit,
    viewModel: BeamLocalMediaViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    var hasPermission by remember { mutableStateOf(hasBeamLocalVideoAccess(context)) }

    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            hasPermission = hasBeamLocalVideoAccess(context)
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
                    text = "Browse videos stored on the Beam device and launch them in the new Beam player.",
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
                                Button(onClick = { permissionLauncher.launch(beamLocalVideoPermissions()) }) {
                                    Text("Grant Access")
                                }
                                OutlinedButton(onClick = onResetOnboarding) {
                                    Text("Reset Onboarding")
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
                                OutlinedButton(onClick = onResetOnboarding) {
                                    Text("Reset Onboarding")
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
                            OutlinedButton(onClick = onResetOnboarding) {
                                Text("Reset Onboarding")
                            }
                        }
                    }
                }
            }
            else -> {
                items(state.items, key = { it.mediaStoreId }) { item ->
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
                                Button(
                                    onClick = {
                                        context.startActivity(
                                            BeamPlayerActivity.createIntentForLocalMedia(
                                                context = context,
                                                mediaStoreId = item.mediaStoreId,
                                                startFromBeginning = false,
                                            )
                                        )
                                    }
                                ) {
                                    Text("Play")
                                }
                                OutlinedButton(
                                    onClick = {
                                        context.startActivity(
                                            BeamPlayerActivity.createIntentForLocalMedia(
                                                context = context,
                                                mediaStoreId = item.mediaStoreId,
                                                startFromBeginning = true,
                                            )
                                        )
                                    }
                                ) {
                                    Text("Play From Start")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun beamLocalVideoPermissions(): Array<String> {
    return when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
            arrayOf(
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            )
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
            arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
}

private fun hasBeamLocalVideoAccess(context: Context): Boolean {
    return when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
                ) == PackageManager.PERMISSION_GRANTED
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO) ==
                PackageManager.PERMISSION_GRANTED
        else ->
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
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
