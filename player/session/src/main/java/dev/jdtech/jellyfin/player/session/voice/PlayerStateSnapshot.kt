package dev.jdtech.jellyfin.player.session.voice

data class PlayerStateSnapshot(
    val isPlaying: Boolean = false,
    val positionSeconds: Long = 0,
    val durationSeconds: Long = 0,
    val controlsVisible: Boolean = false,
    val currentItemTitle: String = "",
    val currentSegmentType: String? = null,
    val currentChapterName: String? = null,
    val nextEpisodeTitle: String? = null,
    val audioTrackNames: List<String> = emptyList(),
    val subtitleTrackNames: List<String> = emptyList(),
    val chapterNames: List<String> = emptyList(),
    val currentAudioTrack: String? = null,
    val currentSubtitleTrack: String? = null,
)
