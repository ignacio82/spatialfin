package dev.jdtech.jellyfin.cast

import dev.jdtech.jellyfin.cast.adapter.FCastAdapter

/**
 * Single entry point that maps a [CastReceiver] to the right [ProtocolAdapter]. The session
 * manager holds at most one adapter at a time; constructing a new one tears down the previous.
 *
 * PR 1 only ships the FCast branch; PR 2 / PR 3 add the Google Cast and AirPlay branches. Until
 * those land, picking a non-FCast receiver (which the picker won't surface yet) returns a
 * [NotImplementedError] — defensive, never user-reachable.
 */
object CastAdapterFactory {

    fun create(receiver: CastReceiver, deps: CastDeps = CastDeps()): ProtocolAdapter =
        when (receiver.protocol) {
            CastProtocol.FCast -> FCastAdapter(receiver, deps)
            CastProtocol.GoogleCast -> throw NotImplementedError(
                "Google Cast adapter ships in PR 2",
            )
            CastProtocol.AirPlay -> throw NotImplementedError(
                "AirPlay adapter ships in PR 3",
            )
        }
}
