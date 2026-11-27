package dev.jdtech.jellyfin.cast

/**
 * Optional capabilities a [CastReceiver] may expose. Adapters populate these from the
 * protocol-specific discovery payload (FCast appName, Google Cast `ca` bitmask, AirPlay
 * `features`). UI surfaces gate affordances on these — for example the volume slider is hidden
 * for receivers without [Volume], and the SplitAv toggle is hidden for receivers without
 * [SplitAv].
 *
 * [SplitAv] is reserved for split-A/V receivers ([dev.jdtech.jellyfin.cast.adapter.FCastAdapter]
 * and [CastProtocol.SendspinAvr]). The split-A/V synchronization and calibration pipelines rely on
 * primitives that Google Cast and AirPlay don't expose.
 */
enum class CastCapability {
    Video,
    Audio,
    Photos,
    ScreenMirror,
    Volume,
    Seek,
    Speed,
    Subtitles,

    /**
     * Receiver supports SpatialFin's split-A/V mode: XR/Beam renders video locally while the
     * receiver renders audio in sync (via FCast or Theater Audio `SendspinAvr`).
     */
    SplitAv,

    /**
     * Receiver speaks Spec 0 Theater Audio v1 (`_spatialfin-avr._tcp` / `CastProtocol.SendspinAvr`)
     * with authenticated Bearer WS RPC control and multi-channel room calibration.
     */
    TheaterAudioV1,

    /**
     * Receiver renders ASS/SSA subtitles natively via libass with full styling (positioning,
     * karaoke, signs, typesetting). When absent, the sender must request a Jellyfin burn-in
     * transcode for ASS tracks with override tags or the receiver will degrade them to plain
     * dialogue. Only populated post-handshake by [dev.jdtech.jellyfin.cast.adapter.FCastAdapter]
     * when the peer's `appName` matches SpatialFin.
     */
    NativeAss,

    /**
     * Receiver extracts MKV-embedded font attachments and registers them with libass before
     * rendering. Without this, styled anime subs may render in a fallback font even on a
     * libass-capable receiver. Granted together with [NativeAss] today; see PR 6 for the
     * receiver-side font-extraction work that closes the visible gap.
     */
    EmbeddedFonts,
}
