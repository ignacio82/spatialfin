package dev.spatialfin.companion.wear.transport

import android.content.Context
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.spatialfin.companion.protocol.WearCommandRequest
import dev.spatialfin.companion.protocol.WearCommandResponse
import dev.spatialfin.companion.protocol.WearPlayerAction
import dev.spatialfin.companion.protocol.WearProtocolCodec
import dev.spatialfin.companion.protocol.WearProtocolPaths
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout

@Singleton
class WearMessageClientRepository
@Inject
constructor(@ApplicationContext private val context: Context) {

    suspend fun getConnectedHostNode(): Node? {
        return try {
            kotlinx.coroutines.withTimeoutOrNull(1_500) {
                val capabilityInfo =
                    Wearable.getCapabilityClient(context)
                        .getCapability(
                            WearProtocolPaths.CAPABILITY_HOST,
                            CapabilityClient.FILTER_REACHABLE,
                        )
                        .await()
                capabilityInfo.nodes.firstOrNull { it.isNearby }
                    ?: capabilityInfo.nodes.firstOrNull()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
    }

    suspend fun sendAction(action: WearPlayerAction, targetNodeId: String? = null): Result<String> {
        return sendRequest(
            WearCommandRequest(UUID.randomUUID().toString(), action = action),
            targetNodeId,
        )
    }

    suspend fun sendVoice(transcript: String, nodeId: String): Result<String> =
        sendRequest(
            WearCommandRequest(UUID.randomUUID().toString(), transcript = transcript),
            nodeId,
        )

    suspend fun requestCredentialRefresh(): Result<String> = runCatching {
        val node = getConnectedHostNode() ?: error("Open SpatialFin on your paired phone")
        Wearable.getMessageClient(context)
            .sendMessage(node.id, WearProtocolPaths.PATH_CREDENTIAL_REFRESH, byteArrayOf())
            .await()
        "Requested account sync from ${node.displayName}"
    }
        .onFailure { if (it is CancellationException) throw it }

    private suspend fun sendRequest(
        request: WearCommandRequest,
        targetNodeId: String?,
    ): Result<String> {
        val client = Wearable.getMessageClient(context)
        val response = CompletableDeferred<WearCommandResponse>()
        val nodeId =
            targetNodeId
                ?: getConnectedHostNode()?.id
                ?: return Result.failure(
                    IllegalStateException("No paired SpatialFin host connected")
                )
        val listener = MessageClient.OnMessageReceivedListener { event ->
            if (
                event.sourceNodeId == nodeId && event.path == WearProtocolPaths.PATH_ACTION_RESPONSE
            ) {
                val reply = runCatching {
                    WearProtocolCodec.json.decodeFromString(
                        WearCommandResponse.serializer(),
                        event.data.decodeToString(),
                    )
                }
                    .getOrNull()
                if (reply?.requestId == request.requestId) response.complete(reply)
            }
        }
        return try {
            withTimeout(15_000) {
                client.addListener(listener).await()
                val payload =
                    WearProtocolCodec.json.encodeToString(WearCommandRequest.serializer(), request)
                client
                    .sendMessage(
                        nodeId,
                        WearProtocolPaths.PATH_COMMAND_REQUEST,
                        payload.encodeToByteArray(),
                    )
                    .await()
                val reply = response.await()
                if (reply.successful) Result.success(reply.message)
                else Result.failure(IllegalStateException(reply.message))
            }
        } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
            Result.failure(
                IllegalStateException(
                    "No response from the phone. Check its connection and update SpatialFin."
                )
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        } finally {
            client.removeListener(listener)
        }
    }
}
