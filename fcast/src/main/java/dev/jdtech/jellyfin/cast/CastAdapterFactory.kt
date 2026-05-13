package dev.jdtech.jellyfin.cast

import dev.jdtech.jellyfin.cast.adapter.FCastAdapter
import dev.jdtech.jellyfin.cast.adapter.googlecast.GoogleCastAdapter

/**
 * Single entry point that maps a [CastReceiver] to the right [ProtocolAdapter]. The session
 * manager holds at most one adapter at a time; constructing a new one tears down the previous.
 *
 * AirPlay is still pending (PR 4); picking an AirPlay receiver throws [NotImplementedError] —
 * defensive, never user-reachable because the picker doesn't surface AirPlay yet either.
 */
object CastAdapterFactory {

    fun create(receiver: CastReceiver, deps: CastDeps = CastDeps()): ProtocolAdapter =
        when (receiver.protocol) {
            CastProtocol.FCast -> FCastAdapter(receiver, deps)
            CastProtocol.GoogleCast -> GoogleCastAdapter(receiver, deps)
            CastProtocol.AirPlay -> throw NotImplementedError(
                "AirPlay adapter ships in PR 4",
            )
        }
}
