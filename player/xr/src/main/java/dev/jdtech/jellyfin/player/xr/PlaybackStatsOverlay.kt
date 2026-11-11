package dev.jdtech.jellyfin.player.xr

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import dev.jdtech.jellyfin.player.local.presentation.PlaybackStatsSnapshot
import dev.jdtech.jellyfin.player.local.presentation.PlayerViewModel
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * Polls [PlayerViewModel.statsCollector] at 1 Hz only while [enabled] is `true`.
 * Returns `null` and performs zero work when [enabled] is `false`.
 */
@Composable
fun rememberPlaybackStatsSnapshot(
    enabled: Boolean,
    player: Player,
    viewModel: PlayerViewModel,
    displayRefreshRateHz: Float = 0f,
    audioModeSummaryOverride: String? = null,
    subtitleEngineSummaryOverride: String? = null,
): PlaybackStatsSnapshot? {
    var snapshot by remember { mutableStateOf<PlaybackStatsSnapshot?>(null) }

    LaunchedEffect(
        enabled,
        player,
        viewModel,
        displayRefreshRateHz,
        audioModeSummaryOverride,
        subtitleEngineSummaryOverride,
    ) {
        if (!enabled) {
            snapshot = null
            return@LaunchedEffect
        }
        while (true) {
            snapshot = viewModel.statsCollector.buildSnapshot(
                player = player,
                viewModel = viewModel,
                displayRefreshRateHz = displayRefreshRateHz,
                audioModeSummaryOverride = audioModeSummaryOverride,
                subtitleEngineSummaryOverride = subtitleEngineSummaryOverride,
            )
            delay(1_000L)
        }
    }

    return if (enabled) snapshot else null
}

/**
 * Shared real-time playback diagnostics ("Stats for Nerds") HUD overlay used across XR, Beam, and TV.
 */
@Composable
fun PlaybackStatsOverlay(
    snapshot: PlaybackStatsSnapshot,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .widthIn(min = 320.dp, max = 460.dp)
            .background(Color(0xD90E131F), RoundedCornerShape(16.dp))
            .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Stats for Nerds",
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
            )
            TextButton(onClick = onClose) {
                Text(
                    text = "Close",
                    color = Color(0xFF90CAF9),
                    fontSize = 12.sp,
                )
            }
        }

        val deliveryReasons = if (snapshot.transcodeReasons.isNotEmpty()) {
            " (${snapshot.transcodeReasons.joinToString(", ")})"
        } else {
            ""
        }
        StatsRow(
            label = "Delivery",
            value = "${snapshot.deliveryMethod}$deliveryReasons · ${snapshot.containerFormat}",
        )

        val fpsStr = if (snapshot.videoFrameRate > 0f) {
            String.format(Locale.US, "%.3f fps", snapshot.videoFrameRate)
        } else {
            "Unknown fps"
        }
        val hzStr = if (snapshot.displayRefreshRateHz > 0f) {
            String.format(Locale.US, " @ %.0f Hz", snapshot.displayRefreshRateHz)
        } else {
            ""
        }
        StatsRow(
            label = "Video",
            value = "${snapshot.videoCodec} · ${snapshot.videoResolution} · $fpsStr$hzStr",
        )

        val hwBadge = if (snapshot.isHardwareVideoDecoder) "[HW]" else "[SW]"
        StatsRow(
            label = "V-Decoder",
            value = "${snapshot.videoDecoderName} $hwBadge",
        )
        StatsRow(
            label = "Color/HDR",
            value = snapshot.colorTransferSummary,
        )
        StatsRow(
            label = "Audio",
            value = "${snapshot.audioCodec} · ${snapshot.audioDecoderName}",
        )
        StatsRow(
            label = "Audio Mode",
            value = snapshot.audioModeSummary,
        )
        StatsRow(
            label = "Subtitles",
            value = snapshot.subtitleEngineSummary,
        )
        StatsRow(
            label = "Frames",
            value = "Rendered ${snapshot.renderedVideoFrames} · Dropped ${snapshot.droppedVideoFrames} · Underruns ${snapshot.audioUnderruns}",
        )
        val bufferSec = String.format(Locale.US, "%.1fs", snapshot.bufferedDurationMs / 1_000f)
        val bwStr = if (snapshot.estimatedBandwidthMbps > 0f) {
            String.format(Locale.US, " · %.1f Mbps", snapshot.estimatedBandwidthMbps)
        } else {
            ""
        }
        StatsRow(
            label = "Buffer/BW",
            value = "$bufferSec$bwStr",
        )
        StatsRow(
            label = "A/V Sync",
            value = "Sub ${formatSignedMs(snapshot.subtitleOffsetMs)} · Audio ${formatSignedMs(snapshot.audioOffsetMs)}",
        )
        Spacer(modifier = Modifier.height(2.dp))
    }
}

@Composable
private fun StatsRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = "$label:",
            color = Color.White.copy(alpha = 0.65f),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.widthIn(min = 82.dp),
        )
        Text(
            text = value,
            color = Color.White,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

internal fun formatSignedMs(offsetMs: Long): String =
    when {
        offsetMs > 0L -> "+${offsetMs}ms"
        else -> "${offsetMs}ms"
    }
