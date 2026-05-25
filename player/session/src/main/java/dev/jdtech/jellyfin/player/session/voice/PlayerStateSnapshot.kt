package dev.jdtech.jellyfin.player.session.voice

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

enum class VoiceScreenContext {
    HOME,
    PLAYER,
}

@Immutable
data class PlayerStateSnapshot(
    val screenContext: VoiceScreenContext = VoiceScreenContext.PLAYER,
    val isPlaying: Boolean = false,
    val positionSeconds: Long = 0,
    val durationSeconds: Long = 0,
    val controlsVisible: Boolean = false,
    val currentItemTitle: String = "",
    val currentOverview: String = "",
    val currentSeriesName: String? = null,
    val currentSeasonNumber: Int? = null,
    val currentEpisodeNumber: Int? = null,
    val currentSegmentType: String? = null,
    val currentChapterName: String? = null,
    val nextEpisodeTitle: String? = null,
    val currentGenres: ImmutableList<String> = persistentListOf(),
    /** Actors only (directors/writers are in their own fields). */
    val castNames: ImmutableList<String> = persistentListOf(),
    val directors: ImmutableList<String> = persistentListOf(),
    val writers: ImmutableList<String> = persistentListOf(),
    val productionYear: Int? = null,
    /** Content/age rating string (e.g. "PG-13", "TV-MA"). */
    val officialRating: String? = null,
    val audioTrackNames: ImmutableList<String> = persistentListOf(),
    val subtitleTrackNames: ImmutableList<String> = persistentListOf(),
    val chapterNames: ImmutableList<String> = persistentListOf(),
    val currentAudioTrack: String? = null,
    val currentSubtitleTrack: String? = null,
    val currentAudioLanguageCode: String? = null,
    val currentSubtitleLanguageCode: String? = null,
    val inVoiceSearch: Boolean = false,
    val voiceSearchQuery: String? = null,
    val voiceSearchResultsCount: Int = 0,
    val syncPlayActive: Boolean = false,
    val syncPlayGroupName: String? = null,
    val syncPlayParticipantNames: ImmutableList<String> = persistentListOf(),
    val lastRecommendationQuery: String? = null,
    val lastRecommendationCount: Int = 0,
    val lastRecommendationTitles: ImmutableList<String> = persistentListOf(),
    val currentRatings: ImmutableList<String> = persistentListOf(),
    val passthroughEnabled: Boolean = false,
    /**
     * Actor→character pairs extracted from Jellyfin's People metadata.
     * first = actor name (e.g. "Pedro Pascal")
     * second = character name (e.g. "Joel Miller")
     * Empty when metadata is unavailable.
     */
    val castWithCharacters: ImmutableList<Pair<String, String>> = persistentListOf(),
)
