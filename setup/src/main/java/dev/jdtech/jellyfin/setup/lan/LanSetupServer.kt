package dev.jdtech.jellyfin.setup.lan

import android.content.Context
import android.net.ConnectivityManager
import android.net.InetAddresses
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.SystemClock
import android.util.Base64
import dev.jdtech.jellyfin.models.companion.CompanionPairingOffers
import dev.jdtech.jellyfin.models.companion.CompanionTvPairingEnvelope
import dev.jdtech.jellyfin.models.companion.CompanionTvPairingInfo
import dev.jdtech.jellyfin.models.companion.CompanionTvPairingPayload
import dev.jdtech.jellyfin.network.PhysicalLanAddressResolver
import dev.jdtech.jellyfin.onboarding.OnboardingFailureCode
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber

private data class CompletedDrainEntry(
    val outcome: DrainOutcome,
    val handoffReplay: HandoffReplayRecord?,
)

private data class PeerFailureBucket(
    var failures: Int = 0,
    var blockedUntilElapsedMs: Long = 0L,
    var lastSeenElapsedMs: Long = 0L,
)

private data class StatusMissBucket(
    val timestamps: ArrayDeque<Long> = ArrayDeque(),
    var lastSeenElapsedMs: Long = 0L,
)

/**
 * Hardened Stage 2 LAN setup & Companion receiver HTTP listener (`:41230` default) implementing
 * the Canonical 7-Step Request Pipeline (`P1`–`P7`), `/setup` bundled page with per-response
 * 128-bit CSP nonce, device-setup and tv-pairing routes, shared
 * [ReentrantLock] with [LanSetupController], App Lock `LOCKED` gating, and `COMPANION_RECEIVER`-
 * gated NSD + Wear `CompanionPairingOffers`.
 */
class LanSetupServer(
    private val context: Context,
    private val deviceName: String,
    private val deviceClass: String,
    private val callbacks: LanSetupServerCallbacks,
    internal val stateLock: ReentrantLock = ReentrantLock(),
    private val isAppLocked: () -> Boolean = { false },
    private val addressSupplier: () -> List<InetAddress> = {
        PhysicalLanAddressResolver.resolvePhysicalLanAddresses(
            context.getSystemService(ConnectivityManager::class.java),
            includeVpn = false,
        )
    },
    private val elapsedRealtimeClock: () -> Long = { SystemClock.elapsedRealtime() },
    private val wallClock: () -> Long = { System.currentTimeMillis() },
    private val preferredPort: Int = DEFAULT_PORT,
    private val enableNsdAndOffers: Boolean = true,
    private val setupPageHtmlProvider: () -> String = { loadDefaultSetupHtml(context) },
) {
    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val secureRandom = SecureRandom()
    private val _state = MutableStateFlow(LanSetupServerSnapshot())
    val state: StateFlow<LanSetupServerSnapshot> = _state.asStateFlow()

    private val connectionSemaphore = Semaphore(MAX_CONCURRENT_CONNECTIONS)
    private val perIpConnections = ConcurrentHashMap<String, AtomicInteger>()
    private val activeSockets = ConcurrentHashMap.newKeySet<Socket>()

    private var scope: CoroutineScope? = null
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private var watchDecisionJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var nsdRegistrationListener: NsdManager.RegistrationListener? = null

    // Guarded by stateLock (never held across blocking socket I/O or suspension)
    private var running: Boolean = false
    private var receiverAdvertisingEnabled: Boolean = false
    private var boundPort: Int = preferredPort
    private var secrets: PairingSecrets = PairingSecrets.generate(wallClock(), elapsedRealtimeClock())
    private var secretsEpoch: Long = 0L
    private var expiryFrozen: Boolean = false
    private var manualCodeExpired: Boolean = false
    private var secretsConsumedByRejection: Boolean = false
    private var manualCodeMismatchCount: Int = 0
    private var autoRotations: Int = 0
    private var lastRotationElapsedMs: Long? = null
    private var lastTypedCodeFailureElapsedMs: Long? = null
    private var completedDrainState: CompletedDrainEntry? = null
    private var nsdWarning: OnboardingFailureCode? = null
    private val failureBuckets = LinkedHashMap<String, PeerFailureBucket>()
    private val statusMissBuckets = LinkedHashMap<String, StatusMissBucket>()

    internal fun currentSecretsForTests(): PairingSecrets = stateLock.withLock { secrets }

    internal fun currentSecretsEpochLocked(): Long = secretsEpoch

    internal fun isRunningLocked(): Boolean = running

    internal fun isCompletedDrainActiveLocked(): Boolean = completedDrainState != null

    internal fun drainOutcomeLocked(): DrainOutcome? = completedDrainState?.outcome

    fun start(enableReceiverAdvertising: Boolean) {
        val srvScope: CoroutineScope
        val socket: ServerSocket
        stateLock.withLock {
            if (running) {
                receiverAdvertisingEnabled = enableReceiverAdvertising
                return@withLock
            }
            socket = bindServerSocket(preferredPort)
            boundPort = socket.localPort
            serverSocket = socket
            running = true
            receiverAdvertisingEnabled = enableReceiverAdvertising
            secrets = PairingSecrets.generate(wallClock(), elapsedRealtimeClock())
            secretsEpoch++
            expiryFrozen = false
            manualCodeExpired = false
            secretsConsumedByRejection = false
            manualCodeMismatchCount = 0
            autoRotations = 0
            lastRotationElapsedMs = null
            lastTypedCodeFailureElapsedMs = null
            completedDrainState = null
            nsdWarning = null
            failureBuckets.clear()
            statusMissBuckets.clear()
            srvScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            scope = srvScope

            launchAcceptAndWatchLocked(srvScope, socket)
        }

        publishSnapshotAndOffers(republishNsd = enableReceiverAdvertising)
        registerLanNetworkCallback()
    }

    private fun launchAcceptAndWatchLocked(srvScope: CoroutineScope, socket: ServerSocket) {
        if (enableNsdAndOffers) {
            watchDecisionJob =
                srvScope.launch {
                    CompanionPairingOffers.decisions.collect { decision ->
                        val rejected =
                            stateLock.withLock {
                                !decision.approved &&
                                    MessageDigest.isEqual(
                                        secrets.pairingToken.toByteArray(Charsets.US_ASCII),
                                        decision.pairingToken.toByteArray(Charsets.US_ASCII),
                                    )
                            }
                        if (rejected) {
                            consumeActiveSecretsForRejection()
                            callbacks.onWatchRejected()
                        }
                    }
                }
        }

        acceptJob =
            srvScope.launch {
                while (true) {
                    val accepted =
                        try {
                            socket.accept()
                        } catch (_: IOException) {
                            break
                        }
                    val acceptElapsedMs = elapsedRealtimeClock()
                    if (!connectionSemaphore.tryAcquire()) {
                        closeSocketAbruptly(accepted)
                        continue
                    }
                    activeSockets.add(accepted)
                    srvScope.launch {
                        try {
                            handleClientSocket(accepted, acceptElapsedMs)
                        } finally {
                            activeSockets.remove(accepted)
                            runCatching { accepted.close() }
                            connectionSemaphore.release()
                        }
                    }
                }
            }
    }

    fun stop() {
        val socketToClose: ServerSocket?
        val scopeToCancel: CoroutineScope?
        stateLock.withLock {
            if (!running) return
            running = false
            receiverAdvertisingEnabled = false
            socketToClose = serverSocket
            serverSocket = null
            scopeToCancel = scope
            scope = null
            watchDecisionJob?.cancel()
            watchDecisionJob = null
            acceptJob?.cancel()
            acceptJob = null
            completedDrainState = null
            expiryFrozen = false
        }

        unregisterLanNetworkCallback()
        unregisterNsd()
        if (enableNsdAndOffers) {
            CompanionPairingOffers.clear()
        }
        runCatching { socketToClose?.close() }
        for (s in activeSockets.toList()) {
            closeSocketAbruptly(s)
        }
        activeSockets.clear()
        scopeToCancel?.cancel()

        _state.value = LanSetupServerSnapshot(isRunning = false, boundPort = boundPort)
    }

    fun setReceiverAdvertisingEnabled(enabled: Boolean) {
        val changed =
            stateLock.withLock {
                if (!running) return
                val prev = receiverAdvertisingEnabled
                receiverAdvertisingEnabled = enabled
                prev != enabled
            }
        if (!changed) return
        if (!enabled) {
            unregisterNsd()
            if (enableNsdAndOffers) {
                CompanionPairingOffers.clear()
            }
            publishSnapshotAndOffers(republishNsd = false)
        } else {
            publishSnapshotAndOffers(republishNsd = true)
        }
    }

    fun setExpiryFrozen(frozen: Boolean) {
        stateLock.withLock {
            expiryFrozen = frozen
        }
    }

    fun regenerateManualCode() {
        val shouldRepublishNsd: Boolean
        stateLock.withLock {
            if (!running) return
            secrets = PairingSecrets.generate(wallClock(), elapsedRealtimeClock())
            secretsEpoch++
            manualCodeExpired = false
            secretsConsumedByRejection = false
            manualCodeMismatchCount = 0
            autoRotations = 0
            lastRotationElapsedMs = null
            lastTypedCodeFailureElapsedMs = null
            completedDrainState = null
            nsdWarning = null
            shouldRepublishNsd = receiverAdvertisingEnabled
        }
        publishSnapshotAndOffers(republishNsd = shouldRepublishNsd)
    }

    fun enterCompletedDrainLocked(outcome: DrainOutcome, handoffReplay: HandoffReplayRecord?) {
        completedDrainState = CompletedDrainEntry(outcome = outcome, handoffReplay = handoffReplay)
        secretsEpoch++
        expiryFrozen = false
    }

    fun clearCompletedDrain() {
        val shouldRepublishNsd: Boolean
        stateLock.withLock {
            if (completedDrainState == null) return
            completedDrainState = null
            if (!running) return
            secrets = PairingSecrets.generate(wallClock(), elapsedRealtimeClock())
            secretsEpoch++
            manualCodeExpired = false
            secretsConsumedByRejection = false
            manualCodeMismatchCount = 0
            autoRotations = 0
            lastRotationElapsedMs = null
            lastTypedCodeFailureElapsedMs = null
            shouldRepublishNsd = receiverAdvertisingEnabled
        }
        publishSnapshotAndOffers(republishNsd = shouldRepublishNsd)
    }

    fun consumeActiveSecretsLocked() {
        secretsEpoch++
        manualCodeExpired = true
        completedDrainState = null
        expiryFrozen = false
    }

    fun consumeActiveSecretsForRejection() {
        val changed =
            stateLock.withLock {
                if (!running || secretsConsumedByRejection) return@withLock false
                secretsConsumedByRejection = true
                secretsEpoch++
                manualCodeExpired = true
                true
            }
        if (!changed) return
        clearDiscoveryAndOffersOffLock()
        publishSnapshotAndOffers(republishNsd = false)
    }

    fun checkSecretsValidLocked(
        capturedSecretsEpoch: Long,
        usedManualCode: Boolean,
        nowElapsedMs: Long,
    ): SecretCheckResult {
        if (isAppLocked() || secretsConsumedByRejection) {
            return SecretCheckResult.REJECTED
        }
        if (completedDrainState != null ||
            secretsEpoch != capturedSecretsEpoch ||
            (usedManualCode && manualCodeExpired) ||
            isActiveSecretsExpiredLocked(nowElapsedMs)
        ) {
            return SecretCheckResult.EXPIRED
        }
        return SecretCheckResult.VALID
    }

    fun currentExpiryDeadlinesLocked(): Pair<Long, Long> =
        secrets.idleExpiresAtElapsedMs to secrets.hardCapElapsedMs

    fun isExpiryFrozenLocked(): Boolean = expiryFrozen

    fun isActiveSecretsExpiredLocked(nowElapsedMs: Long): Boolean =
        (!expiryFrozen && nowElapsedMs >= secrets.idleExpiresAtElapsedMs) ||
            nowElapsedMs >= secrets.hardCapElapsedMs

    fun markManualCodeExpiredLocked() {
        manualCodeExpired = true
    }

    internal fun clearDiscoveryAndOffersOffLock() {
        unregisterNsd()
        if (enableNsdAndOffers) {
            CompanionPairingOffers.clear()
        }
    }

    internal fun republishSnapshot(republishNsd: Boolean = false) {
        publishSnapshotAndOffers(republishNsd = republishNsd)
    }

    internal fun refreshAddresses() {
        publishSnapshotAndOffers(republishNsd = false)
    }

    private fun bindServerSocket(preferred: Int): ServerSocket {
        if (preferred > 0) {
            runCatching {
                return ServerSocket(preferred).apply { reuseAddress = true }
            }.onFailure {
                Timber.w(it, "LanSetupServer: preferred port %d busy, falling back to ephemeral", preferred)
            }
        }
        return ServerSocket(0).apply { reuseAddress = true }
    }

    private fun handleClientSocket(socket: Socket, acceptElapsedMs: Long) {
        runCatching { socket.sendBufferSize = SEND_BUFFER_BYTES }
        val peerBucket = peerBucketKey(socket.inetAddress)
        val ipCounter = perIpConnections.computeIfAbsent(peerBucket) { AtomicInteger(0) }
        val currentForIp = ipCounter.incrementAndGet()
        try {
            if (currentForIp > MAX_CONCURRENT_PER_IP) {
                writeJsonError(socket, 503, "server_busy", "Too many concurrent connections")
                return
            }

            // Step P1: NIC gate on localAddress
            val localAddr = PhysicalLanAddressResolver.unwrapIpv4InIpv6(socket.localAddress)
            val allowedAddresses = addressSupplier().map(PhysicalLanAddressResolver::unwrapIpv4InIpv6)
            val localAllowed = allowedAddresses.any { it.address.contentEquals(localAddr.address) }
            if (!localAllowed) {
                writeJsonError(socket, 403, "host_rejected", "Request received on non-LAN interface")
                return
            }

            // Step P2: Read headers within cumulative 3 s deadline
            val input = BufferedInputStream(socket.getInputStream())
            val headerDeadlineElapsedMs = acceptElapsedMs + HEADER_TIMEOUT_MS
            val parsedHead =
                when (val headResult = readRequestHead(socket, input, headerDeadlineElapsedMs)) {
                    is HeadReadResult.Ok -> headResult
                    is HeadReadResult.Error -> {
                        writeJsonError(socket, headResult.statusCode, headResult.errorCode, headResult.message)
                        return
                    }
                }
            val headersDoneElapsedMs = elapsedRealtimeClock()

            // Validate numeric Host header against localAddr raw bytes and boundPort
            val hostHeader = parsedHead.headers["host"]
            if (hostHeader == null || !isValidNumericHostHeader(hostHeader, localAddr, boundPort)) {
                writeJsonError(socket, 403, "host_rejected", "Invalid or mismatched Host header")
                return
            }

            // Step P3: Route & method gate
            val method = parsedHead.method
            val path = parsedHead.path
            when (path) {
                PATH_SETUP -> {
                    if (method != "GET") {
                        writeJsonError(socket, 405, "method_not_allowed", "Method not allowed")
                        return
                    }
                    handleGetSetupPage(socket)
                    return
                }
                PATH_FAVICON -> {
                    if (method != "GET") {
                        writeJsonError(socket, 405, "method_not_allowed", "Method not allowed")
                        return
                    }
                    writeEmptyNoContent(socket)
                    return
                }
                PATH_INFO -> {
                    if (method != "GET") {
                        writeJsonError(socket, 405, "method_not_allowed", "Method not allowed")
                        return
                    }
                    handleGetInfo(socket)
                    return
                }
                PATH_STATUS -> {
                    if (method != "GET") {
                        writeJsonError(socket, 405, "method_not_allowed", "Method not allowed")
                        return
                    }
                    handleGetStatus(socket, parsedHead, peerBucket)
                    return
                }
                PATH_CONFIG,
                PATH_SERVER,
                PATH_COMPANION_HANDOFF,
                PATH_CANCEL,
                -> {
                    if (method != "POST") {
                        writeJsonError(socket, 405, "method_not_allowed", "Method not allowed")
                        return
                    }
                }
                else -> {
                    writeJsonError(socket, 404, "route_not_found", "Route not found")
                    return
                }
            }

            // POST pre-body framing validation (before Steps P4–P6 and before reading body bytes)
            val contentType =
                parsedHead.headers["content-type"]
                    ?.substringBefore(';')
                    ?.trim()
                    ?.lowercase(Locale.US)
            if (contentType != "application/json") {
                writeJsonError(socket, 415, "unsupported_media_type", "Expected application/json")
                return
            }
            if (parsedHead.headers.containsKey("transfer-encoding") || !parsedHead.headers.containsKey("content-length")) {
                writeJsonError(socket, 411, "content_length_required", "Content-Length required")
                return
            }
            val contentLengthRaw = parsedHead.headers["content-length"].orEmpty().trim()
            if (!DIGITS_ONLY_REGEX.matches(contentLengthRaw)) {
                writeJsonError(socket, 400, "invalid_request", "Invalid Content-Length")
                return
            }
            val contentLength = contentLengthRaw.toLongOrNull() ?: -1L
            val maxBodyBytes = if (path == PATH_CONFIG) MAX_V1_BODY_BYTES else MAX_V2_BODY_BYTES
            if (contentLength < 0L || contentLength > maxBodyBytes) {
                writeJsonError(socket, 413, "payload_too_large", "Payload exceeds limit")
                return
            }

            val deadlineElapsedMs = computeRouteDeadlineElapsedMs(path, parsedHead.headers, acceptElapsedMs)
            val isHandoffRoute = path == PATH_CONFIG || path == PATH_COMPANION_HANDOFF
            val rawTokenHeader = parsedHead.headers["x-pairing-token"]
            val bodyDeadlineElapsedMs = headersDoneElapsedMs + BODY_TIMEOUT_MS

            // Steps P4–P6: Authenticate X-Pairing-Token under short-held stateLock BEFORE reading body
            val authDecision = evaluatePairingAuth(rawTokenHeader, peerBucket, isHandoffRoute)
            when (authDecision) {
                is AuthDecision.Reject -> {
                    writeJsonError(
                        socket = socket,
                        statusCode = authDecision.statusCode,
                        errorCode = authDecision.errorCode,
                        message = authDecision.message,
                        extraHeaders = authDecision.retryAfterSec?.let { mapOf("Retry-After" to it.toString()) }.orEmpty(),
                    )
                    return
                }
                is AuthDecision.DrainReplayCandidate -> {
                    handleDrainReplay(
                        socket = socket,
                        input = input,
                        path = path,
                        contentLength = contentLength.toInt(),
                        bodyDeadlineElapsedMs = bodyDeadlineElapsedMs,
                        credentialDigest = authDecision.credentialDigest,
                    )
                    return
                }
                is AuthDecision.Authorized -> {
                    val bodyBytes =
                        when (val bodyRes = readBodyBytes(socket, input, contentLength.toInt(), bodyDeadlineElapsedMs)) {
                            is BodyReadResult.Ok -> bodyRes.bytes
                            is BodyReadResult.Error -> {
                                writeJsonError(socket, bodyRes.statusCode, bodyRes.errorCode, bodyRes.message)
                                return
                            }
                        }
                    val bodyText = bodyBytes.toString(Charsets.UTF_8)
                    dispatchAuthorizedPost(
                        socket = socket,
                        path = path,
                        bodyText = bodyText,
                        auth = authDecision,
                        deadlineElapsedMs = deadlineElapsedMs,
                    )
                }
            }
        } catch (_: IOException) {
            // Peer disconnected
        } finally {
            if (ipCounter.decrementAndGet() <= 0) {
                perIpConnections.remove(peerBucket, ipCounter)
            }
        }
    }

    private fun computeRouteDeadlineElapsedMs(
        path: String,
        headers: Map<String, String>,
        acceptElapsedMs: Long,
    ): Long {
        if (path == PATH_SERVER) {
            return acceptElapsedMs + SERVER_SUBMIT_TIMEOUT_MS
        }
        val defaultTimeout =
            if (path == PATH_CONFIG) DEFAULT_V1_HANDOFF_TIMEOUT_MS else DEFAULT_V2_HANDOFF_TIMEOUT_MS
        val rawTimeoutHeader = headers["x-handoff-timeout-ms"]?.trim()
        val effective =
            if (rawTimeoutHeader != null && POSITIVE_INT_REGEX.matches(rawTimeoutHeader)) {
                (rawTimeoutHeader.toLongOrNull() ?: defaultTimeout).coerceAtMost(MAX_HANDOFF_TIMEOUT_MS)
            } else {
                defaultTimeout
            }
        return acceptElapsedMs + effective
    }

    private fun handleGetSetupPage(socket: Socket) {
        val nonceBytes = ByteArray(16).also { secureRandom.nextBytes(it) }
        val nonce = Base64.encodeToString(nonceBytes, Base64.NO_WRAP)
        val html = setupPageHtmlProvider().replace("__CSP_NONCE__", nonce)
        val bodyBytes = html.toByteArray(Charsets.UTF_8)
        val csp =
            "default-src 'none'; script-src 'nonce-$nonce'; style-src 'nonce-$nonce'; " +
                "connect-src 'self'; img-src 'none'; object-src 'none'; " +
                "base-uri 'none'; form-action 'none'; frame-ancestors 'none'"
        val headers =
            buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("Content-Type: text/html; charset=utf-8\r\n")
                append("Content-Length: ").append(bodyBytes.size).append("\r\n")
                append("Content-Security-Policy: ").append(csp).append("\r\n")
                append("X-Frame-Options: DENY\r\n")
                append("Referrer-Policy: no-referrer\r\n")
                append("Cache-Control: no-store\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }
        runCatching {
            val out = socket.getOutputStream()
            out.write(headers.toByteArray(Charsets.ISO_8859_1))
            out.write(bodyBytes)
            out.flush()
        }
    }

    private fun writeEmptyNoContent(socket: Socket) {
        val response = "HTTP/1.1 204 No Content\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
        runCatching {
            val out = socket.getOutputStream()
            out.write(response.toByteArray(Charsets.ISO_8859_1))
            out.flush()
        }
    }

    private fun handleGetInfo(socket: Socket) {
        val now = elapsedRealtimeClock()
        var locked = false
        val infoBody: String? =
            stateLock.withLock {
                if (isAppLocked()) {
                    locked = true
                    null
                } else if (completedDrainState != null || secretsConsumedByRejection || isActiveSecretsExpiredLocked(now)) {
                    null
                } else {
                    val info =
                        CompanionTvPairingInfo(
                            version = 1,
                            port = boundPort,
                            device_name = deviceName,
                            device_class = deviceClass,
                            manual_code = null,
                            expires_at_epoch_ms = secrets.expiresAtEpochMs,
                            protocol_v = 2,
                            capabilities = listOf("companion_handoff_v2", "setup_page_v1"),
                        )
                    json.encodeToString(info)
                }
            }
        when {
            locked -> writeJsonError(socket, 409, "pairing_cancelled", "Device is locked")
            infoBody == null -> writeJsonError(socket, 409, "pairing_code_expired", "Pairing code has expired")
            else -> writeJsonResponse(socket, 200, infoBody)
        }
    }

    private fun handleGetStatus(socket: Socket, head: HeadReadResult.Ok, peerBucket: String) {
        val secFetchSite = head.headers["sec-fetch-site"]?.trim()?.lowercase(Locale.US)
        if (secFetchSite == "cross-site") {
            writeJsonError(socket, 403, "csrf_rejected", "Cross-site status poll rejected")
            return
        }
        val sessionId = head.queryParams["session"]?.trim()
        if (sessionId == null || !SESSION_ID_REGEX.matches(sessionId)) {
            writeJsonError(socket, 400, "invalid_request", "Expected 32-character hex session id")
            return
        }
        val status = callbacks.onStatusRequested(sessionId)
        if (status != null) {
            writeJsonResponse(socket, 200, json.encodeToString(status))
            return
        }
        val now = elapsedRealtimeClock()
        val rateLimited =
            stateLock.withLock {
                recordStatusMissAndCheckLimitedLocked(peerBucket, now)
            }
        if (rateLimited) {
            writeJsonError(
                socket = socket,
                statusCode = 429,
                errorCode = "pairing_rate_limited",
                message = "Too many status requests",
                extraHeaders = mapOf("Retry-After" to "60"),
            )
        } else {
            writeJsonError(socket, 404, "session_not_found", "Setup session not found")
        }
    }

    private fun recordStatusMissAndCheckLimitedLocked(peerBucket: String, now: Long): Boolean {
        if (statusMissBuckets.size >= MAX_FAILURE_BUCKETS && !statusMissBuckets.containsKey(peerBucket)) {
            val oldest = statusMissBuckets.entries.minByOrNull { it.value.lastSeenElapsedMs }?.key
            if (oldest != null) statusMissBuckets.remove(oldest)
        }
        val bucket = statusMissBuckets.getOrPut(peerBucket) { StatusMissBucket() }
        bucket.lastSeenElapsedMs = now
        val cutoff = now - STATUS_MISS_WINDOW_MS
        while (bucket.timestamps.isNotEmpty() && bucket.timestamps.first() <= cutoff) {
            bucket.timestamps.removeFirst()
        }
        if (bucket.timestamps.size >= MAX_STATUS_MISSES_PER_MIN) {
            return true
        }
        bucket.timestamps.addLast(now)
        return false
    }

    private sealed interface AuthDecision {
        data class Reject(
            val statusCode: Int,
            val errorCode: String,
            val message: String,
            val retryAfterSec: Int? = null,
        ) : AuthDecision

        data class DrainReplayCandidate(
            val credentialDigest: ByteArray,
        ) : AuthDecision

        data class Authorized(
            val credentialDigest: ByteArray,
            val capturedSecretsEpoch: Long,
            val usedManualCode: Boolean,
        ) : AuthDecision
    }

    private fun evaluatePairingAuth(
        rawTokenHeader: String?,
        peerBucket: String,
        isHandoffRoute: Boolean,
    ): AuthDecision {
        val now = elapsedRealtimeClock()
        var shouldRepublish = false
        val decision =
            stateLock.withLock {
                // Step P4: App Lock gate BEFORE reading body bytes
                if (isAppLocked()) {
                    return@withLock AuthDecision.Reject(409, "pairing_cancelled", "Device is locked")
                }

                // Step P4: completedDrainState gate
                val drain = completedDrainState
                if (drain != null) {
                    if (!isHandoffRoute) {
                        return@withLock AuthDecision.Reject(409, "pairing_code_expired", "Pairing session already completed")
                    }
                    val parsed = PairingSecrets.normalizePairingCredential(rawTokenHeader)
                        ?: return@withLock AuthDecision.Reject(400, "pairing_code_format", "Invalid pairing token format")
                    val (normalized, isTypedCode) = parsed
                    checkRateLimitsLocked(peerBucket, isTypedCode, now)?.let { return@withLock it }
                    val digest = sha256(normalized)
                    val replay = drain.handoffReplay
                    if (replay != null && replay.matchesCredential(digest)) {
                        return@withLock AuthDecision.DrainReplayCandidate(digest)
                    }
                    recordFailureLocked(peerBucket, isTypedCode, now)
                    return@withLock AuthDecision.Reject(409, "pairing_code_expired", "Pairing session already completed")
                }

                // Active secrets rejection / expiry check
                if (secretsConsumedByRejection) {
                    return@withLock AuthDecision.Reject(409, "pairing_cancelled", "Pairing was rejected on device")
                }
                if (isActiveSecretsExpiredLocked(now)) {
                    manualCodeExpired = true
                    shouldRepublish = true
                    return@withLock AuthDecision.Reject(409, "pairing_code_expired", "Pairing code has expired")
                }

                // Step P4 QR 32-hex path
                val trimmed = rawTokenHeader?.trim()
                if (PairingSecrets.isQrPairingToken(trimmed)) {
                    val normalizedHex = trimmed!!.lowercase(Locale.US)
                    val matches =
                        MessageDigest.isEqual(
                            secrets.pairingToken.toByteArray(Charsets.US_ASCII),
                            normalizedHex.toByteArray(Charsets.US_ASCII),
                        )
                    if (matches) {
                        return@withLock AuthDecision.Authorized(
                            credentialDigest = sha256(normalizedHex),
                            capturedSecretsEpoch = secretsEpoch,
                            usedManualCode = false,
                        )
                    }
                    checkRateLimitsLocked(peerBucket, isTypedCode = false, now = now)?.let { return@withLock it }
                    recordFailureLocked(peerBucket, isTypedCode = false, now = now)
                    return@withLock AuthDecision.Reject(409, "pairing_code_expired", "QR pairing token expired or invalid")
                }

                // Step P5: Typed manual_code syntax + rate limiters
                val normalizedCode = PairingSecrets.normalizeManualCode(rawTokenHeader)
                    ?: return@withLock AuthDecision.Reject(400, "pairing_code_format", "Expected 6-character pairing code")
                checkRateLimitsLocked(peerBucket, isTypedCode = true, now = now)?.let { return@withLock it }

                // Step P6: manual_code comparison + bounded rotation
                if (manualCodeExpired) {
                    return@withLock AuthDecision.Reject(409, "pairing_code_expired", "Pairing code has expired")
                }
                val matchesCode =
                    MessageDigest.isEqual(
                        secrets.manualCode.toByteArray(Charsets.US_ASCII),
                        normalizedCode.toByteArray(Charsets.US_ASCII),
                    )
                if (matchesCode) {
                    return@withLock AuthDecision.Authorized(
                        credentialDigest = sha256(normalizedCode),
                        capturedSecretsEpoch = secretsEpoch,
                        usedManualCode = true,
                    )
                }

                recordFailureLocked(peerBucket, isTypedCode = true, now = now)
                manualCodeMismatchCount++
                if (manualCodeMismatchCount >= ROTATION_MISMATCH_THRESHOLD) {
                    if (autoRotations < MAX_AUTO_ROTATIONS &&
                        (lastRotationElapsedMs == null || now - lastRotationElapsedMs!! >= MIN_ROTATION_INTERVAL_MS)
                    ) {
                        secrets = secrets.rotateManualCode(wallClock(), now)
                        secretsEpoch++
                        autoRotations++
                        lastRotationElapsedMs = now
                        manualCodeMismatchCount = 0
                        shouldRepublish = true
                        return@withLock AuthDecision.Reject(401, "pairing_token_invalid", "Invalid pairing code")
                    } else if (autoRotations >= MAX_AUTO_ROTATIONS) {
                        manualCodeExpired = true
                        shouldRepublish = true
                        return@withLock AuthDecision.Reject(409, "pairing_code_expired", "Pairing code expired after too many attempts")
                    }
                }
                AuthDecision.Reject(401, "pairing_token_invalid", "Invalid pairing code")
            }
        if (shouldRepublish) {
            publishSnapshotAndOffers(republishNsd = false)
        }
        return decision
    }

    private fun handleDrainReplay(
        socket: Socket,
        input: BufferedInputStream,
        path: String,
        contentLength: Int,
        bodyDeadlineElapsedMs: Long,
        credentialDigest: ByteArray,
    ) {
        val bodyBytes =
            when (val bodyRes = readBodyBytes(socket, input, contentLength, bodyDeadlineElapsedMs)) {
                is BodyReadResult.Ok -> bodyRes.bytes
                is BodyReadResult.Error -> {
                    writeJsonError(socket, bodyRes.statusCode, bodyRes.errorCode, bodyRes.message)
                    return
                }
            }
        val bodyText = bodyBytes.toString(Charsets.UTF_8)
        val parsedMatchFields = parseHandoffMatchFields(path, bodyText)
        if (parsedMatchFields == null) {
            writeJsonError(socket, 409, "pairing_code_expired", "Pairing session already completed")
            return
        }

        // Step P4 post-body lock re-check: verify !LOCKED and live completedDrainState before cached 200
        var locked = false
        val cachedResponse: CompanionHandoffResponse? =
            stateLock.withLock {
                if (isAppLocked()) {
                    locked = true
                    null
                } else {
                    val replay = completedDrainState?.handoffReplay
                    if (replay != null &&
                        replay.matchesCredential(credentialDigest) &&
                        replay.matchesBody(
                            setupTokenDigest = parsedMatchFields.setupTokenDigest,
                            companionUrl = parsedMatchFields.companionUrl,
                            serverId = parsedMatchFields.serverId,
                            userId = parsedMatchFields.userId,
                            shareOnly = parsedMatchFields.shareOnly,
                        )
                    ) {
                        replay.response
                    } else {
                        null
                    }
                }
            }
        when {
            locked -> writeJsonError(socket, 409, "pairing_cancelled", "Device is locked")
            cachedResponse != null -> writeJsonResponse(socket, 200, json.encodeToString(cachedResponse))
            else -> writeJsonError(socket, 409, "pairing_code_expired", "Pairing session already completed")
        }
    }

    private data class HandoffMatchFields(
        val setupTokenDigest: ByteArray,
        val companionUrl: String,
        val serverId: String?,
        val userId: String?,
        val shareOnly: Boolean,
    )

    private fun parseHandoffMatchFields(path: String, bodyText: String): HandoffMatchFields? =
        when (path) {
            PATH_CONFIG -> {
                val env = runCatching { json.decodeFromString<CompanionTvPairingEnvelope>(bodyText) }.getOrNull()
                    ?: return null
                HandoffMatchFields(
                    setupTokenDigest = sha256(env.config.setup_token),
                    companionUrl = env.companion_url.trim().trimEnd('/'),
                    serverId = env.server_id?.trim()?.takeIf { it.isNotEmpty() },
                    userId = env.user_id?.trim()?.takeIf { it.isNotEmpty() },
                    shareOnly = env.config.servers.isEmpty(),
                )
            }
            PATH_COMPANION_HANDOFF -> {
                val req = runCatching { json.decodeFromString<CompanionHandoffRequest>(bodyText) }.getOrNull()
                    ?: return null
                HandoffMatchFields(
                    setupTokenDigest = sha256(req.setupToken),
                    companionUrl = req.companionUrl.trim().trimEnd('/'),
                    serverId = req.serverId?.trim()?.takeIf { it.isNotEmpty() },
                    userId = req.userId?.trim()?.takeIf { it.isNotEmpty() },
                    shareOnly = req.shareOnly,
                )
            }
            else -> null
        }

    private fun dispatchAuthorizedPost(
        socket: Socket,
        path: String,
        bodyText: String,
        auth: AuthDecision.Authorized,
        deadlineElapsedMs: Long,
    ) {
        when (path) {
            PATH_SERVER -> {
                val req = runCatching { json.decodeFromString<ServerSubmitRequest>(bodyText) }.getOrNull()
                if (req == null || req.serverUrl.isBlank()) {
                    writeJsonError(socket, 400, "invalid_request", "Missing server_url")
                    return
                }
                val preCheck = preDispatchP7Check(isHandoffRoute = false, auth = auth, matchFields = null)
                if (preCheck != null) {
                    writeJsonResponse(socket, preCheck.first, preCheck.second)
                    return
                }
                val waitMs = (deadlineElapsedMs + IN_FLIGHT_LEASE_GRACE_MS - elapsedRealtimeClock()).coerceAtLeast(100L)
                val result = runBlocking {
                    withTimeoutOrNull(waitMs) {
                        callbacks.onServerSubmitted(
                            serverUrl = req.serverUrl.trim(),
                            capturedSecretsEpoch = auth.capturedSecretsEpoch,
                            usedManualCode = auth.usedManualCode,
                            deadlineElapsedMs = deadlineElapsedMs,
                        )
                    }
                }
                writeHttpResult(socket, result) { json.encodeToString(it) }
            }

            PATH_CANCEL -> {
                val req = runCatching { json.decodeFromString<CancelSessionRequest>(bodyText) }.getOrNull()
                if (req == null || (!req.force && req.sessionId.isNullOrBlank())) {
                    writeJsonError(socket, 400, "invalid_request", "Expected session_id or force=true")
                    return
                }
                val preCheck = preDispatchP7Check(isHandoffRoute = false, auth = auth, matchFields = null)
                if (preCheck != null) {
                    writeJsonResponse(socket, preCheck.first, preCheck.second)
                    return
                }
                val result = runBlocking {
                    callbacks.onCancelSession(
                        sessionId = req.sessionId?.trim()?.takeIf { it.isNotEmpty() },
                        force = req.force,
                        capturedSecretsEpoch = auth.capturedSecretsEpoch,
                        usedManualCode = auth.usedManualCode,
                    )
                }
                writeHttpResult(socket, result) { json.encodeToString(it) }
            }

            PATH_COMPANION_HANDOFF -> {
                val req = runCatching { json.decodeFromString<CompanionHandoffRequest>(bodyText) }.getOrNull()
                if (req == null ||
                    req.companionUrl.isBlank() ||
                    req.setupToken.isBlank() ||
                    (req.shareOnly && (req.serverId != null || req.userId != null)) ||
                    (req.userId != null && req.serverId == null)
                ) {
                    writeJsonError(socket, 400, "invalid_request", "Invalid companion handoff payload")
                    return
                }
                val matchFields =
                    HandoffMatchFields(
                        setupTokenDigest = sha256(req.setupToken),
                        companionUrl = req.companionUrl.trim().trimEnd('/'),
                        serverId = req.serverId?.trim()?.takeIf { it.isNotEmpty() },
                        userId = req.userId?.trim()?.takeIf { it.isNotEmpty() },
                        shareOnly = req.shareOnly,
                    )
                val preCheck = preDispatchP7Check(isHandoffRoute = true, auth = auth, matchFields = matchFields)
                if (preCheck != null) {
                    writeJsonResponse(socket, preCheck.first, preCheck.second)
                    return
                }
                val waitMs = (deadlineElapsedMs + IN_FLIGHT_LEASE_GRACE_MS - elapsedRealtimeClock()).coerceAtLeast(100L)
                val result = runBlocking {
                    withTimeoutOrNull(waitMs) {
                        callbacks.onCompanionHandoff(
                            request = req,
                            credentialDigest = auth.credentialDigest,
                            capturedSecretsEpoch = auth.capturedSecretsEpoch,
                            usedManualCode = auth.usedManualCode,
                            deadlineElapsedMs = deadlineElapsedMs,
                        )
                    }
                }
                writeHttpResult(socket, result) { json.encodeToString(it) }
            }

            PATH_CONFIG -> {
                val env = runCatching { json.decodeFromString<CompanionTvPairingEnvelope>(bodyText) }.getOrNull()
                if (env == null) {
                    writeJsonError(socket, 400, "invalid_request", "Malformed JSON pairing envelope")
                    return
                }
                val matchFields =
                    HandoffMatchFields(
                        setupTokenDigest = sha256(env.config.setup_token),
                        companionUrl = env.companion_url.trim().trimEnd('/'),
                        serverId = env.server_id?.trim()?.takeIf { it.isNotEmpty() },
                        userId = env.user_id?.trim()?.takeIf { it.isNotEmpty() },
                        shareOnly = env.config.servers.isEmpty(),
                    )
                val preCheck = preDispatchP7Check(isHandoffRoute = true, auth = auth, matchFields = matchFields)
                if (preCheck != null) {
                    writeJsonResponse(socket, preCheck.first, preCheck.second)
                    return
                }
                val waitMs = (deadlineElapsedMs + IN_FLIGHT_LEASE_GRACE_MS - elapsedRealtimeClock()).coerceAtLeast(100L)
                val result = runBlocking {
                    withTimeoutOrNull(waitMs) {
                        callbacks.onPairingEnvelope(
                            envelope = env,
                            credentialDigest = auth.credentialDigest,
                            capturedSecretsEpoch = auth.capturedSecretsEpoch,
                            usedManualCode = auth.usedManualCode,
                            deadlineElapsedMs = deadlineElapsedMs,
                        )
                    }
                }
                writeHttpResult(socket, result) { json.encodeToString(it) }
            }
        }
    }

    /**
     * Step P7 pre-dispatch drain replay and TOCTOU check under [stateLock].
     * Returns `Pair(statusCode, jsonBody)` if the request should be answered immediately, or `null`
     * to proceed to callback dispatch.
     */
    private fun preDispatchP7Check(
        isHandoffRoute: Boolean,
        auth: AuthDecision.Authorized,
        matchFields: HandoffMatchFields?,
    ): Pair<Int, String>? {
        val now = elapsedRealtimeClock()
        return stateLock.withLock {
            if (isAppLocked()) {
                return@withLock 409 to errorJson("pairing_cancelled", "Device is locked")
            }
            val drain = completedDrainState
            if (drain != null) {
                if (isHandoffRoute && matchFields != null) {
                    val replay = drain.handoffReplay
                    if (replay != null &&
                        replay.matchesCredential(auth.credentialDigest) &&
                        replay.matchesBody(
                            setupTokenDigest = matchFields.setupTokenDigest,
                            companionUrl = matchFields.companionUrl,
                            serverId = matchFields.serverId,
                            userId = matchFields.userId,
                            shareOnly = matchFields.shareOnly,
                        )
                    ) {
                        return@withLock 200 to json.encodeToString(replay.response)
                    }
                }
                return@withLock 409 to errorJson("pairing_code_expired", "Pairing session already completed")
            }
            when (checkSecretsValidLocked(auth.capturedSecretsEpoch, auth.usedManualCode, now)) {
                SecretCheckResult.VALID -> null
                SecretCheckResult.REJECTED -> 409 to errorJson("pairing_cancelled", "Pairing cancelled")
                SecretCheckResult.EXPIRED -> 409 to errorJson("pairing_code_expired", "Pairing code has expired")
            }
        }
    }

    private inline fun <T> writeHttpResult(
        socket: Socket,
        result: LanSetupHttpResult<T>?,
        encode: (T) -> String,
    ) {
        when (result) {
            null -> writeJsonError(socket, 504, "pairing_timeout", "Setup request timed out")
            is LanSetupHttpResult.Ok -> writeJsonResponse(socket, 200, encode(result.body))
            is LanSetupHttpResult.Error -> {
                writeJsonError(socket, result.statusCode, result.errorCode, result.message)
            }
        }
    }

    private fun checkRateLimitsLocked(
        peerBucket: String,
        isTypedCode: Boolean,
        now: Long,
    ): AuthDecision.Reject? {
        val bucket = failureBuckets[peerBucket]
        if (bucket != null && bucket.blockedUntilElapsedMs > now) {
            val retryAfterSec = (((bucket.blockedUntilElapsedMs - now) + 999L) / 1_000L).toInt().coerceAtLeast(1)
            return AuthDecision.Reject(
                statusCode = 429,
                errorCode = "pairing_rate_limited",
                message = "Too many failed pairing attempts",
                retryAfterSec = retryAfterSec,
            )
        }
        if (isTypedCode) {
            val lastFail = lastTypedCodeFailureElapsedMs
            if (lastFail != null && now - lastFail < GLOBAL_TYPED_CODE_PACE_MS) {
                return AuthDecision.Reject(
                    statusCode = 429,
                    errorCode = "pairing_rate_limited",
                    message = "Please wait before retrying pairing code",
                    retryAfterSec = 2,
                )
            }
        }
        return null
    }

    private fun recordFailureLocked(peerBucket: String, isTypedCode: Boolean, now: Long) {
        if (failureBuckets.size >= MAX_FAILURE_BUCKETS && !failureBuckets.containsKey(peerBucket)) {
            evictFailureBucketLocked(now)
        }
        val bucket = failureBuckets.getOrPut(peerBucket) { PeerFailureBucket() }
        bucket.failures++
        bucket.lastSeenElapsedMs = now
        if (bucket.failures >= 3) {
            val shift = (bucket.failures - 2).coerceAtMost(5)
            val backoffSec = (1 shl shift).coerceAtMost(30)
            bucket.blockedUntilElapsedMs = now + backoffSec * 1_000L
        }
        if (isTypedCode) {
            lastTypedCodeFailureElapsedMs = now
        }
    }

    private fun evictFailureBucketLocked(now: Long) {
        val expiredKey =
            failureBuckets.entries
                .filter { it.value.blockedUntilElapsedMs <= now }
                .minByOrNull { it.value.lastSeenElapsedMs }
                ?.key
        if (expiredKey != null) {
            failureBuckets.remove(expiredKey)
            return
        }
        val oldestKey = failureBuckets.entries.minByOrNull { it.value.lastSeenElapsedMs }?.key
        if (oldestKey != null) {
            failureBuckets.remove(oldestKey)
        }
    }

    private fun publishSnapshotAndOffers(republishNsd: Boolean) {
        val lanAddresses = addressSupplier()
        val snapshot: LanSetupServerSnapshot
        val offer: CompanionPairingOffers.PairingOffer?
        val portForNsd: Int
        val advertiseNsd: Boolean
        stateLock.withLock {
            portForNsd = boundPort
            val urls =
                lanAddresses.map { addr ->
                    "http://${PhysicalLanAddressResolver.formatHostLiteral(addr)}:$boundPort"
                }
            val primaryUrl = urls.firstOrNull()
            val activeAndUsable = running && completedDrainState == null && !secretsConsumedByRejection
            val setupUrl = if (activeAndUsable && primaryUrl != null) "$primaryUrl$PATH_SETUP" else null
            val setupQrUrl =
                if (activeAndUsable && primaryUrl != null) {
                    "$primaryUrl$PATH_SETUP#t=${secrets.pairingToken}"
                } else {
                    null
                }
            val qrJson =
                if (activeAndUsable && primaryUrl != null && receiverAdvertisingEnabled) {
                    val payload =
                        CompanionTvPairingPayload(
                            version = 1,
                            receiver_url = "$primaryUrl$PATH_CONFIG",
                            pairing_token = secrets.pairingToken,
                            manual_code = null,
                            device_name = deviceName,
                            expires_at_epoch_ms = secrets.expiresAtEpochMs,
                        )
                    json.encodeToString(payload)
                } else {
                    null
                }
            snapshot =
                LanSetupServerSnapshot(
                    isRunning = running,
                    localUrls = urls,
                    boundPort = boundPort,
                    setupUrl = setupUrl,
                    setupQrUrl = setupQrUrl,
                    receiverPairingQrJson = qrJson,
                    formattedManualCode = if (activeAndUsable) secrets.formattedManualCode else null,
                    expiresAtEpochMs = secrets.expiresAtEpochMs,
                    manualCodeExpired = manualCodeExpired,
                    nsdWarning = nsdWarning,
                )
            offer =
                if (enableNsdAndOffers && receiverAdvertisingEnabled && activeAndUsable && !manualCodeExpired && primaryUrl != null) {
                    CompanionPairingOffers.PairingOffer(
                        deviceName = deviceName,
                        pairingToken = secrets.pairingToken,
                        manualCode = secrets.formattedManualCode,
                        receiverUrl = "$primaryUrl$PATH_CONFIG",
                        expiresAtEpochMs = secrets.expiresAtEpochMs,
                    )
                } else {
                    null
                }
            advertiseNsd = republishNsd && enableNsdAndOffers && receiverAdvertisingEnabled && activeAndUsable
        }
        _state.value = snapshot
        if (offer != null) {
            CompanionPairingOffers.offer(offer)
        } else if (enableNsdAndOffers) {
            CompanionPairingOffers.clear()
        }
        if (advertiseNsd) {
            registerNsd(portForNsd)
        }
    }

    private fun registerLanNetworkCallback() {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        val cb =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = refreshAddresses()

                override fun onLost(network: Network) = refreshAddresses()

                override fun onLinkPropertiesChanged(network: Network, linkProperties: android.net.LinkProperties) =
                    refreshAddresses()
            }
        networkCallback = cb
        runCatching {
            val request =
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build()
            cm.registerNetworkCallback(request, cb)
        }.onFailure {
            Timber.w(it, "LanSetupServer: failed to register LAN NetworkCallback")
        }
    }

    private fun unregisterLanNetworkCallback() {
        val cb = networkCallback ?: return
        networkCallback = null
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        runCatching { cm.unregisterNetworkCallback(cb) }
    }

    private fun registerNsd(port: Int) {
        unregisterNsd()
        val nsd = context.getSystemService(NsdManager::class.java) ?: return
        val listener =
            object : NsdManager.RegistrationListener {
                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Timber.w("LanSetupServer: NSD registration failed (%d)", errorCode)
                    stateLock.withLock { nsdWarning = OnboardingFailureCode.NSD_REGISTRATION_FAILED }
                    publishSnapshotAndOffers(republishNsd = false)
                }

                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit

                override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                    Timber.i("LanSetupServer: NSD registered %s on port %d", serviceInfo.serviceName, port)
                }

                override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            }
        nsdRegistrationListener = listener
        runCatching {
            val info =
                NsdServiceInfo().apply {
                    serviceName = "SpatialFin-$deviceName".take(60)
                    serviceType = NSD_SERVICE_TYPE
                    setPort(port)
                    setAttribute("v", "2")
                    setAttribute("class", deviceClass)
                }
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure {
            Timber.w(it, "LanSetupServer: NSD registerService threw")
            stateLock.withLock { nsdWarning = OnboardingFailureCode.NSD_REGISTRATION_FAILED }
            publishSnapshotAndOffers(republishNsd = false)
        }
    }

    private fun unregisterNsd() {
        val listener = nsdRegistrationListener ?: return
        nsdRegistrationListener = null
        val nsd = context.getSystemService(NsdManager::class.java) ?: return
        runCatching { nsd.unregisterService(listener) }
    }

    private sealed interface HeadReadResult {
        data class Ok(
            val method: String,
            val path: String,
            val queryParams: Map<String, String>,
            val headers: Map<String, String>,
        ) : HeadReadResult

        data class Error(
            val statusCode: Int,
            val errorCode: String,
            val message: String,
        ) : HeadReadResult
    }

    private fun readRequestHead(
        socket: Socket,
        input: BufferedInputStream,
        deadlineElapsedMs: Long,
    ): HeadReadResult {
        val buf = ByteArrayOutputStream(512)
        var prev3 = -1
        var prev2 = -1
        var prev1 = -1
        while (true) {
            val remaining = deadlineElapsedMs - elapsedRealtimeClock()
            if (remaining <= 0L) {
                return HeadReadResult.Error(408, "request_timeout", "Timed out reading request headers")
            }
            socket.soTimeout = remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(1)
            val b =
                try {
                    input.read()
                } catch (_: SocketTimeoutException) {
                    return HeadReadResult.Error(408, "request_timeout", "Timed out reading request headers")
                }
            if (b == -1) {
                return HeadReadResult.Error(400, "invalid_request", "Unexpected EOF reading headers")
            }
            buf.write(b)
            if (buf.size() > MAX_HEADER_BYTES) {
                return HeadReadResult.Error(400, "invalid_request", "Request headers exceed 8 KiB limit")
            }
            if (prev3 == '\r'.code && prev2 == '\n'.code && prev1 == '\r'.code && b == '\n'.code) {
                break
            }
            prev3 = prev2
            prev2 = prev1
            prev1 = b
        }

        val rawHead = buf.toString(Charsets.ISO_8859_1.name())
        val lines = rawHead.split("\r\n").dropLast(2)
        if (lines.isEmpty()) {
            return HeadReadResult.Error(400, "invalid_request", "Empty HTTP request")
        }
        val requestLineParts = lines[0].split(' ')
        if (requestLineParts.size != 3) {
            return HeadReadResult.Error(400, "invalid_request", "Malformed request line")
        }
        val method = requestLineParts[0].uppercase(Locale.US)
        val rawTarget = requestLineParts[1]
        val path = rawTarget.substringBefore('?')
        val queryString = rawTarget.substringAfter('?', "")
        val queryParams = parseQueryParams(queryString)
        val headerLines = lines.drop(1)
        if (headerLines.size > MAX_HEADER_COUNT) {
            return HeadReadResult.Error(400, "invalid_request", "Too many HTTP headers")
        }

        val headers = LinkedHashMap<String, String>()
        for (line in headerLines) {
            val colon = line.indexOf(':')
            if (colon <= 0) {
                return HeadReadResult.Error(400, "invalid_request", "Malformed HTTP header")
            }
            val name = line.substring(0, colon).trim().lowercase(Locale.US)
            val value = line.substring(colon + 1).trim()
            if (name in SINGLETON_HEADERS && headers.containsKey(name)) {
                return HeadReadResult.Error(400, "invalid_request", "Duplicate $name header")
            }
            headers[name] = value
        }
        return HeadReadResult.Ok(method = method, path = path, queryParams = queryParams, headers = headers)
    }

    private fun parseQueryParams(query: String): Map<String, String> {
        if (query.isEmpty()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val idx = pair.indexOf('=')
            val key = if (idx >= 0) pair.substring(0, idx) else pair
            val value = if (idx >= 0) pair.substring(idx + 1) else ""
            val decodedKey = runCatching { URLDecoder.decode(key, "UTF-8") }.getOrDefault(key)
            val decodedVal = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
            result[decodedKey] = decodedVal
        }
        return result
    }

    private sealed interface BodyReadResult {
        data class Ok(val bytes: ByteArray) : BodyReadResult

        data class Error(
            val statusCode: Int,
            val errorCode: String,
            val message: String,
        ) : BodyReadResult
    }

    private fun readBodyBytes(
        socket: Socket,
        input: BufferedInputStream,
        length: Int,
        deadlineElapsedMs: Long,
    ): BodyReadResult {
        val out = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val remaining = deadlineElapsedMs - elapsedRealtimeClock()
            if (remaining <= 0L) {
                return BodyReadResult.Error(408, "request_timeout", "Timed out reading request body")
            }
            socket.soTimeout = remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(1)
            val n =
                try {
                    input.read(out, offset, length - offset)
                } catch (_: SocketTimeoutException) {
                    return BodyReadResult.Error(408, "request_timeout", "Timed out reading request body")
                }
            if (n == -1) {
                return BodyReadResult.Error(400, "invalid_request", "Truncated request body")
            }
            offset += n
        }
        return BodyReadResult.Ok(out)
    }

    private fun writeJsonError(
        socket: Socket,
        statusCode: Int,
        errorCode: String,
        message: String,
        extraHeaders: Map<String, String> = emptyMap(),
    ) {
        writeJsonResponse(socket, statusCode, errorJson(errorCode, message), extraHeaders)
    }

    private fun errorJson(errorCode: String, message: String): String =
        json.encodeToString(LanSetupErrorResponse(error = message, errorCode = errorCode))

    private fun writeJsonResponse(
        socket: Socket,
        statusCode: Int,
        jsonBody: String,
        extraHeaders: Map<String, String> = emptyMap(),
    ) {
        val bodyBytes = jsonBody.toByteArray(Charsets.UTF_8)
        val reason = httpReasonPhrase(statusCode)
        val headers =
            buildString {
                append("HTTP/1.1 ").append(statusCode).append(' ').append(reason).append("\r\n")
                append("Content-Type: application/json; charset=utf-8\r\n")
                append("Content-Length: ").append(bodyBytes.size).append("\r\n")
                append("Cache-Control: no-store\r\n")
                append("Connection: close\r\n")
                extraHeaders.forEach { (k, v) ->
                    append(k).append(": ").append(v).append("\r\n")
                }
                append("\r\n")
            }
        runCatching {
            val out = socket.getOutputStream()
            out.write(headers.toByteArray(Charsets.ISO_8859_1))
            out.write(bodyBytes)
            out.flush()
        }
    }

    private fun closeSocketAbruptly(socket: Socket) {
        runCatching { socket.setSoLinger(true, 0) }
        runCatching { socket.close() }
    }

    companion object {
        const val DEFAULT_PORT: Int = 41230
        private const val NSD_SERVICE_TYPE: String = "_spatialfin-tv._tcp."
        const val PATH_SETUP: String = "/setup"
        private const val PATH_FAVICON: String = "/favicon.ico"
        const val PATH_INFO: String = "/api/v1/tv-pairing/info"
        const val PATH_CONFIG: String = "/api/v1/tv-pairing/config"
        const val PATH_SERVER: String = "/api/v1/device-setup/server"
        const val PATH_COMPANION_HANDOFF: String = "/api/v1/device-setup/companion-handoff"
        const val PATH_STATUS: String = "/api/v1/device-setup/status"
        const val PATH_CANCEL: String = "/api/v1/device-setup/cancel"

        private const val MAX_CONCURRENT_CONNECTIONS: Int = 8
        private const val MAX_CONCURRENT_PER_IP: Int = 3
        private const val SEND_BUFFER_BYTES: Int = 65_536
        private const val MAX_HEADER_BYTES: Int = 8 * 1024
        private const val MAX_HEADER_COUNT: Int = 32
        private const val MAX_V1_BODY_BYTES: Long = 256L * 1024L
        private const val MAX_V2_BODY_BYTES: Long = 64L * 1024L
        private const val HEADER_TIMEOUT_MS: Long = 3_000L
        private const val BODY_TIMEOUT_MS: Long = 1_500L
        private const val SERVER_SUBMIT_TIMEOUT_MS: Long = 12_000L
        private const val DEFAULT_V1_HANDOFF_TIMEOUT_MS: Long = 14_000L
        private const val DEFAULT_V2_HANDOFF_TIMEOUT_MS: Long = 16_000L
        private const val MAX_HANDOFF_TIMEOUT_MS: Long = 16_000L
        private const val IN_FLIGHT_LEASE_GRACE_MS: Long = 2_000L
        private const val GLOBAL_TYPED_CODE_PACE_MS: Long = 1_500L
        private const val ROTATION_MISMATCH_THRESHOLD: Int = 10
        private const val MAX_AUTO_ROTATIONS: Int = 5
        private const val MIN_ROTATION_INTERVAL_MS: Long = 15_000L
        private const val MAX_FAILURE_BUCKETS: Int = 256
        private const val MAX_STATUS_MISSES_PER_MIN: Int = 30
        private const val STATUS_MISS_WINDOW_MS: Long = 60_000L

        private val DIGITS_ONLY_REGEX = Regex("^\\d+$")
        private val POSITIVE_INT_REGEX = Regex("^[1-9]\\d*$")
        private val SESSION_ID_REGEX = Regex("^[0-9a-f]{32}$")
        private val SINGLETON_HEADERS =
            setOf("host", "content-length", "x-pairing-token", "content-type", "sec-fetch-site")

        internal fun loadDefaultSetupHtml(context: Context): String =
            runCatching {
                context.assets.open("setup/index.html").bufferedReader(Charsets.UTF_8).use { it.readText() }
            }.getOrElse {
                Timber.w(it, "LanSetupServer: failed to read setup/index.html asset")
                "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>SpatialFin Setup</title>" +
                    "<style nonce=\"__CSP_NONCE__\"></style></head><body><h1>SpatialFin Setup</h1>" +
                    "<script nonce=\"__CSP_NONCE__\"></script></body></html>"
            }

        internal fun isValidNumericHostHeader(
            rawHost: String,
            unwrappedLocalAddress: InetAddress,
            expectedPort: Int,
        ): Boolean {
            val trimmed = rawHost.trim()
            if (trimmed.isEmpty()) return false
            val hostLiteral: String
            val portStr: String
            if (trimmed.startsWith("[")) {
                val closeIdx = trimmed.indexOf(']')
                if (closeIdx <= 1) return false
                hostLiteral = trimmed.substring(1, closeIdx)
                if (hostLiteral.contains('%')) return false
                val rest = trimmed.substring(closeIdx + 1)
                if (!rest.startsWith(":")) return false
                portStr = rest.substring(1)
            } else {
                val colon = trimmed.indexOf(':')
                if (colon <= 0 || trimmed.indexOf(':', colon + 1) != -1) return false
                hostLiteral = trimmed.substring(0, colon)
                portStr = trimmed.substring(colon + 1)
            }
            if (!POSITIVE_INT_REGEX.matches(portStr)) return false
            val port = portStr.toIntOrNull() ?: return false
            if (port != expectedPort) return false

            val parsedAddr = parseNumericAddressOnly(hostLiteral) ?: return false
            val unwrappedParsed = PhysicalLanAddressResolver.unwrapIpv4InIpv6(parsedAddr)
            return unwrappedParsed.address.contentEquals(unwrappedLocalAddress.address)
        }

        private fun parseNumericAddressOnly(literal: String): InetAddress? {
            if (literal.isEmpty()) return null
            if (literal.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' && it != '.' && it != ':' }) {
                return null
            }
            if (literal.contains('.') && !literal.contains(':')) {
                return parseNumericAddressFallback(literal)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                return try {
                    if (!InetAddresses.isNumericAddress(literal)) return null
                    InetAddresses.parseNumericAddress(literal)
                } catch (_: Throwable) {
                    parseNumericAddressFallback(literal)
                }
            }
            return parseNumericAddressFallback(literal)
        }

        private fun parseNumericAddressFallback(literal: String): InetAddress? {
            if (literal.contains('.')) {
                val parts = literal.split('.')
                if (parts.size != 4) return null
                val bytes = ByteArray(4)
                for (i in 0 until 4) {
                    val p = parts[i]
                    if (p.isEmpty() || (p.length > 1 && p.startsWith("0"))) return null
                    val v = p.toIntOrNull() ?: return null
                    if (v !in 0..255) return null
                    bytes[i] = v.toByte()
                }
                return InetAddress.getByAddress(bytes)
            }
            if (literal.contains(':')) {
                if (literal.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' && it != ':' }) {
                    return null
                }
                return runCatching { InetAddress.getByName("[$literal]") }.getOrNull()
            }
            return null
        }

        internal fun peerBucketKey(address: InetAddress?): String {
            if (address == null) return "unknown"
            val unwrapped = PhysicalLanAddressResolver.unwrapIpv4InIpv6(address)
            return when (unwrapped) {
                is Inet4Address -> unwrapped.hostAddress.orEmpty()
                is Inet6Address -> {
                    val b = unwrapped.address
                    (0 until 8).joinToString(":") { "%02x".format(b[it]) }
                }
                else -> unwrapped.hostAddress.orEmpty()
            }
        }

        internal fun sha256(value: String): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))

        private fun httpReasonPhrase(statusCode: Int): String =
            when (statusCode) {
                200 -> "OK"
                204 -> "No Content"
                400 -> "Bad Request"
                401 -> "Unauthorized"
                403 -> "Forbidden"
                404 -> "Not Found"
                405 -> "Method Not Allowed"
                408 -> "Request Timeout"
                409 -> "Conflict"
                411 -> "Length Required"
                413 -> "Payload Too Large"
                415 -> "Unsupported Media Type"
                422 -> "Unprocessable Entity"
                429 -> "Too Many Requests"
                500 -> "Internal Server Error"
                503 -> "Service Unavailable"
                504 -> "Gateway Timeout"
                else -> "Error"
            }
    }
}
