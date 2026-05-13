package dev.jdtech.jellyfin.cast.adapter.googlecast

/**
 * The four Google Cast V2 namespaces SpatialFin sends and receives on. There are more in the
 * full Chromium protocol (settings, multi-zone, screen mirroring, etc.) but they are out of
 * scope for sender-side video casting.
 *
 * Receivers route inbound messages by namespace, so getting these strings wrong is the most
 * common reason a Cast session silently goes nowhere — keep them as constants here so the
 * adapter doesn't drift one character on a copy-paste.
 */
internal object CastNamespaces {
    /** Open / close the virtual connection on top of the TLS socket. */
    const val CONNECTION = "urn:x-cast:com.google.cast.tp.connection"

    /** PING / PONG every ~5s. Receiver drops the connection if no PING arrives. */
    const val HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat"

    /** Receiver-level lifecycle: LAUNCH a registered app id, GET_STATUS, STOP. */
    const val RECEIVER = "urn:x-cast:com.google.cast.receiver"

    /** Media controls (LOAD / PLAY / PAUSE / SEEK / STOP / MEDIA_STATUS / SET_VOLUME). */
    const val MEDIA = "urn:x-cast:com.google.cast.media"
}

/**
 * Constants the adapter sends in JSON payloads. Cast V2 receivers are case-sensitive on these
 * — `"connect"` is silently ignored, `"CONNECT"` works.
 */
internal object CastMessages {
    const val CONNECT = "CONNECT"
    const val CLOSE = "CLOSE"
    const val PING = "PING"
    const val PONG = "PONG"
    const val LAUNCH = "LAUNCH"
    const val STOP = "STOP"
    const val GET_STATUS = "GET_STATUS"
    const val LOAD = "LOAD"
    const val PLAY = "PLAY"
    const val PAUSE = "PAUSE"
    const val SEEK = "SEEK"
    const val MEDIA_STATUS = "MEDIA_STATUS"
    const val RECEIVER_STATUS = "RECEIVER_STATUS"
    const val SET_VOLUME = "SET_VOLUME"

    /**
     * The Google-published Default Media Receiver app id. Plays any HTTP(S) URL the sender
     * passes via LOAD. Registering a custom receiver would let us ship libass over WebAssembly
     * for high-fidelity anime subs on Chromecast, but that's a "someday maybe" — see §17 of
     * the implementation brief.
     */
    const val DEFAULT_MEDIA_RECEIVER_APP_ID = "CC1AD845"

    const val DEFAULT_SENDER_ID_PREFIX = "sender-"
    const val DEFAULT_RECEIVER_ID = "receiver-0"
}
