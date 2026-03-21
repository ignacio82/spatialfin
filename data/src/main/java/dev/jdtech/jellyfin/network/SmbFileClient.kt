package dev.jdtech.jellyfin.network

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

class SmbFileClient : NetworkFileClient {

    private val config = SmbConfig.builder()
        .withTimeout(15, TimeUnit.SECONDS)
        .withSoTimeout(15, TimeUnit.SECONDS)
        .build()

    private val client = SMBClient(config)

    override suspend fun listFiles(
        host: String,
        shareName: String,
        path: String,
        credentials: NetworkCredentials,
    ): List<NetworkFileEntry> = withContext(Dispatchers.IO) {
        useShare(host, shareName, credentials) { share ->
            val normalizedPath = path.trimStart('/').ifEmpty { "" }
            share.list(normalizedPath).mapNotNull { info ->
                val name = info.fileName
                if (name == "." || name == "..") return@mapNotNull null
                val fullPath = if (normalizedPath.isEmpty()) name else "$normalizedPath/$name"
                val isDir = info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L
                NetworkFileEntry(
                    name = name,
                    path = fullPath,
                    isDirectory = isDir,
                    size = info.endOfFile,
                    lastModified = info.lastWriteTime?.toEpochMillis(),
                )
            }
        }
    }

    override suspend fun openFile(
        host: String,
        shareName: String,
        filePath: String,
        credentials: NetworkCredentials,
        offset: Long,
    ): InputStream = withContext(Dispatchers.IO) {
        val connection = client.connect(host)
        val authContext = credentials.toAuthContext()
        val session = connection.authenticate(authContext)
        val share = session.connectShare(shareName) as DiskShare

        val normalizedPath = filePath.trimStart('/')
        val file = share.openFile(
            normalizedPath,
            setOf(AccessMask.GENERIC_READ),
            null,
            setOf(SMB2ShareAccess.FILE_SHARE_READ),
            SMB2CreateDisposition.FILE_OPEN,
            setOf(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
        )

        val inputStream = file.inputStream
        if (offset > 0) {
            inputStream.skip(offset)
        }

        // Wrap to close resources when stream is closed
        object : InputStream() {
            override fun read(): Int = inputStream.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int = inputStream.read(b, off, len)
            override fun available(): Int = inputStream.available()
            override fun close() {
                try { inputStream.close() } catch (_: Exception) {}
                try { file.close() } catch (_: Exception) {}
                try { share.close() } catch (_: Exception) {}
                try { session.close() } catch (_: Exception) {}
                try { connection.close() } catch (_: Exception) {}
            }
        }
    }

    override suspend fun getFileSize(
        host: String,
        shareName: String,
        filePath: String,
        credentials: NetworkCredentials,
    ): Long = withContext(Dispatchers.IO) {
        useShare(host, shareName, credentials) { share ->
            val normalizedPath = filePath.trimStart('/')
            val info = share.getFileInformation(normalizedPath)
            info.standardInformation.endOfFile
        }
    }

    override suspend fun testConnection(
        host: String,
        shareName: String,
        credentials: NetworkCredentials,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            useShare(host, shareName, credentials) { share ->
                share.list("").size >= 0
            }
            true
        } catch (e: Exception) {
            Timber.e(e, "SMB connection test failed for $host/$shareName")
            false
        }
    }

    private inline fun <T> useShare(
        host: String,
        shareName: String,
        credentials: NetworkCredentials,
        block: (DiskShare) -> T,
    ): T {
        val connection = client.connect(host)
        try {
            val authContext = credentials.toAuthContext()
            val session = connection.authenticate(authContext)
            try {
                val share = session.connectShare(shareName) as DiskShare
                try {
                    return block(share)
                } finally {
                    try { share.close() } catch (_: Exception) {}
                }
            } finally {
                try { session.close() } catch (_: Exception) {}
            }
        } finally {
            try { connection.close() } catch (_: Exception) {}
        }
    }

    private fun NetworkCredentials.toAuthContext(): AuthenticationContext {
        return if (username.isNullOrBlank()) {
            AuthenticationContext.guest()
        } else {
            AuthenticationContext(
                username,
                password?.toCharArray() ?: charArrayOf(),
                domain,
            )
        }
    }
}
