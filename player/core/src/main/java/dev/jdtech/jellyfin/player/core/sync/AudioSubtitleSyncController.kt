package dev.jdtech.jellyfin.player.core.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns session-scoped live subtitle timing offset ([subtitleOffsetMs]) and audio synchronization
 * delay ([audioOffsetMs]) across XR, Beam, and TV players.
 *
 * Sign conventions match MPV / VLC / Jellyfin:
 * - [subtitleOffsetMs] `> 0`: delays subtitles so they appear later on screen.
 * - [subtitleOffsetMs] `< 0`: advances subtitles so they appear earlier on screen.
 * - [audioOffsetMs] `> 0`: delays audio so it plays later relative to video.
 * - [audioOffsetMs] `< 0`: advances audio relative to video (delays video frame release to
 *   compensate for lagging wireless headphones or AV receivers).
 */
class AudioSubtitleSyncController {
    @Volatile
    private var currentSubtitleOffsetMsValue: Long = 0L

    @Volatile
    private var currentAudioOffsetMsValue: Long = 0L

    private val _subtitleOffsetMs = MutableStateFlow(0L)
    val subtitleOffsetMs: StateFlow<Long> = _subtitleOffsetMs.asStateFlow()

    private val _audioOffsetMs = MutableStateFlow(0L)
    val audioOffsetMs: StateFlow<Long> = _audioOffsetMs.asStateFlow()

    fun setSubtitleOffsetMs(offsetMs: Long): Long {
        val clamped = offsetMs.coerceIn(MIN_SUBTITLE_OFFSET_MS, MAX_SUBTITLE_OFFSET_MS)
        currentSubtitleOffsetMsValue = clamped
        _subtitleOffsetMs.value = clamped
        return clamped
    }

    fun adjustSubtitleOffsetMs(deltaMs: Long): Long =
        setSubtitleOffsetMs(currentSubtitleOffsetMsValue + deltaMs)

    fun setAudioOffsetMs(offsetMs: Long): Long {
        val clamped = offsetMs.coerceIn(MIN_AUDIO_OFFSET_MS, MAX_AUDIO_OFFSET_MS)
        currentAudioOffsetMsValue = clamped
        _audioOffsetMs.value = clamped
        return clamped
    }

    fun adjustAudioOffsetMs(deltaMs: Long): Long =
        setAudioOffsetMs(currentAudioOffsetMsValue + deltaMs)

    fun currentSubtitleOffsetMs(): Long = currentSubtitleOffsetMsValue

    fun currentAudioOffsetMs(): Long = currentAudioOffsetMsValue

    fun currentPositivePcmDelayMs(): Long =
        currentAudioOffsetMsValue.coerceIn(0L, MAX_POSITIVE_PCM_DELAY_MS)

    fun reset() {
        currentSubtitleOffsetMsValue = 0L
        currentAudioOffsetMsValue = 0L
        _subtitleOffsetMs.value = 0L
        _audioOffsetMs.value = 0L
    }

    companion object {
        const val MIN_SUBTITLE_OFFSET_MS = -10_000L
        const val MAX_SUBTITLE_OFFSET_MS = 10_000L
        const val MIN_AUDIO_OFFSET_MS = -5_000L
        const val MAX_AUDIO_OFFSET_MS = 5_000L
        const val MAX_POSITIVE_PCM_DELAY_MS = 5_000L
        const val FINE_STEP_MS = 50L
        const val COARSE_STEP_MS = 250L

        /**
         * Computes the effective playback position in milliseconds to pass to `libass` or text
         * renderers given the raw player position and [subtitleOffsetMs].
         *
         * A positive [subtitleOffsetMs] delays subtitles (subtracts from [rawPositionMs] so a cue
         * at `t = 5000 ms` with `+500 ms` offset is rendered when `rawPositionMs == 5500 ms`).
         */
        fun effectiveLibassPositionMs(rawPositionMs: Long, subtitleOffsetMs: Long): Long =
            (rawPositionMs - subtitleOffsetMs).coerceAtLeast(0L)

        fun formatSignedOffsetMs(offsetMs: Long): String =
            when {
                offsetMs > 0L -> "+${offsetMs} ms"
                else -> "${offsetMs} ms"
            }
    }
}
