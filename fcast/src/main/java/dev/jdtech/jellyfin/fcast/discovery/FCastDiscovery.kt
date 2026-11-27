package dev.jdtech.jellyfin.fcast.discovery

import android.content.Context
import android.net.wifi.WifiManager
import dev.jdtech.jellyfin.cast.discovery.JmdnsHelper
import dev.jdtech.jellyfin.fcast.protocol.FCAST_DEFAULT_PORT
import dev.jdtech.jellyfin.fcast.protocol.FCAST_MDNS_SERVICE_TYPE
import dev.jdtech.jellyfin.fcast.sender.FCastReceiver
import java.net.InetAddress
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
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
                val resolved = services.mapNotNull(::toReceiver)
                    .filterNot { it.host in localIps }
                    .distinctBy { "${it.host}:${it.port}" }
                Timber.tag(TAG).i(
                    "FCast browse on %s finished: raw=%d filtered=%d (%s)",
                    bindAddress.hostAddress,
                    services.size,
                    resolved.size,
                    resolved.joinToString { "${it.name}@${it.host}:${it.port}[${it.appName}]" },
                )
                resolved
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "FCast browse failed")
                emptyList()
            } finally {
                try { jmdns?.close() } catch (_: Exception) {}
                try { multicastLock?.release() } catch (_: Exception) {}
            }
        }

    /**
     * Continuously observe FCast receivers on the network.
     */
    fun browseFlow(): Flow<List<FCastReceiver>> = callbackFlow {
        val multicastLock = acquireMulticastLock()
        val bindAddress = findBindableAddress()
        var jmdns: JmDNS? = null

        if (bindAddress == null) {
            Timber.tag(TAG).w("FCast browseFlow skipped: no bindable address")
            try { multicastLock?.release() } catch (_: Exception) {}
            close()
            return@callbackFlow
        }

        try {
            Timber.tag(TAG).i("FCast browseFlow started on %s", bindAddress.hostAddress)
            jmdns = JmDNS.create(bindAddress)
            val localIps = localIpAddresses()
            val receivers = linkedMapOf<String, FCastReceiver>()

            val listener = object : javax.jmdns.ServiceListener {
                override fun serviceAdded(event: javax.jmdns.ServiceEvent) {
                    jmdns.requestServiceInfo(event.type, event.name, REQUEST_SERVICE_INFO_TIMEOUT_MS)
                }

                override fun serviceRemoved(event: javax.jmdns.ServiceEvent) {
                    if (receivers.remove(event.name) != null) {
                        trySend(receivers.values.toList())
                    }
                }

                override fun serviceResolved(event: javax.jmdns.ServiceEvent) {
                    val receiver = toReceiver(event.info) ?: return
                    if (receiver.host in localIps) return
                    Timber.tag(TAG).i(
                        "FCast browseFlow resolved %s@%s:%d appName=%s appVersion=%s",
                        receiver.name,
                        receiver.host,
                        receiver.port,
                        receiver.appName,
                        receiver.appVersion,
                    )
                    receivers[event.name] = receiver
                    trySend(receivers.values.toList())
                }
            }

            jmdns.addServiceListener(FCAST_MDNS_SERVICE_TYPE, listener)

            awaitClose {
                try { jmdns?.removeServiceListener(FCAST_MDNS_SERVICE_TYPE, listener) } catch (_: Exception) {}
                try { jmdns?.close() } catch (_: Exception) {}
                try { multicastLock?.release() } catch (_: Exception) {}
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "FCast browseFlow failed")
            try { jmdns?.close() } catch (_: Exception) {}
            try { multicastLock?.release() } catch (_: Exception) {}
            close(e)
        }
    }.flowOn(Dispatchers.IO)

    companion object {
        private const val TAG = "FCastDiscovery"
        private const val MULTICAST_LOCK_TAG = "SpatialFinFCastDiscovery"
        private const val REQUEST_SERVICE_INFO_TIMEOUT_MS = 2_000L

        fun localIpAddresses(): Set<String> = JmdnsHelper.localIpAddresses()
    }

    private fun toReceiver(info: ServiceInfo): FCastReceiver? {
        val host = resolveHostAddress(info) ?: return null
        return FCastReceiver(
            host = host,
            port = info.port.takeIf { it > 0 } ?: FCAST_DEFAULT_PORT,
            name = info.name.ifBlank { host },
            appName = info.getPropertyString("appName"),
            appVersion = info.getPropertyString("appVersion"),
            source = FCastReceiver.Source.Mdns,
        )
    }

    private fun acquireMulticastLock(): WifiManager.MulticastLock? =
        JmdnsHelper.acquireMulticastLock(context, MULTICAST_LOCK_TAG, TAG)

    private fun findBindableAddress(): InetAddress? =
        JmdnsHelper.findBindableIpv4Address(TAG)

    private fun resolveHostAddress(info: ServiceInfo): String? =
        JmdnsHelper.resolveIpv4HostAddress(info)
}
