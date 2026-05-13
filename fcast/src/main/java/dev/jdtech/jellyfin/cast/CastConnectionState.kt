package dev.jdtech.jellyfin.cast

/**
 * High-level connection state for a [ProtocolAdapter]. Adapters emit transitions via
 * [CastSessionEvent.ConnectionStateChanged].
 *
 * Idle: never connected or fully torn down.
 * Connecting: transport handshake in flight (TCP/TLS dial, FCast Version, Cast CONNECT/LAUNCH).
 * Connected: ready to accept load/play/control.
 * Disconnected: gracefully closed; the adapter is single-use, construct a fresh one.
 * Failed: terminal failure; the [CastSessionEvent.Error] preceding it carries the reason.
 */
enum class CastConnectionState { Idle, Connecting, Connected, Disconnected, Failed }
