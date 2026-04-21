package dev.jdtech.jellyfin.player.xr

import androidx.media3.common.C
import androidx.media3.common.Player
import dev.jdtech.jellyfin.models.SpatialFinItem
import dev.jdtech.jellyfin.player.local.presentation.PlayerViewModel
import dev.jdtech.jellyfin.player.session.voice.PlayerStateSnapshot
import dev.jdtech.jellyfin.player.session.voice.VoiceScreenContext
import dev.jdtech.jellyfin.player.xr.voice.RecommendationContext

/**
 * Builds the [PlayerStateSnapshot] the voice / chat pipeline feeds into parsing and
 * model prompts. The builder consolidates what used to be two near-duplicate inline
 * instantiations (one in `SpatialPlayerScreen.currentRecommendationSnapshot`, one
 * in `startVoiceCapture`) — they drifted on `audioTrackNames`, `subtitleTrackNames`,
 * and `chapterNames`, which this helper now always populates.
 */
internal fun buildPlayerStateSnapshot(
    player: Player,
    uiState: PlayerViewModel.UiState,
    controlsVisible: Boolean,
    voiceSearchOpen: Boolean,
    voiceSearchQuery: String,
    voiceSearchResults: List<SpatialFinItem>,
    recommendationContext: RecommendationContext?,
    passthroughEnabled: Boolean,
): PlayerStateSnapshot =
    PlayerStateSnapshot(
        screenContext = VoiceScreenContext.PLAYER,
        isPlaying = player.isPlaying,
        positionSeconds = player.currentPosition / 1_000L,
        durationSeconds = player.duration.coerceAtLeast(0L) / 1_000L,
        controlsVisible = controlsVisible,
        currentItemTitle = uiState.currentItemTitle,
        currentOverview = uiState.currentOverview,
        currentSeriesName = uiState.currentSeriesName,
        currentSeasonNumber = uiState.currentSeasonNumber,
        currentEpisodeNumber = uiState.currentEpisodeNumber,
        currentSegmentType = uiState.currentSegment?.type?.toString(),
        currentChapterName = currentChapterName(uiState, player.currentPosition),
        nextEpisodeTitle = uiState.nextEpisode?.name,
        currentGenres = uiState.currentGenres,
        currentRatings = uiState.currentRatings.map { "${it.type.label}: ${it.value}" },
        castNames = uiState.currentPeople
            .filter { it.type.equals("Actor", ignoreCase = true) }
            .map { it.name },
        directors = uiState.currentPeople
            .filter { it.type.equals("Director", ignoreCase = true) }
            .map { it.name },
        writers = uiState.currentPeople
            .filter { it.type.equals("Writer", ignoreCase = true) }
            .map { it.name },
        castWithCharacters = uiState.currentPeople
            .filter { it.type.equals("Actor", ignoreCase = true) && it.role.isNotBlank() }
            .map { it.name to it.role },
        productionYear = uiState.currentProductionYear,
        officialRating = uiState.currentOfficialRating,
        audioTrackNames = trackNames(player, C.TRACK_TYPE_AUDIO),
        subtitleTrackNames = trackNames(player, C.TRACK_TYPE_TEXT),
        chapterNames = uiState.currentChapters.mapNotNull { it.name },
        currentAudioTrack = selectedTrackName(player, C.TRACK_TYPE_AUDIO),
        currentSubtitleTrack = selectedTrackName(player, C.TRACK_TYPE_TEXT),
        currentAudioLanguageCode = selectedTrackLanguage(player, C.TRACK_TYPE_AUDIO),
        currentSubtitleLanguageCode = selectedTrackLanguage(player, C.TRACK_TYPE_TEXT),
        inVoiceSearch = voiceSearchOpen,
        voiceSearchQuery = voiceSearchQuery.ifBlank { null },
        voiceSearchResultsCount = voiceSearchResults.size,
        lastRecommendationQuery = recommendationContext?.query,
        lastRecommendationCount = recommendationContext?.items?.size ?: 0,
        lastRecommendationTitles = recommendationContext?.items?.take(6)?.map { it.name } ?: emptyList(),
        passthroughEnabled = passthroughEnabled,
    )
