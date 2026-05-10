package dev.jdtech.jellyfin.fcast.discovery

import android.content.Context
import android.net.wifi.WifiManager
import dev.jdtech.jellyfin.fcast.protocol.FCAST_DEFAULT_PORT
import dev.jdtech.jellyfin.fcast.protocol.FCAST_MDNS_SERVICE_TYPE
import dev.jdtech.jellyfin.fcast.sender.FCastReceiver
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * mDNS discovery for FCast receivers (`_fcast._tcp.local.`). Mirrors the multicast-lock /
 * bindable-address pattern used by the SMB/NFS discovery path so behaviour on Galaxy XR's
 * network stack is consistent — the multicast lock is mandatory or browse silently returns nothing.
 */
class FCastDiscovery(private val context: Context) {

    /**
     * Browse for FCast receivers. Suspends for [timeoutMs] then returns whatever was discovered.
     */
    suspend fun browse(timeoutMs: Long = 5_000L): List<FCastReceiver> =
        withContext(Dispatchers.IO) {
            val multicastLock = acquireMulticastLock()
            val bindAddress = findBindableAddress()
            var jmdns: JmDNS? = null
            try {
                if (bindAddress == null) {
                    Timber.tag(TAG).w("FCast browse skipped: no bindable address")
                    return@withContext emptyList()
                }
                jmdns = JmDNS.create(bindAddress)
                val services = jmdns.list(FCAST_MDNS_SERVICE_TYPE, timeoutMs)
                // Filter out *this* device — every SpatialFin install is also a receiver, so
                // the local mDNS responder shows up in our own scan. Picking yourself opens
                // a sender → loopback → receiver path that can't actually play anything.
                val localIps = localIpAddresses()
                services.mapNotNull(::toReceiver)
                    .filterNot { it.host in localIps }
                    .distinctBy { "${it.host}:${it.port}" }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "FCast browse failed")
                emptyList()
            } finally {
                try { jmdns?.close() } catch (_: Exception) {}
                try { multicastLock?.release() } catch (_: Exception) {}
            }
        }

    private fun localIpAddresses(): Set<String> {
        val out = mutableSetOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return out
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    addresses.nextElement().hostAddress?.let { out.add(it) }
                }
            }
        } catch (_: Exception) {}
        return out
    }

    private fun toReceiver(info: ServiceInfo): FCastReceiver? {
        val host = info.resolveHostAddress() ?: return null
        return FCastReceiver(
            host = host,
            port = info.port.takeIf { it > 0 } ?: FCAST_DEFAULT_PORT,
            name = info.name.ifBlank { host },
            appName = info.getPropertyString("appName"),
            appVersion = info.getPropertyString("appVersion"),
            source = FCastReceiver.Source.Mdns,
        )
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
            Timber.tag(TAG).w(e, "Unable to acquire multicast lock for FCast discovery")
            null
        } catch (e: RuntimeException) {
            Timber.tag(TAG).w(e, "Failed to create multicast lock for FCast discovery")
            null
        }
    }

    private fun findBindableAddress(): InetAddress? {
        val interfaces = try {
            NetworkInterface.getNetworkInterfaces()
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Unable to enumerate network interfaces for FCast discovery")
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
        val ipv4 = inet4Addresses.firstOrNull()?.hostAddress
        if (!ipv4.isNullOrBlank()) return ipv4
        return hostAddresses.firstOrNull { it.isNotBlank() }
    }

    private companion object {
        const val TAG = "FCastDiscovery"
        const val MULTICAST_LOCK_TAG = "SpatialFinFCastDiscovery"
    }
}
