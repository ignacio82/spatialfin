package dev.jdtech.jellyfin.cast.adapter.airplay

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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * mDNS discovery for AirPlay receivers. Browses two service types in parallel:
 *
 *  - `_airplay._tcp.local.` — the v1 video protocol (Apple TVs ≤ tvOS 9, most AV receivers).
 *  - `_raop._tcp.local.` — audio-only RAOP. Used here only as an AirPlay-2 hint: a device
 *    that advertises `_raop` but not `_airplay`, or whose features bitmask sets the AirPlay 2
 *    audio bit, is almost certainly a HomePod / modern Apple TV that needs HomeKit pairing.
 *    Those land in the picker as **disabled** rows so users see them and understand why they
 *    can't pick them, rather than missing them entirely.
 *
 * Capability inference from the TXT `features` hex bitmask (a 32-bit integer encoded as a
 * `0xNNN` hex string — sometimes two of them comma-separated for AirPlay 2):
 *   bit 9  - video supported
 *   bit 26 - AirPlay 2 audio
 *   bit 30 - HomeKit pairing required
 *
 * Devices that lack bit 9 but have bit 26 are AirPlay-2-only audio devices; we surface them
 * but the [CastCapability] set won't include [CastCapability.Video], and the [appName] field
 * carries the marker `"AirPlay 2 (pairing required)"` so the picker UI can grey them out.
 */
class AirPlayDiscovery(private val context: Context) {

    suspend fun browse(timeoutMs: Long = 5_000L): List<CastReceiver> =
        withContext(Dispatchers.IO) {
            val multicastLock = acquireMulticastLock()
            val bindAddress = findBindableAddress()
            if (bindAddress == null) {
                multicastLock?.release()
                Timber.tag(TAG).w("AirPlay browse skipped: no bindable address")
                return@withContext emptyList()
            }
            val v1Job = async { browseService(bindAddress, AIRPLAY_TCP, timeoutMs) }
            val raopJob = async { browseService(bindAddress, RAOP_TCP, timeoutMs) }
            try {
                val (v1, raop) = listOf(v1Job, raopJob).awaitAll()
                mergeResults(v1, raop)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "AirPlay browse failed")
                emptyList()
            } finally {
                multicastLock?.release()
            }
        }

    /**
     * Merge `_airplay` and `_raop` results. A device that responds on both gets one entry
     * (preferring the `_airplay` data so we have the video features). Audio-only devices
     * still appear so users see them in the picker.
     */
    internal fun mergeResults(
        airplayV1: List<CastReceiver>,
        raop: List<CastReceiver>,
    ): List<CastReceiver> {
        val byHost = mutableMapOf<String, CastReceiver>()
        // A receiver publishes AirPlay video and RAOP audio on different ports. Merge by
        // address so the unusable RAOP endpoint cannot duplicate a video endpoint.
        for (entry in airplayV1) byHost[entry.host] = entry
        for (entry in raop) {
            val key = entry.host
            if (byHost[key] == null) byHost[key] = entry
        }
        return byHost.values.toList()
    }

    private suspend fun browseService(
        bindAddress: InetAddress,
        serviceType: String,
        timeoutMs: Long,
    ): List<CastReceiver> = withContext(Dispatchers.IO) {
        var jmdns: JmDNS? = null
        try {
            jmdns = JmDNS.create(bindAddress)
            val services = jmdns.list(serviceType, timeoutMs)
            val localIps = localIpAddresses()
            services.mapNotNull { info -> toReceiver(info, serviceType) }
                .filterNot { it.host in localIps }
                .distinctBy { it.id }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "AirPlay browse(%s) failed", serviceType)
            emptyList()
        } finally {
            try { jmdns?.close() } catch (_: Exception) {}
        }
    }

    private fun toReceiver(info: ServiceInfo, serviceType: String): CastReceiver? {
        val host = info.resolveHostAddress() ?: return null
        val port = info.port.takeIf { it > 0 } ?: DEFAULT_AIRPLAY_PORT
        val deviceId = info.getPropertyString("deviceid")?.takeIf { it.isNotBlank() }
            ?: "${host}:${port}"
        val friendlyName = info.name.takeIf { it.isNotBlank() } ?: host
        val featuresHex = info.getPropertyString("features")
        val capabilities = capabilitiesFromFeatures(featuresHex, fromRaop = serviceType == RAOP_TCP)
        val isV2Only = isV2Only(featuresHex)
        val appName = when {
            isV2Only -> "AirPlay 2 (pairing required)"
            serviceType == RAOP_TCP -> "AirPlay (audio only)"
            else -> "AirPlay"
        }
        return CastReceiver(
            id = "airplay:$deviceId",
            name = friendlyName,
            host = host,
            port = port,
            protocol = CastProtocol.AirPlay,
            modelName = info.getPropertyString("model"),
            appName = appName,
            appVersion = info.getPropertyString("srcvers")?.takeIf { it.isNotBlank() },
            capabilities = capabilities,
            source = CastReceiver.Source.Mdns,
        )
    }

    /**
     * Decode the AirPlay `features` TXT bitmask. The string is hex (optionally `0x`-prefixed),
     * sometimes a comma-separated pair for AirPlay 2 (`0xABC,0xDEF`) — we OR the two halves
     * together since we only care about presence of individual bits.
     *
     * Missing or unparseable `features` falls back to a permissive set (Video + Volume + Seek)
     * so an off-spec receiver still gets a tappable picker row.
     */
    internal fun capabilitiesFromFeatures(
        featuresHex: String?,
        fromRaop: Boolean,
    ): Set<CastCapability> {
        val baseline = mutableSetOf(
            CastCapability.Volume,
            CastCapability.Seek,
            CastCapability.Subtitles,
        )
        if (fromRaop) {
            // RAOP-only entry: audio device, no video.
            baseline.add(CastCapability.Audio)
            return baseline
        }
        val bits = parseFeaturesBitmask(featuresHex)
        if (bits == 0L) {
            baseline.add(CastCapability.Video)
            return baseline
        }
        if ((bits and FEATURE_VIDEO) != 0L) baseline.add(CastCapability.Video)
        if ((bits and FEATURE_AUDIO_AIRPLAY2) != 0L) baseline.add(CastCapability.Audio)
        // Always grant Audio for AirPlay devices that claim video — AirPlay v1 video carries
        // audio in the same stream.
        if ((bits and FEATURE_VIDEO) != 0L) baseline.add(CastCapability.Audio)
        return baseline
    }

    /** True when the device advertises AirPlay 2 audio but not the v1 video bit. */
    internal fun isV2Only(featuresHex: String?): Boolean {
        val bits = parseFeaturesBitmask(featuresHex)
        if (bits == 0L) return false
        val hasV2 = (bits and FEATURE_AUDIO_AIRPLAY2) != 0L
        val hasV1Video = (bits and FEATURE_VIDEO) != 0L
        return hasV2 && !hasV1Video
    }

    private fun parseFeaturesBitmask(featuresHex: String?): Long {
        val raw = featuresHex?.trim().orEmpty()
        if (raw.isEmpty()) return 0L
        return raw.split(',').fold(0L) { acc, chunk ->
            val cleaned = chunk.trim().removePrefix("0x").removePrefix("0X")
            val parsed = cleaned.toLongOrNull(radix = 16) ?: return@fold acc
            acc or parsed
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
            Timber.tag(TAG).w(e, "Unable to acquire multicast lock for AirPlay discovery")
            null
        } catch (e: RuntimeException) {
            Timber.tag(TAG).w(e, "Failed to create multicast lock for AirPlay discovery")
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
            } catch (_: Exception) { false }
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
        return hostAddresses.firstOrNull { it.isNotBlank() && !it.contains(":") }
    }

    private companion object {
        const val TAG = "AirPlayDiscovery"
        const val MULTICAST_LOCK_TAG = "SpatialFinAirPlayDiscovery"
        const val AIRPLAY_TCP = "_airplay._tcp.local."
        const val RAOP_TCP = "_raop._tcp.local."
        const val DEFAULT_AIRPLAY_PORT = 7000

        // Features bitmask — see https://openairplay.github.io/airplay-spec/features.html
        const val FEATURE_VIDEO = 1L shl 9
        const val FEATURE_AUDIO_AIRPLAY2 = 1L shl 26
    }
}
