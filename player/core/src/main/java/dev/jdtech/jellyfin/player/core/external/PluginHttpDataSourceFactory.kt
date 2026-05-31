package dev.jdtech.jellyfin.player.core.external

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource

/**
 * HTTP data source factory for media URLs returned by installed plugins.
 * Provider-specific headers or URL handling should be supplied by the plugin contract,
 * not hard-coded in SpatialFin.
 */
object PluginHttpDataSourceFactory {
    fun create(defaultRequestProperties: Map<String, String> = emptyMap()): DataSource.Factory {
        return DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent(SPATIALFIN_DEFAULT_USER_AGENT)
            .setDefaultRequestProperties(defaultRequestProperties.filterValues { it.isNotBlank() })
    }

    private const val SPATIALFIN_DEFAULT_USER_AGENT =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
}
