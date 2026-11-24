package dev.jdtech.jellyfin.plugins.bridge

import android.content.Context
import dev.jdtech.jellyfin.network.UrlTargetPolicy
import dev.jdtech.jellyfin.network.newBuilderWithSsrfGuard
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Headers.Companion.toHeaders
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttp
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber

@Serializable
data class HttpResponse(
    val isOk: Boolean,
    val code: Int,
    val body: String?,
    val headers: Map<String, String>,
    val base64: Boolean = false
)

class RealHttpBridge(
    context: Context,
    client: OkHttpClient,
) : HttpBridge {

    private val json = Json { ignoreUnknownKeys = true }
    private val cookieJars = ConcurrentHashMap<Pair<String, String>, InMemoryCookieJar>()

    @Volatile
    private var appLockLockedProvider: () -> Boolean = { false }

    init {
        runCatching { OkHttp.initialize(context.applicationContext) }
            .onFailure { Timber.w(it, "OkHttp initialization failed for plugin HTTP bridge") }
    }

    internal val guardedBaseClient: OkHttpClient =
        runCatching {
            client
                .newBuilderWithSsrfGuard(UrlTargetPolicy.PUBLIC_INTERNET_ONLY)
                .build()
        }.getOrElse {
            client
        }

    private fun cookieJarFor(userScope: String, pluginId: String): InMemoryCookieJar =
        cookieJars.computeIfAbsent(userScope to pluginId) {
            InMemoryCookieJar { appLockLockedProvider.invoke() }
        }

    private fun clientFor(userScope: String, pluginId: String): OkHttpClient =
        runCatching {
            guardedBaseClient
                .newBuilder()
                .cookieJar(cookieJarFor(userScope, pluginId))
                .build()
        }.getOrElse {
            guardedBaseClient
        }

    override fun forScope(userScope: String, pluginId: String): HttpBridge =
        ScopedHttpBridge(userScope = userScope, pluginId = pluginId)

    override fun setAppLockLockedProvider(provider: () -> Boolean) {
        appLockLockedProvider = provider
    }

    override fun clearAllCookies() {
        val entries = cookieJars.entries.toList()
        for ((key, jar) in entries) {
            jar.clear()
            cookieJars.remove(key, jar)
        }
    }

    override fun clearUserCookies(userScope: String) {
        val entries = cookieJars.entries.filter { it.key.first == userScope }
        for ((key, jar) in entries) {
            jar.clear()
            cookieJars.remove(key, jar)
        }
    }

    override fun request(
        method: String,
        url: String,
        headersJson: String,
        body: String?,
        usePlatformAuth: Boolean,
    ): String = executeScopedRequest(
        userScope = "default",
        pluginId = "default",
        method = method,
        url = url,
        headersJson = headersJson,
        body = body,
    )

    internal fun executeScopedRequest(
        userScope: String,
        pluginId: String,
        method: String,
        url: String,
        headersJson: String,
        body: String?,
    ): String {
        if (appLockLockedProvider.invoke()) {
            return json.encodeToString(
                HttpResponse(
                    isOk = false,
                    code = -1,
                    body = "Device is locked",
                    headers = emptyMap(),
                    base64 = false,
                ),
            )
        }

        val logUrl = url.substringBefore('?')
        return try {
            Timber.d("Plugin HTTP request [%s/%s]: %s %s", userScope, pluginId, method, logUrl)
            val headers: MutableMap<String, String> = try {
                json.decodeFromString<Map<String, String>>(headersJson).toMutableMap()
            } catch (_: Exception) {
                mutableMapOf()
            }

            // Platform WebView cookies (usePlatformAuth / CookieManager) are intentionally disabled
            // so untrusted QuickJS plugins cannot exfiltrate browser/platform cookies.
            normalizeHeaders(headers)
            Timber.d("Plugin HTTP headers: %s", headers.redactedForLog())

            val requestBuilder = Request.Builder()
                .url(url)
                .headers(headers.toHeaders())

            when (method.uppercase()) {
                "GET" -> requestBuilder.get()
                "POST" -> {
                    val mediaType = headers["Content-Type"]?.toMediaTypeOrNull()
                    val requestBody = if (body != null && body.startsWith("BASE64:")) {
                        val bytes = android.util.Base64.decode(body.substring(7), android.util.Base64.NO_WRAP)
                        bytes.toRequestBody(mediaType)
                    } else {
                        (body ?: "").toRequestBody(mediaType)
                    }
                    requestBuilder.post(requestBody)
                }
            }

            val scopedClient = clientFor(userScope, pluginId)
            val response = scopedClient.newCall(requestBuilder.build()).execute().use { response ->
                val contentType = response.body.contentType()
                val isText =
                    contentType == null ||
                        contentType.type == "text" ||
                        contentType.subtype.contains("json", ignoreCase = true) ||
                        contentType.subtype.contains("xml", ignoreCase = true)

                val rawBytes = readBoundedBytes(response.body.byteStream(), MAX_RESPONSE_BYTES)
                val responseBody = if (isText) {
                    String(rawBytes, Charsets.UTF_8)
                } else {
                    android.util.Base64.encodeToString(rawBytes, android.util.Base64.NO_WRAP)
                }

                Timber.d(
                    "Plugin HTTP response [%s/%s]: %d for %s (isText=%b, bytes=%d)",
                    userScope,
                    pluginId,
                    response.code,
                    logUrl,
                    isText,
                    rawBytes.size,
                )
                HttpResponse(
                    isOk = response.isSuccessful,
                    code = response.code,
                    body = responseBody,
                    headers = response.headers.toMap(),
                    base64 = !isText,
                )
            }
            json.encodeToString(response)
        } catch (e: IOException) {
            Timber.d(e, "Plugin HTTP request IOException: %s %s", method, logUrl)
            json.encodeToString(
                HttpResponse(
                    isOk = false,
                    code = -1,
                    body = e.message,
                    headers = emptyMap(),
                    base64 = false,
                ),
            )
        } catch (e: Throwable) {
            Timber.d(e, "Plugin HTTP request failed: %s %s", method, logUrl)
            json.encodeToString(
                HttpResponse(
                    isOk = false,
                    code = -1,
                    body = e.message ?: e::class.java.name,
                    headers = emptyMap(),
                    base64 = false,
                ),
            )
        }
    }

    private inner class ScopedHttpBridge(
        private val userScope: String,
        private val pluginId: String,
    ) : HttpBridge {
        override fun request(
            method: String,
            url: String,
            headersJson: String,
            body: String?,
            usePlatformAuth: Boolean,
        ): String = executeScopedRequest(
            userScope = userScope,
            pluginId = pluginId,
            method = method,
            url = url,
            headersJson = headersJson,
            body = body,
        )

        override fun forScope(userScope: String, pluginId: String): HttpBridge =
            this@RealHttpBridge.forScope(userScope, pluginId)

        override fun setAppLockLockedProvider(provider: () -> Boolean) {
            this@RealHttpBridge.setAppLockLockedProvider(provider)
        }

        override fun clearAllCookies() {
            this@RealHttpBridge.clearAllCookies()
        }

        override fun clearUserCookies(userScope: String) {
            this@RealHttpBridge.clearUserCookies(userScope)
        }
    }

    private fun readBoundedBytes(input: java.io.InputStream, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            total += n
            if (total > maxBytes) {
                throw IOException("Plugin HTTP response exceeds $maxBytes byte cap")
            }
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun normalizeHeaders(headers: MutableMap<String, String>) {
        headers.entries.removeAll { (name, value) ->
            name.isBlank() || value.isBlank()
        }

        val userAgentKey = headers.keys.firstOrNull { it.equals("User-Agent", ignoreCase = true) }
        if (userAgentKey == null) {
            headers["User-Agent"] = SPATIALFIN_DEFAULT_USER_AGENT
        } else if (userAgentKey != "User-Agent") {
            val userAgent = headers.remove(userAgentKey)
            if (userAgent != null) headers["User-Agent"] = userAgent
        }

        val contentTypeKey = headers.keys.firstOrNull { it.equals("Content-Type", ignoreCase = true) }
        if (contentTypeKey != null && contentTypeKey != "Content-Type") {
            val contentType = headers.remove(contentTypeKey)
            if (contentType != null) headers["Content-Type"] = contentType
        }
    }

    internal fun Map<String, String>.redactedForLog(): Map<String, String> =
        mapValues { (key, value) ->
            if (
                key.equals("Cookie", ignoreCase = true) ||
                key.equals("Authorization", ignoreCase = true) ||
                key.equals("X-Api-Key", ignoreCase = true)
            ) {
                "<redacted ${value.length} chars>"
            } else {
                value
            }
        }

    internal fun cookieCountForScope(userScope: String, pluginId: String): Int =
        cookieJars[userScope to pluginId]?.size() ?: 0

    internal class InMemoryCookieJar(
        private val isLocked: () -> Boolean = { false },
    ) : CookieJar {
        private val cookies = CopyOnWriteArrayList<Cookie>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            if (isLocked()) return
            this.cookies.removeAll { existing ->
                cookies.any { incoming ->
                    existing.name == incoming.name &&
                        existing.domain == incoming.domain &&
                        existing.path == incoming.path
                }
            }
            this.cookies.addAll(cookies)
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            if (isLocked()) return emptyList()
            val now = System.currentTimeMillis()
            cookies.removeAll { it.expiresAt < now }
            return cookies.filter { it.matches(url) }
        }

        fun clear() {
            cookies.clear()
        }

        fun size(): Int = cookies.size
    }

    companion object {
        const val MAX_RESPONSE_BYTES: Int = 4 * 1024 * 1024
        private const val SPATIALFIN_DEFAULT_USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}
