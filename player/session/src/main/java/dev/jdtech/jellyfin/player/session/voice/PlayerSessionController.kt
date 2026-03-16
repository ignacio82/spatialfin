package dev.jdtech.jellyfin.player.session.voice

import androidx.media3.common.C
import androidx.media3.common.Player
import dev.jdtech.jellyfin.models.SpatialFinEpisode
import dev.jdtech.jellyfin.models.SpatialFinItem
import dev.jdtech.jellyfin.models.SpatialFinMovie
import dev.jdtech.jellyfin.models.SpatialFinSeason
import dev.jdtech.jellyfin.models.SpatialFinShow
import dev.jdtech.jellyfin.models.SyncPlayGroup
import dev.jdtech.jellyfin.player.local.presentation.PlayerViewModel
import timber.log.Timber
import java.util.UUID
import kotlin.math.min

class PlayerSessionController(
    private val viewModel: PlayerViewModel,
    private val player: Player,
    private val onControlsVisibilityChange: (Boolean) -> Unit,
    private val onNavigateBack: () -> Unit,
    private val onShowVoiceSearch: (query: String, results: List<SpatialFinItem>, error: String?) -> Unit,
    private val onShowSyncPlayDialog: () -> Unit,
    private val onGoHome: () -> Boolean,
    private val onCloseApp: () -> Unit,
    private val onLaunchSearchResult: (SpatialFinItem) -> Unit,
    private val onSearchQuery: suspend (String) -> List<SpatialFinItem>,
    private val getAvailableSyncPlayGroups: () -> List<SyncPlayGroup>,
) {
    private var pendingSelection: PendingSelection? = null

    suspend fun dispatch(action: XrPlayerAction): String {
        Timber.d("VOICE: Dispatching %s", action)
        return when (action) {
            is XrPlayerAction.Play -> {
                player.play()
                "Playing"
            }
            is XrPlayerAction.Pause -> {
                player.pause()
                "Paused"
            }
            is XrPlayerAction.TogglePlayPause -> {
                if (player.isPlaying) {
                    player.pause()
                    "Paused"
                } else {
                    player.play()
                    "Playing"
                }
            }
            is XrPlayerAction.SeekForward -> {
                player.seekTo(player.currentPosition + action.seconds * 1_000L)
                "Skipped forward ${action.seconds}s"
            }
            is XrPlayerAction.SeekBackward -> {
                player.seekTo((player.currentPosition - action.seconds * 1_000L).coerceAtLeast(0))
                "Rewound ${action.seconds}s"
            }
            is XrPlayerAction.SeekTo -> {
                player.seekTo(action.positionSeconds * 1_000L)
                "Seeked to ${action.positionSeconds}s"
            }
            is XrPlayerAction.SkipIntro -> {
                val segment = viewModel.uiState.value.currentSegment
                if (segment != null) {
                    viewModel.skipSegment(segment)
                    "Skipping intro"
                } else {
                    "No skippable intro right now"
                }
            }
            is XrPlayerAction.SkipOutro -> {
                val segment = viewModel.uiState.value.currentSegment
                if (segment != null) {
                    viewModel.skipSegment(segment)
                    "Skipping outro"
                } else {
                    "No skippable outro right now"
                }
            }
            is XrPlayerAction.NextEpisode -> {
                viewModel.skipToNextItem()
                "Next item"
            }
            is XrPlayerAction.PreviousEpisode -> {
                viewModel.skipToPreviousItem()
                "Previous item"
            }
            is XrPlayerAction.SetSpeed -> {
                viewModel.selectSpeed(action.speed)
                "${action.speed}x speed"
            }
            is XrPlayerAction.SetQuality -> updateQuality(action.maxBitrate)
            is XrPlayerAction.SelectAudioTrack -> {
                dispatchTrackSelection(
                    trackType = C.TRACK_TYPE_AUDIO,
                    language = action.language,
                    directIndex = action.index,
                    successPrefix = "Audio",
                    failureText = "Audio track not found",
                )
            }
            is XrPlayerAction.SelectSubtitleTrack -> {
                dispatchTrackSelection(
                    trackType = C.TRACK_TYPE_TEXT,
                    language = action.language,
                    directIndex = action.index,
                    successPrefix = "Subtitles",
                    failureText = "Subtitle track not found",
                )
            }
            is XrPlayerAction.DisableSubtitles -> {
                viewModel.switchToTrack(C.TRACK_TYPE_TEXT, -1)
                "Subtitles off"
            }
            is XrPlayerAction.Search -> handleSearch(action.query, action.autoPlay)
            is XrPlayerAction.SelectOption -> handleSelection(action.index)
            is XrPlayerAction.OpenSyncPlay -> {
                pendingSelection = null
                viewModel.refreshSyncPlayGroups()
                onShowSyncPlayDialog()
                "Opened SyncPlay"
            }
            is XrPlayerAction.CreateSyncPlayGroup -> {
                pendingSelection = null
                onShowSyncPlayDialog()
                viewModel.createSyncPlayGroup()
                "Creating SyncPlay group"
            }
            is XrPlayerAction.JoinSyncPlayGroup -> handleJoinSyncPlay(action.groupName, action.selectionIndex)
            is XrPlayerAction.LeaveSyncPlayGroup -> {
                pendingSelection = null
                viewModel.leaveSyncPlayGroup()
                "Left SyncPlay group"
            }
            is XrPlayerAction.RefreshSyncPlay -> {
                pendingSelection = null
                viewModel.refreshSyncPlayGroups()
                onShowSyncPlayDialog()
                "Refreshing SyncPlay groups"
            }
            is XrPlayerAction.AdjustVolume -> {
                if (action.percentage != null) {
                    player.volume = action.percentage.coerceIn(0f, 1f)
                    "Volume: ${(player.volume * 100).toInt()}%"
                } else if (action.delta != null) {
                    player.volume = (player.volume + action.delta).coerceIn(0f, 1f)
                    "Volume: ${(player.volume * 100).toInt()}%"
                } else {
                    "Volume unchanged"
                }
            }
            is XrPlayerAction.GoHome -> {
                if (onGoHome()) "Returning to home" else "Home unavailable"
            }
            is XrPlayerAction.CloseApp -> {
                onCloseApp()
                "Closing SpatialFin"
            }
            is XrPlayerAction.GoBack -> {
                onNavigateBack()
                "Going back"
            }
            is XrPlayerAction.ShowControls -> {
                onControlsVisibilityChange(true)
                "Controls shown"
            }
            is XrPlayerAction.HideControls -> {
                onControlsVisibilityChange(false)
                "Controls hidden"
            }
            is XrPlayerAction.ChatQuery -> {
                "Thinking..." // Handled upstream, should not reach here
            }
            is XrPlayerAction.Unrecognized -> {
                "Sorry, I didn't understand: ${action.transcript}"
            }
        }
    }

    fun clearPendingSelection() {
        pendingSelection = null
    }

    private suspend fun handleSearch(query: String, autoPlay: Boolean): String {
        val results = runCatching { onSearchQuery(query) }
            .onFailure { Timber.w(it, "VOICE: search failed for %s", query) }
            .getOrElse {
                pendingSelection = null
                onShowVoiceSearch(query, emptyList(), "Search failed")
                return "Search failed"
            }

        val playableResults = results.filter(::canPlayFromVoiceSearch)
        onShowVoiceSearch(query, results, null)

        if (results.isEmpty()) {
            pendingSelection = null
            return "No results found for $query"
        }

        if (autoPlay && playableResults.size == 1) {
            pendingSelection = null
            onLaunchSearchResult(playableResults.first())
            return "Playing ${playableResults.first().name}"
        }

        pendingSelection =
            playableResults.takeIf { it.isNotEmpty() }?.let {
                PendingSelection.SearchResults(query = query, results = it)
            }

        val playableCount = playableResults.size
        return when {
            playableCount > 1 -> {
                "I found $playableCount playable results for $query. Say play the first one or pick from the dialog."
            }
            playableCount == 1 && autoPlay -> {
                "Opening the result for $query"
            }
            playableCount == 1 -> {
                "Found 1 playable result for $query"
            }
            else -> {
                "Found ${results.size} results for $query"
            }
        }
    }

    private fun handleJoinSyncPlay(groupName: String?, selectionIndex: Int?): String {
        val groups = getAvailableSyncPlayGroups()

        if (groups.isEmpty()) {
            pendingSelection = null
            viewModel.refreshSyncPlayGroups()
            onShowSyncPlayDialog()
            return "No SyncPlay groups are available right now"
        }

        val selectedGroup = when {
            selectionIndex != null -> groups.getOrNull(selectionIndex)
            !groupName.isNullOrBlank() -> findGroupByName(groups, groupName)
            groups.size == 1 -> groups.first()
            else -> null
        }

        if (selectedGroup != null) {
            pendingSelection = null
            onShowSyncPlayDialog()
            viewModel.joinSyncPlayGroup(selectedGroup.id)
            return "Joining ${selectedGroup.name}"
        }

        if (!groupName.isNullOrBlank()) {
            return "I couldn't find a SyncPlay group named $groupName"
        }

        pendingSelection = PendingSelection.SyncPlayGroups(groups)
        onShowSyncPlayDialog()
        return "I found ${groups.size} SyncPlay groups. Say join the first one or choose from the dialog."
    }

    private fun handleSelection(index: Int): String {
        val pending = pendingSelection ?: return "There's nothing to choose from right now"
        return when (pending) {
            is PendingSelection.SearchResults -> {
                val item = pending.results.getOrNull(index)
                    ?: return invalidSelectionFeedback(index, pending.results.size)
                pendingSelection = null
                onLaunchSearchResult(item)
                "Playing ${item.name}"
            }
            is PendingSelection.SyncPlayGroups -> {
                val group = pending.groups.getOrNull(index)
                    ?: return invalidSelectionFeedback(index, pending.groups.size)
                pendingSelection = null
                onShowSyncPlayDialog()
                viewModel.joinSyncPlayGroup(group.id)
                "Joining ${group.name}"
            }
        }
    }

    private fun updateQuality(maxBitrate: Long): String {
        val itemIdString = viewModel.uiState.value.currentItemId
        val itemKind = viewModel.uiState.value.currentItemKind
        if (itemIdString != null && itemKind != null) {
            val label = when (maxBitrate) {
                0L -> "Auto"
                120_000_000L -> "120 Mbps"
                80_000_000L -> "80 Mbps"
                60_000_000L -> "60 Mbps"
                40_000_000L -> "40 Mbps"
                30_000_000L -> "30 Mbps"
                20_000_000L -> "20 Mbps"
                15_000_000L -> "15 Mbps"
                10_000_000L -> "10 Mbps"
                8_000_000L -> "8 Mbps"
                6_000_000L -> "6 Mbps"
                5_000_000L -> "5 Mbps"
                4_000_000L -> "4 Mbps"
                3_000_000L -> "3 Mbps"
                2_000_000L -> "2 Mbps"
                1_500_000L -> "1.5 Mbps"
                1_000_000L -> "1 Mbps"
                720_000L -> "720 Kbps"
                480_000L -> "480 Kbps"
                else -> "${maxBitrate / 1_000_000} Mbps"
            }
            viewModel.changeQuality(UUID.fromString(itemIdString), itemKind, maxBitrate)
            return "Quality set to $label"
        }

        return "Cannot change quality right now"
    }

    private fun dispatchTrackSelection(
        trackType: @C.TrackType Int,
        language: String?,
        directIndex: Int?,
        successPrefix: String,
        failureText: String,
    ): String {
        val index = resolveTrackIndex(trackType = trackType, language = language, directIndex = directIndex)
            ?: return failureText
        viewModel.switchToTrack(trackType, index)
        return "$successPrefix: ${language ?: "track ${index + 1}"}"
    }

    private fun resolveTrackIndex(
        trackType: @C.TrackType Int,
        language: String?,
        directIndex: Int?,
    ): Int? {
        val groups = player.currentTracks.groups.filter { it.type == trackType && it.isSupported }
        if (directIndex != null) return directIndex.takeIf { it in groups.indices }
        if (language == null) {
            val selectedIndex = groups.indexOfFirst(::groupIsSelected)
            return when (trackType) {
                C.TRACK_TYPE_TEXT -> groups.indices.firstOrNull()
                C.TRACK_TYPE_AUDIO -> {
                    if (groups.isEmpty()) null
                    else if (selectedIndex >= 0 && groups.size > 1) (selectedIndex + 1) % groups.size
                    else groups.indices.firstOrNull()
                }
                else -> null
            }
        }

        val normalized = language.lowercase()
        return groups.indexOfFirst { group ->
            val format = group.getTrackFormat(0)
            val langTag = format.language?.lowercase().orEmpty()
            val label = format.label?.lowercase().orEmpty()
            langTag.startsWith(normalized) ||
                label.contains(normalized) ||
                languageAliases(normalized).any { alias ->
                    langTag.startsWith(alias) || label.contains(alias)
                }
        }.takeIf { it >= 0 }
    }

    private fun findGroupByName(groups: List<SyncPlayGroup>, query: String): SyncPlayGroup? {
        val normalizedQuery = query.lowercase()
        return groups.minByOrNull { group ->
            val name = group.name.lowercase()
            when {
                name == normalizedQuery -> 0
                name.contains(normalizedQuery) -> 1
                normalizedQuery.contains(name) -> 2
                else -> Int.MAX_VALUE
            }
        }?.takeIf { candidate ->
            val name = candidate.name.lowercase()
            name == normalizedQuery || name.contains(normalizedQuery) || normalizedQuery.contains(name)
        }
    }

    private fun invalidSelectionFeedback(index: Int, size: Int): String {
        val maxNumber = min(size, 9)
        return "I only have $size options right now. Try a number from 1 to $maxNumber."
    }

    private fun languageAliases(input: String): List<String> =
        when (input) {
            "japanese" -> listOf("ja", "jpn")
            "english" -> listOf("en", "eng")
            "spanish" -> listOf("es", "spa")
            "french" -> listOf("fr", "fra", "fre")
            "german" -> listOf("de", "deu", "ger")
            "chinese" -> listOf("zh", "zho", "chi", "cmn")
            "korean" -> listOf("ko", "kor")
            "portuguese" -> listOf("pt", "por")
            "italian" -> listOf("it", "ita")
            "russian" -> listOf("ru", "rus")
            else -> listOf(input.take(2))
        }

    private fun groupIsSelected(group: androidx.media3.common.Tracks.Group): Boolean {
        return (0 until group.length).any(group::isTrackSelected)
    }

    private fun canPlayFromVoiceSearch(item: SpatialFinItem): Boolean {
        return item.canPlay &&
            (
                item is SpatialFinMovie ||
                    item is SpatialFinEpisode ||
                    item is SpatialFinSeason ||
                    item is SpatialFinShow
                )
    }

    private sealed interface PendingSelection {
        data class SearchResults(val query: String, val results: List<SpatialFinItem>) : PendingSelection

        data class SyncPlayGroups(val groups: List<SyncPlayGroup>) : PendingSelection
    }
}
