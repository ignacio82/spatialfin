package dev.jdtech.jellyfin.cast.discovery

import android.content.Context
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import javax.jmdns.ServiceInfo
import timber.log.Timber

/**
 * Shared JmDNS multicast-lock, bindable Wi-Fi/Ethernet IPv4 interface lookup, and self-IP
 * filtering helpers reused by all mDNS discovery browsers inside `:fcast`.
 */
internal object JmdnsHelper {

    fun acquireMulticastLock(
        context: Context,
        lockTag: String,
        logTag: String,
    ): WifiManager.MulticastLock? {
        val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)
            ?: return null
        return try {
            wifiManager.createMulticastLock(lockTag).apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: SecurityException) {
            Timber.tag(logTag).w(e, "Unable to acquire multicast lock")
            null
        } catch (e: RuntimeException) {
            Timber.tag(logTag).w(e, "Failed to create multicast lock")
            null
        }
    }

    fun findBindableIpv4Address(logTag: String): InetAddress? {
        val interfaces = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        } catch (e: Exception) {
            Timber.tag(logTag).w(e, "Unable to enumerate network interfaces")
            return null
        }

        var fallback: InetAddress? = null
        for (iface in interfaces) {
            val usable = try {
                iface.isUp && !iface.isLoopback && iface.supportsMulticast()
            } catch (_: Exception) {
                false
            }
            if (!usable) continue

            val name = iface.name.orEmpty().lowercase()
            if (isExcludedInterface(name)) continue

            val isPreferred = isPreferredInterface(name)
            val addresses = try {
                iface.inetAddresses?.toList().orEmpty()
            } catch (_: Exception) {
                emptyList()
            }
            for (address in addresses) {
                if (address.isLoopbackAddress || address.isAnyLocalAddress) continue
                if (address is Inet4Address && !address.isLinkLocalAddress) {
                    if (isPreferred) return address
                    if (fallback == null) fallback = address
                }
            }
        }
        return fallback
    }

    fun localIpAddresses(): Set<String> {
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
        } catch (_: Exception) {
        }
        return out
    }

    fun resolveIpv4HostAddress(info: ServiceInfo): String? {
        val ipv4 = info.inet4Addresses.firstOrNull()?.hostAddress
        if (!ipv4.isNullOrBlank()) return ipv4
        return info.hostAddresses.firstOrNull { it.isNotBlank() && !it.contains(":") }
    }

    private fun isExcludedInterface(lower: String): Boolean =
        lower.startsWith("rmnet") ||
            lower.startsWith("ccmni") ||
            lower.startsWith("pdp") ||
            lower.startsWith("wwan") ||
            lower.startsWith("clat") ||
            lower.startsWith("radio") ||
            lower.startsWith("cellular") ||
            lower.startsWith("dummy") ||
            lower.startsWith("p2p")

    private fun isPreferredInterface(lower: String): Boolean =
        lower.startsWith("wlan") ||
            lower.startsWith("wifi") ||
            lower.startsWith("ap") ||
            lower.startsWith("softap") ||
            lower.startsWith("eth") ||
            lower.startsWith("en")
}
