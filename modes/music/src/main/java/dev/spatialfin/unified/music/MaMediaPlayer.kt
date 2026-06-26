package dev.spatialfin.unified.music

import android.net.Uri
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dev.jdtech.jellyfin.data.musicassistant.data.model.server.RepeatMode
import dev.jdtech.jellyfin.data.musicassistant.repository.MaSession
import dev.jdtech.jellyfin.data.musicassistant.repository.MaSessionRepository
import dev.jdtech.jellyfin.data.musicassistant.repository.PlaybackPhase
import dev.spatialfin.unified.MaPlayDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * A [SimpleBasePlayer] that mirrors the live Music Assistant session as a
 * Media3 player, so a [androidx.media3.session.MediaSession] can expose MA
 * playback to the system (shade / lock-screen notification, Bluetooth + wired
 * headset transport, Android Auto later). It is a *control surface*, not an
 * audio engine — the audio plays via the SendSpin receiver or a remote MA
 * player; this only reflects state and forwards transport to [dispatcher].
 *
 * State is republished on every [MaSessionRepository.session] emission via
 * [invalidateState]; between emissions the position auto-advances through
 * [PositionSupplier.getExtrapolating].
 */
@UnstableApi
class MaMediaPlayer(
    looper: Looper,
    private val session: MaSessionRepository,
    private val dispatcher: MaPlayDispatcher,
) : SimpleBasePlayer(looper) {

    // Plain Dispatchers.Main (NOT .immediate): the collector's first emission
    // (StateFlow replays its current value) must not run invalidateState()
    // synchronously inside the SimpleBasePlayer constructor — that touches not-
    // yet-initialized base state and throws, killing the collector and freezing
    // the published state. Dispatching defers it to after construction.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    init {
        scope.launch {
            session.session.collect { runCatching { invalidateState() } }
        }
    }

    override fun getState(): State {
        val s = session.session.value
        val builder = State.Builder()
            .setAvailableCommands(COMMANDS)
            .setRepeatMode(
                when (s.repeatMode) {
                    RepeatMode.OFF -> Player.REPEAT_MODE_OFF
                    RepeatMode.ALL -> Player.REPEAT_MODE_ALL
                    RepeatMode.ONE -> Player.REPEAT_MODE_ONE
                },
            )
            .setShuffleModeEnabled(s.shuffleEnabled)

        val np = s.nowPlaying
        if (np == null) {
            return builder
                .setPlaybackState(Player.STATE_IDLE)
                .setPlayWhenReady(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                .build()
        }

        val playing = s.playbackPhase == PlaybackPhase.Playing
        val playbackState = when (s.playbackPhase) {
            PlaybackPhase.Preparing -> Player.STATE_BUFFERING
            PlaybackPhase.Stopped -> Player.STATE_IDLE
            else -> Player.STATE_READY
        }

        val metadata = MediaMetadata.Builder()
            .setTitle(np.title)
            .setArtist(np.artist)
            .apply {
                np.artworkUrl?.takeIf { it.isNotBlank() }?.let { setArtworkUri(Uri.parse(it)) }
            }
            .build()

        val durationUs = np.durationMs?.takeIf { it > 0L }?.let { it * 1000L } ?: C.TIME_UNSET
        val uid = np.uri.ifBlank { "ma:nowplaying" }
        val mediaItem = MediaItem.Builder()
            .setMediaId(uid)
            .apply { np.uri.takeIf { it.isNotBlank() }?.let { setUri(it) } }
            .setMediaMetadata(metadata)
            .build()
        val itemData = MediaItemData.Builder(uid)
            .setMediaItem(mediaItem)
            .setMediaMetadata(metadata)
            .setDurationUs(durationUs)
            .setIsSeekable(durationUs != C.TIME_UNSET)
            .build()

        val positionMs = currentPositionMs(s)
        val positionSupplier = if (playing) {
            PositionSupplier.getExtrapolating(positionMs, 1f)
        } else {
            PositionSupplier.getConstant(positionMs)
        }

        return builder
            .setPlaybackState(playbackState)
            .setPlayWhenReady(playing, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaylist(listOf(itemData))
            .setCurrentMediaItemIndex(0)
            .setContentPositionMs(positionSupplier)
            .build()
    }

    /** Server elapsed time, extrapolated to now while playing, clamped to duration. */
    private fun currentPositionMs(s: MaSession): Long {
        val elapsed = s.elapsedMs ?: return 0L
        val asOf = s.elapsedAsOfEpochMs
        val extra = if (s.playbackPhase == PlaybackPhase.Playing && asOf != null) {
            (System.currentTimeMillis() - asOf).coerceAtLeast(0L)
        } else {
            0L
        }
        val pos = elapsed + extra
        val dur = s.nowPlaying?.durationMs
        return if (dur != null) pos.coerceIn(0L, dur) else pos.coerceAtLeast(0L)
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if ((session.session.value.playbackPhase == PlaybackPhase.Playing) != playWhenReady) {
            dispatcher.playPause()
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        dispatcher.stop()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> dispatcher.next()
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> dispatcher.previous()
            else -> if (positionMs != C.TIME_UNSET) dispatcher.seekTo(positionMs)
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        dispatcher.setRepeatMode(
            when (repeatMode) {
                Player.REPEAT_MODE_ONE -> RepeatMode.ONE
                Player.REPEAT_MODE_ALL -> RepeatMode.ALL
                else -> RepeatMode.OFF
            },
        )
        return Futures.immediateVoidFuture()
    }

    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> {
        dispatcher.setShuffle(shuffleModeEnabled)
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        scope.cancel()
        return Futures.immediateVoidFuture()
    }

    private companion object {
        val COMMANDS: Player.Commands = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                Player.COMMAND_SEEK_BACK,
                Player.COMMAND_SEEK_FORWARD,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_STOP,
                Player.COMMAND_SET_REPEAT_MODE,
                Player.COMMAND_SET_SHUFFLE_MODE,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_METADATA,
                Player.COMMAND_GET_TIMELINE,
                Player.COMMAND_RELEASE,
            )
            .build()
    }
}
