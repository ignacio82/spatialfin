package dev.jdtech.jellyfin.fcast.sender

import dev.jdtech.jellyfin.fcast.protocol.FCAST_DEFAULT_PORT

/**
 * A discovered or manually-configured FCast receiver. [host] is the IPv4/IPv6 literal or hostname
 * the sender will dial. [port] defaults to the FCast canonical port. [name] is the user-facing
 * label (mDNS instance name or user-typed alias).
 */
data class FCastReceiver(
    val host: String,
    val port: Int = FCAST_DEFAULT_PORT,
    val name: String,
    val appName: String? = null,
    val appVersion: String? = null,
    val source: Source = Source.Manual,
) {
    enum class Source { Mdns, Manual }
}
