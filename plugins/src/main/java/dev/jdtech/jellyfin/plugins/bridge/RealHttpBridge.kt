package dev.jdtech.jellyfin.plugins.bridge

import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Headers.Companion.toHeaders
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

@Serializable
data class HttpResponse(
    val isOk: Boolean,
    val code: Int,
    val body: String?,
    val headers: Map<String, String>,
    val base64: Boolean = false
)

class RealHttpBridge(
    client: OkHttpClient
) : HttpBridge {

    private val json = Json { ignoreUnknownKeys = true }
    private val client =
        runCatching {
            client.newBuilder()
                .cookieJar(InMemoryCookieJar())
                .build()
        }.getOrElse {
            client
        }

    override fun request(
        method: String,
        url: String,
        headersJson: String,
        body: String?,
        usePlatformAuth: Boolean
    ): String {
        android.util.Log.e("CRITICAL_HTTP", "Plugin Request: $method $url")
        val headers: MutableMap<String, String> = try {
            json.decodeFromString<Map<String, String>>(headersJson).toMutableMap()
        } catch (e: Exception) {
            mutableMapOf()
        }

        normalizeHeaders(headers)
        if (usePlatformAuth) {
            platformCookieHeader(url)?.let { cookieHeader ->
                headers["Cookie"] = cookieHeader
            }
        }

        android.util.Log.e("CRITICAL_HTTP", "Request Headers: ${headers.redactedForLog()}")

        val requestBuilder = Request.Builder()
            .url(url)
            .headers(headers.toHeaders())

        when (method.uppercase()) {
            "GET" -> requestBuilder.get()
            "POST" -> {
                val mediaType = headers["Content-Type"]?.toMediaTypeOrNull()
                val requestBody = if (body != null && body.startsWith("BASE64:")) {
                    val bytes = android.util.Base64.decode(body.substring(7), android.util.Base64.NO_WRAP)
                    okhttp3.RequestBody.create(mediaType, bytes)
                } else {
                    (body ?: "").toRequestBody(mediaType)
                }
                requestBuilder.post(requestBody)
            }
        }

        val response = try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                val contentType = response.body?.contentType()
                var isText =
                    contentType == null ||
                        contentType.type == "text" ||
                        contentType.subtype.contains("json", ignoreCase = true) ||
                        contentType.subtype.contains("xml", ignoreCase = true)
                
                val responseBody = if (isText) {
                    response.body?.string()
                } else {
                    response.body?.bytes()?.let { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) }
                }
                
                if (!response.isSuccessful) {
                    android.util.Log.e("CRITICAL_HTTP", "Plugin Response: ${response.code} for $url Body: ${responseBody?.take(100)}")
                } else {
                    android.util.Log.e("CRITICAL_HTTP", "Plugin Response: ${response.code} for $url (isText=$isText)")
                }
                HttpResponse(
                    isOk = response.isSuccessful,
                    code = response.code,
                    body = responseBody,
                    headers = response.headers.toMap(),
                    base64 = !isText
                )
            }
        } catch (e: IOException) {
            Timber.e(e, "HTTP request failed: $method $url")
            HttpResponse(
                isOk = false,
                code = -1,
                body = e.message,
                headers = emptyMap(),
                base64 = false
            )
        }

        return json.encodeToString(response)
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

    private fun platformCookieHeader(url: String): String? =
        runCatching {
            CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }
        }.getOrNull()

    private fun Map<String, String>.redactedForLog(): Map<String, String> =
        mapValues { (key, value) ->
            if (key.equals("Cookie", ignoreCase = true)) "<redacted ${value.length} chars>" else value
        }

    private class InMemoryCookieJar : CookieJar {
        private val cookies = CopyOnWriteArrayList<Cookie>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
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
            val now = System.currentTimeMillis()
            cookies.removeAll { it.expiresAt < now }
            return cookies.filter { it.matches(url) }
        }
    }

    private companion object {
        const val SPATIALFIN_DEFAULT_USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}
