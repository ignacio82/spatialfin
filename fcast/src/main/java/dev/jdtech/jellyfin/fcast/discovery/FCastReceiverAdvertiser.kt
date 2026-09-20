package dev.jdtech.jellyfin.fcast.discovery

import android.content.Context
import android.net.wifi.WifiManager
import dev.jdtech.jellyfin.fcast.protocol.FCAST_MDNS_SERVICE_TYPE
import java.net.InetAddress
import java.net.NetworkInterface
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Publishes an `_fcast._tcp.local.` service record so FCast senders on the LAN can discover this
 * device. Mirrors [FCastDiscovery]'s multicast-lock and bind-address handling.
 *
 * Lifecycle: [register] when the receiver service starts, [unregister] when it stops.
 * Re-registering replaces the previous record.
 */
class FCastReceiverAdvertiser(
    private val context: Context,
    private val jmdnsFactory: (InetAddress) -> JmDNS = { JmDNS.create(it) },
    private val interfaceProvider: () -> List<NetworkInterface>? = {
        try { NetworkInterface.getNetworkInterfaces()?.toList() } catch (_: Exception) { null }
    },
) {

    private var jmdns: JmDNS? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var serviceInfo: ServiceInfo? = null

    suspend fun register(
        instanceName: String,
        port: Int,
        properties: Map<String, String> = emptyMap(),
    ) {
        unregister()
        withContext(Dispatchers.IO) {
            var dns: JmDNS? = null
            try {
                multicastLock = acquireMulticastLock()
                val bind = findBindableAddress() ?: run {
                    Timber.tag(TAG).w("FCast advertise skipped: no bindable address")
                    releaseMulticastLock()
                    return@withContext
                }
                dns = jmdnsFactory(bind)
                val info = ServiceInfo.create(
                    FCAST_MDNS_SERVICE_TYPE,
                    instanceName,
                    port,
                    0, // weight
                    0, // priority
                    properties,
                )
                dns.registerService(info)
                jmdns = dns
                serviceInfo = info
                Timber.tag(TAG).i("FCast advertised as %s on %s:%d", instanceName, bind.hostAddress, port)
            } catch (e: kotlinx.coroutines.CancellationException) {
                try { dns?.close() } catch (_: Exception) {}
                jmdns = null
                serviceInfo = null
                releaseMulticastLock()
                throw e
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "FCast advertise failed")
                try { dns?.close() } catch (_: Exception) {}
                jmdns = null
                serviceInfo = null
                releaseMulticastLock()
            }
        }
    }

    suspend fun unregister() {
        withContext(Dispatchers.IO) {
            try {
                serviceInfo?.let { jmdns?.unregisterService(it) }
                jmdns?.close()
            } catch (_: Exception) {
            } finally {
                jmdns = null
                serviceInfo = null
                releaseMulticastLock()
            }
        }
    }

    private fun acquireMulticastLock(): WifiManager.MulticastLock? {
        val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)
            ?: return null
        return try {
            wifiManager.createMulticastLock(LOCK_TAG).apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Multicast lock acquire failed")
            null
        }
    }

    private fun releaseMulticastLock() {
        try {
            multicastLock?.release()
        } catch (_: Exception) {
        } finally {
            multicastLock = null
        }
    }

    internal fun findBindableAddress(): InetAddress? {
        val interfaces = interfaceProvider() ?: return null
        var fallback: InetAddress? = null
        for (iface in interfaces) {
            val usable = try {
                iface.isUp && !iface.isLoopback && iface.supportsMulticast()
            } catch (_: Exception) {
                false
            }
            if (!usable) continue

            val name = iface.name.orEmpty()
            if (isExcludedInterface(name)) continue

            val isPreferred = isPreferredInterface(name)
            val addresses = try {
                iface.inetAddresses?.toList().orEmpty()
            } catch (_: Exception) {
                emptyList()
            }
            for (address in addresses) {
                if (address.isLoopbackAddress || address.isAnyLocalAddress) continue
                if (address is java.net.Inet4Address && !address.isLinkLocalAddress) {
                    if (isPreferred) {
                        return address
                    }
                    if (fallback == null) {
                        fallback = address
                    }
                }
            }
        }
        return fallback
    }

    internal fun isExcludedInterface(name: String): Boolean {
        val lower = name.lowercase()
        return lower.startsWith("rmnet") ||
            lower.startsWith("ccmni") ||
            lower.startsWith("pdp") ||
            lower.startsWith("wwan") ||
            lower.startsWith("clat") ||
            lower.startsWith("radio") ||
            lower.startsWith("cellular") ||
            lower.startsWith("dummy") ||
            lower.startsWith("p2p")
    }

    internal fun isPreferredInterface(name: String): Boolean {
        val lower = name.lowercase()
        return lower.startsWith("wlan") ||
            lower.startsWith("wifi") ||
            lower.startsWith("ap") ||
            lower.startsWith("softap") ||
            lower.startsWith("eth") ||
            lower.startsWith("en")
    }

    private companion object {
        const val TAG = "FCastAdvertise"
        const val LOCK_TAG = "SpatialFinFCastAdvertiser"
    }
}
