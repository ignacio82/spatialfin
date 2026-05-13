package dev.jdtech.jellyfin.cast

import dev.jdtech.jellyfin.fcast.sender.FCastReceiver

/**
 * Adapter glue between the protocol-specific [FCastReceiver] (still used by the FCast wire code
 * and the picker types in PR 1) and the new protocol-agnostic [CastReceiver].
 *
 * FCast receivers always carry the full SplitAv-capable feature set — only the FCast wire code
 * supports the custom `splitAv` metadata + drift correction pipeline. Other adapters must never
 * surface [CastCapability.SplitAv].
 */
internal val FCAST_DEFAULT_CAPABILITIES: Set<CastCapability> = setOf(
    CastCapability.Video,
    CastCapability.Audio,
    CastCapability.Volume,
    CastCapability.Seek,
    CastCapability.Speed,
    CastCapability.Subtitles,
    CastCapability.SplitAv,
)

/** Build a protocol-agnostic [CastReceiver] from an FCast wire-level receiver. */
fun FCastReceiver.toCastReceiver(): CastReceiver = CastReceiver(
    id = "fcast:$host:$port",
    name = name,
    host = host,
    port = port,
    protocol = CastProtocol.FCast,
    appName = appName,
    appVersion = appVersion,
    capabilities = FCAST_DEFAULT_CAPABILITIES,
    source = when (source) {
        FCastReceiver.Source.Mdns -> CastReceiver.Source.Mdns
        FCastReceiver.Source.Manual -> CastReceiver.Source.Manual
    },
)

/**
 * Translate a protocol-agnostic [CastReceiver] back to the FCast wire type. Only valid when
 * [CastReceiver.protocol] is [CastProtocol.FCast]; throws otherwise so misrouted calls fail loud.
 */
fun CastReceiver.toFCastReceiver(): FCastReceiver {
    require(protocol == CastProtocol.FCast) {
        "Cannot convert $protocol receiver to FCastReceiver; route through the matching adapter"
    }
    return FCastReceiver(
        host = host,
        port = port,
        name = name,
        appName = appName,
        appVersion = appVersion,
        source = when (source) {
            CastReceiver.Source.Mdns -> FCastReceiver.Source.Mdns
            CastReceiver.Source.Manual, CastReceiver.Source.Remembered -> FCastReceiver.Source.Manual
        },
    )
}
