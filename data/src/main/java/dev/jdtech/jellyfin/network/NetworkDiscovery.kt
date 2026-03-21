package dev.jdtech.jellyfin.network

import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

data class DiscoveredShare(
    val host: String,
    val serviceName: String,
    val port: Int,
    val protocol: String,
)

class NetworkDiscovery {

    /**
     * Discover both SMB and NFS shares on the local network using mDNS.
     */
    suspend fun discoverAll(timeoutMs: Long = 5000L): List<DiscoveredShare> =
        withContext(Dispatchers.IO) {
            val results = mutableListOf<DiscoveredShare>()
            var jmdns: JmDNS? = null
            try {
                jmdns = JmDNS.create()

                withTimeoutOrNull(timeoutMs) {
                    // Discover SMB shares
                    val smbServices = jmdns.list(SMB_SERVICE_TYPE, timeoutMs)
                    for (service in smbServices) {
                        val host = service.hostAddresses.firstOrNull() ?: continue
                        results.add(
                            DiscoveredShare(
                                host = host,
                                serviceName = service.name,
                                port = service.port.takeIf { it > 0 } ?: 445,
                                protocol = "smb",
                            )
                        )
                    }

                    // Discover NFS shares
                    val nfsServices = jmdns.list(NFS_SERVICE_TYPE, timeoutMs)
                    for (service in nfsServices) {
                        val host = service.hostAddresses.firstOrNull() ?: continue
                        val exportPath = service.getPropertyString("path")
                        results.add(
                            DiscoveredShare(
                                host = host,
                                serviceName = exportPath ?: service.name,
                                port = service.port.takeIf { it > 0 } ?: 2049,
                                protocol = "nfs",
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "mDNS discovery failed")
            } finally {
                try { jmdns?.close() } catch (_: Exception) {}
            }

            results.distinctBy { "${it.protocol}:${it.host}:${it.serviceName}" }
        }

    @Deprecated("Use discoverAll() instead", replaceWith = ReplaceWith("discoverAll(timeoutMs)"))
    suspend fun discoverSmb(timeoutMs: Long = 5000L): List<DiscoveredShare> =
        discoverAll(timeoutMs).filter { it.protocol == "smb" }

    private companion object {
        private const val SMB_SERVICE_TYPE = "_smb._tcp.local."
        private const val NFS_SERVICE_TYPE = "_nfs._tcp.local."
    }
}
