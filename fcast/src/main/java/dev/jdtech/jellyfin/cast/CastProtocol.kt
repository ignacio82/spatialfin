package dev.jdtech.jellyfin.cast

/**
 * The casting protocol a remote [CastReceiver] speaks. This enum models outbound sender support;
 * inbound receiver services are wired separately by their owning modules.
 *
 * Adding a new protocol means:
 *  1. Add the enum value here.
 *  2. Add a discovery browser under `cast/discovery/`.
 *  3. Add a `ProtocolAdapter` implementation under `cast/adapter/<protocol>/`.
 *  4. Wire it into `CastAdapterFactory.create(...)`.
 *
 * `displayName` is the user-facing label (used in picker badges, mini-controller). Don't use
 * trademarked logos in code; the UI layer is responsible for any branding decisions.
 */
enum class CastProtocol(val displayName: String) {
    FCast("FCast"),
    GoogleCast("Chromecast"),
    AirPlay("AirPlay")
}
