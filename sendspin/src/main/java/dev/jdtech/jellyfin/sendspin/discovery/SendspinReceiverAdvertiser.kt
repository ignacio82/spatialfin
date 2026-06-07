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
class SendspinReceiverAdvertiser(private val context: Context) {

    private var jmdns: JmDNS? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var serviceInfo: ServiceInfo? = null

    suspend fun register(
        serviceName: String,
        port: Int,
        properties: Map<String, String> = emptyMap(),
    ) {
        unregister()
        withContext(Dispatchers.IO) {
            multicastLock = acquireMulticastLock()
            val bind = findBindableAddress() ?: run {
                Timber.tag(TAG).w("Sendspin advertise skipped: no bindable address")
                return@withContext
            }
            val dns = JmDNS.create(bind)
            val info = ServiceInfo.create(
                SENDSPIN_MDNS_SERVICE_TYPE,
                serviceName,
                port,
                0, // weight
                0, // priority
                properties,
            )
            try {
                dns.registerService(info)
                jmdns = dns
                serviceInfo = info
                Timber.tag(TAG).i("Sendspin advertised as %s on %s:%d", serviceName, bind.hostAddress, port)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Sendspin advertise failed")
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

    companion object {
        const val TAG = "SendspinAdvertise"
        const val LOCK_TAG = "SpatialFinSendspinAdvertiser"
        const val SENDSPIN_MDNS_SERVICE_TYPE = "_sendspin._tcp.local."
    }
}
