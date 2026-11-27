package dev.jdtech.jellyfin.cast.adapter.sendspinavr

import android.content.Context
import dev.jdtech.jellyfin.cast.CastCapability
import dev.jdtech.jellyfin.cast.CastProtocol
import dev.jdtech.jellyfin.cast.CastReceiver
import dev.jdtech.jellyfin.cast.discovery.JmdnsHelper
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * mDNS discovery browser for `spatialfin-avr` theater receivers (`_spatialfin-avr._tcp.local.`).
 * Validates Spec 0 mandatory TXT keys (`v == "1"`, `id` matching `^avr-[0-9a-f-]{8,64}$`,
 * `auth == "bearer"`) and maps verified instances to [CastProtocol.SendspinAvr].
 */
class SendspinAvrDiscoveryBrowser(private val context: Context) {

    suspend fun browse(timeoutMs: Long = 5_000L): List<CastReceiver> =
        withContext(Dispatchers.IO) {
            val multicastLock = JmdnsHelper.acquireMulticastLock(context, MULTICAST_LOCK_TAG, TAG)
            val bindAddress = JmdnsHelper.findBindableIpv4Address(TAG)
            var jmdns: JmDNS? = null
            try {
                if (bindAddress == null) {
                    Timber.tag(TAG).w("SendspinAvr browse skipped: no bindable IPv4 address")
                    return@withContext emptyList()
                }
                jmdns = JmDNS.create(bindAddress)
                val services = jmdns.list(SENDSPIN_AVR_MDNS_SERVICE_TYPE, timeoutMs)
                val localIps = JmdnsHelper.localIpAddresses()
                services.mapNotNull(::toCastReceiver)
                    .filterNot { it.host in localIps }
                    .distinctBy { it.id }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "SendspinAvr browse failed")
                emptyList()
            } finally {
                try {
                    jmdns?.close()
                } catch (_: Exception) {
                }
                try {
                    multicastLock?.release()
                } catch (_: Exception) {
                }
            }
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
