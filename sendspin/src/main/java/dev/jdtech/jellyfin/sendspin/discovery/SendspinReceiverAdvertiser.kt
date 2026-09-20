package dev.jdtech.jellyfin.sendspin.discovery

import android.content.Context
import android.net.wifi.WifiManager
import java.net.InetAddress
import java.net.NetworkInterface
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Publishes an `_sendspin._tcp.local.` service record so Sendspin senders on the LAN can discover this
 * device.
 */
class SendspinReceiverAdvertiser(
    private val context: Context,
    private val jmdnsFactory: (InetAddress) -> JmDNS = { JmDNS.create(it) },
    private val interfaceProvider: () -> List<NetworkInterface>? = {
        try { NetworkInterface.getNetworkInterfaces()?.toList() } catch (_: Exception) { null }
    },
) {

    private var jmdns: JmDNS? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var serviceInfo: ServiceInfo? = null
    private var boundAddress: InetAddress? = null

    /** True while an mDNS service record is published. */
    fun isActive(): Boolean = jmdns != null && serviceInfo != null

    /**
     * True when we're advertising but the device's current LAN address differs
     * from the one we bound jmDNS to (a DHCP / network change). The record then
     * announces a stale IP the server can't reach, so the caller must
     * re-register. jmDNS itself won't notice the interface change.
     */
    fun addressChanged(): Boolean {
        if (!isActive()) return false
        val current = runCatching { findBindableAddress() }.getOrNull() ?: return false
        return current != boundAddress
    }

    /**
     * Publish the service record. Returns true on success. Both failure paths
     * (no bindable address yet — common right after a restart while Wi-Fi
     * reconnects — and a jmDNS registration error) return false so the caller
     * can retry; a service that's running but un-advertised is invisible to MA.
     */
    suspend fun register(
        serviceName: String,
        port: Int,
        properties: Map<String, String> = emptyMap(),
    ): Boolean {
        unregister()
        return withContext(Dispatchers.IO) {
            var dns: JmDNS? = null
            try {
                multicastLock = acquireMulticastLock()
                val bind = findBindableAddress() ?: run {
                    Timber.tag(TAG).w("Sendspin advertise skipped: no bindable address")
                    releaseMulticastLock()
                    return@withContext false
                }
                dns = jmdnsFactory(bind)
                val info = ServiceInfo.create(
                    SENDSPIN_MDNS_SERVICE_TYPE,
                    serviceName,
                    port,
                    0, // weight
                    0, // priority
                    properties,
                )
                dns.registerService(info)
                jmdns = dns
                serviceInfo = info
                boundAddress = bind
                Timber.tag(TAG).i("Sendspin advertised as %s on %s:%d", serviceName, bind.hostAddress, port)
                true
            } catch (e: kotlinx.coroutines.CancellationException) {
                try { dns?.close() } catch (_: Exception) {}
                jmdns = null
                serviceInfo = null
                boundAddress = null
                releaseMulticastLock()
                throw e
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Sendspin advertise failed")
                try { dns?.close() } catch (_: Exception) {}
                jmdns = null
                serviceInfo = null
                boundAddress = null
                releaseMulticastLock()
                false
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
                boundAddress = null
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

    companion object {
        const val TAG = "SendspinAdvertise"
        const val LOCK_TAG = "SpatialFinSendspinAdvertiser"
        const val SENDSPIN_MDNS_SERVICE_TYPE = "_sendspin._tcp.local."
    }
}
