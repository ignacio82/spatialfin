package dev.jdtech.jellyfin.sendspin.receiver

/**
 * Process-global bridge that exposes the WebRTC `ma-api` JSON-RPC transport to other modules
 * so their Music Assistant API calls work off-LAN too — notably the `:modes:film` home-screen
 * MA rows, which otherwise POST directly to the LAN `/api` and come up empty on cellular.
 *
 * [SendspinReceiverService] sets [remoteExecutor] when the WebRTC `ma-api` channel is up and
 * authenticated, and clears it (null) on LAN / teardown. Callers should prefer it when non-null
 * and fall back to their direct LAN path otherwise.
 *
 * [remoteExecutor] takes an MA command and an optional args JSON string, and returns the raw
 * response JSON (a `{message_id, result}` object); it may throw on timeout / RPC error.
 */
object MusicAssistantRemoteBridge {
    @Volatile
    var remoteExecutor: ((command: String, argsJson: String?) -> String?)? = null

    @Volatile
    var remoteImageFetcher: (suspend (path: String) -> dev.jdtech.jellyfin.sendspin.receiver.remote.MaWebRtcHttpProxy.ProxyResponse)? = null
}
