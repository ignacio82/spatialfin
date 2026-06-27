package dev.jdtech.jellyfin.data.musicassistant.utils

/**
 * Global override for Music Assistant image resolution.
 * Allows the SendSpin receiver (which manages the WebRTC off-LAN connection)
 * to intercept image URL resolution and redirect them to `mawebrtc://...`
 * for the Coil WebRTC image fetcher.
 */
object MaImageProxyOverride {
    @Volatile
    var resolver: ((path: String, provider: String) -> String?)? = null
}
