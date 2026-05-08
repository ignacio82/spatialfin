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
class FCastReceiverAdvertiser(private val context: Context) {

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
            multicastLock = acquireMulticastLock()
            val bind = findBindableAddress() ?: run {
                Timber.tag(TAG).w("FCast advertise skipped: no bindable address")
                return@withContext
            }
            val dns = JmDNS.create(bind)
            val info = ServiceInfo.create(
                FCAST_MDNS_SERVICE_TYPE,
                instanceName,
                port,
                0, // weight
                0, // priority
                properties,
            )
            try {
                dns.registerService(info)
                jmdns = dns
                serviceInfo = info
                Timber.tag(TAG).i("FCast advertised as %s on %s:%d", instanceName, bind.hostAddress, port)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "FCast advertise failed")
                try { dns.close() } catch (_: Exception) {}
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
                try { multicastLock?.release() } catch (_: Exception) {}
                multicastLock = null
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

    private fun findBindableAddress(): InetAddress? {
        val interfaces = try {
            NetworkInterface.getNetworkInterfaces()
        } catch (_: Exception) {
            return null
        } ?: return null
        var fallback: InetAddress? = null
        while (interfaces.hasMoreElements()) {
            val iface = interfaces.nextElement()
            val usable = try {
                iface.isUp && !iface.isLoopback && iface.supportsMulticast()
            } catch (_: Exception) {
                false
            }
            if (!usable) continue
            val addresses = iface.inetAddresses
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (address.isLoopbackAddress || address.isAnyLocalAddress) continue
                if (address is java.net.Inet4Address && !address.isLinkLocalAddress) return address
                if (fallback == null && !address.isLinkLocalAddress) fallback = address
            }
        }
        return fallback
    }

    private companion object {
        const val TAG = "FCastAdvertise"
        const val LOCK_TAG = "SpatialFinFCastAdvertiser"
    }
}
