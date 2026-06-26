package dev.spatialfin.unified.audio

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jdtech.jellyfin.models.AudioQueueItem
import dev.jdtech.jellyfin.models.SpatialFinLyrics
import dev.jdtech.jellyfin.repository.JellyfinRepository
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

class JellyfinAudioPlayDispatcher
@Inject
constructor(
    private val engine: AudioOutputEngine,
    private val repository: JellyfinRepository,
) : AudioPlaybackDispatcher by engine {
    override suspend fun lyricsFor(itemId: UUID): SpatialFinLyrics? =
        repository.getAudioLyrics(itemId)
}

@Singleton
@OptIn(UnstableApi::class)
class Media3JellyfinAudioOutputEngine
@Inject
constructor(
    @param:ApplicationContext private val context: Context,
    private val repository: JellyfinRepository,
) : AudioOutputEngine {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // No MediaSession here: the system session lives in
    // [JellyfinAudioMediaSessionService] with a unique session ID. Building one in
    // this singleton used the default empty ID and crashed MaMediaSessionService
    // (Media3 requires process-unique session IDs) the moment MA playback started.
    val player: ExoPlayer =
        ExoPlayer.Builder(context)
            .setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                    .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus= */ true,
            )
            .setWakeMode(androidx.media3.common.C.WAKE_MODE_NETWORK)
            .build()
    private val _state = MutableStateFlow(AudioSessionState())
    override val state: StateFlow<AudioSessionState> = _state.asStateFlow()

    private var progressJob: Job? = null
    private var currentStartedItemId: UUID? = null
    private var lastKnownPositionsMs: MutableMap<UUID, Long> = linkedMapOf()

    init {
        player.addListener(
            object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    publishState()
                    when (playbackState) {
                        Player.STATE_READY -> reportStartForCurrentItem()
                        Player.STATE_ENDED -> reportStopForCurrentItem(markedPlayed = true)
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    publishState()
                    if (isPlaying) {
                        reportStartForCurrentItem()
                    } else {
                        reportProgressForCurrentItem(isPaused = true)
                    }
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    val newId = mediaItem?.mediaId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    val previousId = currentStartedItemId?.takeIf { it != newId }
                    if (previousId != null) {
                        val previousItem = _state.value.queue.firstOrNull { it.id == previousId }
                        reportStop(previousItem, lastKnownPositionsMs[previousId] ?: 0L, markedPlayed = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
                        currentStartedItemId = null
                    }
                    publishState()
                    reportStartForCurrentItem()
                }

                override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int,
                ) {
                    publishState()
                    if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                        reportProgressForCurrentItem(isPaused = !player.isPlaying)
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    Timber.w(error, "Jellyfin audio playback failed")
                    publishState()
                }
            }
        )

        scope.launch {
            while (isActive) {
                publishState()
                delay(500L)
            }
        }
        progressJob =
            scope.launch {
                while (isActive) {
                    delay(10_000L)
                    reportProgressForCurrentItem(isPaused = !player.isPlaying)
                }
            }
    }

    override fun playQueue(queue: List<AudioQueueItem>, startIndex: Int) {
        if (queue.isEmpty()) return
        scope.launch {
            reportStopForCurrentItem(markedPlayed = false)
            val safeIndex = startIndex.coerceIn(queue.indices)
            val mediaItems =
                queue.map { item ->
                    val url =
                        repository.getAudioStreamUrl(
                            itemId = item.id,
                            mediaSourceId = item.mediaSourceId,
                        )
                    item.toMediaItem(url)
                }
            _state.value =
                _state.value.copy(
                    queue = queue,
                    currentIndex = safeIndex,
                    positionMs = queue[safeIndex].resumePositionMs,
                    durationMs = queue[safeIndex].durationMs,
                    phase = AudioPlaybackPhase.Buffering,
                )
            player.setMediaItems(mediaItems, safeIndex, queue[safeIndex].resumePositionMs)
            player.prepare()
            player.playWhenReady = true
            publishState()
        }
    }

    override fun playPause() {
        scope.launch {
            when {
                player.isPlaying -> player.pause()
                player.mediaItemCount > 0 -> {
                    if (player.playbackState == Player.STATE_IDLE) {
                        player.prepare()
                    }
                    player.play()
                }
            }
            publishState()
        }
    }

    override fun pause() {
        scope.launch {
            player.pause()
            publishState()
        }
    }

    override fun stop() {
        scope.launch {
            reportStopForCurrentItem(markedPlayed = false)
            player.stop()
            player.clearMediaItems()
            currentStartedItemId = null
            lastKnownPositionsMs.clear()
            _state.value = AudioSessionState()
        }
    }

    override fun next() {
        scope.launch {
            if (player.hasNextMediaItem()) player.seekToNextMediaItem()
            publishState()
        }
    }

    override fun previous() {
        scope.launch {
            if (player.hasPreviousMediaItem()) player.seekToPreviousMediaItem()
            else player.seekTo(0L)
            publishState()
        }
    }

    override fun seekTo(positionMs: Long) {
        scope.launch {
            player.seekTo(positionMs.coerceAtLeast(0L))
            publishState()
            reportProgressForCurrentItem(isPaused = !player.isPlaying)
        }
    }

    override fun playIndex(index: Int) {
        scope.launch {
            if (index in 0 until player.mediaItemCount) {
                player.seekTo(index, 0L)
                player.play()
            }
            publishState()
        }
    }

    override fun setPlaybackSpeed(speed: Float) {
        scope.launch {
            player.setPlaybackSpeed(speed.coerceIn(0.5f, 3f))
            publishState()
        }
    }

    override fun cycleRepeatMode() {
        scope.launch {
            player.repeatMode =
                when (player.repeatMode) {
                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                    else -> Player.REPEAT_MODE_OFF
                }
            publishState()
        }
    }

    override fun toggleShuffle() {
        scope.launch {
            player.shuffleModeEnabled = !player.shuffleModeEnabled
            publishState()
        }
    }

    override suspend fun lyricsFor(itemId: UUID): SpatialFinLyrics? =
        repository.getAudioLyrics(itemId)

    private fun publishState() {
        val queue = _state.value.queue
        val index = player.currentMediaItemIndex.takeIf { it in queue.indices } ?: _state.value.currentIndex
        val current = queue.getOrNull(index)
        if (current != null) {
            lastKnownPositionsMs[current.id] = player.currentPosition.coerceAtLeast(0L)
        }
        _state.value =
            AudioSessionState(
                queue = queue,
                currentIndex = index,
                phase = player.toAudioPlaybackPhase(),
                positionMs = player.currentPosition.coerceAtLeast(0L),
                durationMs =
                    player.duration
                        .takeIf { it > 0L && it != androidx.media3.common.C.TIME_UNSET }
                        ?: current?.durationMs
                        ?: 0L,
                playbackSpeed = player.playbackParameters.speed,
                shuffleEnabled = player.shuffleModeEnabled,
                repeatMode =
                    when (player.repeatMode) {
                        Player.REPEAT_MODE_ONE -> AudioRepeatMode.One
                        Player.REPEAT_MODE_ALL -> AudioRepeatMode.All
                        else -> AudioRepeatMode.Off
                    },
            )
    }

    private fun Player.toAudioPlaybackPhase(): AudioPlaybackPhase =
        when (playbackState) {
            Player.STATE_BUFFERING -> AudioPlaybackPhase.Buffering
            Player.STATE_READY -> if (isPlaying) AudioPlaybackPhase.Playing else AudioPlaybackPhase.Paused
            Player.STATE_ENDED -> AudioPlaybackPhase.Ended
            else -> AudioPlaybackPhase.Idle
        }

    private fun reportStartForCurrentItem() {
        val item = _state.value.currentItem ?: return
        if (currentStartedItemId == item.id) return
        currentStartedItemId = item.id
        scope.launch {
            runCatching { repository.postPlaybackStart(item.id) }
                .onFailure { Timber.d(it, "Failed to report Jellyfin audio start for %s", item.id) }
            reportProgress(item, player.currentPosition.coerceAtLeast(0L), isPaused = !player.isPlaying)
        }
    }

    private fun reportProgressForCurrentItem(isPaused: Boolean) {
        val item = _state.value.currentItem ?: return
        reportProgress(item, player.currentPosition.coerceAtLeast(0L), isPaused)
    }

    private fun reportProgress(item: AudioQueueItem, positionMs: Long, isPaused: Boolean) {
        lastKnownPositionsMs[item.id] = positionMs
        scope.launch {
            runCatching {
                repository.postPlaybackProgress(
                    itemId = item.id,
                    positionTicks = positionMs * 10_000L,
                    isPaused = isPaused,
                )
            }.onFailure {
                Timber.d(it, "Failed to report Jellyfin audio progress for %s", item.id)
            }
        }
    }

    private fun reportStopForCurrentItem(markedPlayed: Boolean) {
        val item = _state.value.currentItem ?: return
        reportStop(item, player.currentPosition.coerceAtLeast(0L), markedPlayed)
        if (currentStartedItemId == item.id) currentStartedItemId = null
    }

    private fun reportStop(item: AudioQueueItem?, positionMs: Long, markedPlayed: Boolean) {
        item ?: return
        lastKnownPositionsMs[item.id] = positionMs
        val percentage =
            if (item.durationMs > 0L) {
                ((positionMs.toDouble() / item.durationMs.toDouble()) * 100.0).toInt()
            } else {
                0
            }.coerceIn(0, 100)
        scope.launch {
            runCatching {
                repository.postPlaybackStop(
                    itemId = item.id,
                    positionTicks = positionMs * 10_000L,
                    playedPercentage = percentage,
                    markedPlayed = markedPlayed || percentage > 95,
                )
            }.onFailure {
                Timber.d(it, "Failed to report Jellyfin audio stop for %s", item.id)
            }
        }
    }

    private fun AudioQueueItem.toMediaItem(url: String): MediaItem {
        val metadata =
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(subtitle)
                .setAlbumTitle(album)
                .apply { artwork?.let { setArtworkUri(it) } }
                .build()
        // No mimeType hint: /Audio/{id}/stream?static=true serves the original container
        // (mp3/flac/m4a/m4b/...), so let the progressive extractor sniff the format.
        return MediaItem.Builder()
            .setMediaId(id.toString())
            .setUri(url)
            .setMediaMetadata(metadata)
            .build()
    }

    @Suppress("unused")
    private fun release() {
        progressJob?.cancel()
        player.release()
    }
}
