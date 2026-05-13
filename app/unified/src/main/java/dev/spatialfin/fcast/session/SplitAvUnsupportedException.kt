package dev.spatialfin.fcast.session

import dev.jdtech.jellyfin.cast.CastReceiver

/**
 * Thrown / wrapped in a [Result.failure] when a SplitAv entry point is called with a receiver
 * that doesn't carry [dev.jdtech.jellyfin.cast.CastCapability.SplitAv]. The session manager
 * surfaces this to the UI as the "Audio split isn't available for this device — casting
 * normally" snackbar (PR 4).
 *
 * The exception is intentionally non-fatal: callers fall back to full-A/V mode on the receiver
 * rather than aborting the session.
 */
class SplitAvUnsupportedException(receiver: CastReceiver) : IllegalStateException(
    "SplitAv unavailable for ${receiver.protocol} receiver '${receiver.name}'",
)
