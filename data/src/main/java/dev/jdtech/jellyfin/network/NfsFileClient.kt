package dev.jdtech.jellyfin.network

import java.io.InputStream
import java.io.IOException
import java.net.InetAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.dcache.nfs.v4.xdr.nfs_fh4
import org.dcache.nfs.v4.xdr.stateid4
import timber.log.Timber

/**
 * NFS v4.1 client implementation using the Android-safe nfs4j-core client.
 */
class NfsFileClient : NetworkFileClient {

    override suspend fun listFiles(
        host: String,
        shareName: String,
        path: String,
        credentials: NetworkCredentials,
    ): List<NetworkFileEntry> = withContext(Dispatchers.IO) {
        useSession(host, shareName) { client ->
            val rootFh = client.rootFileHandle()
            val targetFh = if (path.trim('/').isEmpty()) {
                rootFh
            } else {
                lookupPath(client, rootFh, path)
            }

            val names = client.list(targetFh)
            val entries = mutableListOf<NetworkFileEntry>()

            for (name in names) {
                if (name == "." || name == "..") continue
                try {
                    val entryFh = lookupPath(client, targetFh, name)
                    val stat = client.stat(entryFh)
                    val fullPath = normalizePath(path, name)
                    entries.add(
                        NetworkFileEntry(
                            name = name,
                            path = fullPath,
                            isDirectory = stat.isDirectory,
                            size = stat.size,
                            lastModified = stat.lastModifiedMillis,
                        )
                    )
                } catch (e: Exception) {
                    Timber.w(e, "Failed to stat NFS entry: $name")
                }
            }
            entries
        }
    }

    override suspend fun openFile(
        host: String,
        shareName: String,
        filePath: String,
        credentials: NetworkCredentials,
        offset: Long,
    ): InputStream = withContext(Dispatchers.IO) {
        require(offset >= 0L) { "NFS read offset must not be negative" }
        val address = InetAddress.getByName(host)
        val client = AndroidNfsClient.connect(address)
        try {
            client.mount(shareName)
            val rootFh = client.rootFileHandle()

            val normalizedPath = filePath.trim('/')
            val dirPath = normalizedPath.substringBeforeLast('/', "")
            val fileName = normalizedPath.substringAfterLast('/')
            val dirFh = if (dirPath.isEmpty()) rootFh else lookupPath(client, rootFh, dirPath)
            val openFile = client.openForRead(dirFh, fileName)

            val stat = client.stat(openFile.fileHandle)
            val fileSize = stat.size

            NfsInputStream(
                client = client,
                fileFh = openFile.fileHandle,
                stateId = openFile.stateId,
                fileSize = fileSize,
                position = offset,
            )
        } catch (e: Exception) {
            client.close()
            throw e
        }
    }

    override suspend fun getFileSize(
        host: String,
        shareName: String,
        filePath: String,
        credentials: NetworkCredentials,
    ): Long = withContext(Dispatchers.IO) {
        useSession(host, shareName) { client ->
            val rootFh = client.rootFileHandle()
            val fileFh = lookupPath(client, rootFh, filePath)
            client.stat(fileFh).size
        }
    }

    override suspend fun testConnection(
        host: String,
        shareName: String,
        credentials: NetworkCredentials,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            useSession(host, shareName) { client ->
                client.list(client.rootFileHandle())
            }
            true
        } catch (e: Exception) {
            Timber.e(e, "NFS connection test failed for $host:$shareName")
            false
        }
    }

    // --- Private helpers ---

    private inline fun <T> useSession(
        host: String,
        exportPath: String,
        block: (AndroidNfsClient) -> T,
    ): T {
        val address = InetAddress.getByName(host)
        return AndroidNfsClient.connect(address).use { client ->
            client.mount(exportPath)
            block(client)
        }
    }

    /**
     * Lookup a path component by component, returning the final file handle.
     */
    private fun lookupPath(
        client: AndroidNfsClient,
        startFh: nfs_fh4,
        path: String,
    ): nfs_fh4 = client.lookup(startFh, path)

    private fun normalizePath(parent: String, name: String): String {
        val base = parent.trim('/').trimEnd('/')
        return if (base.isEmpty()) name else "$base/$name"
    }

    /**
     * InputStream that reads from an NFS file using compound READ operations.
     * Owns the NFS session and closes it when the stream is closed.
     */
    private class NfsInputStream(
        private val client: AndroidNfsClient,
        private val fileFh: nfs_fh4,
        private val stateId: stateid4,
        private val fileSize: Long,
        private var position: Long,
    ) : InputStream() {

        private var closed = false

        override fun read(): Int {
            val buf = ByteArray(1)
            val n = read(buf, 0, 1)
            return if (n == -1) -1 else buf[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (closed) throw IOException("NFS stream is closed")
            if (off < 0 || len < 0 || off > b.size - len) throw IndexOutOfBoundsException()
            if (len == 0) return 0
            if (position >= fileSize) return -1
            val toRead = minOf(
                len.toLong(),
                READ_CHUNK_SIZE.toLong(),
                fileSize - position,
            ).toInt()

            repeat(MAX_EMPTY_READ_ATTEMPTS) {
                val readResult = client.read(fileFh, stateId, position, toRead)
                val data = readResult.data
                val bytesRead = data.remaining()
                if (bytesRead > 0) {
                    data.get(b, off, bytesRead)
                    position += bytesRead
                    return bytesRead
                }
                if (readResult.endOfFile || position >= fileSize) return -1
            }

            throw IOException("NFS server repeatedly returned an empty non-EOF read")
        }

        override fun available(): Int {
            return minOf(fileSize - position, Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(0)
        }

        override fun close() {
            if (!closed) {
                closed = true
                runCatching { client.closeFile(fileFh, stateId) }
                client.close()
            }
        }

        private companion object {
            private const val READ_CHUNK_SIZE = 65536 // 64KB
            private const val MAX_EMPTY_READ_ATTEMPTS = 3
        }
    }
}
