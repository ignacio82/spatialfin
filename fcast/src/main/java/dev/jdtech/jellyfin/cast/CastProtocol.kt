package dev.jdtech.jellyfin.cast

/**
 * The casting protocol a remote [CastReceiver] speaks. This enum models outbound sender support;
 * inbound receiver services are wired separately by their owning modules.
 *
 * Adding a new protocol means:
 *  1. Add the enum value here.
 *  2. Add a discovery browser under `cast/discovery/` or `cast/adapter/<protocol>/`.
 *  3. For full-A/V protocols, add a `ProtocolAdapter` under `cast/adapter/<protocol>/` and wire
 *     it into `CastAdapterFactory.create(...)`. Audio-only Split A/V protocols (such as
 *     [SendspinAvr]) drive audio directly via `SplitAvSessionEngine` and intentionally do not
 *     implement `ProtocolAdapter`.
 *
 * `displayName` is the user-facing label (used in picker badges, mini-controller). Don't use
 * trademarked logos in code; the UI layer is responsible for any branding decisions.
 */
enum class CastProtocol(val displayName: String) {
    FCast("FCast"),
    GoogleCast("Chromecast"),
    AirPlay("AirPlay"),
    SendspinAvr("Theater Audio"),
}
