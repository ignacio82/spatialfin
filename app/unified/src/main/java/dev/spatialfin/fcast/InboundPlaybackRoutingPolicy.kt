package dev.spatialfin.fcast

import dev.jdtech.jellyfin.fcast.protocol.SplitAvRole
import dev.jdtech.jellyfin.fcast.receiver.ExternalStreamRequest
import dev.spatialfin.unified.DeviceClass

enum class InboundPlaybackDestination { FLAT, IMMERSIVE_XR }

/** Pure launch-time decision for externally received media. */
object InboundPlaybackRoutingPolicy {
    fun select(
        deviceClass: DeviceClass,
        fullSpaceEnabled: Boolean,
        request: ExternalStreamRequest,
    ): InboundPlaybackDestination {
        val audioOnly = request.splitAv?.role == SplitAvRole.AUDIO ||
            request.container.trim().startsWith("audio/", ignoreCase = true)
        return if (deviceClass == DeviceClass.XR && fullSpaceEnabled && !audioOnly) {
            InboundPlaybackDestination.IMMERSIVE_XR
        } else {
            InboundPlaybackDestination.FLAT
        }
    }
}
