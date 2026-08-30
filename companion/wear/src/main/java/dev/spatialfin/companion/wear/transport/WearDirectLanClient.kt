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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WearDirectLanClient @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeClient: FCastSenderClient? = null
    private var discoveryJob: Job? = null

    private val _discoveredReceivers = MutableStateFlow<List<FCastReceiver>>(emptyList())
    val discoveredReceivers: StateFlow<List<FCastReceiver>> = _discoveredReceivers.asStateFlow()

    private val _connectedReceiver = MutableStateFlow<FCastReceiver?>(null)
    val connectedReceiver: StateFlow<FCastReceiver?> = _connectedReceiver.asStateFlow()

    private val _lanPlaybackState = MutableStateFlow<WearNowPlayingState?>(null)
    val lanPlaybackState: StateFlow<WearNowPlayingState?> = _lanPlaybackState.asStateFlow()

    fun startDiscovery() {
        if (discoveryJob != null) return
        discoveryJob = scope.launch {
            Timber.i("WearDirectLanClient: starting mDNS discovery for _fcast._tcp")
            val discovery = FCastDiscovery(context)
            runCatching {
                val results = discovery.browse(timeoutMs = 4000)
                _discoveredReceivers.value = results
                Timber.i("WearDirectLanClient: discovered %d FCast receivers", results.size)
            }.onFailure {
                Timber.w(it, "WearDirectLanClient: discovery failed")
            }
        }
    }

    fun stopDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = null
    }

    suspend fun connectToReceiver(receiver: FCastReceiver): Boolean {
        return runCatching {
            activeClient?.close()

            val client = FCastSenderClient(
                receiver = receiver,
                parentScope = scope,
                senderInfo = InitialSenderMessage(
                    displayName = "SpatialFin Watch",
                    appName = "SpatialFin",
                ),
            )
            client.connect()
            activeClient = client
            _connectedReceiver.value = receiver

            scope.launch {
                client.playbackUpdates.collect { update ->
                    val isPlaying = update.state == 1 // 1 = Playing
                    val currentPos = (update.time ?: 0.0).toLong()
                    val duration = (update.duration ?: 0.0).toLong()
                    _lanPlaybackState.value = WearNowPlayingState(
                        isPlaying = isPlaying,
                        positionSeconds = currentPos,
                        durationSeconds = duration,
                        speed = (update.speed ?: 1.0).toFloat(),
                        targetDeviceName = receiver.name,
                    )
                }
            }

            Timber.i("WearDirectLanClient: connected to %s (%s:%d)", receiver.name, receiver.host, receiver.port)
            true
        }.onFailure {
            Timber.w(it, "WearDirectLanClient: failed to connect to receiver %s", receiver.name)
            _connectedReceiver.value = null
        }.getOrDefault(false)
    }

    /**
     * Fling the stream the watch is currently showing onto a discovered receiver.
     *
     * The URL comes from the host's own now-playing state, so this works whenever the
     * watch can see one — tethered or on the LAN — and needs no Jellyfin credentials.
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
        client.play(
            PlayMessageBuilder.build(
                url = streamUrl,
                // Receivers sniff the container when the sender can't name it.
                container = container ?: "application/octet-stream",
                positionSeconds = positionSeconds,
                title = title,
            ),
        )
        Timber.i("WearDirectLanClient: flung '%s' to %s", title, receiver.name)
        "Casting to ${receiver.name}"
    }.onFailure {
        Timber.w(it, "WearDirectLanClient: cast to %s failed", receiver.name)
    }

    fun disconnect() {
        activeClient?.close()
        activeClient = null
        _connectedReceiver.value = null
        _lanPlaybackState.value = null
    }

    suspend fun dispatch(action: WearPlayerAction): Result<String> {
        val client = activeClient ?: return Result.failure(IllegalStateException("No LAN receiver connected"))
        return runCatching {
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
                    val pct = action.percentage
                    if (pct != null) {
                        client.setVolume(pct.toDouble())
                    }
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
                    "Action not supported in standalone LAN mode"
                }
            }
        }
    }
}
