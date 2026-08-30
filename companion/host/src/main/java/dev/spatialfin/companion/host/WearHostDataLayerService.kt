package dev.spatialfin.companion.host

import android.content.Intent
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import dagger.hilt.android.AndroidEntryPoint
import dev.jdtech.jellyfin.player.session.voice.ActivePlayerSessionHolder
import dev.spatialfin.companion.protocol.WearProtocolCodec
import dev.spatialfin.companion.protocol.WearProtocolPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class WearHostDataLayerService : WearableListenerService() {

    @Inject
    lateinit var statePublisher: WearStatePublisher

    @Inject
    lateinit var credentialPusher: WearCredentialPusher

    @Inject
    lateinit var pairingBroker: WearTvPairingBroker

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        Timber.i("WearHostDataLayerService: created")
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
        Timber.i("WearHostDataLayerService: destroyed")
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        val path = messageEvent.path
        val data = messageEvent.data
        val sourceNodeId = messageEvent.sourceNodeId

        Timber.d("WearHostDataLayerService: message received on path %s from %s", path, sourceNodeId)

        when (path) {
            WearProtocolPaths.PATH_ACTION -> {
                serviceScope.launch {
                    val action = WearProtocolCodec.decodeAction(data)
                    Timber.d("WearHostDataLayerService: executing action %s", action)
                    val resultFeedback = ActivePlayerSessionHolder.dispatch(action)
                    Timber.d("WearHostDataLayerService: action result: %s", resultFeedback)
                    respondTo(sourceNodeId, resultFeedback)
                }
            }

            WearProtocolPaths.PATH_VOICE_QUERY -> {
                serviceScope.launch {
                    val query = runCatching { WearProtocolCodec.decodeVoiceQuery(data) }.getOrNull()
                    if (query == null) {
                        Timber.w("WearHostDataLayerService: undecodable voice query from %s", sourceNodeId)
                        return@launch
                    }
                    Timber.i("WearHostDataLayerService: wrist voice command '%s'", query.transcript)
                    val feedback = ActivePlayerSessionHolder.dispatchVoiceCommand(query.transcript)
                    respondTo(sourceNodeId, feedback)
                }
            }

            WearProtocolPaths.PATH_PAIRING_APPROVE -> {
                serviceScope.launch {
                    val approval = WearProtocolCodec.decodePairingApproval(data)
                    Timber.i("WearHostDataLayerService: received TV pairing approval: %s", approval)
                    pairingBroker.handleApproval(approval)
                }
            }

            else -> {
                super.onMessageReceived(messageEvent)
            }
        }
    }

    private suspend fun respondTo(nodeId: String, feedback: String) {
        runCatching {
            Wearable.getMessageClient(this@WearHostDataLayerService)
                .sendMessage(nodeId, WearProtocolPaths.PATH_ACTION_RESPONSE, feedback.encodeToByteArray())
                .await()
        }.onFailure {
            Timber.w(it, "WearHostDataLayerService: failed to send response to %s", nodeId)
        }
    }

    override fun onPeerConnected(peer: Node) {
        super.onPeerConnected(peer)
        Timber.i("WearHostDataLayerService: peer connected: %s (%s)", peer.displayName, peer.id)
        statePublisher.onPeerPresenceChanged()
        serviceScope.launch {
            statePublisher.publishNowPlaying()
            statePublisher.publishVitals()
            statePublisher.publishNextUp()
            credentialPusher.pushCredentials()
        }
    }

    override fun onPeerDisconnected(peer: Node) {
        super.onPeerDisconnected(peer)
        Timber.i("WearHostDataLayerService: peer disconnected: %s (%s)", peer.displayName, peer.id)
        statePublisher.onPeerPresenceChanged()
    }
}
