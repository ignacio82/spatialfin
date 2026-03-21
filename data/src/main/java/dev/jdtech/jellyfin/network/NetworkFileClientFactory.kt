package dev.jdtech.jellyfin.network

/**
 * Provides the correct [NetworkFileClient] implementation based on protocol.
 */
class NetworkFileClientFactory(
    private val smbFileClient: SmbFileClient,
    private val nfsFileClient: NfsFileClient,
) {
    fun clientFor(protocol: String): NetworkFileClient = when (protocol.lowercase()) {
        "smb" -> smbFileClient
        "nfs" -> nfsFileClient
        else -> throw IllegalArgumentException("Unsupported protocol: $protocol")
    }
}
