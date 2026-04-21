package dev.jdtech.jellyfin.player.xr

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList
import dev.jdtech.jellyfin.player.local.presentation.PlayerViewModel
import java.util.UUID

/**
 * Rolling subtitle context the assistant reads: a live in-memory buffer of recent cues plus
 * a disk-cache fallback that covers gaps before a subtitle track was selected or after a seek.
 *
 * Libass rendering state (bitmaps, frame versions, overlay attachment) stays in
 * `SpatialPlayerScreen` because it is tightly bound to entity lifecycle effects.
 */
@Stable
internal class PlayerSubtitleContext(
    val recentSubtitles: SnapshotStateList<Pair<Long, String>>,
    private val assistantHistory: State<List<Pair<Long, String>>>,
    val cacheFallback: (fromMs: Long, toMs: Long) -> List<Pair<Long, String>>,
) {
    /** Prefer the ViewModel's disk-backed history; fall back to the live in-memory buffer. */
    val assistantLines: List<Pair<Long, String>>
        get() = assistantHistory.value.ifEmpty { recentSubtitles.toList() }

    /** Record a cue the player surfaced and trim entries older than a 20-minute rolling window. */
    fun recordCueLine(positionMs: Long, text: String) {
        recentSubtitles.add(positionMs to text)
        recentSubtitles.removeAll { positionMs - it.first > 1_200_000L }
    }
}

@Composable
internal fun rememberPlayerSubtitleContext(viewModel: PlayerViewModel): PlayerSubtitleContext {
    val recent = remember { mutableStateListOf<Pair<Long, String>>() }
    val assistantHistoryState = viewModel.assistantSubtitleHistory.collectAsState()
    val cacheFallback =
        remember(viewModel) {
            { fromMs: Long, toMs: Long ->
                val itemId = runCatching {
                    UUID.fromString(viewModel.uiState.value.currentItemId)
                }.getOrNull()
                if (itemId != null) {
                    viewModel.subtitleCacheManager.loadWindow(itemId, fromMs, toMs)
                } else {
                    emptyList()
                }
            }
        }
    return remember(recent, assistantHistoryState, cacheFallback) {
        PlayerSubtitleContext(
            recentSubtitles = recent,
            assistantHistory = assistantHistoryState,
            cacheFallback = cacheFallback,
        )
    }
}
