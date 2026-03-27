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
import dev.jdtech.jellyfin.network.SmbConnectionTarget
import dev.jdtech.jellyfin.network.SmbPathNormalizer
import java.io.InputStream
import java.util.Properties
import java.util.concurrent.TimeUnit
import jcifs.CIFSContext
import jcifs.DialectVersion
import jcifs.SmbConstants
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
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
        val target = requireTarget(host, shareName)
        useShare(target.host, target.shareName, credentials) { share ->
            val normalizedPath = SmbPathNormalizer.normalizeRelativePath(path)
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
        val target = requireTarget(host, shareName)
        val connection = client.connect(target.host)
        val authContext = credentials.toAuthContext()
        val session = connection.authenticate(authContext)
        val share = session.connectShare(target.shareName) as DiskShare

        val normalizedPath = SmbPathNormalizer.normalizeRelativePath(filePath)
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
            skipFully(inputStream, offset)
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
        val target = requireTarget(host, shareName)
        useShare(target.host, target.shareName, credentials) { share ->
            val normalizedPath = SmbPathNormalizer.normalizeRelativePath(filePath)
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
            val target = requireTarget(host, shareName)
            useShare(target.host, target.shareName, credentials) { share ->
                share.list("").size >= 0
            }
            true
        } catch (e: Exception) {
            Timber.e(e, "SMB connection test failed for $host/$shareName")
            false
        }
    }

    suspend fun listServerShares(
        host: String,
        credentials: NetworkCredentials,
    ): List<DiscoveredSmbServerShare> = withContext(Dispatchers.IO) {
        val normalizedHost = SmbPathNormalizer.normalizeConnectionTarget(host, "").host
        require(normalizedHost.isNotBlank()) { "SMB host is required." }

        runCatching {
            listServerSharesWithContext(
                host = normalizedHost,
                context = createBrowserContext(credentials, anonymous = false),
            )
        }.recoverCatching { error ->
            if (credentials.username.isNullOrBlank()) {
                Timber.d(error, "Guest SMB share listing failed for %s, retrying anonymously", normalizedHost)
                listServerSharesWithContext(
                    host = normalizedHost,
                    context = createBrowserContext(credentials, anonymous = true),
                )
            } else {
                throw error
            }
        }.getOrElse { error ->
            Timber.e(error, "SMB server share listing failed for %s", normalizedHost)
            throw error
        }
    }

    private fun listServerSharesWithContext(
        host: String,
        context: CIFSContext,
    ): List<DiscoveredSmbServerShare> {
        val server = SmbFile("smb://$host/", context)
        return try {
            server.listFiles()
                .mapNotNull { file -> file.toDiscoveredServerShare() }
                .distinctBy { it.name.lowercase() }
                .sortedBy { it.name.lowercase() }
        } finally {
            try { server.close() } catch (_: Exception) {}
            try { context.close() } catch (_: Exception) {}
        }
    }

    private fun createBrowserContext(
        credentials: NetworkCredentials,
        anonymous: Boolean,
    ): CIFSContext {
        val properties = Properties().apply {
            setProperty("jcifs.smb.client.minVersion", DialectVersion.SMB202.name)
            setProperty("jcifs.smb.client.maxVersion", DialectVersion.SMB311.name)
            setProperty("jcifs.resolveOrder", "DNS")
            setProperty("jcifs.smb.client.responseTimeout", TimeUnit.SECONDS.toMillis(15).toString())
            setProperty("jcifs.smb.client.soTimeout", TimeUnit.SECONDS.toMillis(15).toString())
        }
        val baseContext = BaseContext(PropertyConfiguration(properties))
        if (anonymous) {
            return baseContext.withAnonymousCredentials()
        }

        val username = credentials.username?.takeIf { it.isNotBlank() }
        return if (username == null) {
            baseContext.withGuestCrendentials()
        } else {
            baseContext.withCredentials(
                NtlmPasswordAuthenticator(
                    credentials.domain.orEmpty(),
                    username,
                    credentials.password.orEmpty(),
                )
            )
        }
    }

    private fun SmbFile.toDiscoveredServerShare(): DiscoveredSmbServerShare? {
        val type = runCatching { getType() }.getOrElse { error ->
            Timber.w(error, "Skipping SMB server child %s because its type could not be read", path)
            return null
        }
        if (type != SmbConstants.TYPE_SHARE && type != SmbConstants.TYPE_FILESYSTEM) {
            return null
        }

        val shareName = runCatching { getShare() }
            .getOrNull()
            .orEmpty()
            .ifBlank { getName().trimEnd('/', '\\') }
            .trim()
        if (shareName.isBlank()) return null

        return DiscoveredSmbServerShare(name = shareName)
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

    private fun requireTarget(host: String, shareName: String): SmbConnectionTarget {
        val target = SmbPathNormalizer.normalizeConnectionTarget(host, shareName)
        require(target.host.isNotBlank()) { "SMB host is required." }
        require(target.shareName.isNotBlank()) { "SMB share name is required." }
        return target
    }

    private fun skipFully(inputStream: InputStream, offset: Long) {
        var remaining = offset
        while (remaining > 0) {
            val skipped = inputStream.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
                continue
            }

            if (inputStream.read() == -1) {
                break
            }
            remaining--
        }
    }
}
