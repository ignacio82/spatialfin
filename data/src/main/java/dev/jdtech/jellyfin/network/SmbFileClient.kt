package dev.jdtech.jellyfin.network

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
    override suspend fun listFiles(
        host: String,
        shareName: String,
        path: String,
        credentials: NetworkCredentials,
    ): List<NetworkFileEntry> = withContext(Dispatchers.IO) {
        val target = requireTarget(host, shareName)
        val normalizedPath = SmbPathNormalizer.normalizeRelativePath(path)
        useSmbFile(target, normalizedPath, credentials) { directory ->
            val children = directory.listFiles()
            try {
                children.mapNotNull { child ->
                    val name = child.getName().trimEnd('/', '\\')
                    if (name == "." || name == ".." || name.isEmpty()) return@mapNotNull null
                    val isDirectory = child.isDirectory
                    NetworkFileEntry(
                        name = name,
                        path = if (normalizedPath.isEmpty()) name else "$normalizedPath/$name",
                        isDirectory = isDirectory,
                        size = if (isDirectory) 0L else child.length(),
                        lastModified = child.lastModified().takeIf { it > 0L },
                    )
                }
            } finally {
                children.forEach { child -> runCatching { child.close() } }
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
        require(offset >= 0L) { "SMB read offset must not be negative" }
        val context = createBrowserContext(credentials, anonymous = false)
        val file = createOwnedSmbFile(context) { smbUrl(target, filePath) }
        try {
            val inputStream = file.openInputStream()
            try {
                if (offset > 0L) skipFully(inputStream, offset)
                object : InputStream() {
                    private var closed = false

                    override fun read(): Int = inputStream.read()

                    override fun read(b: ByteArray, off: Int, len: Int): Int =
                        inputStream.read(b, off, len)

                    override fun available(): Int = inputStream.available()

                    override fun close() {
                        if (closed) return
                        closed = true
                        runCatching { inputStream.close() }
                        runCatching { file.close() }
                        runCatching { context.close() }
                    }
                }
            } catch (e: Throwable) {
                runCatching { inputStream.close() }
                throw e
            }
        } catch (e: Throwable) {
            runCatching { file.close() }
            runCatching { context.close() }
            throw e
        }
    }

    override suspend fun getFileSize(
        host: String,
        shareName: String,
        filePath: String,
        credentials: NetworkCredentials,
    ): Long = withContext(Dispatchers.IO) {
        val target = requireTarget(host, shareName)
        useSmbFile(target, filePath, credentials) { file -> file.length() }
    }

    override suspend fun testConnection(
        host: String,
        shareName: String,
        credentials: NetworkCredentials,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val target = requireTarget(host, shareName)
            useSmbFile(target, "", credentials) { directory ->
                val children = directory.listFiles()
                children.forEach { child -> runCatching { child.close() } }
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
        val server = createOwnedSmbFile(context) { "smb://${host.toSmbUrlHost()}/" }
        return try {
            val children = server.listFiles()
            try {
                children
                    .mapNotNull { file -> file.toDiscoveredServerShare() }
                    .distinctBy { it.name.lowercase() }
                    .sortedBy { it.name.lowercase() }
            } finally {
                children.forEach { child -> runCatching { child.close() } }
            }
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
        return try {
            if (anonymous) {
                baseContext.withAnonymousCredentials()
            } else {
                val username = credentials.username?.takeIf { it.isNotBlank() }
                if (username == null) {
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
        } catch (error: Throwable) {
            runCatching { baseContext.close() }
            throw error
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

    private inline fun <T> useSmbFile(
        target: SmbConnectionTarget,
        path: String,
        credentials: NetworkCredentials,
        block: (SmbFile) -> T,
    ): T {
        val context = createBrowserContext(credentials, anonymous = false)
        val file = createOwnedSmbFile(context) { smbUrl(target, path) }
        try {
            return block(file)
        } finally {
            runCatching { file.close() }
            runCatching { context.close() }
        }
    }

    private inline fun createOwnedSmbFile(context: CIFSContext, url: () -> String): SmbFile =
        try {
            SmbFile(url(), context)
        } catch (error: Throwable) {
            runCatching { context.close() }
            throw error
        }

    private fun smbUrl(target: SmbConnectionTarget, path: String): String {
        val normalizedPath = SmbPathNormalizer.normalizeRelativePath(path)
        val base = "smb://${target.host.toSmbUrlHost()}/${target.shareName.trim('/', '\\')}/"
        return if (normalizedPath.isEmpty()) base else "$base$normalizedPath"
    }

    private fun String.toSmbUrlHost(): String =
        if (contains(':') && !startsWith('[')) "[$this]" else this

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
