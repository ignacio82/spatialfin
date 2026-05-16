package dev.jdtech.jellyfin.fcast.protocol

/**
 * Protocol version this codec speaks. Negotiated against peer via the [FCastOpcode.Version] message
 * — both sides downgrade to the lower version's opcode set if needed.
 *
 * v4 adds two SpatialFin-only, fully back-compatible split-A/V extensions (see
 * `SPLIT_AV_SYNC_V4.md`): optional NTP-timestamp bodies on Ping/Pong and an optional
 * synchronized-start body on Resume. All three opcodes remain body-less in v2/v3; a v4
 * message with no body is byte-identical to v3, so the bump never breaks interop.
 */
const val FCAST_PROTOCOL_VERSION: Int = 4

/** TCP port used by FCast receivers (all versions). */
const val FCAST_DEFAULT_PORT: Int = 46899

/** mDNS service type that receivers register and senders browse. */
const val FCAST_MDNS_SERVICE_TYPE: String = "_fcast._tcp.local."

/** Hard cap on a single packet (header + body) per spec. */
const val FCAST_MAX_PACKET_BYTES: Int = 32 * 1024
