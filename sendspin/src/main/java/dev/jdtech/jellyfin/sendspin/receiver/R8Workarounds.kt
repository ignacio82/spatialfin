package dev.jdtech.jellyfin.sendspin.receiver

import com.android.tools.r8.keepanno.annotations.KeepConstraint
import com.android.tools.r8.keepanno.annotations.KeepEdge
import com.android.tools.r8.keepanno.annotations.KeepTarget

/** Narrow optimizer workaround for a verifier failure observed on Android XR. */
@KeepEdge(
    description =
        "Keep Java-WebSocket's synchronized timer setup out of WebSocketServer.run on Android XR",
    consequences =
        [
            KeepTarget(
                className = "org.java_websocket.AbstractWebSocket",
                methodName = "startConnectionLostTimer",
                constraints = [KeepConstraint.NEVER_INLINE],
            )
        ],
)
internal object R8Workarounds
