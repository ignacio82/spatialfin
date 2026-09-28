package dev.spatialfin.companion.wear.fcast

/** A late rendezvous resumes immediately; reject unreasonable remote clock lead times. */
internal fun resumeDelayMs(nowMonotonicMs: Long, targetMonotonicMs: Long): Long =
    if (targetMonotonicMs <= nowMonotonicMs) 0
    else (targetMonotonicMs - nowMonotonicMs).coerceIn(0, 10_000)
