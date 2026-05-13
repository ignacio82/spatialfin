package dev.jdtech.jellyfin.cast

/**
 * The casting protocol a [CastReceiver] speaks. SpatialFin ships sender support for all values;
 * the receiver side (i.e. SpatialFin advertising itself as a cast target) is FCast-only by design.
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
    AirPlay("AirPlay"),
}
