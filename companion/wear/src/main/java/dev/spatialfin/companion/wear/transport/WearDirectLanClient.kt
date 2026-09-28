package dev.spatialfin.companion.wear.transport

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jdtech.jellyfin.fcast.discovery.FCastDiscovery
import dev.jdtech.jellyfin.fcast.protocol.InitialSenderMessage
import dev.jdtech.jellyfin.fcast.sender.FCastReceiver
import dev.jdtech.jellyfin.fcast.sender.FCastSenderClient
import dev.jdtech.jellyfin.fcast.sender.PlayMessageBuilder
import dev.spatialfin.companion.protocol.WearNowPlayingState
import dev.spatialfin.companion.protocol.WearPlayerAction
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

@Singleton
class WearDirectLanClient
internal constructor(
    private val scope: CoroutineScope,
    private val discover: suspend () -> List<FCastReceiver>,
    private val createClient: (FCastReceiver, CoroutineScope) -> FCastSenderClient,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context
    ) : this(
        CoroutineScope(SupervisorJob() + Dispatchers.IO),
        { FCastDiscovery(context).browse(timeoutMs = 4_000) },
        { receiver, connectionScope ->
            FCastSenderClient(
                receiver = receiver,
                parentScope = connectionScope,
                senderInfo =
                    InitialSenderMessage(displayName = "SpatialFin Watch", appName = "SpatialFin"),
            )
        },
    )

    private var activeClient: FCastSenderClient? = null
    private var discoveryJob: Job? = null
    private var connectionJob: Job? = null
    private val connectionMutex = Mutex()
    private var volume: Float? = null
    private var castTitle = "Cast playback"

    private val _discoveredReceivers = MutableStateFlow<List<FCastReceiver>>(emptyList())
    val discoveredReceivers: StateFlow<List<FCastReceiver>> = _discoveredReceivers.asStateFlow()

    private val _connectedReceiver = MutableStateFlow<FCastReceiver?>(null)
    val connectedReceiver: StateFlow<FCastReceiver?> = _connectedReceiver.asStateFlow()

    private val _lanPlaybackState = MutableStateFlow<WearNowPlayingState?>(null)
    val lanPlaybackState: StateFlow<WearNowPlayingState?> = _lanPlaybackState.asStateFlow()

    fun startDiscovery() {
        if (discoveryJob?.isActive == true) return
        discoveryJob = scope.launch {
            Timber.i("WearDirectLanClient: starting mDNS discovery for _fcast._tcp")
            runCatching {
                val results = discover()
                _discoveredReceivers.value = results
                Timber.i("WearDirectLanClient: discovered %d FCast receivers", results.size)
            }
                .onFailure {
                    if (it is CancellationException) throw it
                    Timber.w("Wear receiver discovery failed")
                }
        }
    }

    fun stopDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = null
    }

    suspend fun connectToReceiver(receiver: FCastReceiver): Boolean = connectionMutex.withLock {
        disconnect()
        val job = SupervisorJob(scope.coroutineContext[Job])
        connectionJob = job
        val connectionScope = CoroutineScope(scope.coroutineContext + job)
        val client = createClient(receiver, connectionScope)
        activeClient = client
        try {
            client.connect()
            _connectedReceiver.value = receiver
            connectionScope.launch {
                client.playbackUpdates.collect { update ->
                    if (activeClient !== client) return@collect
                    _lanPlaybackState.value =
                        if (update.state == 0) null
                        else
                            WearNowPlayingState(
                                title = castTitle,
                                isPlaying = update.state == 1,
                                positionSeconds = (update.time ?: 0.0).toLong(),
                                durationSeconds = (update.duration ?: 0.0).toLong(),
                                speed = (update.speed ?: 1.0).toFloat(),
                                volume = volume ?: 1f,
                                targetDeviceName = receiver.name,
                                timestampEpochMs = System.currentTimeMillis(),
                            )
                }
            }
            connectionScope.launch {
                client.volumeUpdates.collect { update ->
                    if (activeClient === client) {
                        volume = update.volume.toFloat()
                        _lanPlaybackState.value =
                            _lanPlaybackState.value?.copy(volume = update.volume.toFloat())
                    }
                }
            }
            connectionScope.launch {
                client.state.collect { state ->
                    if (
                        activeClient === client &&
                            (state == FCastSenderClient.State.Disconnected ||
                                state == FCastSenderClient.State.Failed)
                    )
                        disconnect()
                }
            }
            connectionScope.launch {
                client.errors.collect {
                    // Receiver errors invalidate the cast session. Never report a native player as
                    // controlled.
                    if (activeClient === client) disconnect()
                }
            }
            true
        } catch (error: Exception) {
            if (activeClient === client) disconnect()
            if (error is CancellationException) throw error
            false
        }
    }

    /**
     * Fling the stream the watch is currently showing onto a discovered receiver.
     *
     * The URL comes from the host's own now-playing state, so this works whenever the watch can see
     * one — tethered or on the LAN — and needs no Jellyfin credentials.
     */
    suspend fun castStream(
        receiver: FCastReceiver,
        streamUrl: String,
        container: String?,
        title: String,
        positionSeconds: Double,
    ): Result<String> = runCatching {
        if (!connectToReceiver(receiver)) error("Could not reach ${receiver.name}")
        val client = activeClient ?: error("No sender client")
        castTitle = title.ifBlank { "Cast playback" }
        client.play(
            PlayMessageBuilder.build(
                url = streamUrl,
                // Receivers sniff the container when the sender can't name it.
                container = container ?: "application/octet-stream",
                positionSeconds = positionSeconds,
                title = title,
            )
        )
        Timber.i("WearDirectLanClient: flung '%s' to %s", title, receiver.name)
        "Casting to ${receiver.name}"
    }
        .onFailure {
            if (it is CancellationException) throw it
            Timber.w("Wear cast request failed")
        }

    fun disconnect() {
        val oldClient = activeClient
        activeClient = null
        oldClient?.close()
        connectionJob?.cancel()
        connectionJob = null
        volume = null
        castTitle = "Cast playback"
        _connectedReceiver.value = null
        _lanPlaybackState.value = null
    }

    suspend fun dispatch(action: WearPlayerAction): Result<String> {
        val client =
            activeClient
                ?: return Result.failure(IllegalStateException("No LAN receiver connected"))
        return runCatching {
            check(_lanPlaybackState.value != null) {
                "No cast playback. Start a cast stream or select the device under Jellyfin players to control its library playback."
            }
            when (action) {
                is WearPlayerAction.Play -> {
                    client.resume()
                    "Playing"
                }
                is WearPlayerAction.TogglePlayPause -> {
                    if (_lanPlaybackState.value?.isPlaying == true) {
                        client.pause()
                        "Paused"
                    } else {
                        client.resume()
                        "Playing"
                    }
                }
                is WearPlayerAction.Pause -> {
                    client.pause()
                    "Paused"
                }
                is WearPlayerAction.SeekTo -> {
                    client.seek(action.positionSeconds.toDouble())
                    "Seeked to ${action.positionSeconds}s"
                }
                is WearPlayerAction.SeekForward -> {
                    val cur = _lanPlaybackState.value?.positionSeconds ?: 0L
                    client.seek((cur + action.seconds).toDouble())
                    "Skipped forward"
                }
                is WearPlayerAction.SeekBackward -> {
                    val cur = _lanPlaybackState.value?.positionSeconds ?: 0L
                    client.seek((cur - action.seconds).coerceAtLeast(0L).toDouble())
                    "Rewound"
                }
                is WearPlayerAction.AdjustVolume -> {
                    val next =
                        action.percentage
                            ?: volume?.let { it + (action.delta ?: 0f) }
                            ?: error("Receiver has not reported its volume")
                    client.setVolume(next.coerceIn(0f, 1f).toDouble())
                    "Volume updated"
                }
                is WearPlayerAction.SetSpeed -> {
                    client.setSpeed(action.speed.toDouble())
                    "Speed ${action.speed}x"
                }
                is WearPlayerAction.StopFCastCasting -> {
                    client.stop()
                    "Stopped"
                }
                else -> {
                    error("This action is unavailable for cast playback")
                }
            }
        }
            .onFailure { if (it is CancellationException) throw it }
    }
}
