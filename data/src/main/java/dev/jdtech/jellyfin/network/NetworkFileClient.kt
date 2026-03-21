package dev.jdtech.jellyfin.network

import java.io.InputStream

data class NetworkFileEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long?,
)

data class NetworkCredentials(
    val username: String? = null,
    val password: String? = null,
    val domain: String? = null,
)

interface NetworkFileClient {
    suspend fun listFiles(
        host: String,
        shareName: String,
        path: String = "/",
        credentials: NetworkCredentials = NetworkCredentials(),
    ): List<NetworkFileEntry>

    suspend fun openFile(
        host: String,
        shareName: String,
        filePath: String,
        credentials: NetworkCredentials = NetworkCredentials(),
        offset: Long = 0L,
    ): InputStream

    suspend fun getFileSize(
        host: String,
        shareName: String,
        filePath: String,
        credentials: NetworkCredentials = NetworkCredentials(),
    ): Long

    suspend fun testConnection(
        host: String,
        shareName: String,
        credentials: NetworkCredentials = NetworkCredentials(),
    ): Boolean

    companion object {
        val VIDEO_EXTENSIONS = setOf(
            "mkv", "mp4", "avi", "m4v", "ts", "wmv", "mov", "webm", "flv", "mpg", "mpeg", "m2ts",
        )

        fun isVideoFile(name: String): Boolean {
            val ext = name.substringAfterLast('.', "").lowercase()
            return ext in VIDEO_EXTENSIONS
        }
    }
}
