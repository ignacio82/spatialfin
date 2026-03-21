package dev.jdtech.jellyfin.network

import dev.jdtech.jellyfin.database.ServerDatabaseDao
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Local HTTP proxy that bridges network file access (SMB/NFS) with ExoPlayer.
 * Starts a lightweight HTTP server on localhost that serves files
 * with Range header support for seeking.
 */
class NetworkStreamProxy(
    private val clientFactory: NetworkFileClientFactory,
    private val database: ServerDatabaseDao,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null
    private var port: Int = 0

    @Synchronized
    fun start() {
        if (serverSocket != null) return
        val socket = ServerSocket(0)
        serverSocket = socket
        port = socket.localPort
        Timber.d("NetworkStreamProxy started on port $port")
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
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
    }

    fun getStreamUrl(shareId: String, filePath: String): String {
        if (serverSocket == null) start()
        return "http://127.0.0.1:$port/stream?shareId=$shareId&path=${
            java.net.URLEncoder.encode(filePath, "UTF-8")
        }"
    }

    private suspend fun handleClient(socket: Socket) {
        try {
            socket.use { client ->
                val reader = client.getInputStream().bufferedReader()
                val requestLine = reader.readLine() ?: return
                val headers = mutableMapOf<String, String>()
                var line = reader.readLine()
                while (!line.isNullOrEmpty()) {
                    val colonIndex = line.indexOf(':')
                    if (colonIndex > 0) {
                        headers[line.substring(0, colonIndex).trim().lowercase()] =
                            line.substring(colonIndex + 1).trim()
                    }
                    line = reader.readLine()
                }

                val parts = requestLine.split(" ")
                if (parts.size < 2 || !parts[1].startsWith("/stream?")) {
                    sendError(client.getOutputStream(), 400, "Bad Request")
                    return
                }

                val queryString = parts[1].substringAfter("?")
                val params = parseQueryString(queryString)
                val shareId = params["shareId"] ?: run {
                    sendError(client.getOutputStream(), 400, "Missing shareId")
                    return
                }
                val filePath = params["path"] ?: run {
                    sendError(client.getOutputStream(), 400, "Missing path")
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

                val rangeHeader = headers["range"]
                val (rangeStart, rangeEnd) = parseRange(rangeHeader, fileSize)

                val contentLength = rangeEnd - rangeStart + 1
                val outputStream = client.getOutputStream()

                if (rangeHeader != null) {
                    outputStream.write(
                        "HTTP/1.1 206 Partial Content\r\n".toByteArray()
                    )
                    outputStream.write(
                        "Content-Range: bytes $rangeStart-$rangeEnd/$fileSize\r\n".toByteArray()
                    )
                } else {
                    outputStream.write("HTTP/1.1 200 OK\r\n".toByteArray())
                }

                outputStream.write("Content-Type: application/octet-stream\r\n".toByteArray())
                outputStream.write("Content-Length: $contentLength\r\n".toByteArray())
                outputStream.write("Accept-Ranges: bytes\r\n".toByteArray())
                outputStream.write("Connection: close\r\n".toByteArray())
                outputStream.write("\r\n".toByteArray())
                outputStream.flush()

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
            Timber.e(e, "Error handling stream request")
        }
    }

    private fun parseRange(rangeHeader: String?, fileSize: Long): Pair<Long, Long> {
        if (rangeHeader == null) return 0L to fileSize - 1

        val range = rangeHeader.removePrefix("bytes=").trim()
        return when {
            range.endsWith("-") -> {
                val start = range.removeSuffix("-").toLongOrNull() ?: 0L
                start to fileSize - 1
            }
            range.startsWith("-") -> {
                val suffix = range.removePrefix("-").toLongOrNull() ?: fileSize
                maxOf(fileSize - suffix, 0L) to fileSize - 1
            }
            else -> {
                val parts = range.split("-", limit = 2)
                val start = parts[0].toLongOrNull() ?: 0L
                val end = parts.getOrNull(1)?.toLongOrNull() ?: (fileSize - 1)
                start to minOf(end, fileSize - 1)
            }
        }
    }

    private fun parseQueryString(query: String): Map<String, String> {
        return query.split("&").mapNotNull { param ->
            val kv = param.split("=", limit = 2)
            if (kv.size == 2) {
                kv[0] to URLDecoder.decode(kv[1], "UTF-8")
            } else {
                null
            }
        }.toMap()
    }

    private fun sendError(outputStream: OutputStream, code: Int, message: String) {
        val response = "HTTP/1.1 $code $message\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        outputStream.write(response.toByteArray())
        outputStream.flush()
    }

    private companion object {
        private const val BUFFER_SIZE = 64 * 1024 // 64KB
    }
}
