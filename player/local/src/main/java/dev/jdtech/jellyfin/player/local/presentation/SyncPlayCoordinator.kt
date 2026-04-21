package dev.jdtech.jellyfin.player.local.presentation

import android.app.Application
import android.os.Build
import android.os.SystemClock
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import dev.jdtech.jellyfin.models.SpatialFinEpisode
import dev.jdtech.jellyfin.models.SpatialFinItem
import dev.jdtech.jellyfin.models.SpatialFinMediaStream
import dev.jdtech.jellyfin.models.SpatialFinMovie
import dev.jdtech.jellyfin.models.SpatialFinSeason
import dev.jdtech.jellyfin.models.SpatialFinShow
import dev.jdtech.jellyfin.models.toSyncPlayGroup
import dev.jdtech.jellyfin.player.core.domain.models.PlayerContentSource
import dev.jdtech.jellyfin.player.core.domain.models.PlayerItem
import dev.jdtech.jellyfin.player.local.domain.PlaylistManager
import dev.jdtech.jellyfin.repository.JellyfinRepository
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jellyfin.sdk.api.sockets.SocketApiState
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.GeneralCommandMessage
import org.jellyfin.sdk.model.api.GeneralCommandType
import org.jellyfin.sdk.model.api.GroupStateType
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.PlaystateCommand
import org.jellyfin.sdk.model.api.SendCommandType
import org.jellyfin.sdk.model.api.SyncPlayCommandMessage
import org.jellyfin.sdk.model.api.SyncPlayGroupDoesNotExistUpdate
import org.jellyfin.sdk.model.api.SyncPlayGroupJoinedUpdate
import org.jellyfin.sdk.model.api.SyncPlayGroupLeftUpdate
import org.jellyfin.sdk.model.api.SyncPlayGroupUpdateMessage
import org.jellyfin.sdk.model.api.SyncPlayNotInGroupUpdate
import org.jellyfin.sdk.model.api.SyncPlayPlayQueueUpdate
import org.jellyfin.sdk.model.api.SyncPlayQueueItem
import org.jellyfin.sdk.model.api.SyncPlayStateUpdate
import timber.log.Timber

internal class SyncPlayCoordinator(
    private val application: Application,
    private val repository: JellyfinRepository,
    private val playlistManager: PlaylistManager,
    private val scope: CoroutineScope,
    private val host: Host,
) {

    interface Host {
        val player: Player
        val currentMediaSourceStreams: List<SpatialFinMediaStream>
        var playbackPosition: Long
        fun currentPlayerItem(): PlayerItem?
        fun currentItemTitle(): String
        fun updateNextEpisode(nextEpisode: PlayerItem?)
        fun replaceItems(items: MutableList<PlayerItem>)
        fun initializePlayer(
            itemId: UUID,
            itemKind: String,
            startFromBeginning: Boolean,
            mediaSourceIndex: Int? = null,
            autoPlay: Boolean = true,
        )
        fun switchToTrack(trackType: Int, index: Int)
        fun skipToNextItem()
        fun toMediaItem(item: PlayerItem): MediaItem
    }

    private val _state = MutableStateFlow(PlayerViewModel.SyncPlayUiState())
    val state: StateFlow<PlayerViewModel.SyncPlayUiState> = _state.asStateFlow()

    var activeGroupId: UUID? = null
        private set
    var currentPlaylistItemId: UUID? = null

    private var suppressSyncUntilMs: Long = 0L
    private var isSocketConnected = false
    private var pendingRemoteAudioStreamIndex: Int? = null
    private var pendingRemoteSubtitleStreamIndex: Int? = null
    private var lastNonMutedVolume: Float = 1f
    private var remoteSessionConfigured = false
    private var configuredDeviceName: String? = null

    fun isActive(): Boolean = activeGroupId != null

    fun shouldSuppressEvents(): Boolean = SystemClock.elapsedRealtime() < suppressSyncUntilMs

    private inline fun applyRemoteSync(action: () -> Unit) {
        suppressSyncUntilMs = SystemClock.elapsedRealtime() + 1_500L
        action()
    }

    fun refreshGroups() {
        scope.launch {
            _state.update { it.copy(isLoading = true, statusMessage = null) }
            runCatching { repository.getSyncPlayGroups() }
                .onSuccess { groups ->
                    _state.update {
                        it.copy(
                            isLoading = false,
                            availableGroups = groups,
                            activeGroup = groups.firstOrNull { group -> group.id == activeGroupId } ?: it.activeGroup,
                        )
                    }
                }
                .onFailure { error ->
                    Timber.w(error, "Failed to refresh SyncPlay groups")
                    _state.update {
                        it.copy(
                            isLoading = false,
                            statusMessage = error.localizedMessage ?: "Unable to load SyncPlay groups",
                        )
                    }
                }
        }
    }

    fun createGroup() {
        val currentItem = host.currentPlayerItem()
        if (currentItem?.contentSource != PlayerContentSource.JELLYFIN) {
            _state.update {
                it.copy(statusMessage = "SyncPlay is only available for Jellyfin playback")
            }
            return
        }

        val groupName = host.currentItemTitle().ifBlank { currentItem.name }.take(60)
        scope.launch {
            _state.update { it.copy(isLoading = true, statusMessage = null) }
            runCatching {
                    val group = repository.createSyncPlayGroup(groupName)
                    repository.setSyncPlayQueue(
                        itemIds = listOf(currentItem.itemId),
                        playingItemIndex = 0,
                        startPositionTicks = host.player.currentPosition.coerceAtLeast(0L) * 10_000L,
                    )
                    group
                }
                .onSuccess { group ->
                    activeGroupId = group.id
                    currentPlaylistItemId = null
                    _state.update {
                        it.copy(
                            isLoading = false,
                            activeGroup = group,
                            statusMessage = "Created SyncPlay group: ${group.name}",
                        )
                    }
                    refreshGroups()
                }
                .onFailure { error ->
                    Timber.w(error, "Failed to create SyncPlay group")
                    _state.update {
                        it.copy(
                            isLoading = false,
                            statusMessage = error.localizedMessage ?: "Unable to create SyncPlay group",
                        )
                    }
                }
        }
    }

    fun joinGroup(groupId: UUID) {
        val currentItem = host.currentPlayerItem()
        if (currentItem?.contentSource != PlayerContentSource.JELLYFIN) {
            _state.update {
                it.copy(statusMessage = "SyncPlay is only available for Jellyfin playback")
            }
            return
        }

        scope.launch {
            _state.update { it.copy(isLoading = true, statusMessage = null) }
            runCatching {
                    repository.joinSyncPlayGroup(groupId)
                    repository.getSyncPlayGroups().firstOrNull { it.id == groupId }
                }
                .onSuccess { group ->
                    activeGroupId = groupId
                    currentPlaylistItemId = null
                    _state.update {
                        it.copy(
                            isLoading = false,
                            activeGroup = group ?: it.activeGroup,
                            statusMessage = "Joined SyncPlay group",
                        )
                    }
                    refreshGroups()
                }
                .onFailure { error ->
                    Timber.w(error, "Failed to join SyncPlay group")
                    _state.update {
                        it.copy(
                            isLoading = false,
                            statusMessage = error.localizedMessage ?: "Unable to join SyncPlay group",
                        )
                    }
                }
        }
    }

    fun leaveGroup() {
        scope.launch {
            runCatching { repository.leaveSyncPlayGroup() }
                .onFailure { Timber.w(it, "Failed to leave SyncPlay group") }
            activeGroupId = null
            currentPlaylistItemId = null
            _state.update {
                it.copy(activeGroup = null, statusMessage = "Left SyncPlay group")
            }
            refreshGroups()
        }
    }

    suspend fun handleSyncPlayCommandMessage(message: SyncPlayCommandMessage) {
        val command = message.data ?: return
        if (command.groupId != activeGroupId) return

        when (command.command) {
            SendCommandType.PAUSE -> applyRemoteSync { host.player.pause() }
            SendCommandType.UNPAUSE -> applyRemoteSync { host.player.play() }
            SendCommandType.SEEK -> command.positionTicks?.let { seekToRemotePosition(it) }
            SendCommandType.STOP -> applyRemoteSync {
                host.player.pause()
                host.player.seekTo(0)
            }
        }
    }

    suspend fun handleSyncPlayGroupUpdate(message: SyncPlayGroupUpdateMessage) {
        val update = message.data
        when (update) {
            is SyncPlayGroupJoinedUpdate -> {
                if (activeGroupId == update.groupId) {
                    _state.update { it.copy(activeGroup = update.data.toSyncPlayGroup()) }
                }
            }
            is SyncPlayPlayQueueUpdate -> {
                if (activeGroupId != update.groupId) return
                applySyncPlayQueueUpdate(
                    queue = update.data.playlist,
                    playingItemIndex = update.data.playingItemIndex,
                    startPositionTicks = update.data.startPositionTicks,
                    shouldPlay = update.data.isPlaying,
                )
            }
            is SyncPlayStateUpdate -> {
                if (activeGroupId != update.groupId) return
                when (update.data.state) {
                    GroupStateType.PLAYING -> applyRemoteSync { host.player.play() }
                    GroupStateType.PAUSED,
                    GroupStateType.WAITING -> applyRemoteSync { host.player.pause() }
                    GroupStateType.IDLE -> Unit
                }
            }
            is SyncPlayGroupLeftUpdate,
            is SyncPlayNotInGroupUpdate,
            is SyncPlayGroupDoesNotExistUpdate -> {
                if (activeGroupId == update.groupId) {
                    activeGroupId = null
                    currentPlaylistItemId = null
                    _state.update {
                        it.copy(activeGroup = null, statusMessage = "SyncPlay group ended")
                    }
                    refreshGroups()
                }
            }
            else -> Unit
        }
    }

    fun handlePlayStateMessage(command: PlaystateCommand, seekPositionTicks: Long?) {
        when (command) {
            PlaystateCommand.PAUSE -> applyRemoteSync { host.player.pause() }
            PlaystateCommand.UNPAUSE,
            PlaystateCommand.PLAY_PAUSE -> applyRemoteSync {
                if (host.player.isPlaying) host.player.pause() else host.player.play()
            }
            PlaystateCommand.SEEK -> seekPositionTicks?.let { ticks ->
                scope.launch { seekToRemotePosition(ticks) }
            }
            PlaystateCommand.STOP -> applyRemoteSync {
                host.player.pause()
                host.player.seekTo(0)
            }
            else -> Unit
        }
    }

    suspend fun handleGeneralCommandMessage(message: GeneralCommandMessage) {
        val command = message.data ?: return
        when (command.name) {
            GeneralCommandType.VOLUME_UP -> setPlayerVolume(host.player.volume + 0.05f)
            GeneralCommandType.VOLUME_DOWN -> setPlayerVolume(host.player.volume - 0.05f)
            GeneralCommandType.MUTE -> mutePlayer()
            GeneralCommandType.UNMUTE -> unmutePlayer()
            GeneralCommandType.TOGGLE_MUTE -> {
                if (host.player.volume <= 0.001f) unmutePlayer() else mutePlayer()
            }
            GeneralCommandType.SET_VOLUME -> parseRemoteVolume(message)?.let(::setPlayerVolume)
            GeneralCommandType.SET_AUDIO_STREAM_INDEX -> {
                pendingRemoteAudioStreamIndex =
                    parseRemoteIntArgument(message, "AudioStreamIndex", "StreamIndex", "Index")
                applyPendingRemoteStreamSelections()
            }
            GeneralCommandType.SET_SUBTITLE_STREAM_INDEX -> {
                pendingRemoteSubtitleStreamIndex =
                    parseRemoteIntArgument(message, "SubtitleStreamIndex", "StreamIndex", "Index")
                applyPendingRemoteStreamSelections()
            }
            GeneralCommandType.DISPLAY_MESSAGE -> {
                parseRemoteArgument(message, "Text", "Message", "DisplayMessage")?.let { text ->
                    Toast.makeText(application, text, Toast.LENGTH_LONG).show()
                }
            }
            GeneralCommandType.PLAY -> {
                if (!handleRemotePlayMediaSource(message)) {
                    applyRemoteSync { host.player.play() }
                }
            }
            GeneralCommandType.PLAY_NEXT -> host.skipToNextItem()
            GeneralCommandType.PLAY_STATE -> handleRemotePlayStateCommand(message)
            GeneralCommandType.PLAY_MEDIA_SOURCE -> {
                handleRemotePlayMediaSource(message)
            }
            else -> Unit
        }
    }

    private suspend fun seekToRemotePosition(positionTicks: Long) {
        val targetMs = positionTicks / 10_000L
        applyRemoteSync { host.player.seekTo(targetMs.coerceAtLeast(0L)) }
    }

    suspend fun ensureRemotePlaybackSessionReady(force: Boolean = false) {
        if (host.currentPlayerItem()?.contentSource != PlayerContentSource.JELLYFIN) return

        val deviceName = buildPreferredDeviceName()
        if (!force && remoteSessionConfigured && configuredDeviceName == deviceName) return

        runCatching { repository.postCapabilities() }
            .onSuccess { remoteSessionConfigured = true }
            .onFailure { Timber.w(it, "Failed to register Jellyfin remote-control capabilities") }

        runCatching { repository.updateDeviceName(deviceName) }
            .onSuccess { configuredDeviceName = deviceName }
            .onFailure { Timber.w(it, "Failed to update Jellyfin device name") }
    }

    private fun buildPreferredDeviceName(): String {
        val appName = application.applicationInfo.loadLabel(application.packageManager).toString().trim()
        val manufacturer = Build.MANUFACTURER?.trim().orEmpty()
        val model = Build.MODEL?.trim().orEmpty()
        val deviceLabel =
            buildList {
                if (manufacturer.isNotBlank()) add(manufacturer)
                if (model.isNotBlank() && !model.equals(manufacturer, ignoreCase = true)) add(model)
            }.joinToString(" ")

        return if (deviceLabel.isBlank()) appName else "$appName on $deviceLabel"
    }

    private fun setPlayerVolume(targetVolume: Float) {
        val clamped = targetVolume.coerceIn(0f, 1f)
        if (clamped > 0f) {
            lastNonMutedVolume = clamped
        }
        applyRemoteSync { host.player.volume = clamped }
    }

    private fun mutePlayer() {
        if (host.player.volume > 0f) {
            lastNonMutedVolume = host.player.volume
        }
        applyRemoteSync { host.player.volume = 0f }
    }

    private fun unmutePlayer() {
        setPlayerVolume(lastNonMutedVolume.takeIf { it > 0f } ?: 1f)
    }

    fun applyPendingRemoteStreamSelections() {
        pendingRemoteAudioStreamIndex?.let { streamIndex ->
            if (switchRemoteTrackByStreamIndex(C.TRACK_TYPE_AUDIO, streamIndex)) {
                pendingRemoteAudioStreamIndex = null
            }
        }

        pendingRemoteSubtitleStreamIndex?.let { streamIndex ->
            if (switchRemoteTrackByStreamIndex(C.TRACK_TYPE_TEXT, streamIndex)) {
                pendingRemoteSubtitleStreamIndex = null
            }
        }
    }

    private fun switchRemoteTrackByStreamIndex(
        trackType: @C.TrackType Int,
        streamIndex: Int,
    ): Boolean {
        if (trackType == C.TRACK_TYPE_TEXT && streamIndex < 0) {
            host.switchToTrack(trackType, -1)
            return true
        }

        val streamType =
            when (trackType) {
                C.TRACK_TYPE_AUDIO -> MediaStreamType.AUDIO
                C.TRACK_TYPE_TEXT -> MediaStreamType.SUBTITLE
                else -> return false
            }
        val candidateStreams = host.currentMediaSourceStreams.filter { it.type == streamType }
        if (candidateStreams.isEmpty()) return false

        val targetOrder = candidateStreams.indexOfFirst { it.index == streamIndex }
        val groups = host.player.currentTracks.groups.filter { it.type == trackType && it.isSupported }
        if (targetOrder !in groups.indices) {
            Timber.w(
                "Remote stream selection failed type=%d streamIndex=%d candidateStreams=%s groups=%d",
                trackType,
                streamIndex,
                candidateStreams.map(SpatialFinMediaStream::index),
                groups.size,
            )
            return false
        }

        host.switchToTrack(trackType, targetOrder)
        return true
    }

    private suspend fun handleRemotePlayMediaSource(message: GeneralCommandMessage): Boolean {
        val itemId = parseRemoteItemId(message) ?: return false
        val item =
            runCatching { repository.getItem(itemId) }
                .onFailure { Timber.w(it, "Failed to resolve remote play item %s", itemId) }
                .getOrNull() ?: return false
        val itemKind = item.toPlayerItemKind() ?: return false

        val requestedSourceId = parseRemoteArgument(message, "MediaSourceId", "SourceId")
        val sourceIndex =
            requestedSourceId?.let { sourceId ->
                runCatching { repository.getMediaSources(itemId, includePath = true) }
                    .getOrNull()
                    ?.indexOfFirst { source -> source.id == sourceId }
                    ?.takeIf { it >= 0 }
            }

        pendingRemoteAudioStreamIndex =
            parseRemoteIntArgument(message, "AudioStreamIndex", "AudioIndex")
        pendingRemoteSubtitleStreamIndex =
            parseRemoteIntArgument(message, "SubtitleStreamIndex", "SubtitleIndex")

        host.playbackPosition =
            (parseRemoteLongArgument(message, "StartPositionTicks", "PositionTicks")
                ?.div(10_000L)
                ?.coerceAtLeast(0L))
                ?: 0L

        applyRemoteSync {
            host.initializePlayer(
                itemId = itemId,
                itemKind = itemKind,
                startFromBeginning = host.playbackPosition <= 0L,
                mediaSourceIndex = sourceIndex,
                autoPlay = true,
            )
        }
        return true
    }

    private suspend fun handleRemotePlayStateCommand(message: GeneralCommandMessage) {
        val command =
            when (parseRemoteArgument(message, "Command", "PlayCommand", "PlaystateCommand")
                ?.trim()
                ?.lowercase()) {
                "pause" -> PlaystateCommand.PAUSE
                "unpause", "play" -> PlaystateCommand.UNPAUSE
                "playpause", "play_pause", "toggle" -> PlaystateCommand.PLAY_PAUSE
                "seek" -> PlaystateCommand.SEEK
                "stop" -> PlaystateCommand.STOP
                else -> null
            } ?: return

        val seekTicks = parseRemoteLongArgument(message, "SeekPositionTicks", "PositionTicks")
        handlePlayStateMessage(command, seekTicks)
    }

    private fun parseRemoteItemId(message: GeneralCommandMessage): UUID? {
        val raw =
            parseRemoteArgument(message, "ItemId", "ItemIds", "Ids")
                ?.split(',', ';', '|')
                ?.map(String::trim)
                ?.firstOrNull { it.isNotEmpty() }
                ?: return null
        return runCatching { UUID.fromString(raw) }
            .onFailure { Timber.w(it, "Ignoring invalid remote item id %s", raw) }
            .getOrNull()
    }

    private fun parseRemoteVolume(message: GeneralCommandMessage): Float? {
        val raw =
            parseRemoteArgument(message, "Volume", "Value", "Argument")
                ?: return null
        val numeric = raw.toFloatOrNull() ?: return null
        return if (numeric > 1f) numeric / 100f else numeric
    }

    private fun parseRemoteIntArgument(message: GeneralCommandMessage, vararg keys: String): Int? =
        parseRemoteArgument(message, *keys)?.toIntOrNull()

    private fun parseRemoteLongArgument(message: GeneralCommandMessage, vararg keys: String): Long? =
        parseRemoteArgument(message, *keys)?.toLongOrNull()

    private fun parseRemoteArgument(message: GeneralCommandMessage, vararg keys: String): String? {
        val arguments = message.data?.arguments ?: return null
        return keys.firstNotNullOfOrNull { key ->
            arguments.entries.firstOrNull { entry -> entry.key.equals(key, ignoreCase = true) }?.value
        }?.takeIf { it.isNotBlank() }
    }

    suspend fun handleSocketState(socketState: SocketApiState) {
        when (socketState) {
            is SocketApiState.Connected -> {
                val reconnected = !isSocketConnected
                isSocketConnected = true
                if (reconnected && host.currentPlayerItem()?.contentSource == PlayerContentSource.JELLYFIN) {
                    ensureRemotePlaybackSessionReady(force = true)
                }
                if (reconnected && isActive()) {
                    _state.update { it.copy(statusMessage = "SyncPlay reconnected") }
                    refreshGroups()
                }
            }
            is SocketApiState.Connecting -> {
                if (isActive()) {
                    _state.update { it.copy(statusMessage = "Reconnecting SyncPlay...") }
                }
            }
            is SocketApiState.Disconnected -> {
                isSocketConnected = false
                remoteSessionConfigured = false
                if (isActive()) {
                    _state.update { it.copy(statusMessage = "SyncPlay connection lost") }
                }
            }
        }
    }

    private suspend fun applySyncPlayQueueUpdate(
        queue: List<SyncPlayQueueItem>,
        playingItemIndex: Int,
        startPositionTicks: Long,
        shouldPlay: Boolean,
    ) {
        val targetQueueItem = queue.getOrNull(playingItemIndex) ?: return
        val resolvedItems =
            queue.mapNotNull { queueItem ->
                playlistManager.getPlayerItem(
                    itemId = queueItem.itemId,
                    playbackPosition = 0L,
                    playlistItemId = queueItem.playlistItemId,
                )
            }

        if (resolvedItems.isEmpty()) return

        val targetIndex =
            resolvedItems.indexOfFirst { it.playlistItemId == targetQueueItem.playlistItemId }
                .takeIf { it >= 0 } ?: return

        val mutableResolved = resolvedItems.toMutableList()
        host.replaceItems(mutableResolved)
        currentPlaylistItemId = targetQueueItem.playlistItemId
        host.updateNextEpisode(mutableResolved.getOrNull(targetIndex + 1))

        val targetPositionMs = startPositionTicks / 10_000L
        val queueMatches =
            host.player.mediaItemCount == mutableResolved.size &&
                mutableResolved.indices.all { index ->
                    host.player.getMediaItemAt(index).mediaId == mutableResolved[index].itemId.toString()
                }

        if (!queueMatches) {
            applyRemoteSync {
                host.player.setMediaItems(
                    mutableResolved.map { host.toMediaItem(it) },
                    targetIndex,
                    targetPositionMs,
                )
                host.player.prepare()
                if (shouldPlay) host.player.play() else host.player.pause()
            }
            return
        }

        applyRemoteSync {
            if (host.player.currentMediaItemIndex != targetIndex) {
                host.player.seekTo(targetIndex, targetPositionMs)
            } else if (kotlin.math.abs(host.player.currentPosition - targetPositionMs) > 1_500L) {
                host.player.seekTo(targetPositionMs)
            }

            if (shouldPlay) host.player.play() else host.player.pause()
        }
    }
}

private fun SpatialFinItem.toPlayerItemKind(): String? =
    when (this) {
        is SpatialFinMovie -> BaseItemKind.MOVIE.serialName
        is SpatialFinEpisode -> BaseItemKind.EPISODE.serialName
        is SpatialFinSeason -> BaseItemKind.SEASON.serialName
        is SpatialFinShow -> BaseItemKind.SERIES.serialName
        else -> null
    }
