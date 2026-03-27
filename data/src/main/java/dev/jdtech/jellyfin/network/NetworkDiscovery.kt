package dev.jdtech.jellyfin.network

import android.content.Context
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

data class DiscoveredShare(
    val host: String,
    val serviceName: String,
    val port: Int,
    val protocol: String,
)

class NetworkDiscovery(
    private val context: Context,
) {

    /**
     * Discover both SMB and NFS shares on the local network using mDNS.
     */
    suspend fun discoverAll(timeoutMs: Long = 5000L): List<DiscoveredShare> =
        withContext(Dispatchers.IO) {
            val results = mutableListOf<DiscoveredShare>()
            val multicastLock = acquireMulticastLock()
            val bindAddress = findBindableAddress()
            var jmdns: JmDNS? = null
            try {
                if (bindAddress == null) {
                    Timber.w("mDNS discovery skipped because no suitable bind address was found")
                    return@withContext emptyList()
                }
                jmdns = JmDNS.create(bindAddress)

                // Discover SMB shares.
                val smbServices = jmdns.list(SMB_SERVICE_TYPE, timeoutMs)
                for (service in smbServices) {
                    val host = service.resolveHostAddress() ?: continue
                    results.add(
                        DiscoveredShare(
                            host = host,
                            serviceName = service.name,
                            port = service.port.takeIf { it > 0 } ?: 445,
                            protocol = "smb",
                        )
                    )
                }

                // Discover NFS shares.
                val nfsServices = jmdns.list(NFS_SERVICE_TYPE, timeoutMs)
                for (service in nfsServices) {
                    val host = service.resolveHostAddress() ?: continue
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
            } catch (e: Exception) {
                Timber.e(e, "mDNS discovery failed")
            } finally {
                try { jmdns?.close() } catch (_: Exception) {}
                try { multicastLock?.release() } catch (_: Exception) {}
            }

            results.distinctBy { "${it.protocol}:${it.host}:${it.serviceName}" }
        }

    private fun acquireMulticastLock(): WifiManager.MulticastLock? {
        val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)
            ?: return null
        return try {
            wifiManager.createMulticastLock(MULTICAST_LOCK_TAG).apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: SecurityException) {
            Timber.w(e, "Unable to acquire multicast lock for mDNS discovery")
            null
        } catch (e: RuntimeException) {
            Timber.w(e, "Failed to create multicast lock for mDNS discovery")
            null
        }
    }

    private fun findBindableAddress(): InetAddress? {
        val interfaces = try {
            NetworkInterface.getNetworkInterfaces()
        } catch (e: Exception) {
            Timber.w(e, "Unable to enumerate network interfaces for mDNS discovery")
            return null
        } ?: return null

        var fallback: InetAddress? = null
        while (interfaces.hasMoreElements()) {
            val networkInterface = interfaces.nextElement()
            val usable = try {
                networkInterface.isUp && !networkInterface.isLoopback && networkInterface.supportsMulticast()
            } catch (_: Exception) {
                false
            }
            if (!usable) continue

            val addresses = networkInterface.inetAddresses
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (address.isLoopbackAddress || address.isAnyLocalAddress) continue
                if (address is Inet4Address && !address.isLinkLocalAddress) {
                    return address
                }
                if (fallback == null && !address.isLinkLocalAddress) {
                    fallback = address
                }
            }
        }

        return fallback
    }

    private fun ServiceInfo.resolveHostAddress(): String? {
        val ipv4Host = inet4Addresses.firstOrNull()?.hostAddress
        if (!ipv4Host.isNullOrBlank()) return ipv4Host
        return hostAddresses.firstOrNull { it.isNotBlank() }
    }

    private companion object {
        private const val SMB_SERVICE_TYPE = "_smb._tcp.local."
        private const val NFS_SERVICE_TYPE = "_nfs._tcp.local."
        private const val MULTICAST_LOCK_TAG = "SpatialFinNetworkDiscovery"
    }

    @Deprecated("Use discoverAll() instead", replaceWith = ReplaceWith("discoverAll(timeoutMs)"))
    suspend fun discoverSmb(timeoutMs: Long = 5000L): List<DiscoveredShare> =
        discoverAll(timeoutMs).filter { it.protocol == "smb" }
}
