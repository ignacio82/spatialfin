package dev.spatialfin.unified.audio

import androidx.compose.runtime.compositionLocalOf
import dev.jdtech.jellyfin.models.AudioQueueItem
import dev.jdtech.jellyfin.models.SpatialFinChapter
import dev.jdtech.jellyfin.models.SpatialFinLyrics
import java.util.UUID
import kotlinx.coroutines.flow.StateFlow

enum class AudioPlaybackPhase {
    Idle,
    Buffering,
    Playing,
    Paused,
    Ended,
}

enum class AudioRepeatMode {
    Off,
    One,
    All,
}

data class AudioSessionState(
    val queue: List<AudioQueueItem> = emptyList(),
    val currentIndex: Int = -1,
    val phase: AudioPlaybackPhase = AudioPlaybackPhase.Idle,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val playbackSpeed: Float = 1f,
    val shuffleEnabled: Boolean = false,
    val repeatMode: AudioRepeatMode = AudioRepeatMode.Off,
) {
    val currentItem: AudioQueueItem?
        get() = queue.getOrNull(currentIndex)

    val chapters: List<SpatialFinChapter>
        get() = currentItem?.chapters.orEmpty()
}

interface AudioPlaybackDispatcher {
    val state: StateFlow<AudioSessionState>

    fun playQueue(queue: List<AudioQueueItem>, startIndex: Int = 0)

    fun playPause()

    fun pause()

    fun stop()

    fun next()

    fun previous()

    fun seekTo(positionMs: Long)

    fun playIndex(index: Int)

    fun setPlaybackSpeed(speed: Float)

    fun cycleRepeatMode()

    fun toggleShuffle()

    suspend fun lyricsFor(itemId: UUID): SpatialFinLyrics?
}

interface AudioOutputEngine : AudioPlaybackDispatcher

val LocalAudioPlaybackDispatcher =
    compositionLocalOf<AudioPlaybackDispatcher?> { null }
