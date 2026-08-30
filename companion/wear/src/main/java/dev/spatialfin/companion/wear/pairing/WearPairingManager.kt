package dev.spatialfin.companion.wear.pairing

import android.content.Context
import com.google.android.gms.wearable.Wearable
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.spatialfin.companion.protocol.WearProtocolCodec
import dev.spatialfin.companion.protocol.WearProtocolPaths
import dev.spatialfin.companion.protocol.WearTvPairingApproval
import dev.spatialfin.companion.protocol.WearTvPairingRequest
import dev.spatialfin.companion.wear.transport.WearMessageClientRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WearPairingManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val messageClientRepo: WearMessageClientRepository,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _pendingPairingRequest = MutableStateFlow<WearTvPairingRequest?>(null)
    val pendingPairingRequest: StateFlow<WearTvPairingRequest?> = _pendingPairingRequest.asStateFlow()

    fun offerPairingRequest(request: WearTvPairingRequest) {
        if (System.currentTimeMillis() > request.expiresAtEpochMs) {
            Timber.w("WearPairingManager: ignoring expired pairing request for %s", request.deviceName)
            return
        }
        Timber.i("WearPairingManager: received pairing request from %s (code: %s)", request.deviceName, request.manualCode)
        _pendingPairingRequest.value = request
    }

    fun approvePairing(request: WearTvPairingRequest) {
        scope.launch {
            sendApproval(request.pairingToken, approved = true)
            _pendingPairingRequest.value = null
        }
    }

    fun rejectPairing(request: WearTvPairingRequest) {
        scope.launch {
            sendApproval(request.pairingToken, approved = false)
            _pendingPairingRequest.value = null
        }
    }

    private suspend fun sendApproval(pairingToken: String, approved: Boolean) {
        val approval = WearTvPairingApproval(
            pairingToken = pairingToken,
            approved = approved,
        )
        val payload = WearProtocolCodec.encodePairingApproval(approval)
        val node = messageClientRepo.getConnectedHostNode()
        if (node != null) {
            runCatching {
                Wearable.getMessageClient(context)
                    .sendMessage(node.id, WearProtocolPaths.PATH_PAIRING_APPROVE, payload)
                    .await()
                Timber.i("WearPairingManager: sent pairing response (approved=%b) to %s", approved, node.displayName)
            }.onFailure {
                Timber.w(it, "WearPairingManager: failed to send pairing approval")
            }
        }
    }
}
