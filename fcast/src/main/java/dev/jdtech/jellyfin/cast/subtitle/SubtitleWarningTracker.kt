package dev.jdtech.jellyfin.cast.subtitle

/**
 * Tracks which receivers have already been warned about subtitle-fidelity degradation in the
 * current process. The session manager calls [shouldWarn] before queuing the detect-and-warn
 * toast — the tracker returns `true` only the first time it sees each receiver, so the user
 * gets one toast per device per app session and isn't nagged on every play tap.
 *
 * Process-scoped by design: an app restart re-arms the warning because mDNS may have placed a
 * different physical device at the same `host:port` since last run.
 */
class SubtitleWarningTracker {

    private val warnedKeys = mutableSetOf<String>()

    /**
     * Returns `true` the first time [host] / [port] is offered, `false` thereafter. Side effect:
     * marks the receiver as warned even if the caller chose not to surface the toast (e.g. the
     * user disabled warnings) — once we've decided not to warn we don't second-guess on the
     * next play tap with the same setting.
     */
    fun shouldWarn(host: String, port: Int): Boolean = warnedKeys.add(key(host, port))

    /** Forget all prior warnings — for tests, or if we ever expose a "reset warnings" toggle. */
    fun reset() {
        warnedKeys.clear()
    }

    private fun key(host: String, port: Int): String = "$host:$port"
}
