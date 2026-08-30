package dev.spatialfin.companion.wear.transport

import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.WearableListenerService
import dagger.hilt.android.AndroidEntryPoint
import dev.spatialfin.companion.protocol.WearProtocolCodec
import dev.spatialfin.companion.protocol.WearProtocolPaths
import dev.spatialfin.companion.wear.pairing.WearPairingManager
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class WearDataLayerListenerService : WearableListenerService() {

    @Inject
    lateinit var transportManager: WearTransportManager

    @Inject
    lateinit var pairingManager: WearPairingManager

    @Inject
    lateinit var dataClientRepository: WearDataClientRepository

    /**
     * Data items also arrive here when no activity is alive — which is the normal case
     * for a tile or complication redraw. Without this override the repository's own
     * `DataClient` listener (registered only once something constructs the transport
     * manager) would be the sole path, and background state would never reach the
     * watch face.
     */
    override fun onDataChanged(dataEvents: DataEventBuffer) {
        dataClientRepository.onDataChanged(dataEvents)
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        val path = messageEvent.path
        val data = messageEvent.data

        Timber.d("WearDataLayerListenerService: message received on path %s", path)

        when (path) {
            WearProtocolPaths.PATH_PAIRING_REQUEST -> {
                val request = runCatching { WearProtocolCodec.decodePairingRequest(data) }.getOrNull()
                if (request != null) {
                    pairingManager.offerPairingRequest(request)
                }
            }
            else -> {
                super.onMessageReceived(messageEvent)
            }
        }
    }

    override fun onPeerConnected(peer: Node) {
        super.onPeerConnected(peer)
        Timber.i("WearDataLayerListenerService: peer connected: %s (%s)", peer.displayName, peer.id)
        transportManager.checkConnectivity()
    }

    override fun onPeerDisconnected(peer: Node) {
        super.onPeerDisconnected(peer)
        Timber.i("WearDataLayerListenerService: peer disconnected: %s (%s)", peer.displayName, peer.id)
        transportManager.checkConnectivity()
    }
}
