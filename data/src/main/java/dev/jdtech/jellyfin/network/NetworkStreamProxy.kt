package dev.jdtech.jellyfin.network

import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.spatialfin.unified.applock.AppLockManager
import dev.spatialfin.unified.applock.AppLockObserver
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Semaphore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Local HTTP proxy that bridges network file access (SMB/NFS) with ExoPlayer.
 * Starts a lightweight HTTP server on 127.0.0.1 that serves files
 * with per-instance CSPRNG token auth, bounded header parsing, and Range support.
 */
class NetworkStreamProxy(
    private val clientFactory: NetworkFileClientFactory,
    private val database: ServerDatabaseDao,
    appLockObserver: AppLockObserver = AppLockObserver.NoOp,
) {
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null
    private var port: Int = 0
    private val authToken: String = generateAuthToken()
    private val activeSockets = CopyOnWriteArraySet<Socket>()
    private val connectionSemaphore = Semaphore(MAX_CONCURRENT_CONNECTIONS)

    @Volatile
    private var paused: Boolean = false

    @Volatile
    private var lockedProvider: () -> Boolean = {
        appLockObserver.lockState.value == AppLockManager.LockState.LOCKED
    }

    fun setPaused(paused: Boolean) {
        this.paused = paused
        if (paused) {
            closeActiveSockets()
        }
    }

    fun setLockedProvider(provider: () -> Boolean) {
        this.lockedProvider = provider
    }

    fun closeActiveSockets() {
        for (socket in activeSockets) {
            runCatching { socket.close() }
        }
        activeSockets.clear()
    }

    @Synchronized
    fun start() {
        if (serverSocket != null) return
        val socket = ServerSocket(0, 50, InetAddress.getByName(LOOPBACK_HOST))
        serverSocket = socket
        port = socket.localPort
        activeTargets.add(LOOPBACK_HOST to port)
        Timber.d("NetworkStreamProxy started on port $port")
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            while (!socket.isClosed) {
                try {
                    val client = socket.accept()
                    scope.launch { handleClient(client) }
                } catch (e: Exception) {
                    if (!socket.isClosed) {
                        Timber.e(e, "Error accepting connection")
                    }
                }
            }
        }
    }

    @Synchronized
    fun stop() {
        val currentPort = port
        if (currentPort > 0) {
            activeTargets.remove(LOOPBACK_HOST to currentPort)
        }
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        port = 0
        closeActiveSockets()
        scope.cancel()
    }

    fun getStreamUrl(shareId: String, filePath: String): String {
        if (serverSocket == null) start()
        return "http://$LOOPBACK_HOST:$port/stream?shareId=${
            URLEncoder.encode(shareId, "UTF-8")
        }&path=${
            URLEncoder.encode(filePath, "UTF-8")
        }&token=$authToken"
    }

    internal fun currentPortForTests(): Int = port

    private suspend fun handleClient(socket: Socket) {
        if (!connectionSemaphore.tryAcquire()) {
            runCatching {
                socket.use { client ->
                    client.soTimeout = SOCKET_TIMEOUT_MS
                    sendError(client.getOutputStream(), 503, "Service Unavailable")
                }
            }
            return
        }
        activeSockets.add(socket)
        try {
            socket.use { client ->
                client.soTimeout = SOCKET_TIMEOUT_MS
                val parsed = readHttpRequestHead(client.getInputStream())
                if (parsed == null) {
                    sendError(client.getOutputStream(), 400, "Bad Request")
                    return
                }
                if (parsed.tooLarge) {
                    sendError(client.getOutputStream(), 431, "Request Header Fields Too Large")
                    return
                }

                val parts = parsed.requestLine.split(" ")
                if (parts.size < 2 || !parts[1].startsWith("/stream?")) {
                    sendError(client.getOutputStream(), 400, "Bad Request")
                    return
                }

                val queryString = parts[1].substringAfter("?")
                val params = parseQueryString(queryString)

                // Constant-time token auth + lock/pause check after bounded header parsing
                val providedToken = params["token"]
                if (
                    paused ||
                    lockedProvider.invoke() ||
                    providedToken == null ||
                    !MessageDigest.isEqual(
                        providedToken.toByteArray(Charsets.UTF_8),
                        authToken.toByteArray(Charsets.UTF_8),
                    )
                ) {
                    sendError(client.getOutputStream(), 403, "Forbidden")
                    return
                }

                val shareId = params["shareId"] ?: run {
                    sendError(client.getOutputStream(), 400, "Missing shareId")
                    return
                }
                val filePath = params["path"] ?: run {
                    sendError(client.getOutputStream(), 400, "Missing path")
                    return
                }
                if (hasTraversalSegment(filePath)) {
                    sendError(client.getOutputStream(), 400, "Invalid path")
                    return
                }

                val share = database.getNetworkShare(shareId) ?: run {
                    sendError(client.getOutputStream(), 404, "Share not found")
                    return
                }

                val credentials = NetworkCredentials(
                    username = share.username,
                    password = share.password,
                    domain = share.domain,
                )

                val fileClient = clientFactory.clientFor(share.protocol)

                val fileSize = fileClient.getFileSize(
                    host = share.host,
                    shareName = share.shareName,
                    filePath = filePath,
                    credentials = credentials,
                )

                val rangeHeader = parsed.headers["range"]
                val range = parseRangeStrict(rangeHeader, fileSize)
                if (range == null) {
                    sendError(
                        client.getOutputStream(),
                        416,
                        "Range Not Satisfiable",
                        extraHeaders = "Content-Range: bytes */$fileSize\r\n",
                    )
                    return
                }
                val (rangeStart, rangeEnd) = range
                val contentLength = if (fileSize == 0L) 0L else (rangeEnd - rangeStart + 1)
                val outputStream = client.getOutputStream()

                if (rangeHeader != null && fileSize > 0L) {
                    outputStream.write("HTTP/1.1 206 Partial Content\r\n".toByteArray())
                    outputStream.write("Content-Range: bytes $rangeStart-$rangeEnd/$fileSize\r\n".toByteArray())
                } else {
                    outputStream.write("HTTP/1.1 200 OK\r\n".toByteArray())
                }

                outputStream.write("Content-Type: application/octet-stream\r\n".toByteArray())
                outputStream.write("Content-Length: $contentLength\r\n".toByteArray())
                outputStream.write("Accept-Ranges: bytes\r\n".toByteArray())
                outputStream.write("Connection: close\r\n".toByteArray())
                outputStream.write("\r\n".toByteArray())
                outputStream.flush()

                if (contentLength <= 0L) return

                val inputStream = fileClient.openFile(
                    host = share.host,
                    shareName = share.shareName,
                    filePath = filePath,
                    credentials = credentials,
                    offset = rangeStart,
                )

                inputStream.use { stream ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var remaining = contentLength
                    while (remaining > 0) {
                        if (paused || lockedProvider.invoke()) {
                            return
                        }
                        val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                        val read = stream.read(buffer, 0, toRead)
                        if (read == -1) break
                        outputStream.write(buffer, 0, read)
                        remaining -= read
                    }
                    outputStream.flush()
                }
            }
        } catch (e: Exception) {
            if (!paused) {
                Timber.d(e, "NetworkStreamProxy request ended")
            }
        } finally {
            activeSockets.remove(socket)
            connectionSemaphore.release()
        }
    }

    private data class ParsedHttpHead(
        val requestLine: String,
        val headers: Map<String, String>,
        val tooLarge: Boolean = false,
    )

    private fun readHttpRequestHead(input: InputStream): ParsedHttpHead? {
        val raw = ByteArrayOutputStream()
        var totalBytes = 0
        var last0 = -1
        var last1 = -1
        var last2 = -1
        var last3 = -1
        while (true) {
            val b = input.read()
            if (b == -1) break
            totalBytes++
            if (totalBytes > MAX_HEADER_BYTES) {
                return ParsedHttpHead("", emptyMap(), tooLarge = true)
            }
            raw.write(b)
            last0 = last1
            last1 = last2
            last2 = last3
            last3 = b
            if (last0 == '\r'.code && last1 == '\n'.code && last2 == '\r'.code && last3 == '\n'.code) {
                break
            }
        }
        val text = raw.toString(Charsets.ISO_8859_1.name())
        val lines = text.split("\r\n")
        val requestLine = lines.firstOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        val headers = mutableMapOf<String, String>()
        var headerCount = 0
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.isEmpty()) break
            headerCount++
            if (headerCount > MAX_HEADER_COUNT) {
                return ParsedHttpHead("", emptyMap(), tooLarge = true)
            }
            val colonIndex = line.indexOf(':')
            if (colonIndex > 0) {
                headers[line.substring(0, colonIndex).trim().lowercase()] =
                    line.substring(colonIndex + 1).trim()
            }
        }
        return ParsedHttpHead(requestLine, headers)
    }

    private fun hasTraversalSegment(path: String): Boolean {
        val segments = path.split('/', '\\')
        return segments.any { it == "." || it == ".." }
    }

    internal fun parseRangeStrict(rangeHeader: String?, fileSize: Long): Pair<Long, Long>? {
        if (fileSize < 0L) return null
        if (rangeHeader == null) {
            return if (fileSize == 0L) 0L to -1L else 0L to (fileSize - 1L)
        }
        if (fileSize == 0L) return null
        if (!rangeHeader.startsWith("bytes=", ignoreCase = true)) return null

        val spec = rangeHeader.substring(6).trim()
        if (spec.isEmpty() || ',' in spec) return null

        val dashIdx = spec.indexOf('-')
        if (dashIdx == -1) return null

        val startPart = spec.substring(0, dashIdx).trim()
        val endPart = spec.substring(dashIdx + 1).trim()

        val start: Long
        val end: Long
        if (startPart.isEmpty()) {
            // Suffix range: bytes=-N
            val suffix = endPart.toLongOrNull() ?: return null
            if (suffix <= 0L) return null
            start = maxOf(fileSize - suffix, 0L)
            end = fileSize - 1L
        } else {
            start = startPart.toLongOrNull() ?: return null
            end = if (endPart.isEmpty()) {
                fileSize - 1L
            } else {
                val parsedEnd = endPart.toLongOrNull() ?: return null
                minOf(parsedEnd, fileSize - 1L)
            }
        }

        if (start < 0L || end < start || start >= fileSize || end >= fileSize) {
            return null
        }
        return start to end
    }

    private fun parseQueryString(query: String): Map<String, String> {
        return query.split("&").mapNotNull { param ->
            val kv = param.split("=", limit = 2)
            if (kv.size == 2) {
                runCatching { kv[0] to URLDecoder.decode(kv[1], "UTF-8") }.getOrNull()
            } else {
                null
            }
        }.toMap()
    }

    private fun sendError(
        outputStream: OutputStream,
        code: Int,
        message: String,
        extraHeaders: String = "",
    ) {
        val response =
            "HTTP/1.1 $code $message\r\n${extraHeaders}Content-Length: 0\r\nConnection: close\r\n\r\n"
        outputStream.write(response.toByteArray())
        outputStream.flush()
    }

    companion object {
        private const val BUFFER_SIZE = 64 * 1024 // 64KB
        private const val LOOPBACK_HOST = "127.0.0.1"
        private const val SOCKET_TIMEOUT_MS = 5_000
        private const val MAX_CONCURRENT_CONNECTIONS = 8
        private const val MAX_HEADER_BYTES = 8 * 1024
        private const val MAX_HEADER_COUNT = 32

        private val activeTargets: MutableSet<Pair<String, Int>> =
            Collections.newSetFromMap(ConcurrentHashMap())

        fun activeLoopbackTargets(): Set<Pair<String, Int>> = activeTargets.toSet()

        private fun generateAuthToken(): String {
            val bytes = ByteArray(16)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
