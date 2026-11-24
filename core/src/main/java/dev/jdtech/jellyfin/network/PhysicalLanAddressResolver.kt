package dev.jdtech.jellyfin.network

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Resolves local IP addresses bound to active physical LAN (Wi-Fi / Ethernet) interfaces,
 * optionally including VPN interfaces when registering cast receivers with a Companion server.
 *
 * Security boundaries:
 * - When `includeVpn == false` (`LanSetupServer` `:41230`), ONLY RFC 1918 IPv4 and ULA IPv6
 *   addresses on non-VPN, non-cellular `TRANSPORT_WIFI` / `TRANSPORT_ETHERNET` networks are
 *   accepted. Cellular CGNAT (`100.64.0.0/10`), Global Unicast IPv6 (`2000::/3`), link-local
 *   (`fe80::/10`, `169.254.0.0/16`), and loopback are always rejected.
 * - When `includeVpn == true` (`FCastCompanionRegistrar`), `TRANSPORT_VPN` addresses including
 *   CGNAT `100.64.0.0/10` (e.g. Tailscale) are also permitted, sorted RFC 1918 IPv4 -> CGNAT -> ULA.
 * - Always fails closed to `emptyList()` when `connectivityManager` is null or throws.
 */
object PhysicalLanAddressResolver {

    fun unwrapIpv4InIpv6(address: InetAddress): InetAddress {
        if (address is Inet6Address) {
            val bytes = address.address
            if (bytes.size == 16) {
                var isMapped = true
                for (i in 0 until 10) {
                    if (bytes[i] != 0.toByte()) {
                        isMapped = false
                        break
                    }
                }
                if (isMapped && bytes[10] == 0xFF.toByte() && bytes[11] == 0xFF.toByte()) {
                    return runCatching {
                        InetAddress.getByAddress(bytes.copyOfRange(12, 16))
                    }.getOrDefault(address)
                }
            }
        }
        return address
    }

    fun stripIpv6Scope(address: InetAddress): InetAddress {
        val unwrapped = unwrapIpv4InIpv6(address)
        return if (unwrapped is Inet6Address && (unwrapped.scopeId != 0 || unwrapped.scopedInterface != null)) {
            runCatching { InetAddress.getByAddress(unwrapped.address) }.getOrDefault(unwrapped)
        } else {
            unwrapped
        }
    }

    fun formatHostLiteral(address: InetAddress): String {
        val clean = stripIpv6Scope(address)
        val raw = clean.hostAddress?.substringBefore('%').orEmpty()
        return if (clean is Inet6Address) "[$raw]" else raw
    }

    fun isAcceptedLanAddress(address: InetAddress, includeVpn: Boolean = false): Boolean =
        addressSortRank(unwrapIpv4InIpv6(address), includeVpn) >= 0

    internal fun addressSortRank(address: InetAddress, includeVpn: Boolean): Int {
        if (address.isLoopbackAddress || address.isLinkLocalAddress || address.isAnyLocalAddress || address.isMulticastAddress) {
            return -1
        }
        return when (address) {
            is Inet4Address -> {
                val b = address.address
                val b0 = b[0].toInt() and 0xFF
                val b1 = b[1].toInt() and 0xFF
                when {
                    // RFC 1918: 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16
                    b0 == 10 -> 0
                    b0 == 172 && b1 in 16..31 -> 0
                    b0 == 192 && b1 == 168 -> 0
                    // CGNAT 100.64.0.0/10 (only when includeVpn == true)
                    includeVpn && b0 == 100 && (b1 and 0xC0) == 64 -> 1
                    else -> -1
                }
            }
            is Inet6Address -> {
                val b = address.address
                val b0 = b[0].toInt() and 0xFF
                // ULA fc00::/7
                if ((b0 and 0xFE) == 0xFC) 2 else -1
            }
            else -> -1
        }
    }

    fun resolvePhysicalLanAddresses(
        connectivityManager: ConnectivityManager?,
        includeVpn: Boolean = false,
    ): List<InetAddress> {
        if (connectivityManager == null) return emptyList()
        return runCatching {
            val ranked = mutableListOf<Pair<Int, InetAddress>>()
            val seen = mutableSetOf<String>()

            @Suppress("DEPRECATION")
            val networks = connectivityManager.allNetworks
            for (network in networks) {
                val caps = connectivityManager.getNetworkCapabilities(network) ?: continue
                val isVpn =
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                        !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                if (!includeVpn && isVpn) continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) && !isVpn) continue
                val isPhysicalLan =
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                if (!isPhysicalLan && !(includeVpn && isVpn)) continue

                val linkProps = connectivityManager.getLinkProperties(network) ?: continue
                for (linkAddress in linkProps.linkAddresses) {
                    val clean = stripIpv6Scope(linkAddress.address)
                    val rank = addressSortRank(clean, includeVpn)
                    if (rank < 0) continue
                    val key = clean.address.joinToString(":") { "%02x".format(it) }
                    if (seen.add(key)) {
                        ranked += rank to clean
                    }
                }
            }

            ranked
                .sortedWith(compareBy<Pair<Int, InetAddress>>({ it.first }, { it.second.hostAddress.orEmpty() }))
                .map { it.second }
        }.getOrElse { emptyList() }
    }
}
