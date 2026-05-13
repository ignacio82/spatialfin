package dev.jdtech.jellyfin.cast

/**
 * Protocol-agnostic descriptor for a discovered or manually-configured cast target. The picker,
 * session manager, and adapter factory all speak this type; protocol-specific wire types (e.g.
 * [dev.jdtech.jellyfin.fcast.sender.FCastReceiver]) live inside the adapter that owns them.
 *
 * [id] must be stable across discovery sweeps for the same physical device — adapters compose it
 * as `"$protocol:$uniqueDeviceId"` (the FCast adapter uses host+port, Cast uses the mDNS `id`
 * TXT field, AirPlay uses `deviceid`). UI uses this for de-duplication, pin/unpin, and the
 * remembered-receivers store.
 *
 * @property capabilities adapter-populated; never derived elsewhere. UI gates affordances on
 *   this set (volume slider, speed control, SplitAv toggle).
 */
data class CastReceiver(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val protocol: CastProtocol,
    val modelName: String? = null,
    val appName: String? = null,
    val appVersion: String? = null,
    val capabilities: Set<CastCapability> = emptySet(),
    val source: Source = Source.Mdns,
) {
    enum class Source { Mdns, Manual, Remembered }

    /** Convenience predicate used at every SplitAv entry-point. See [SplitAvCapability]. */
    fun supports(capability: CastCapability): Boolean = capability in capabilities
}
