package dev.jdtech.jellyfin.cast.adapter.googlecast

import android.content.Context
import android.net.wifi.WifiManager
import dev.jdtech.jellyfin.cast.CastCapability
import dev.jdtech.jellyfin.cast.CastProtocol
import dev.jdtech.jellyfin.cast.CastReceiver
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * mDNS discovery for Google Cast receivers (`_googlecast._tcp.local.`). Mirrors the lock /
 * bindable-address pattern of [dev.jdtech.jellyfin.fcast.discovery.FCastDiscovery] so behaviour
 * on Galaxy XR's network stack is identical — both browsers will share the same multicast lock
 * when fanned out from `MultiProtocolDiscovery`.
 *
 * TXT records on `_googlecast._tcp.`:
 *   fn  — friendly name ("Living Room TV")
 *   md  — model name ("Chromecast", "Google TV Streamer", "Nest Hub", ...)
 *   id  — stable UUID per receiver, used to dedup across mDNS sweeps
 *   ca  — capability bitmask (decimal string; see [capabilitiesFromCaBitmask])
 *   rs  — receiver status text
 *   ve  — protocol version
 *   ic  — receiver icon path
 *
 * Discovery is one-shot: caller invokes [browse], gets a snapshot. Continuous browsing lives in
 * `MultiProtocolDiscovery` which composes this + FCast discovery.
 */
class GoogleCastDiscovery(private val context: Context) {

    suspend fun browse(timeoutMs: Long = 5_000L): List<CastReceiver> =
        withContext(Dispatchers.IO) {
            val multicastLock = acquireMulticastLock()
            val bindAddress = findBindableAddress()
            var jmdns: JmDNS? = null
            try {
                if (bindAddress == null) {
                    Timber.tag(TAG).w("Google Cast browse skipped: no bindable address")
                    return@withContext emptyList()
                }
                jmdns = JmDNS.create(bindAddress)
                val services = jmdns.list(GOOGLECAST_MDNS_SERVICE_TYPE, timeoutMs)
                val localIps = localIpAddresses()
                services.mapNotNull(::toCastReceiver)
                    .filterNot { it.host in localIps }
                    .distinctBy { it.id }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Google Cast browse failed")
                emptyList()
            } finally {
                try { jmdns?.close() } catch (_: Exception) {}
                try { multicastLock?.release() } catch (_: Exception) {}
            }
        }

    private fun toCastReceiver(info: ServiceInfo): CastReceiver? {
        val host = info.resolveHostAddress() ?: return null
        val port = info.port.takeIf { it > 0 } ?: DEFAULT_CAST_PORT
        // Prefer the TXT `id` for the receiver id — it's stable across DHCP renewals. Fall back
        // to host:port for receivers (rare, mostly older/3p) that don't publish it.
        val deviceId = info.getPropertyString("id")?.takeIf { it.isNotBlank() }
            ?: "${host}:${port}"
        val friendlyName = info.getPropertyString("fn")?.takeIf { it.isNotBlank() }
            ?: info.name.takeIf { it.isNotBlank() }
            ?: host
        val modelName = info.getPropertyString("md")?.takeIf { it.isNotBlank() }
        val capabilities = capabilitiesFromCaBitmask(info.getPropertyString("ca"))
        val appVersion = info.getPropertyString("ve")?.takeIf { it.isNotBlank() }
        return CastReceiver(
            id = "googlecast:$deviceId",
            name = friendlyName,
            host = host,
            port = port,
            protocol = CastProtocol.GoogleCast,
            modelName = modelName,
            appName = "Google Cast",
            appVersion = appVersion,
            capabilities = capabilities,
            source = CastReceiver.Source.Mdns,
        )
    }

    /**
     * Parse the Cast `ca` TXT record into a capability set. `ca` is a decimal string holding a
     * bitmask the receiver advertises:
     *   bit 0  (0x001) - video output
     *   bit 1  (0x002) - video input (sender-side, not interesting to us)
     *   bit 2  (0x004) - audio output
     *   bit 3  (0x008) - multizone group leader
     *   bit 5  (0x020) - on-screen display
     *   bit 11 (0x800) - audio assist (always pairs with video)
     *
     * In practice every Cast V2 receiver supports volume + seek + subtitles on the Default
     * Media Receiver, so we don't try to read those from the bitmask. Missing or unparseable
     * `ca` → conservative full set so the picker doesn't disable affordances on weird
     * receivers.
     */
    internal fun capabilitiesFromCaBitmask(ca: String?): Set<CastCapability> {
        val baseline = setOf(
            CastCapability.Volume,
            CastCapability.Seek,
            CastCapability.Subtitles,
        )
        val mask = ca?.trim()?.toIntOrNull() ?: return baseline + CastCapability.Video + CastCapability.Audio
        val video = (mask and 0x001) != 0
        val audio = (mask and 0x004) != 0
        return buildSet {
            addAll(baseline)
            if (video) add(CastCapability.Video)
            if (audio) add(CastCapability.Audio)
            // If the receiver doesn't claim either video or audio output, advertise Audio at
            // least — Cast V2 receivers all play audio.
            if (!video && !audio) add(CastCapability.Audio)
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

    private fun acquireMulticastLock(): WifiManager.MulticastLock? {
        val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)
            ?: return null
        return try {
            wifiManager.createMulticastLock(MULTICAST_LOCK_TAG).apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: SecurityException) {
            Timber.tag(TAG).w(e, "Unable to acquire multicast lock for Google Cast discovery")
            null
        } catch (e: RuntimeException) {
            Timber.tag(TAG).w(e, "Failed to create multicast lock for Google Cast discovery")
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
                if (address is Inet4Address && !address.isLinkLocalAddress) return address
                if (fallback == null && !address.isLinkLocalAddress) fallback = address
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
        const val TAG = "GoogleCastDiscovery"
        const val MULTICAST_LOCK_TAG = "SpatialFinGoogleCastDiscovery"
        const val GOOGLECAST_MDNS_SERVICE_TYPE = "_googlecast._tcp.local."
        const val DEFAULT_CAST_PORT = 8009
    }
}
