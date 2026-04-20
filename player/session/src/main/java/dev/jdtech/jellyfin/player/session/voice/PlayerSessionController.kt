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
import dev.jdtech.jellyfin.settings.presentation.enums.QualityOption
import timber.log.Timber
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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
    private val setPassthroughEnabled: (Boolean) -> Unit,
    private val getPassthroughEnabled: () -> Boolean,
    private val onAdjustScale: (delta: Float?, reset: Boolean) -> Unit = { _, _ -> },
    private val onAdjustDistance: (delta: Float?, reset: Boolean) -> Unit = { _, _ -> },
    private val onResetScreenPlacement: () -> Unit = {},
) {
    private var pendingSelection: PendingSelection? = null

    fun showRecommendations(query: String, results: List<SpatialFinItem>) {
        onShowVoiceSearch(query, results, null)
        val playableResults = results.filter(::canPlayFromVoiceSearch)
        pendingSelection =
            playableResults.takeIf { it.isNotEmpty() }?.let {
                PendingSelection.SearchResults(query = query, results = it)
            }
    }

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
                skipCurrentSegment(
                    defaultSegmentName = "intro",
                    preferredSegmentNames = arrayOf("intro", "recap", "previously on", "preview"),
                )
            }
            is XrPlayerAction.SkipOutro -> {
                skipCurrentSegment(
                    defaultSegmentName = "outro",
                    preferredSegmentNames = arrayOf("outro", "credits", "ending"),
                )
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
                val feedback = dispatchTrackSelection(
                    trackType = C.TRACK_TYPE_AUDIO,
                    language = action.language,
                    directIndex = action.index,
                    successPrefix = "Audio",
                    failureText = "Audio track not found",
                )
                if (action.secondaryAction != null) {
                    val secondaryFeedback = dispatch(action.secondaryAction)
                    "$feedback. $secondaryFeedback"
                } else {
                    feedback
                }
            }
            is XrPlayerAction.SelectSubtitleTrack -> {
                val feedback = dispatchTrackSelection(
                    trackType = C.TRACK_TYPE_TEXT,
                    language = action.language,
                    directIndex = action.index,
                    successPrefix = "Subtitles",
                    failureText = "Subtitle track not found",
                )
                if (action.secondaryAction != null) {
                    val secondaryFeedback = dispatch(action.secondaryAction)
                    "$feedback. $secondaryFeedback"
                } else {
                    feedback
                }
            }
            is XrPlayerAction.DisableSubtitles -> {
                viewModel.switchToTrack(C.TRACK_TYPE_TEXT, -1)
                "Subtitles off"
            }
            is XrPlayerAction.ResolveDisambiguation -> handleDisambiguation(action.query, action.originalTranscript)
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
            is XrPlayerAction.AdjustScale -> {
                onAdjustScale(action.delta, action.reset)
                if (action.reset) "Resetting screen size"
                else if (action.delta != null && action.delta > 0) "Making screen bigger"
                else "Making screen smaller"
            }
            is XrPlayerAction.AdjustDistance -> {
                onAdjustDistance(action.delta, action.reset)
                if (action.reset) "Resetting screen distance"
                else if (action.delta != null && action.delta > 0) "Moving screen further"
                else "Moving screen closer"
            }
            is XrPlayerAction.ResetScreenPlacement -> {
                onResetScreenPlacement()
                "Resetting screen to the default position"
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
            is XrPlayerAction.ReportCurrentTime -> currentTimeFeedback()
            is XrPlayerAction.ReportRemainingTime -> remainingTimeFeedback()
            is XrPlayerAction.ReportEndTime -> endTimeFeedback()
            is XrPlayerAction.ReportCurrentMedia -> currentMediaFeedback()
            is XrPlayerAction.ReportPassthroughStatus -> {
                if (getPassthroughEnabled()) "Passthrough is on" else "Passthrough is off"
            }
            is XrPlayerAction.SetPassthrough -> {
                setPassthroughEnabled(action.enabled)
                if (action.enabled) "Passthrough on" else "Passthrough off"
            }
            is XrPlayerAction.TogglePassthrough -> {
                val enabled = !getPassthroughEnabled()
                setPassthroughEnabled(enabled)
                if (enabled) "Passthrough on" else "Passthrough off"
            }
            is XrPlayerAction.ChatQuery -> {
                // ChatQuery is consumed upstream by SmartChatEngine before dispatch().
                // Reaching here means a routing regression — fail loudly instead of
                // papering over with a placeholder that the user would see as stuck.
                throw IllegalStateException("ChatQuery must be handled upstream and not dispatched here")
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

    private suspend fun handleDisambiguation(query: String, originalTranscript: String): String {
        val currentItemId = viewModel.uiState.value.currentItemId?.let(UUID::fromString)
        if (currentItemId == null) {
            return handleSearch(query, autoPlay = true)
        }

        val sources = viewModel.repository.getMediaSources(currentItemId)
        if (sources.size <= 1) {
            return "There's only one version of this media available."
        }

        val normalized = originalTranscript.lowercase()
        val is3D = normalized.contains("3d") || normalized.contains("spatial") || normalized.contains("stereo")
        val isSmaller = normalized.contains("smaller") || normalized.contains("small") || normalized.contains("low") || normalized.contains("flight")

        val selectedSource = when {
            is3D -> sources.firstOrNull { it.name.contains("3D", ignoreCase = true) || it.name.contains("SBS", ignoreCase = true) || it.name.contains("TAB", ignoreCase = true) }
            isSmaller -> sources.minByOrNull { it.size ?: Long.MAX_VALUE }
            else -> null
        }

        if (selectedSource != null) {
            val itemKind = viewModel.uiState.value.currentItemKind ?: return "Unable to switch versions right now"
            val index = sources.indexOf(selectedSource)
            viewModel.initializePlayer(
                itemId = currentItemId,
                itemKind = itemKind,
                startFromBeginning = false,
                mediaSourceIndex = index,
            )
            return "Switching to ${selectedSource.name}"
        }

        pendingSelection = PendingSelection.AmbiguousVersions(currentItemId, sources)
        return "I found ${sources.size} versions. Say play the first one or pick from the list."
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
            is PendingSelection.AmbiguousVersions -> {
                val source = pending.sources.getOrNull(index)
                    ?: return invalidSelectionFeedback(index, pending.sources.size)
                val itemKind = viewModel.uiState.value.currentItemKind ?: return "Unable to switch versions right now"
                pendingSelection = null
                viewModel.initializePlayer(
                    itemId = pending.itemId,
                    itemKind = itemKind,
                    startFromBeginning = false,
                    mediaSourceIndex = index,
                )
                "Switching to ${source.name}"
            }
        }
    }

    private fun currentTimeFeedback(): String {
        val now =
            DateTimeFormatter.ofPattern("h:mm a")
                .withZone(ZoneId.systemDefault())
                .format(Instant.now())
                .lowercase()
        return "It's $now"
    }

    private fun remainingTimeFeedback(): String {
        val durationMs = player.duration.takeIf { it > 0L } ?: return "I can't tell how much time is left yet"
        val remainingSeconds = ((durationMs - player.currentPosition).coerceAtLeast(0L) / 1_000L)
        return when {
            remainingSeconds < 5L -> "There's only a few seconds left"
            else -> "${formatDuration(remainingSeconds)} left"
        }
    }

    private fun endTimeFeedback(): String {
        val durationMs = player.duration.takeIf { it > 0L } ?: return "I can't tell when this ends yet"
        val remainingMs = (durationMs - player.currentPosition).coerceAtLeast(0L)
        val endTime =
            DateTimeFormatter.ofPattern("h:mm a")
                .withZone(ZoneId.systemDefault())
                .format(Instant.now().plusMillis(remainingMs))
                .lowercase()
        return "This should end around $endTime"
    }

    private fun currentMediaFeedback(): String {
        val state = viewModel.uiState.value
        val seasonEpisode =
            if (state.currentSeasonNumber != null && state.currentEpisodeNumber != null) {
                "Season ${state.currentSeasonNumber}, episode ${state.currentEpisodeNumber}"
            } else {
                null
            }
        val title = state.currentItemTitle.ifBlank { "this title" }
        val series = state.currentSeriesName?.takeIf { it.isNotBlank() }
        val pieces = listOfNotNull(series, seasonEpisode, title)
        return when {
            pieces.isNotEmpty() -> "You're watching ${pieces.joinToString(", ")}"
            else -> "You're watching $title"
        }
    }

    private fun skipCurrentSegment(
        defaultSegmentName: String,
        preferredSegmentNames: Array<String>,
    ): String {
        val segment =
            viewModel.skipActiveSegmentForVoice(*preferredSegmentNames)
                ?: run {
                    Timber.i(
                        "VOICE: no skippable %s segment right now requested=%s posMs=%d",
                        defaultSegmentName,
                        preferredSegmentNames.joinToString(),
                        player.currentPosition,
                    )
                    return "No skippable $defaultSegmentName right now"
                }
        val segmentName =
            when (segment.type.toString().lowercase()) {
                "recap", "previously_on", "previouslyon" -> "recap"
                "intro", "preview" -> "intro"
                "outro" -> "outro"
                "credits" -> "credits"
                else -> segment.type.toString().lowercase().replace('_', ' ')
            }
        Timber.i(
            "VOICE: skipped %s segment actualType=%s rangeMs=%d-%d",
            defaultSegmentName,
            segment.type,
            segment.startTicks,
            segment.endTicks,
        )
        return "Skipping $segmentName"
    }

    private fun formatDuration(totalSeconds: Long): String {
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return buildList {
            if (hours > 0) add("$hours hour${if (hours == 1L) "" else "s"}")
            if (minutes > 0) add("$minutes minute${if (minutes == 1L) "" else "s"}")
            if (hours == 0L && minutes == 0L && seconds > 0) {
                add("$seconds second${if (seconds == 1L) "" else "s"}")
            }
        }.joinToString(" and ")
    }

    private fun updateQuality(maxBitrate: Long): String {
        val itemIdString = viewModel.uiState.value.currentItemId
        val itemKind = viewModel.uiState.value.currentItemKind
        if (itemIdString != null && itemKind != null) {
            viewModel.changeQuality(UUID.fromString(itemIdString), itemKind, maxBitrate)
            return "Quality set to ${bitrateLabel(maxBitrate)}"
        }

        return "Cannot change quality right now"
    }

    private fun bitrateLabel(bps: Long): String = when (QualityOption.fromBps(bps)) {
        QualityOption.AUTO -> "Auto"
        QualityOption.UHD -> "4K"
        QualityOption.FHD -> "1080p"
        QualityOption.HD -> "720p"
        QualityOption.SD -> "480p"
        QualityOption.LOW -> "360p"
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
                // Cycle through available tracks on each unqualified "subtitles"/"audio"
                // request so a second invocation advances rather than re-selecting
                // the same first track. Explicit disable is handled by DisableSubtitles.
                C.TRACK_TYPE_TEXT, C.TRACK_TYPE_AUDIO -> {
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

        data class AmbiguousVersions(val itemId: UUID, val sources: List<dev.jdtech.jellyfin.models.SpatialFinSource>) : PendingSelection
    }
}
