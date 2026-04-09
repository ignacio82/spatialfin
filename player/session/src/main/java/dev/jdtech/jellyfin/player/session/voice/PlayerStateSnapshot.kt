package dev.jdtech.jellyfin.player.session.voice

enum class VoiceScreenContext {
    HOME,
    PLAYER,
}

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
    val currentGenres: List<String> = emptyList(),
    /** Actors only (directors/writers are in their own fields). */
    val castNames: List<String> = emptyList(),
    val directors: List<String> = emptyList(),
    val writers: List<String> = emptyList(),
    val productionYear: Int? = null,
    /** Content/age rating string (e.g. "PG-13", "TV-MA"). */
    val officialRating: String? = null,
    val audioTrackNames: List<String> = emptyList(),
    val subtitleTrackNames: List<String> = emptyList(),
    val chapterNames: List<String> = emptyList(),
    val currentAudioTrack: String? = null,
    val currentSubtitleTrack: String? = null,
    val currentAudioLanguageCode: String? = null,
    val currentSubtitleLanguageCode: String? = null,
    val inVoiceSearch: Boolean = false,
    val voiceSearchQuery: String? = null,
    val voiceSearchResultsCount: Int = 0,
    val syncPlayActive: Boolean = false,
    val syncPlayGroupName: String? = null,
    val syncPlayParticipantNames: List<String> = emptyList(),
    val lastRecommendationQuery: String? = null,
    val lastRecommendationCount: Int = 0,
    val lastRecommendationTitles: List<String> = emptyList(),
    val currentRatings: List<String> = emptyList(),
    val passthroughEnabled: Boolean = false,
    /**
     * Actor→character pairs extracted from Jellyfin's People metadata.
     * first = actor name (e.g. "Pedro Pascal")
     * second = character name (e.g. "Joel Miller")
     * Empty when metadata is unavailable.
     */
    val castWithCharacters: List<Pair<String, String>> = emptyList(),
)
