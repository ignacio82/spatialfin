package dev.jdtech.jellyfin.api

import android.content.Context
import dev.jdtech.jellyfin.data.BuildConfig
import dev.jdtech.jellyfin.network.UrlTargetPolicy
import dev.jdtech.jellyfin.network.newBuilderWithSsrfGuard
import dev.jdtech.jellyfin.network.withSsrfGuard
import dev.jdtech.jellyfin.settings.domain.Constants
import java.util.Locale
import java.util.UUID
import kotlin.time.DurationUnit
import kotlin.time.toDuration
import kotlinx.coroutines.CoroutineScope
import okhttp3.OkHttpClient
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.ApiClientFactory
import org.jellyfin.sdk.api.client.HttpClientOptions
import org.jellyfin.sdk.api.okhttp.OkHttpFactory
import org.jellyfin.sdk.api.sockets.SocketConnection
import org.jellyfin.sdk.api.sockets.SocketConnectionFactory
import org.jellyfin.sdk.model.DeviceInfo
import org.jellyfin.sdk.api.client.extensions.brandingApi
import org.jellyfin.sdk.api.client.extensions.artistsApi
import org.jellyfin.sdk.api.client.extensions.audioApi
import org.jellyfin.sdk.api.client.extensions.devicesApi
import org.jellyfin.sdk.api.client.extensions.itemLookupApi
import org.jellyfin.sdk.api.client.extensions.itemRefreshApi
import org.jellyfin.sdk.api.client.extensions.itemUpdateApi
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.lyricsApi
import org.jellyfin.sdk.api.client.extensions.mediaInfoApi
import org.jellyfin.sdk.api.client.extensions.mediaSegmentsApi
import org.jellyfin.sdk.api.client.extensions.playStateApi
import org.jellyfin.sdk.api.client.extensions.playlistsApi
import org.jellyfin.sdk.api.client.extensions.quickConnectApi
import org.jellyfin.sdk.api.client.extensions.sessionApi
import org.jellyfin.sdk.api.client.extensions.syncPlayApi
import org.jellyfin.sdk.api.client.extensions.subtitleApi
import org.jellyfin.sdk.api.client.extensions.suggestionsApi
import org.jellyfin.sdk.api.client.extensions.systemApi
import org.jellyfin.sdk.api.client.extensions.trickplayApi
import org.jellyfin.sdk.api.client.extensions.tvShowsApi
import org.jellyfin.sdk.api.client.extensions.universalAudioApi
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.api.client.extensions.userViewsApi
import org.jellyfin.sdk.api.client.extensions.videosApi
import org.jellyfin.sdk.createJellyfin
import org.jellyfin.sdk.model.ClientInfo

/**
 * Jellyfin API class using org.jellyfin.sdk:jellyfin-platform-android
 *
 * @param androidContext The context
 * @param socketTimeout The socket timeout
 * @constructor Creates a new [JellyfinApi] instance
 */
class JellyfinApi(
    private val androidContext: Context,
    requestTimeout: Long = Constants.NETWORK_DEFAULT_REQUEST_TIMEOUT,
    connectTimeout: Long = Constants.NETWORK_DEFAULT_CONNECT_TIMEOUT,
    socketTimeout: Long = Constants.NETWORK_DEFAULT_SOCKET_TIMEOUT,
    private val allowLoopbackForTests: Boolean = false,
) {
    val okHttpClient: OkHttpClient =
        OkHttpClient.Builder()
            .withSsrfGuard(
                policy = UrlTargetPolicy.PUBLIC_OR_LAN,
                allowLoopbackForTests = allowLoopbackForTests,
            )
            .addInterceptor { chain ->
                val originalRequest = chain.request()
                val headerValue = buildAcceptLanguageHeader()
                val request =
                    if (originalRequest.header("Accept-Language") == null && headerValue != null) {
                        originalRequest.newBuilder()
                            .header("Accept-Language", headerValue)
                            .build()
                    } else {
                        originalRequest
                    }
                chain.proceed(request)
            }
            .build()

    val httpClientOptions: HttpClientOptions =
        HttpClientOptions(
            followRedirects = false,
            requestTimeout = requestTimeout.toDuration(DurationUnit.MILLISECONDS),
            connectTimeout = connectTimeout.toDuration(DurationUnit.MILLISECONDS),
            socketTimeout = socketTimeout.toDuration(DurationUnit.MILLISECONDS),
        )

    private fun wrapNoRedirectFactory(client: OkHttpClient): NoRedirectOkHttpFactory =
        NoRedirectOkHttpFactory(OkHttpFactory(client))

    val jellyfin = createJellyfin {
        clientInfo =
            ClientInfo(
                name =
                    androidContext.applicationInfo
                        .loadLabel(androidContext.packageManager)
                        .toString(),
                version = BuildConfig.VERSION_NAME,
            )
        context = androidContext

        val factory = wrapNoRedirectFactory(okHttpClient)
        apiClientFactory = factory
        socketConnectionFactory = factory
    }

    private val noRedirectJellyfin by lazy {
        val noRedirectClient =
            okHttpClient
                .newBuilderWithSsrfGuard(
                    policy = UrlTargetPolicy.PUBLIC_OR_LAN,
                    allowLoopbackForTests = allowLoopbackForTests,
                    maxRedirects = 0,
                )
                .build()
        createJellyfin {
            clientInfo =
                ClientInfo(
                    name =
                        androidContext.applicationInfo
                            .loadLabel(androidContext.packageManager)
                            .toString(),
                    version = BuildConfig.VERSION_NAME,
                )
            context = androidContext

            val factory = wrapNoRedirectFactory(noRedirectClient)
            apiClientFactory = factory
            socketConnectionFactory = factory
        }
    }

    fun createApi(
        baseUrl: String,
        accessToken: String? = null,
        httpClientOptions: HttpClientOptions = this.httpClientOptions,
        followRedirects: Boolean = true,
    ): ApiClient {
        val targetJellyfin = if (followRedirects) jellyfin else noRedirectJellyfin
        return targetJellyfin.createApi(
            baseUrl = baseUrl,
            accessToken = accessToken,
            httpClientOptions = httpClientOptions.copy(followRedirects = false),
        )
    }

    val api =
        jellyfin.createApi(
            httpClientOptions = httpClientOptions,
        )
    @Volatile
    var userId: UUID? = null

    val brandingApi = api.brandingApi
    val artistsApi = api.artistsApi
    val audioApi = api.audioApi
    val devicesApi = api.devicesApi
    val itemsApi = api.itemsApi
    val mediaInfoApi = api.mediaInfoApi
    val mediaSegmentsApi = api.mediaSegmentsApi
    val playStateApi = api.playStateApi
    val quickConnectApi = api.quickConnectApi
    val sessionApi = api.sessionApi
    val showsApi = api.tvShowsApi
    val subtitleApi = api.subtitleApi
    val syncPlayApi = api.syncPlayApi
    val suggestionsApi = api.suggestionsApi
    val systemApi = api.systemApi
    val trickplayApi = api.trickplayApi
    val userApi = api.userApi
    val userLibraryApi = api.userLibraryApi
    val videosApi = api.videosApi
    val viewsApi = api.userViewsApi
    val itemLookupApi = api.itemLookupApi
    val itemRefreshApi = api.itemRefreshApi
    val itemUpdateApi = api.itemUpdateApi
    val libraryApi = api.libraryApi
    val lyricsApi = api.lyricsApi
    val playlistsApi = api.playlistsApi
    val universalAudioApi = api.universalAudioApi

    companion object {
        @Volatile private var INSTANCE: JellyfinApi? = null

        fun getInstance(
            context: Context,
            requestTimeout: Long = Constants.NETWORK_DEFAULT_REQUEST_TIMEOUT,
            connectTimeout: Long = Constants.NETWORK_DEFAULT_CONNECT_TIMEOUT,
            socketTimeout: Long = Constants.NETWORK_DEFAULT_SOCKET_TIMEOUT,
        ): JellyfinApi {
            synchronized(this) {
                var instance = INSTANCE
                if (instance == null) {
                    instance =
                        JellyfinApi(
                            androidContext = context.applicationContext,
                            requestTimeout = requestTimeout,
                            connectTimeout = connectTimeout,
                            socketTimeout = socketTimeout,
                        )
                    INSTANCE = instance
                }
                return instance
            }
        }
    }
}

internal class NoRedirectOkHttpFactory(
    private val delegate: OkHttpFactory,
) : ApiClientFactory, SocketConnectionFactory {
    override fun create(
        baseUrl: String?,
        accessToken: String?,
        clientInfo: ClientInfo,
        deviceInfo: DeviceInfo,
        httpClientOptions: HttpClientOptions,
        socketConnectionFactory: SocketConnectionFactory,
    ): ApiClient =
        delegate.create(
            baseUrl = baseUrl,
            accessToken = accessToken,
            clientInfo = clientInfo,
            deviceInfo = deviceInfo,
            httpClientOptions = httpClientOptions.copy(followRedirects = false),
            socketConnectionFactory = socketConnectionFactory,
        )

    override fun create(
        clientOptions: HttpClientOptions,
        scope: CoroutineScope,
    ): SocketConnection =
        delegate.create(
            clientOptions = clientOptions.copy(followRedirects = false),
            scope = scope,
        )
}

/**
 * Builds an RFC 9110 / BCP 47 compliant Accept-Language header value from the given [locale].
 * Used by Jellyfin 12+ for server-side metadata localization matching the device locale.
 */
internal fun buildAcceptLanguageHeader(locale: Locale = Locale.getDefault()): String? {
    val tag = locale.toLanguageTag()
    if (tag.isEmpty() || tag == "und") return null
    val lang = locale.language
    return if (lang.isNotEmpty() && lang != tag) {
        "$tag, $lang;q=0.9"
    } else {
        tag
    }
}
