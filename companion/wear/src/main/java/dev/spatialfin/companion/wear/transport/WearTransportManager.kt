package dev.spatialfin.companion.wear.transport

import android.content.Context
import android.graphics.Bitmap
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.spatialfin.companion.protocol.WearNextUpState
import dev.spatialfin.companion.protocol.WearNowPlayingState
import dev.spatialfin.companion.protocol.WearPlayerAction
import dev.spatialfin.companion.protocol.WearProtocolPaths
import dev.spatialfin.companion.protocol.WearVitalsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

sealed interface TransportState {
    data class ConnectedViaDataLayer(val nodeId: String, val deviceName: String) : TransportState
    data class ConnectedViaFCastLan(val host: String, val port: Int, val deviceName: String) : TransportState
    data class ConnectedViaJellyfinRelay(val serverUrl: String, val sessionId: String, val deviceName: String) : TransportState
    data object Disconnected : TransportState
}

@Singleton
class WearTransportManager @Inject constructor(
    @ApplicationContext private val context: Context,
    val dataClientRepo: WearDataClientRepository,
    val messageClientRepo: WearMessageClientRepository,
    val directLanClient: WearDirectLanClient,
    val credentialsStore: WearCredentialsStore,
    val relayClient: WearJellyfinRelayClient,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _transportState = MutableStateFlow<TransportState>(TransportState.Disconnected)
    val transportState: StateFlow<TransportState> = _transportState.asStateFlow()

    private val _relayPlaybackState = MutableStateFlow<WearNowPlayingState?>(null)

    val nowPlaying: StateFlow<WearNowPlayingState?> =
        combine(
            _transportState,
            dataClientRepo.nowPlayingState,
            directLanClient.lanPlaybackState,
            _relayPlaybackState,
        ) { transport, dataLayerState, lanState, relayState ->
            when (transport) {
                is TransportState.ConnectedViaDataLayer -> dataLayerState
                is TransportState.ConnectedViaFCastLan -> lanState
                is TransportState.ConnectedViaJellyfinRelay -> relayState
                TransportState.Disconnected -> dataLayerState ?: lanState ?: relayState
            }
        }.stateIn(scope, SharingStarted.Eagerly, null)

    val vitals: StateFlow<WearVitalsState?> = dataClientRepo.vitalsState
    val nextUp: StateFlow<WearNextUpState?> = dataClientRepo.nextUpState
    val coverArt: StateFlow<Bitmap?> = dataClientRepo.coverArtBitmap

    init {
        dataClientRepo.startListening()
        checkConnectivity()
        observeCapabilities()
    }

    /**
     * Failover order: the tethered Data Layer first, then a direct FCast socket (which
     * needs no credentials at all), then the Jellyfin server relay (which does). Falls
     * back to Disconnected only when all three are unavailable.
     */
    fun checkConnectivity() {
        scope.launch {
            val node = messageClientRepo.getConnectedHostNode()
            if (node != null) {
                _transportState.value = TransportState.ConnectedViaDataLayer(node.id, node.displayName)
                Timber.i("WearTransportManager: connected via Data Layer to %s", node.displayName)
                return@launch
            }

            val receiver = directLanClient.connectedReceiver.value
            if (receiver != null) {
                _transportState.value =
                    TransportState.ConnectedViaFCastLan(receiver.host, receiver.port, receiver.name)
                Timber.i("WearTransportManager: connected via direct FCast LAN to %s", receiver.name)
                return@launch
            }

            val relaySession = relayClient.listControllableSessions().firstOrNull()
            if (relaySession != null) {
                _relayPlaybackState.value = relayClient.nowPlayingFrom(relaySession)
                _transportState.value = TransportState.ConnectedViaJellyfinRelay(
                    serverUrl = credentialsStore.getCredentials()?.serverUrl.orEmpty(),
                    sessionId = relaySession.sessionId,
                    deviceName = relaySession.deviceName,
                )
                Timber.i("WearTransportManager: connected via Jellyfin relay to %s", relaySession.deviceName)
                return@launch
            }

            _relayPlaybackState.value = null
            _transportState.value = TransportState.Disconnected
            Timber.d("WearTransportManager: disconnected")
        }
    }

    /** Refresh relay-sourced playback state; the relay has no push channel to the watch. */
    fun refreshRelayState() {
        val state = _transportState.value as? TransportState.ConnectedViaJellyfinRelay ?: return
        scope.launch {
            val session = relayClient.listControllableSessions()
                .firstOrNull { it.sessionId == state.sessionId }
            if (session != null) _relayPlaybackState.value = relayClient.nowPlayingFrom(session)
        }
    }

    private fun observeCapabilities() {
        Wearable.getCapabilityClient(context).addListener(
            { capabilityInfo ->
                val reachableNode = capabilityInfo.nodes.firstOrNull()
                if (reachableNode != null) {
                    _transportState.value = TransportState.ConnectedViaDataLayer(
                        reachableNode.id,
                        reachableNode.displayName,
                    )
                } else if (_transportState.value is TransportState.ConnectedViaDataLayer) {
                    checkConnectivity()
                }
            },
            WearProtocolPaths.CAPABILITY_HOST,
        )
    }

    suspend fun dispatchAction(action: WearPlayerAction): Result<String> {
        return when (val state = _transportState.value) {
            is TransportState.ConnectedViaDataLayer -> {
                messageClientRepo.sendAction(action, state.nodeId)
            }
            is TransportState.ConnectedViaFCastLan -> {
                directLanClient.dispatch(action)
            }
            is TransportState.ConnectedViaJellyfinRelay -> {
                relayClient.dispatch(state.sessionId, action).also { refreshRelayState() }
            }
            TransportState.Disconnected -> {
                // One last attempt in case a peer appeared since the last state check.
                messageClientRepo.sendAction(action).onSuccess { checkConnectivity() }
            }
        }
    }
}
