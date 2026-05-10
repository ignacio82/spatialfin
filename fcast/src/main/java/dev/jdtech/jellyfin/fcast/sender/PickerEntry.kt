package dev.jdtech.jellyfin.fcast.sender

/**
 * One entry in the cast picker. Combines a discovered or remembered [FCastReceiver] with the
 * sender's reachability check result. The picker shows entries immediately as `Probing` and
 * flips them to `Online` / `Offline` when the TCP connect probe and / or mDNS scan completes.
 */
data class PickerEntry(
    val receiver: FCastReceiver,
    val state: State,
    /** Wall-clock millis when this receiver was last successfully reached. `null` if never. */
    val lastSeenMs: Long? = null,
) {
    enum class State { Probing, Online, Offline }
}
