package dev.jdtech.jellyfin.presentation.film.components

import android.app.DownloadManager
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.utils.ActiveDownloadEntry

@Composable
fun ActiveDownloadRow(entry: ActiveDownloadEntry, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val total = entry.totalBytes
    val fraction =
        when {
            total != null && total > 0L ->
                (entry.bytesDownloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f)
            entry.progress in 1..100 -> entry.progress / 100f
            else -> 0f
        }

    val sizeText =
        if (total != null && total > 0L) {
            "${Formatter.formatShortFileSize(context, entry.bytesDownloaded)} / " +
                Formatter.formatShortFileSize(context, total)
        } else {
            Formatter.formatShortFileSize(context, entry.bytesDownloaded)
        }
    val speedText =
        entry.downloadSpeedBytesPerSec
            ?.takeIf { it > 0L && entry.status == DownloadManager.STATUS_RUNNING }
            ?.let { "${Formatter.formatShortFileSize(context, it)}/s" }
    val speed = entry.downloadSpeedBytesPerSec
    val remainingText =
        if (entry.status == DownloadManager.STATUS_RUNNING &&
            total != null &&
            total > entry.bytesDownloaded &&
            speed != null &&
            speed > 0L
        ) {
            val remainingBytes = total - entry.bytesDownloaded
            val secs = remainingBytes / speed
            "~${formatSeconds(secs)} left"
        } else null
    val statusText = statusLabel(entry)

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(PaddingValues(horizontal = 12.dp, vertical = 10.dp)),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = entry.itemName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
            )
            val parts = listOfNotNull(statusText, sizeText, speedText, remainingText)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = parts.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            entry.errorMessage?.takeIf { it.isNotBlank() }?.let { msg ->
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun statusLabel(entry: ActiveDownloadEntry): String? =
    when (entry.status) {
        DownloadManager.STATUS_PENDING -> "Pending"
        DownloadManager.STATUS_PAUSED -> "Paused"
        DownloadManager.STATUS_FAILED -> "Failed"
        DownloadManager.STATUS_RUNNING -> null
        DownloadManager.STATUS_SUCCESSFUL -> "Done"
        else -> null
    }

private fun formatSeconds(totalSeconds: Long): String {
    if (totalSeconds <= 0L) return "0s"
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m ${seconds}s"
        else -> "${seconds}s"
    }
}
