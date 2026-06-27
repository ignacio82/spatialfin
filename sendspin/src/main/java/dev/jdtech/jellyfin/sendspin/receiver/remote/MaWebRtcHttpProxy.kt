package dev.jdtech.jellyfin.sendspin.receiver.remote

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import timber.log.Timber

/**
 * HTTP-over-WebRTC-data-channel proxy for Music Assistant.
 *
 * Wire protocol:
 *   Request : { "type": "http-proxy-request",  "id": "...", "method": "GET", "path": "...", "headers": {...} }
 *   Response: { "type": "http-proxy-response", "id": "...", "status": 200, "headers": {...}, "body": "<hex>" }
 */
class MaWebRtcHttpProxy(
    private val sender: (String) -> Unit,
    maxConcurrent: Int = DEFAULT_MAX_CONCURRENT,
) {
    private val pendingMutex = Mutex()
    private val pending = mutableMapOf<String, CompletableDeferred<String>>()
    private val concurrencyGate = Semaphore(maxConcurrent)

    data class ProxyResponse(
        val status: Int,
        val headers: Map<String, String>,
        val body: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as ProxyResponse
            if (status != other.status) return false
            if (headers != other.headers) return false
            if (!body.contentEquals(other.body)) return false
            return true
        }

        override fun hashCode(): Int {
            var result = status
            result = 31 * result + headers.hashCode()
            result = 31 * result + body.contentHashCode()
            return result
        }
    }

    suspend fun get(
        path: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): ProxyResponse {
        return concurrencyGate.withPermit {
            val id = nextRequestId()
            val deferred = CompletableDeferred<String>()
            pendingMutex.withLock { pending[id] = deferred }
            
            val rawJsonString = try {
                sender(buildRequest(id, path, headers).toString())
                withTimeout(timeoutMs) { deferred.await() }
            } finally {
                pendingMutex.withLock { pending.remove(id) }
            }
            
            parseResponse(rawJsonString)
        }
    }

    suspend fun dispatchRawResponse(rawJsonString: String) {
        val id = extractId(rawJsonString)
        if (id == null) {
            Timber.tag("MaWebRtcHttpProxy").w("Dropping http-proxy-response without id")
            return
        }
        val deferred = pendingMutex.withLock { pending[id] }
        if (deferred == null) {
            Timber.tag("MaWebRtcHttpProxy").w("No pending request for id=$id (timed out or cancelled)")
            return
        }
        deferred.complete(rawJsonString)
    }

    suspend fun cancelAll(cause: Throwable) {
        val snapshot = pendingMutex.withLock {
            val all = pending.values.toList()
            pending.clear()
            all
        }
        snapshot.forEach { it.completeExceptionally(cause) }
    }

    private fun buildRequest(
        id: String,
        path: String,
        headers: Map<String, String>,
    ): JSONObject {
        val headersObj = JSONObject()
        headers.forEach { (k, v) -> headersObj.put(k, v) }
        return JSONObject()
            .put("type", "http-proxy-request")
            .put("id", id)
            .put("method", "GET")
            .put("path", path)
            .put("headers", headersObj)
    }

    private suspend fun parseResponse(rawJsonString: String): ProxyResponse = withContext(Dispatchers.Default) {
        fastParseResponse(rawJsonString) ?: run {
            Timber.tag("MaWebRtcHttpProxy").w("Falling back to full JSON parse for http-proxy-response")
            slowParseResponse(rawJsonString)
        }
    }

    private fun fastParseResponse(raw: String): ProxyResponse? {
        val bodyKeyIdx = raw.indexOf(BODY_KEY)
        if (bodyKeyIdx < 0) return null
        val bodyStart = bodyKeyIdx + BODY_KEY.length
        val bodyEnd = raw.indexOf('"', startIndex = bodyStart)
        if (bodyEnd < 0) return null

        val status = findStatusBefore(raw, bodyKeyIdx) ?: return null
        val headers = extractHeadersBefore(raw, bodyKeyIdx) ?: return null
        val body = hexToBytes(raw, bodyStart, bodyEnd)
        
        return ProxyResponse(status, headers, body)
    }

    private fun findStatusBefore(raw: String, limit: Int): Int? {
        val keyIdx = raw.indexOf(STATUS_KEY)
        if (keyIdx !in 0..<limit) return null
        var i = keyIdx + STATUS_KEY.length
        while (i < limit && raw[i].isWhitespace()) i++
        var value = 0
        var any = false
        while (i < limit) {
            val c = raw[i]
            if (c !in '0'..'9') break
            value = value * RADIX_10 + (c.code - '0'.code)
            any = true
            i++
        }
        return if (any) value else null
    }

    private fun extractHeadersBefore(raw: String, limit: Int): Map<String, String>? {
        val keyIdx = raw.indexOf(HEADERS_KEY)
        if (keyIdx !in 0..<limit) return emptyMap()
        val objStart = raw.indexOf('{', startIndex = keyIdx + HEADERS_KEY.length)
        if (objStart !in 0..<limit) return null
        val objEnd = raw.indexOf('}', startIndex = objStart)
        if (objEnd !in 0..<limit) return null
        
        return runCatching {
            val jsonStr = raw.substring(objStart, objEnd + 1)
            val obj = JSONObject(jsonStr)
            val map = mutableMapOf<String, String>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                map[key] = obj.optString(key, "")
            }
            map
        }.getOrNull()
    }

    private fun slowParseResponse(rawJsonString: String): ProxyResponse {
        val json = JSONObject(rawJsonString)
        val status = json.optInt("status", 0)
        val headersObj = json.optJSONObject("headers")
        val headers = mutableMapOf<String, String>()
        if (headersObj != null) {
            val keys = headersObj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                headers[key] = headersObj.optString(key, "")
            }
        }
        val bodyHex = json.optString("body", "")
        return ProxyResponse(status, headers, hexToBytes(bodyHex))
    }

    private fun extractId(rawJsonString: String): String? {
        val head = rawJsonString.substring(0, minOf(ID_SCAN_WINDOW, rawJsonString.length))
        return ID_REGEX.find(head)?.groupValues?.get(1)
    }

    private fun nextRequestId(): String {
        val n = requestCounter++
        return "req_${System.currentTimeMillis()}_$n"
    }

    private var requestCounter = 0L

    companion object {
        private const val DEFAULT_MAX_CONCURRENT = 6
        private const val DEFAULT_TIMEOUT_MS = 30_000L
        private const val ID_SCAN_WINDOW = 256

        private val ID_REGEX = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"")
        private const val RADIX = 16
        private const val RADIX_10 = 10
        private const val SHIFT = 4

        private const val BODY_KEY = "\"body\":\""
        private const val STATUS_KEY = "\"status\":"
        private const val HEADERS_KEY = "\"headers\":"

        fun hexToBytes(hex: String): ByteArray = hexToBytes(hex, 0, hex.length)

        fun hexToBytes(src: String, start: Int, endExclusive: Int): ByteArray {
            val len = endExclusive - start
            require(len % 2 == 0) { "Hex range must have even length" }
            val out = ByteArray(len / 2)
            var i = start
            var o = 0
            while (i < endExclusive) {
                out[o++] = ((src[i].digitToInt(RADIX) shl SHIFT) or src[i + 1].digitToInt(RADIX)).toByte()
                i += 2
            }
            return out
        }
        
        fun isHttpProxyResponse(jsonString: String): Boolean {
            val end = minOf(ID_SCAN_WINDOW, jsonString.length)
            if (jsonString.regionMatches(0, "{", 0, 1) &&
                jsonString.indexOf("\"type\":\"http-proxy-response\"", startIndex = 0, ignoreCase = false) in 0 until end
            ) {
                return true
            }
            return Regex("\"type\"\\s*:\\s*\"http-proxy-response\"").containsMatchIn(jsonString.substring(0, end))
        }
    }
}
