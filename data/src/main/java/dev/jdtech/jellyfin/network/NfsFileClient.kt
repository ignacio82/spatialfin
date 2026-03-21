package dev.jdtech.jellyfin.network

import java.io.InputStream
import java.lang.reflect.Method
import java.net.InetAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.dcache.nfs.v4.CompoundBuilder
import org.dcache.nfs.v4.client.Main as NfsClient
import org.dcache.nfs.v4.xdr.COMPOUND4args
import org.dcache.nfs.v4.xdr.COMPOUND4res
import org.dcache.nfs.v4.xdr.nfs4_prot
import org.dcache.nfs.v4.xdr.nfs_fh4
import org.dcache.nfs.v4.xdr.stateid4
import org.dcache.nfs.v4.xdr.verifier4
import timber.log.Timber

/**
 * NFS v4.1 client implementation using dCache nfs4j library.
 * Uses the [NfsClient] class for session management and
 * [CompoundBuilder] for building NFS4 compound operations.
 */
class NfsFileClient : NetworkFileClient {

    override suspend fun listFiles(
        host: String,
        shareName: String,
        path: String,
        credentials: NetworkCredentials,
    ): List<NetworkFileEntry> = withContext(Dispatchers.IO) {
        useSession(host, shareName) { client ->
            val rootFh = getRootFh(client)
            val targetFh = if (path.trim('/').isEmpty()) {
                rootFh
            } else {
                lookupPath(client, rootFh, path)
            }

            // Use the public list() method to get entry names
            val names = client.list(targetFh)
            val entries = mutableListOf<NetworkFileEntry>()

            for (name in names) {
                if (name == "." || name == "..") continue
                try {
                    // Lookup each entry to get its file handle, then stat
                    val entryFh = lookupPath(client, targetFh, name)
                    val stat = client.stat(entryFh)
                    val fullPath = normalizePath(path, name)
                    entries.add(
                        NetworkFileEntry(
                            name = name,
                            path = fullPath,
                            isDirectory = stat.type() == org.dcache.nfs.vfs.Stat.Type.DIRECTORY,
                            size = stat.size,
                            lastModified = stat.mTime,
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
        val address = InetAddress.getByName(host)
        val client = NfsClient(address)
        try {
            client.mount(shareName)
            val rootFh = getRootFh(client)
            val sendMethod = getSendMethod(client)

            // Open file for reading
            val dirPath = filePath.trim('/').substringBeforeLast('/', "")
            val fileName = filePath.trim('/').substringAfterLast('/')
            val dirFh = if (dirPath.isEmpty()) rootFh else lookupPath(client, rootFh, dirPath)

            val clientId = getClientId(client)
            val seqId = getSequenceId(client)

            val openArgs = CompoundBuilder()
                .withPutfh(dirFh)
                .withOpen(fileName, seqId, clientId, nfs4_prot.OPEN4_SHARE_ACCESS_READ)
                .withGetfh()
                .withTag("open_read")
                .build()

            val openRes = sendMethod.invoke(client, openArgs) as COMPOUND4res
            val opCount = openRes.resarray.size
            val fileFh = openRes.resarray[opCount - 1].opgetfh.resok4.`object`
            val stateId = openRes.resarray[opCount - 2].opopen.resok4.stateid

            // Get file size
            val stat = client.stat(fileFh)
            val fileSize = stat.size

            NfsInputStream(client, fileFh, stateId, fileSize, offset, sendMethod)
        } catch (e: Exception) {
            try { client.umount() } catch (_: Exception) {}
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
            val rootFh = getRootFh(client)
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
                val rootFh = getRootFh(client)
                client.list(rootFh).size >= 0
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
        block: (NfsClient) -> T,
    ): T {
        val address = InetAddress.getByName(host)
        val client = NfsClient(address)
        try {
            client.mount(exportPath)
            return block(client)
        } finally {
            try { client.umount() } catch (_: Exception) {}
        }
    }

    /**
     * Lookup a path component by component, returning the final file handle.
     */
    private fun lookupPath(client: NfsClient, startFh: nfs_fh4, path: String): nfs_fh4 {
        val sendMethod = getSendMethod(client)
        val normalized = path.trim('/')
        if (normalized.isEmpty()) return startFh

        val args = CompoundBuilder()
            .withPutfh(startFh)
            .withLookup(normalized)
            .withGetfh()
            .withTag("lookup")
            .build()

        val res = sendMethod.invoke(client, args) as COMPOUND4res
        return res.resarray[res.resarray.size - 1].opgetfh.resok4.`object`
    }

    private fun getRootFh(client: NfsClient): nfs_fh4 {
        val field = NfsClient::class.java.getDeclaredField("_rootFh")
        field.isAccessible = true
        return field.get(client) as nfs_fh4
    }

    private fun getClientId(client: NfsClient): org.dcache.nfs.v4.xdr.clientid4 {
        val field = NfsClient::class.java.getDeclaredField("_clientIdByServer")
        field.isAccessible = true
        return field.get(client) as org.dcache.nfs.v4.xdr.clientid4
    }

    private fun getSequenceId(client: NfsClient): Int {
        val field = NfsClient::class.java.getDeclaredField("_sequenceID")
        field.isAccessible = true
        val seqId = field.get(client) as org.dcache.nfs.v4.xdr.sequenceid4
        return seqId.value
    }

    private fun getSendMethod(client: NfsClient): Method {
        val method = NfsClient::class.java.getDeclaredMethod(
            "sendCompoundInSession",
            COMPOUND4args::class.java,
        )
        method.isAccessible = true
        return method
    }

    private fun normalizePath(parent: String, name: String): String {
        val base = parent.trim('/').trimEnd('/')
        return if (base.isEmpty()) name else "$base/$name"
    }

    /**
     * InputStream that reads from an NFS file using compound READ operations.
     * Owns the NFS session and closes it when the stream is closed.
     */
    private class NfsInputStream(
        private val client: NfsClient,
        private val fileFh: nfs_fh4,
        private val stateId: stateid4,
        private val fileSize: Long,
        private var position: Long,
        private val sendMethod: Method,
    ) : InputStream() {

        private var closed = false

        override fun read(): Int {
            if (position >= fileSize) return -1
            val buf = ByteArray(1)
            val n = read(buf, 0, 1)
            return if (n == -1) -1 else buf[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (position >= fileSize) return -1
            val toRead = minOf(len, READ_CHUNK_SIZE, (fileSize - position).toInt())

            val args = CompoundBuilder()
                .withPutfh(fileFh)
                .withRead(toRead, position, stateId)
                .withTag("read")
                .build()

            val res = sendMethod.invoke(client, args) as COMPOUND4res
            val readRes = res.resarray[res.resarray.size - 1].opread.resok4
            val data = readRes.data
            if (data.remaining() == 0) return -1

            val bytesRead = data.remaining()
            data.get(b, off, bytesRead)
            position += bytesRead
            return bytesRead
        }

        override fun available(): Int {
            return minOf(fileSize - position, Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(0)
        }

        override fun close() {
            if (!closed) {
                closed = true
                try {
                    // Close the open file
                    val closeArgs = CompoundBuilder()
                        .withPutfh(fileFh)
                        .withClose(stateId, 0)
                        .withTag("close")
                        .build()
                    sendMethod.invoke(client, closeArgs)
                } catch (_: Exception) {}
                try { client.umount() } catch (_: Exception) {}
            }
        }

        private companion object {
            private const val READ_CHUNK_SIZE = 65536 // 64KB
        }
    }
}
