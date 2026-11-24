package dev.spatialfin.unified.audio

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jdtech.jellyfin.models.AudioQueueItem
import dev.jdtech.jellyfin.models.SpatialFinLyrics
import dev.jdtech.jellyfin.player.core.external.PluginHttpDataSourceFactory
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.session.SessionActivationCoordinator
import dev.jdtech.jellyfin.session.SessionMode
import dev.spatialfin.unified.applock.AppLockManager
import dev.spatialfin.unified.applock.AppLockObserver
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
    private val sessionActivationCoordinator: SessionActivationCoordinator,
    private val appLockObserver: AppLockObserver,
) : AudioOutputEngine {

    private data class BoundAudioSessionKey(
        val serverId: String,
        val userId: UUID,
        @Volatile var sessionEpoch: Long,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var sessionPlaybackScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var boundSessionKey: BoundAudioSessionKey? = null

    val player: ExoPlayer =
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(
                    PluginHttpDataSourceFactory.create(allowNetworkProxyLoopback = false),
                ),
            )
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

    private fun isLocked(): Boolean =
        appLockObserver.lockState.value == AppLockManager.LockState.LOCKED

    private fun isSessionBoundAndValid(expected: BoundAudioSessionKey? = boundSessionKey): Boolean {
        if (isLocked() || expected == null) return false
        val current = sessionActivationCoordinator.sessionState.value
        return current.sessionMode == SessionMode.SERVER_AUTHENTICATED &&
            !sessionActivationCoordinator.isActivating.get() &&
            current.activeServerId == expected.serverId &&
            current.activeUserId == expected.userId
    }

    init {
        player.addListener(
            object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    publishState()
                    if (!isSessionBoundAndValid()) return
                    when (playbackState) {
                        Player.STATE_READY -> reportStartForCurrentItem()
                        Player.STATE_ENDED -> reportStopForCurrentItem(markedPlayed = true)
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    publishState()
                    if (!isSessionBoundAndValid()) return
                    if (isPlaying) {
                        reportStartForCurrentItem()
                    } else {
                        reportProgressForCurrentItem(isPaused = true)
                    }
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    val newId = mediaItem?.mediaId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    val previousId = currentStartedItemId?.takeIf { it != newId }
                    if (previousId != null && isSessionBoundAndValid()) {
                        val previousItem = _state.value.queue.firstOrNull { it.id == previousId }
                        reportStop(
                            previousItem,
                            lastKnownPositionsMs[previousId] ?: 0L,
                            markedPlayed = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO,
                        )
                        currentStartedItemId = null
                    }
                    publishState()
                    if (isSessionBoundAndValid()) {
                        reportStartForCurrentItem()
                    }
                }

                override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int,
                ) {
                    publishState()
                    if (reason == Player.DISCONTINUITY_REASON_SEEK && isSessionBoundAndValid()) {
                        reportProgressForCurrentItem(isPaused = !player.isPlaying)
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    Timber.w(error, "Jellyfin audio playback failed")
                    publishState()
                }
            },
        )

        scope.launch {
            combine(
                appLockObserver.lockState,
                sessionActivationCoordinator.sessionState,
            ) { lock, session -> lock to session }.collect { (lock, session) ->
                if (session.isLoading) return@collect
                val bound = boundSessionKey
                val locked = lock == AppLockManager.LockState.LOCKED
                if (locked) {
                    if (bound != null || player.mediaItemCount > 0 || _state.value.queue.isNotEmpty()) {
                        teardownForLockOrSessionLoss("App Lock LOCKED")
                    }
                    return@collect
                }
                if (bound != null) {
                    if (session.sessionMode != SessionMode.SERVER_AUTHENTICATED ||
                        session.activeServerId != bound.serverId ||
                        session.activeUserId != bound.userId
                    ) {
                        teardownForLockOrSessionLoss("Session ended or switched")
                    } else if (session.sessionEpoch != bound.sessionEpoch) {
                        bound.sessionEpoch = session.sessionEpoch
                    }
                }
            }
        }

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
                    if (isSessionBoundAndValid()) {
                        reportProgressForCurrentItem(isPaused = !player.isPlaying)
                    }
                }
            }
    }

    private fun teardownForLockOrSessionLoss(reason: String) {
        Timber.i("Media3JellyfinAudioOutputEngine: tearing down playback (%s)", reason)
        boundSessionKey = null
        sessionPlaybackScope.cancel()
        sessionPlaybackScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        currentStartedItemId = null
        lastKnownPositionsMs.clear()
        runCatching {
            player.stop()
            player.clearMediaItems()
        }
        _state.value = AudioSessionState()
    }

    override fun playQueue(queue: List<AudioQueueItem>, startIndex: Int) {
        if (queue.isEmpty() || isLocked()) return
        val session = sessionActivationCoordinator.sessionState.value
        val serverId = session.activeServerId
        val userId = session.activeUserId
        if (session.sessionMode != SessionMode.SERVER_AUTHENTICATED ||
            sessionActivationCoordinator.isActivating.get() ||
            serverId.isNullOrBlank() ||
            userId == null
        ) {
            Timber.w("Media3JellyfinAudioOutputEngine: playQueue blocked (unauthenticated)")
            return
        }

        val nextKey = BoundAudioSessionKey(serverId, userId, session.sessionEpoch)
        val previousKey = boundSessionKey
        if (previousKey != null && isSessionBoundAndValid(previousKey)) {
            reportStopForCurrentItem(markedPlayed = false)
        }
        sessionPlaybackScope.cancel()
        sessionPlaybackScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        boundSessionKey = nextKey

        val launchScope = sessionPlaybackScope
        launchScope.launch {
            val safeIndex = startIndex.coerceIn(queue.indices)
            val mediaItems =
                queue.map { item ->
                    if (!isSessionBoundAndValid(nextKey)) return@launch
                    val url =
                        repository.getAudioStreamUrl(
                            itemId = item.id,
                            mediaSourceId = item.mediaSourceId,
                        )
                    item.toMediaItem(url)
                }
            if (!isSessionBoundAndValid(nextKey)) return@launch
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
        if (!isSessionBoundAndValid()) return
        sessionPlaybackScope.launch {
            if (!isSessionBoundAndValid()) return@launch
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
            if (isSessionBoundAndValid()) {
                reportStopForCurrentItem(markedPlayed = false)
            }
            boundSessionKey = null
            sessionPlaybackScope.cancel()
            sessionPlaybackScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            player.stop()
            player.clearMediaItems()
            currentStartedItemId = null
            lastKnownPositionsMs.clear()
            _state.value = AudioSessionState()
        }
    }

    override fun next() {
        if (!isSessionBoundAndValid()) return
        sessionPlaybackScope.launch {
            if (!isSessionBoundAndValid()) return@launch
            if (player.hasNextMediaItem()) player.seekToNextMediaItem()
            publishState()
        }
    }

    override fun previous() {
        if (!isSessionBoundAndValid()) return
        sessionPlaybackScope.launch {
            if (!isSessionBoundAndValid()) return@launch
            if (player.hasPreviousMediaItem()) player.seekToPreviousMediaItem()
            else player.seekTo(0L)
            publishState()
        }
    }

    override fun seekTo(positionMs: Long) {
        if (!isSessionBoundAndValid()) return
        sessionPlaybackScope.launch {
            if (!isSessionBoundAndValid()) return@launch
            player.seekTo(positionMs.coerceAtLeast(0L))
            publishState()
            reportProgressForCurrentItem(isPaused = !player.isPlaying)
        }
    }

    override fun playIndex(index: Int) {
        if (!isSessionBoundAndValid()) return
        sessionPlaybackScope.launch {
            if (!isSessionBoundAndValid()) return@launch
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

    override suspend fun lyricsFor(itemId: UUID): SpatialFinLyrics? {
        if (!isSessionBoundAndValid()) return null
        return repository.getAudioLyrics(itemId)
    }

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
        val key = boundSessionKey ?: return
        if (!isSessionBoundAndValid(key)) return
        val item = _state.value.currentItem ?: return
        if (currentStartedItemId == item.id) return
        currentStartedItemId = item.id
        sessionPlaybackScope.launch {
            if (!isSessionBoundAndValid(key)) return@launch
            runCatching { repository.postPlaybackStart(item.id) }
                .onFailure { Timber.d(it, "Failed to report Jellyfin audio start for %s", item.id) }
            if (isSessionBoundAndValid(key)) {
                reportProgress(item, player.currentPosition.coerceAtLeast(0L), isPaused = !player.isPlaying)
            }
        }
    }

    private fun reportProgressForCurrentItem(isPaused: Boolean) {
        if (!isSessionBoundAndValid()) return
        val item = _state.value.currentItem ?: return
        reportProgress(item, player.currentPosition.coerceAtLeast(0L), isPaused)
    }

    private fun reportProgress(item: AudioQueueItem, positionMs: Long, isPaused: Boolean) {
        val key = boundSessionKey ?: return
        if (!isSessionBoundAndValid(key)) return
        lastKnownPositionsMs[item.id] = positionMs
        sessionPlaybackScope.launch {
            if (!isSessionBoundAndValid(key)) return@launch
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
        if (!isSessionBoundAndValid()) return
        val item = _state.value.currentItem ?: return
        reportStop(item, player.currentPosition.coerceAtLeast(0L), markedPlayed)
        if (currentStartedItemId == item.id) currentStartedItemId = null
    }

    private fun reportStop(item: AudioQueueItem?, positionMs: Long, markedPlayed: Boolean) {
        item ?: return
        val key = boundSessionKey ?: return
        if (!isSessionBoundAndValid(key)) return
        lastKnownPositionsMs[item.id] = positionMs
        val percentage =
            if (item.durationMs > 0L) {
                ((positionMs.toDouble() / item.durationMs.toDouble()) * 100.0).toInt()
            } else {
                0
            }.coerceIn(0, 100)
        sessionPlaybackScope.launch {
            if (!isSessionBoundAndValid(key)) return@launch
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
        return MediaItem.Builder()
            .setMediaId(id.toString())
            .setUri(url)
            .setMediaMetadata(metadata)
            .build()
    }

    internal fun release() {
        progressJob?.cancel()
        sessionPlaybackScope.cancel()
        scope.cancel()
        player.release()
    }
}
