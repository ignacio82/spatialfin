package dev.jdtech.jellyfin.cast

import kotlinx.coroutines.CoroutineScope

/**
 * Shared dependencies handed to every [ProtocolAdapter]. Built once by the session manager and
 * reused across adapter constructions to avoid each adapter spinning up its own HTTP client / I/O
 * scope. Keep this surface narrow — anything protocol-specific belongs in the adapter package,
 * not here.
 *
 * The `:fcast` module deliberately doesn't depend on OkHttp or Android `Context` so this lives as
 * a small open value object. The Google Cast and AirPlay adapters (PR 2 / PR 3) will need a
 * richer [CastDeps] shape (HTTP client, Application context for multicast lock + proxy lifecycle);
 * extend this then rather than forcing each module to thread its own deps tuple.
 */
data class CastDeps(
    val parentScope: CoroutineScope? = null,
)
