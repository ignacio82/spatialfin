package dev.jdtech.jellyfin.player.core.external

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import dev.jdtech.jellyfin.network.NetworkStreamProxy
import dev.jdtech.jellyfin.network.UrlTargetPolicy
import dev.jdtech.jellyfin.network.newBuilderWithSsrfGuard
import dev.jdtech.jellyfin.network.withSsrfGuard
import dev.jdtech.jellyfin.player.core.extractor.mp4.Fmp4NegativeTfdtSanitizingDataSource
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * Guarded HTTP data source factory backed by Media3's [OkHttpDataSource.Factory]
 * and [dev.jdtech.jellyfin.network.SafeUrlPolicy].
 */
@UnstableApi
object PluginHttpDataSourceFactory {
    private val lock = Any()

    @Volatile
    private var configuredBaseClient: OkHttpClient? = null

    @Volatile
    private var unallowlistedClient: OkHttpClient? = null

    @Volatile
    private var proxyAllowlistedClient: OkHttpClient? = null

    fun configure(okHttpClient: OkHttpClient) {
        synchronized(lock) {
            configuredBaseClient = okHttpClient
            unallowlistedClient = null
            proxyAllowlistedClient = null
        }
    }

    internal fun resetForTests() {
        synchronized(lock) {
            configuredBaseClient = null
            unallowlistedClient = null
            proxyAllowlistedClient = null
        }
    }

    private fun resolveClient(allowNetworkProxyLoopback: Boolean): OkHttpClient {
        if (!allowNetworkProxyLoopback) {
            unallowlistedClient?.let { return it }
        } else {
            proxyAllowlistedClient?.let { return it }
        }
        return synchronized(lock) {
            val base = configuredBaseClient ?: OkHttpClient.Builder()
                .withSsrfGuard(UrlTargetPolicy.PUBLIC_OR_LAN)
                .build()
                .also { configuredBaseClient = it }
            if (!allowNetworkProxyLoopback) {
                unallowlistedClient ?: base
                    .newBuilderWithSsrfGuard(UrlTargetPolicy.PUBLIC_OR_LAN)
                    .readTimeout(MEDIA_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .build()
                    .also { unallowlistedClient = it }
            } else {
                proxyAllowlistedClient ?: base
                    .newBuilderWithSsrfGuard(
                        policy = UrlTargetPolicy.PUBLIC_OR_LAN,
                        loopbackAllowlistProvider = NetworkStreamProxy::activeLoopbackTargets,
                    )
                    .readTimeout(MEDIA_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .build()
                    .also { proxyAllowlistedClient = it }
            }
        }
    }

    fun create(
        defaultRequestProperties: Map<String, String> = emptyMap(),
        allowNetworkProxyLoopback: Boolean = false,
    ): DataSource.Factory {
        val client = resolveClient(allowNetworkProxyLoopback)
        val okHttpFactory = OkHttpDataSource.Factory(client)
            .setUserAgent(SPATIALFIN_DEFAULT_USER_AGENT)
            .setDefaultRequestProperties(defaultRequestProperties.filterValues { it.isNotBlank() })
        return Fmp4NegativeTfdtSanitizingDataSource.Factory(okHttpFactory)
    }

    private const val MEDIA_READ_TIMEOUT_SECONDS = 30L
    private const val SPATIALFIN_DEFAULT_USER_AGENT =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
}
