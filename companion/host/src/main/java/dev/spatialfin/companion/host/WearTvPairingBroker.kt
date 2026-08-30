package dev.spatialfin.companion.host

import android.content.Context
import com.google.android.gms.wearable.Wearable
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jdtech.jellyfin.models.companion.CompanionPairingOffers
import dev.spatialfin.companion.protocol.WearProtocolCodec
import dev.spatialfin.companion.protocol.WearProtocolPaths
import dev.spatialfin.companion.protocol.WearTvPairingApproval
import dev.spatialfin.companion.protocol.WearTvPairingRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mirrors on-screen pairing prompts to the watch and feeds the answer back.
 *
 * A watch that can approve a pairing is a second factor *and* a second attack surface,
 * so: offers expire (the watch drops anything already past `expiresAtEpochMs`), the
 * prompt always names the requesting device, and nothing is ever auto-granted — a
 * missing answer is not an approval.
 */
@Singleton
class WearTvPairingBroker @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var observationJob: Job? = null

    fun startObserving() {
        if (observationJob != null) return
        observationJob = scope.launch {
            CompanionPairingOffers.offers.collect { offer ->
                broadcastPairingRequest(
                    WearTvPairingRequest(
                        deviceName = offer.deviceName,
                        pairingToken = offer.pairingToken,
                        manualCode = offer.manualCode,
                        receiverUrl = offer.receiverUrl,
                        expiresAtEpochMs = offer.expiresAtEpochMs,
                    ),
                )
            }
        }
    }

    /** Called by [WearHostDataLayerService] when the watch answers. */
    fun handleApproval(approval: WearTvPairingApproval) {
        Timber.i(
            "WearTvPairingBroker: watch %s pairing token %s",
            if (approval.approved) "approved" else "rejected",
            approval.pairingToken.take(6),
        )
        CompanionPairingOffers.decide(
            CompanionPairingOffers.PairingDecision(
                pairingToken = approval.pairingToken,
                approved = approval.approved,
            ),
        )
    }

    private suspend fun broadcastPairingRequest(request: WearTvPairingRequest) {
        Timber.i("WearTvPairingBroker: broadcasting pairing request for %s", request.deviceName)
        val payload = WearProtocolCodec.encodePairingRequest(request)
        val nodes = runCatching {
            Wearable.getNodeClient(context).connectedNodes.await()
        }.getOrDefault(emptyList())

        for (node in nodes) {
            runCatching {
                Wearable.getMessageClient(context)
                    .sendMessage(node.id, WearProtocolPaths.PATH_PAIRING_REQUEST, payload)
                    .await()
                Timber.d("WearTvPairingBroker: sent pairing request to node %s", node.displayName)
            }.onFailure {
                Timber.w(it, "WearTvPairingBroker: failed to send pairing request to node %s", node.id)
            }
        }
    }
}
