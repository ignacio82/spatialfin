package dev.jdtech.jellyfin.fcast.discovery

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Pure decision helper for [LanNetworkChangeMonitor] so network transition rules are
 * unit-testable without an Android [ConnectivityManager].
 */
object LanNetworkChangePolicy {
    enum class EventType {
        AVAILABLE,
        LINK_CHANGED,
        LOST,
    }

    enum class Action {
        NONE,
        RE_REGISTER,
        UNREGISTER,
    }

    fun decide(
        eventType: EventType,
        currentBoundIp: String?,
        candidateBindIp: String?,
        isAdvertiserActive: Boolean,
        networkHandleChanged: Boolean,
    ): Action {
        if (candidateBindIp == null) {
            return if (eventType == EventType.LOST && isAdvertiserActive) {
                Action.UNREGISTER
            } else {
                Action.NONE
            }
        }
        if (!isAdvertiserActive) {
            return Action.RE_REGISTER
        }
        return when (eventType) {
            EventType.AVAILABLE, EventType.LINK_CHANGED -> {
                if (networkHandleChanged || currentBoundIp != candidateBindIp) {
                    Action.RE_REGISTER
                } else {
                    Action.NONE
                }
            }
            EventType.LOST -> {
                if (currentBoundIp != candidateBindIp) {
                    Action.RE_REGISTER
                } else {
                    Action.NONE
                }
            }
        }
    }
}

/**
 * Watches Wi-Fi / Ethernet network transitions and re-registers [advertiser] so JmDNS never
 * gets stranded on a closed socket or stale interface after a Wi-Fi roam or reconnect.
 */
class LanNetworkChangeMonitor(
    context: Context,
    private val scope: CoroutineScope,
    private val advertiser: FCastReceiverAdvertiser,
    private val bindAddressLookup: () -> InetAddress? = { advertiser.findBindableAddress() },
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
    private val onReRegister: suspend () -> Unit,
) {
    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val started = AtomicBoolean(false)
    private val stateLock = Any()
    private var lastNetworkHandle: Long? = null
    private var sawLostSinceLastRegistration: Boolean = false
    private var pendingJob: Job? = null
    private var callback: ConnectivityManager.NetworkCallback? = null

    fun start() {
        val cm = connectivityManager ?: return
        if (!started.compareAndSet(false, true)) return

        synchronized(stateLock) {
            lastNetworkHandle = currentLanNetworkHandle(cm)
            sawLostSinceLastRegistration = false
        }

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                scheduleEvaluation(LanNetworkChangePolicy.EventType.AVAILABLE, network.networkHandle)
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                scheduleEvaluation(LanNetworkChangePolicy.EventType.LINK_CHANGED, network.networkHandle)
            }

            override fun onLost(network: Network) {
                synchronized(stateLock) {
                    if (lastNetworkHandle == null || lastNetworkHandle == network.networkHandle) {
                        sawLostSinceLastRegistration = true
                    }
                }
                scheduleEvaluation(LanNetworkChangePolicy.EventType.LOST, network.networkHandle)
            }
        }

        callback = cb
        try {
            cm.registerNetworkCallback(request, cb)
            Timber.tag(TAG).d("Registered LAN ConnectivityManager callback")
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to register LAN ConnectivityManager callback")
            callback = null
            started.set(false)
        }
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        synchronized(stateLock) {
            pendingJob?.cancel()
            pendingJob = null
            lastNetworkHandle = null
            sawLostSinceLastRegistration = false
        }
        val cb = callback
        callback = null
        if (cb != null) {
            runCatching { connectivityManager?.unregisterNetworkCallback(cb) }
        }
        Timber.tag(TAG).d("Unregistered LAN ConnectivityManager callback")
    }

    private fun scheduleEvaluation(
        eventType: LanNetworkChangePolicy.EventType,
        networkHandle: Long,
    ) {
        if (!started.get()) return
        synchronized(stateLock) {
            pendingJob?.cancel()
            pendingJob = scope.launch {
                if (debounceMs > 0L) delay(debounceMs)
                if (!started.get()) return@launch
                evaluateAndApply(eventType, networkHandle)
            }
        }
    }

    private suspend fun evaluateAndApply(
        eventType: LanNetworkChangePolicy.EventType,
        networkHandle: Long,
    ) {
        val networkHandleChanged: Boolean
        synchronized(stateLock) {
            val previousHandle = lastNetworkHandle
            networkHandleChanged = when (eventType) {
                LanNetworkChangePolicy.EventType.AVAILABLE,
                LanNetworkChangePolicy.EventType.LINK_CHANGED -> {
                    val handleDiffers = previousHandle != null && previousHandle != networkHandle
                    val changed = handleDiffers || sawLostSinceLastRegistration
                    lastNetworkHandle = networkHandle
                    sawLostSinceLastRegistration = false
                    changed
                }
                LanNetworkChangePolicy.EventType.LOST -> {
                    if (previousHandle == networkHandle) {
                        lastNetworkHandle = null
                    }
                    false
                }
            }
        }

        val candidateBindIp = bindAddressLookup()?.hostAddress
        val currentBoundIp = advertiser.boundHostAddress()
        val isActive = advertiser.isActive()
        val action = LanNetworkChangePolicy.decide(
            eventType = eventType,
            currentBoundIp = currentBoundIp,
            candidateBindIp = candidateBindIp,
            isAdvertiserActive = isActive,
            networkHandleChanged = networkHandleChanged,
        )

        when (action) {
            LanNetworkChangePolicy.Action.NONE -> Unit
            LanNetworkChangePolicy.Action.UNREGISTER -> {
                Timber.tag(TAG).i(
                    "LAN lost (event=%s, prevIp=%s); unregistering mDNS advertisement",
                    eventType,
                    currentBoundIp,
                )
                advertiser.unregister()
            }
            LanNetworkChangePolicy.Action.RE_REGISTER -> {
                Timber.tag(TAG).i(
                    "LAN changed (event=%s, prevIp=%s, newIp=%s, active=%s, handleChanged=%s); re-registering mDNS",
                    eventType,
                    currentBoundIp,
                    candidateBindIp,
                    isActive,
                    networkHandleChanged,
                )
                onReRegister()
            }
        }
    }

    private fun currentLanNetworkHandle(cm: ConnectivityManager): Long? {
        val active = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(active) ?: return null
        val isLan = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        return if (isLan) active.networkHandle else null
    }

    private companion object {
        const val TAG = "LanNetworkChangeMonitor"
        const val DEFAULT_DEBOUNCE_MS = 500L
    }
}
