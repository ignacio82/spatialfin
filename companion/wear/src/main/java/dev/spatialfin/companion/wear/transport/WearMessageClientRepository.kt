package dev.spatialfin.companion.wear.transport

import android.content.Context
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.spatialfin.companion.protocol.WearPlayerAction
import dev.spatialfin.companion.protocol.WearProtocolCodec
import dev.spatialfin.companion.protocol.WearProtocolPaths
import kotlinx.coroutines.tasks.await
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WearMessageClientRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    suspend fun getConnectedHostNode(): Node? {
        return runCatching {
            val capabilityInfo = Wearable.getCapabilityClient(context)
                .getCapability(WearProtocolPaths.CAPABILITY_HOST, CapabilityClient.FILTER_REACHABLE)
                .await()
            val node = capabilityInfo.nodes.firstOrNull()
            if (node != null) return@runCatching node

            // Fallback to any connected node
            val allNodes = Wearable.getNodeClient(context).connectedNodes.await()
            allNodes.firstOrNull()
        }.getOrNull()
    }

    suspend fun sendAction(action: WearPlayerAction, targetNodeId: String? = null): Result<String> {
        return runCatching {
            val nodeId = targetNodeId ?: getConnectedHostNode()?.id
                ?: throw IllegalStateException("No host device connected via Data Layer")

            val payload = WearProtocolCodec.encodeAction(action)
            Timber.d("WearMessageClientRepository: sending %s to node %s", action, nodeId)

            Wearable.getMessageClient(context)
                .sendMessage(nodeId, WearProtocolPaths.PATH_ACTION, payload)
                .await()

            "Action sent"
        }.onFailure {
            Timber.w(it, "WearMessageClientRepository: failed to send action %s", action)
        }
    }
}
