package dev.jdtech.jellyfin.player.xr.voice

import androidx.media3.common.C
import androidx.media3.common.Player
import dev.jdtech.jellyfin.player.local.presentation.PlayerViewModel
import timber.log.Timber

class VoiceCommandDispatcher(
    private val viewModel: PlayerViewModel,
    private val player: Player,
    private val onControlsVisibilityChange: (Boolean) -> Unit,
    private val onNavigateBack: () -> Unit,
    private val onSearch: (String) -> Unit,
    private val onGoHome: () -> Unit,
    private val onCloseApp: () -> Unit,
) {
    fun dispatch(action: XrPlayerAction): String {
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
                player.seekToNextMediaItem()
                "Next episode"
            }
            is XrPlayerAction.PreviousEpisode -> {
                player.seekToPreviousMediaItem()
                "Previous episode"
            }
            is XrPlayerAction.SetSpeed -> {
                viewModel.selectSpeed(action.speed)
                "${action.speed}x speed"
            }
            is XrPlayerAction.SetQuality -> {
                val itemIdString = viewModel.uiState.value.currentItemId
                val itemKind = viewModel.uiState.value.currentItemKind
                if (itemIdString != null && itemKind != null) {
                    val label = when (action.maxBitrate) {
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
                        else -> "${action.maxBitrate / 1_000_000} Mbps"
                    }
                    viewModel.changeQuality(java.util.UUID.fromString(itemIdString as String), itemKind as String, action.maxBitrate)
                    "Quality set to $label"
                } else {
                    "Cannot change quality right now"
                }
            }
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
            is XrPlayerAction.Search -> {
                onSearch(action.query)
                "Searching: ${action.query}"
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
                onGoHome()
                "Returning to home"
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
            is XrPlayerAction.Unrecognized -> {
                "Sorry, I didn't understand: ${action.transcript}"
            }
        }
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
}
