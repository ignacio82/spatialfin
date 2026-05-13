package dev.jdtech.jellyfin.cast

/**
 * Optional capabilities a [CastReceiver] may expose. Adapters populate these from the
 * protocol-specific discovery payload (FCast appName, Google Cast `ca` bitmask, AirPlay
 * `features`). UI surfaces gate affordances on these — for example the volume slider is hidden
 * for receivers without [Volume], and the SplitAv toggle is hidden for receivers without
 * [SplitAv].
 *
 * [SplitAv] is intentionally FCast-exclusive. The split-A/V calibration / drift-correction
 * pipeline relies on FCast primitives (custom `splitAv` metadata, beacon cadence, commanded
 * start) that Google Cast and AirPlay don't expose. Only [dev.jdtech.jellyfin.cast.adapter.FCastAdapter]
 * is allowed to set this capability on its receivers.
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
     * Receiver supports SpatialFin's split-A/V mode: XR renders video, the receiver renders
     * audio only, lipsync-corrected via calibration chirp + drift correction. FCast-only.
     */
    SplitAv,
}
