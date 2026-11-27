package dev.jdtech.jellyfin.cast.adapter.sendspinavr

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import dev.jdtech.jellyfin.cast.CastCapability
import dev.jdtech.jellyfin.cast.CastProtocol
import dev.jdtech.jellyfin.cast.CastReceiver
import dev.jdtech.jellyfin.cast.discovery.JmdnsHelper
import java.net.Inet4Address
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * mDNS discovery browser for `spatialfin-avr` theater receivers (`_spatialfin-avr._tcp.local.`).
 * Runs both Android's native [NsdManager] and [JmDNS] in parallel for reliable discovery on
 * Galaxy XR and Pixel Wi-Fi stacks, validating Spec 0 mandatory TXT keys (`v == "1"`, `id`
 * matching `^avr-[0-9a-f-]{8,64}$`, `auth == "bearer"`) and mapping verified instances to
 * [CastProtocol.SendspinAvr].
 */
class SendspinAvrDiscoveryBrowser(private val context: Context) {

    suspend fun browse(timeoutMs: Long = 5_000L): List<CastReceiver> =
        withContext(Dispatchers.IO) {
            val localIps = JmdnsHelper.localIpAddresses()
            val (nsdResults, jmdnsResults) = coroutineScope {
                val nsdDeferred = async { browseViaNsdManager(timeoutMs, localIps) }
                val jmdnsDeferred = async { browseViaJmdns(timeoutMs, localIps) }
                nsdDeferred.await() to jmdnsDeferred.await()
            }
            val merged = (nsdResults + jmdnsResults).distinctBy { it.id }
            Timber.tag(TAG).i(
                "SendspinAvr browse finished: nsd=%d jmdns=%d merged=%d (%s)",
                nsdResults.size,
                jmdnsResults.size,
                merged.size,
                merged.joinToString { "${it.name}@${it.host}:${it.port}" },
            )
            merged
        }

    private suspend fun browseViaJmdns(timeoutMs: Long, localIps: Set<String>): List<CastReceiver> {
        val multicastLock = JmdnsHelper.acquireMulticastLock(context, MULTICAST_LOCK_TAG, TAG)
        val bindAddress = JmdnsHelper.findBindableIpv4Address(TAG)
        var jmdns: JmDNS? = null
        return try {
            if (bindAddress == null) {
                Timber.tag(TAG).w("SendspinAvr JmDNS browse skipped: no bindable IPv4 address")
                return emptyList()
            }
            jmdns = JmDNS.create(bindAddress)
            val services = jmdns.list(SENDSPIN_AVR_MDNS_SERVICE_TYPE, timeoutMs)
            services.mapNotNull(::toCastReceiver)
                .filterNot { it.host in localIps }
                .distinctBy { it.id }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "SendspinAvr JmDNS browse failed")
            emptyList()
        } finally {
            try { jmdns?.close() } catch (_: Exception) {}
            try { multicastLock?.release() } catch (_: Exception) {}
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun browseViaNsdManager(timeoutMs: Long, localIps: Set<String>): List<CastReceiver> {
        val nsdManager = runCatching {
            context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
        }.getOrNull() ?: return emptyList()

        val found = ConcurrentHashMap<String, CastReceiver>()
        val active = AtomicBoolean(true)
        val activeInfoCallbacks = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ConcurrentHashMap<String, NsdManager.ServiceInfoCallback>()
        } else {
            null
        }
        // Direct executor on NsdManager's background HandlerThread: avoids shutting down a
        // ThreadPoolExecutor before NsdManager asynchronously delivers onServiceInfoCallbackUnregistered(),
        // which otherwise throws RejectedExecutionException on the NsdManager thread.
        val callbackExecutor = Executor { command ->
            runCatching { command.run() }
        }

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Timber.tag(TAG).w("NsdManager startDiscovery failed code=%d", errorCode)
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!active.get()) return
                val key = serviceInfo.serviceName ?: return
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    val infoCallback = object : NsdManager.ServiceInfoCallback {
                        override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) = Unit
                        override fun onServiceLost() = Unit
                        override fun onServiceInfoCallbackUnregistered() = Unit
                        override fun onServiceUpdated(updated: NsdServiceInfo) {
                            if (!active.get()) return
                            val receiver = toCastReceiverFromNsd(updated) ?: return
                            if (receiver.host !in localIps) {
                                found[receiver.id] = receiver
                            }
                        }
                    }
                    if (activeInfoCallbacks?.putIfAbsent(key, infoCallback) == null) {
                        runCatching {
                            nsdManager.registerServiceInfoCallback(serviceInfo, callbackExecutor, infoCallback)
                        }
                    }
                } else {
                    runCatching {
                        nsdManager.resolveService(
                            serviceInfo,
                            object : NsdManager.ResolveListener {
                                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = Unit
                                override fun onServiceResolved(resolved: NsdServiceInfo) {
                                    if (!active.get()) return
                                    val receiver = toCastReceiverFromNsd(resolved) ?: return
                                    if (receiver.host !in localIps) {
                                        found[receiver.id] = receiver
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }

        return try {
            nsdManager.discoverServices(
                SENDSPIN_AVR_NSD_SERVICE_TYPE,
                NsdManager.PROTOCOL_DNS_SD,
                discoveryListener,
            )
            delay(timeoutMs.coerceIn(500L, 6_000L))
            found.values.toList()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "SendspinAvr NsdManager browse failed")
            found.values.toList()
        } finally {
            active.set(false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                activeInfoCallbacks?.values?.forEach { cb ->
                    runCatching { nsdManager.unregisterServiceInfoCallback(cb) }
                }
            }
            runCatching { nsdManager.stopServiceDiscovery(discoveryListener) }
        }
    }

    @Suppress("DEPRECATION")
    private fun toCastReceiverFromNsd(info: NsdServiceInfo): CastReceiver? {
        val hostAddress = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            info.hostAddresses
                .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }
                ?.hostAddress
                ?: info.hostAddresses
                    .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                    ?.hostAddress
        } else {
            info.host?.hostAddress
        } ?: return null

        val txt = linkedMapOf<String, String>()
        info.attributes?.forEach { (key, value) ->
            if (value != null) {
                txt[key] = value.toString(Charsets.UTF_8)
            }
        }
        return parseServiceInfo(
            serviceName = info.serviceName,
            host = hostAddress,
            port = info.port,
            txt = txt,
        )
    }

    private fun toCastReceiver(info: ServiceInfo): CastReceiver? {
        val host = JmdnsHelper.resolveIpv4HostAddress(info) ?: return null
        val txt = linkedMapOf<String, String>()
        val names = info.propertyNames
        while (names.hasMoreElements()) {
            val key = names.nextElement()
            info.getPropertyString(key)?.let { value -> txt[key] = value }
        }
        return parseServiceInfo(
            serviceName = info.name,
            host = host,
            port = info.port,
            txt = txt,
        )
    }

    companion object {
        const val SENDSPIN_AVR_MDNS_SERVICE_TYPE = "_spatialfin-avr._tcp.local."
        const val SENDSPIN_AVR_NSD_SERVICE_TYPE = "_spatialfin-avr._tcp."
        const val SENDSPIN_AVR_DEFAULT_PORT = 1984
        private const val TAG = "SendspinAvrDiscovery"
        private const val MULTICAST_LOCK_TAG = "SpatialFinTheaterAudioDiscovery"

        internal fun parseServiceInfo(
            serviceName: String?,
            host: String?,
            port: Int,
            txt: Map<String, String>,
        ): CastReceiver? {
            val normalizedHost = host?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if (txt["v"]?.trim() != TheaterAudioProtocol.VERSION.toString()) return null
            val serverId = txt["id"]?.trim() ?: return null
            if (!TheaterAudioProtocol.SERVER_ID_REGEX.matches(serverId)) return null
            if (txt["auth"]?.trim()?.lowercase() != "bearer") return null

            val resolvedPort = port.takeIf { it in 1..65535 } ?: SENDSPIN_AVR_DEFAULT_PORT
            val useTls = txt["tls"]?.trim() == "1"
            val friendlyName = txt["name"]?.trim()?.takeIf { it.isNotEmpty() }
                ?: serviceName?.trim()?.takeIf { it.isNotEmpty() }
                ?: normalizedHost
            val layout = txt["layout"]?.trim()?.takeIf { it.isNotEmpty() }
            val swVersion = txt["sw"]?.trim()?.takeIf { it.isNotEmpty() }

            return CastReceiver(
                id = "SendspinAvr:$serverId",
                name = friendlyName,
                host = normalizedHost,
                port = resolvedPort,
                protocol = CastProtocol.SendspinAvr,
                modelName = layout,
                appName = "SpatialFin AVR",
                appVersion = swVersion,
                capabilities = setOf(
                    CastCapability.Audio,
                    CastCapability.Volume,
                    CastCapability.SplitAv,
                    CastCapability.TheaterAudioV1,
                ),
                source = CastReceiver.Source.Mdns,
                useTls = useTls,
            )
        }
    }
}
