package dev.jdtech.jellyfin.models.companion

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Process-wide bridge between a form-factor shell that is showing a pairing prompt and
 * whatever surface can confirm it — today the Wear companion.
 *
 * Lives in `:core` because the producer (`:shell:tv`) and the consumer
 * (`:companion:host`) have no dependency edge between them, and neither should grow one.
 * Mirrors the `ActivePlayerSessionHolder` idiom used for the live player session.
 */
object CompanionPairingOffers {

    /** A pairing prompt currently on screen, mirrored to any confirmation surface. */
    data class PairingOffer(
        val deviceName: String,
        val pairingToken: String,
        val manualCode: String,
        val receiverUrl: String,
        val expiresAtEpochMs: Long,
    )

    /** A confirmation surface's answer to a [PairingOffer]. */
    data class PairingDecision(
        val pairingToken: String,
        val approved: Boolean,
    )

    private val _offers = MutableSharedFlow<PairingOffer>(replay = 1, extraBufferCapacity = 4)
    val offers: SharedFlow<PairingOffer> = _offers.asSharedFlow()

    private val _decisions = MutableSharedFlow<PairingDecision>(extraBufferCapacity = 8)
    val decisions: SharedFlow<PairingDecision> = _decisions.asSharedFlow()

    /** Called by the shell when it starts showing a pairing code. */
    fun offer(offer: PairingOffer) {
        _offers.tryEmit(offer)
    }

    /** Called by a confirmation surface when the user approves or rejects. */
    fun decide(decision: PairingDecision) {
        _decisions.tryEmit(decision)
    }

    /** Called by the shell when the pairing window closes, so stale prompts are dropped. */
    fun clear() {
        _offers.resetReplayCache()
    }
}
