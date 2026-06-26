package dev.jdtech.jellyfin.presentation.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.core.llm.AICoreStatus
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.settings.domain.llm.DownloadState
import dev.jdtech.jellyfin.settings.domain.llm.LlmDownloadManager
import dev.jdtech.jellyfin.settings.domain.llm.ModelState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/*
 * Shared on-device LLM management cards rendered on both the Beam (phone) and
 * TV settings hubs. They take their managers/state as plain parameters (the
 * Hilt `@EntryPoint` accessor that fetches them stays in each shell), so this
 * file is dependency-light: only `:core` (AICoreStatus/LlmModelManager) and
 * `:settings` (the download manager + preference state). Despite the `Beam`
 * prefix the cards are flavor-agnostic.
 */

/**
 * LiteRT Gemma (fallback) management card. Renders four states from the
 * download manager:
 *  - Idle        → "model not downloaded" + a download affordance
 *  - Downloading → a determinate progress bar
 *  - Ready       → an active-backend line, an enable toggle once on disk
 *                  for the feature, and a Delete-model button to free disk
 *  - Error       → message + Retry button
 *
 * The toggle only appears once the model is on disk — flipping it when the
 * model isn't present would be meaningless. Disabling the toggle doesn't
 * delete the file, so it's cheap to re-enable later.
 */
@Composable
fun BeamGemmaManagementCard(
    downloadManager: LlmDownloadManager,
    downloadScope: CoroutineScope,
    downloadState: DownloadState,
    modelState: ModelState,
    appPreferences: AppPreferences,
) {
    var gemmaEnabled by rememberSaveable {
        mutableStateOf(appPreferences.getValue(appPreferences.voiceAssistantGemmaEnabled))
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("LiteRT Gemma (fallback)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Universal fallback when AICore is unavailable. Downloads a 2.6 GB " +
                    "Gemma 4 E2B model and runs it on GPU (the model ships without NPU " +
                    "subgraphs, so LiteRT can't use the Tensor NPU on Pixel — AICore above does).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (val ds = downloadState) {
                is DownloadState.Idle -> GemmaIdleState(
                    storedBackend = appPreferences.getValue(appPreferences.voiceAssistantGemmaBackend),
                    onDownload = { downloadScope.launch { downloadManager.downloadModel() } },
                )
                is DownloadState.Downloading -> GemmaDownloadingState(progress = ds.progress)
                is DownloadState.Ready -> GemmaReadyState(
                    modelState = modelState,
                    storedBackend = appPreferences.getValue(appPreferences.voiceAssistantGemmaBackend),
                    enabled = gemmaEnabled,
                    onToggleEnabled = { new ->
                        gemmaEnabled = new
                        appPreferences.setValue(appPreferences.voiceAssistantGemmaEnabled, new)
                    },
                    onDelete = {
                        downloadManager.deleteModel()
                        gemmaEnabled = false
                        appPreferences.setValue(appPreferences.voiceAssistantGemmaEnabled, false)
                    },
                )
                is DownloadState.Error -> GemmaErrorState(
                    message = ds.message,
                    onRetry = { downloadScope.launch { downloadManager.downloadModel() } },
                )
            }
        }
    }
}

@Composable
private fun GemmaIdleState(storedBackend: String, onDownload: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (storedBackend.isBlank() || storedBackend == "Requires Model") "Model not downloaded"
                   else "Previously ran on $storedBackend",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        // Icon-only download affordance. The description line above already
        // tells the user what's needed; a button that repeats "Download model"
        // in multiple rows of text next to that line reads as visual noise.
        FilledTonalIconButton(onClick = onDownload) {
            Icon(Icons.Rounded.Download, contentDescription = "Download model")
        }
    }
}

@Composable
private fun GemmaDownloadingState(progress: Float) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Downloading ${(progress * 100).toInt()}%",
            style = MaterialTheme.typography.bodyMedium,
        )
        // Explicit, always-visible progress bar so a user staring at the
        // screen knows work is happening and roughly how much is left.
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun GemmaReadyState(
    modelState: ModelState,
    storedBackend: String,
    enabled: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val backendLabel = when (modelState) {
        is ModelState.Initializing -> "Initialising engine…"
        is ModelState.Ready -> "Active backend: ${modelState.backendName}"
        is ModelState.Error -> "Error: ${modelState.message}"
        is ModelState.Idle -> {
            if (storedBackend.isNotBlank() && storedBackend != "Requires Model") {
                "Last active on $storedBackend"
            } else {
                "Model downloaded — initialising…"
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = backendLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = enabled, onCheckedChange = onToggleEnabled)
            OutlinedIconButton(onClick = onDelete) {
                Icon(Icons.Rounded.Delete, contentDescription = "Delete model")
            }
        }
    }
}

@Composable
private fun GemmaErrorState(message: String, onRetry: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Rounded.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
        )
        Text(
            text = "Download failed: $message",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
        FilledIconButton(onClick = onRetry) {
            Icon(Icons.Rounded.Refresh, contentDescription = "Retry")
        }
    }
}

/**
 * Gemini Nano via AICore — the fast path on Pixel 10 Pro and other devices
 * that ship Google's on-device GenAI service. When the device reports
 * `FeatureStatus.UNAVAILABLE` we hide the card entirely instead of showing
 * a greyed-out row, so older hardware doesn't get cluttered UI.
 *
 * Feature-model download is managed end-to-end by Google Play Services — our
 * UI only surfaces its progress and lets the user opt in. There is no
 * "delete model" action because we don't own the file; AICore reclaims disk
 * when the user clears storage for Google Play Services.
 */
@Composable
fun BeamAiCoreManagementCard(
    status: AICoreStatus,
    onDownload: () -> Unit,
    onReprobe: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Gemini Nano (AICore)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Runs Google's on-device Gemini Nano through AICore — the fastest path on " +
                    "Pixel and other AICore-capable devices.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (status) {
                is AICoreStatus.Downloadable -> Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Feature model not downloaded yet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    FilledTonalIconButton(onClick = onDownload) {
                        Icon(Icons.Rounded.Download, contentDescription = "Download AICore model")
                    }
                }
                is AICoreStatus.Downloading -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val percent = (status.progress * 100).toInt().coerceIn(0, 100)
                    val label = when {
                        status.totalBytes > 0L -> "Downloading $percent% (${formatBytes(status.bytesDownloaded)} / ${formatBytes(status.totalBytes)})"
                        status.bytesDownloaded > 0L -> "Downloading ${formatBytes(status.bytesDownloaded)} so far…"
                        else -> "Downloading…"
                    }
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                    // Show an indeterminate bar when we don't yet know the total, else a
                    // determinate one that fills as bytes arrive.
                    if (status.totalBytes > 0L) {
                        LinearProgressIndicator(
                            progress = { status.progress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
                is AICoreStatus.Warming -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Warming up the engine…", style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                is AICoreStatus.Ready -> Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Green-tinted check conveys the good state at a glance —
                    // text alone is easy to miss when a user is scanning a
                    // dense settings page for the bit that matters.
                    Icon(
                        Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "Active — Gemini Nano running on device hardware.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is AICoreStatus.Error -> Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Rounded.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        text = "Error: ${status.message}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    FilledIconButton(onClick = onReprobe) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Retry")
                    }
                }
                // Previously hid the card when Unknown / Unavailable. That
                // made it impossible to tell whether "no AICore card" meant
                // "probe hasn't run yet" or "device genuinely unsupported" —
                // so render both states with explicit copy + a manual retry.
                is AICoreStatus.Unknown -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Probing AICore availability…", style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                is AICoreStatus.Unavailable -> Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Unavailable on this device — ${status.reason}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedIconButton(onClick = onReprobe) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Retry probe")
                    }
                }
            }
        }
    }
}

/**
 * Compact bytes formatter — we want "1.3 GB" not "1374389840 B" in the
 * download progress row. Assumes base-1024 units which is how AICore
 * reports sizes internally.
 */
private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.0f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    val gb = mb / 1024.0
    return "%.2f GB".format(gb)
}
