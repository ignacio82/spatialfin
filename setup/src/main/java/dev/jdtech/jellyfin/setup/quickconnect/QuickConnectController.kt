package dev.jdtech.jellyfin.setup.quickconnect

import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle
import dev.jdtech.jellyfin.onboarding.OnboardingFailureCode
import dev.jdtech.jellyfin.onboarding.OnboardingFailureException
import dev.jdtech.jellyfin.onboarding.toOnboardingFailureCode
import dev.jdtech.jellyfin.quickconnect.PollResult
import dev.jdtech.jellyfin.quickconnect.QuickConnectClient
import dev.jdtech.jellyfin.quickconnect.QuickConnectInitiated
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Stateful Quick Connect lifecycle controller supporting:
 * - 240 s per-code window, 40 polls/window, 0..2 auto-rotations (<= 120 total polls)
 * - Hashed [SavedStateHandle] restoration across process death and secret scrubbing on exit
 * - `pauseOnStop()` / `resumeOnStart()` keeping in-flight `exchangeJob` uncancelled
 * - Sticky atomic `isCancelled` so `cancel()` before `startWithInitiated(...)` permanently blocks polling
 */
class QuickConnectController internal constructor(
    private val quickConnectClient: QuickConnectClient,
    private val savedStateHandle: SavedStateHandle?,
    private val scope: CoroutineScope,
    keyPrefix: String?,
    private val elapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime,
    private val clockEpochMs: () -> Long = System::currentTimeMillis,
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
) {
    private val hashedPrefix: String = keyPrefix?.let { sha256Hex(it) } ?: "default"
    private val keySecret get() = "qc_${hashedPrefix}_secret"
    private val keyCode get() = "qc_${hashedPrefix}_code"
    private val keyRotation get() = "qc_${hashedPrefix}_rotation"
    private val keyStartedAtEpochMs get() = "qc_${hashedPrefix}_started_at"

    private val _state = MutableStateFlow<QuickConnectState>(QuickConnectState.Idle)
    val state: StateFlow<QuickConnectState> = _state.asStateFlow()

    private val isCancelled = AtomicBoolean(false)
    private var paused = false
    private var currentServerAddress: String? = null
    private var startedAtElapsedMs: Long = 0L
    private var pollsInWindow: Int = 0
    private var totalPolls: Int = 0
    private var consecutiveFailures: Int = 0
    private var pollJob: Job? = null
    private var exchangeJob: Job? = null
    private var previousSecret: String? = null
    private var previousSecretGraceUntilElapsedMs: Long = 0L

    fun start(serverAddress: String) {
        if (isCancelled.get()) return
        val normalized = serverAddress.trim().trimEnd('/')
        if (currentServerAddress != null && currentServerAddress != normalized) {
            scrubSavedState()
        }
        currentServerAddress = normalized
        paused = false
        previousSecret = null
        previousSecretGraceUntilElapsedMs = 0L

        // Attempt SavedStateHandle restore for the same target
        val restoredSecret = savedStateHandle?.get<String>(keySecret)?.takeIf { it.isNotBlank() }
        val restoredCode = savedStateHandle?.get<String>(keyCode)?.takeIf { it.isNotBlank() }
        val restoredRotation = savedStateHandle?.get<Int>(keyRotation) ?: 0
        val restoredEpochMs = savedStateHandle?.get<Long>(keyStartedAtEpochMs) ?: 0L

        if (restoredSecret != null && restoredCode != null && restoredEpochMs > 0L) {
            val elapsedWall = (clockEpochMs() - restoredEpochMs).coerceAtLeast(0L)
            if (elapsedWall < WINDOW_DURATION_MS && restoredRotation <= MAX_ROTATIONS) {
                startedAtElapsedMs = elapsedRealtimeMs() - elapsedWall
                pollsInWindow = 0
                consecutiveFailures = 0
                _state.value = QuickConnectState.Active(
                    code = restoredCode,
                    secret = restoredSecret,
                    rotation = restoredRotation,
                    startedAtEpochMs = restoredEpochMs,
                    rotatedNotice = restoredRotation > 0,
                )
                launchPollLoop(normalized)
                return
            } else {
                scrubSavedState()
            }
        }

        initiateFresh(normalized, rotation = 0, rotatedNotice = false)
    }

    fun startWithInitiated(serverAddress: String, initial: QuickConnectInitiated) {
        if (isCancelled.get()) return
        val normalized = serverAddress.trim().trimEnd('/')
        currentServerAddress = normalized
        paused = false
        previousSecret = null
        previousSecretGraceUntilElapsedMs = 0L
        val nowEpoch = clockEpochMs()
        startedAtElapsedMs = elapsedRealtimeMs()
        pollsInWindow = 0
        totalPolls = 0
        consecutiveFailures = 0
        persistSavedState(initial.secret, initial.code, 0, nowEpoch)
        _state.value = QuickConnectState.Active(
            code = initial.code,
            secret = initial.secret,
            rotation = 0,
            startedAtEpochMs = nowEpoch,
            rotatedNotice = false,
        )
        launchPollLoop(normalized)
    }

    fun resumeOnStart() {
        if (isCancelled.get()) return
        if (!paused) return
        paused = false
        val addr = currentServerAddress ?: return
        if (exchangeJob?.isActive == true) return
        when (_state.value) {
            is QuickConnectState.Checking -> initiateFresh(addr, rotation = 0, rotatedNotice = false)
            is QuickConnectState.Active -> launchPollLoop(addr)
            else -> Unit
        }
    }

    fun pauseOnStop() {
        paused = true
        pollJob?.cancel()
        pollJob = null
        // Intentionally leave exchangeJob running so a mid-exchange auth completes cleanly.
    }

    fun cancel() {
        isCancelled.set(true)
        paused = true
        previousSecret = null
        previousSecretGraceUntilElapsedMs = 0L
        pollJob?.cancel()
        pollJob = null
        exchangeJob?.cancel()
        exchangeJob = null
        scrubSavedState()
        _state.value = QuickConnectState.Idle
    }

    fun retry(serverAddress: String) {
        if (isCancelled.get()) return
        val normalized = serverAddress.trim().trimEnd('/')
        currentServerAddress = normalized
        paused = false
        previousSecret = null
        previousSecretGraceUntilElapsedMs = 0L
        pollJob?.cancel()
        pollJob = null
        exchangeJob?.cancel()
        exchangeJob = null
        scrubSavedState()
        totalPolls = 0
        pollsInWindow = 0
        consecutiveFailures = 0
        initiateFresh(normalized, rotation = 0, rotatedNotice = false)
    }

    private fun initiateFresh(serverAddress: String, rotation: Int, rotatedNotice: Boolean) {
        pollJob?.cancel()
        _state.value = QuickConnectState.Checking
        pollJob =
            scope.launch {
                try {
                    if (isCancelled.get() || paused) return@launch
                    val enabled = quickConnectClient.checkEnabled(serverAddress)
                    if (isCancelled.get() || paused) return@launch
                    if (!enabled) {
                        scrubSavedState()
                        _state.value = QuickConnectState.DisabledOnServer
                        return@launch
                    }
                    val initiated = quickConnectClient.initiate(serverAddress)
                    if (isCancelled.get() || paused) return@launch
                    val nowEpoch = clockEpochMs()
                    startedAtElapsedMs = elapsedRealtimeMs()
                    pollsInWindow = 0
                    consecutiveFailures = 0
                    persistSavedState(initiated.secret, initiated.code, rotation, nowEpoch)
                    _state.value = QuickConnectState.Active(
                        code = initiated.code,
                        secret = initiated.secret,
                        rotation = rotation,
                        startedAtEpochMs = nowEpoch,
                        rotatedNotice = rotatedNotice,
                    )
                    runPollLoop(serverAddress)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: OnboardingFailureException) {
                    if (isCancelled.get()) return@launch
                    scrubSavedState()
                    _state.value =
                        if (e.code == OnboardingFailureCode.QC_DISABLED_ON_SERVER) {
                            QuickConnectState.DisabledOnServer
                        } else {
                            QuickConnectState.Failed(e.code)
                        }
                } catch (e: Throwable) {
                    if (isCancelled.get()) return@launch
                    scrubSavedState()
                    _state.value = QuickConnectState.Failed(e.toOnboardingFailureCode())
                }
            }
    }

    private fun launchPollLoop(serverAddress: String) {
        pollJob?.cancel()
        pollJob = scope.launch { runPollLoop(serverAddress) }
    }

    private suspend fun runPollLoop(serverAddress: String) {
        while (!isCancelled.get() && !paused) {
            val active = _state.value as? QuickConnectState.Active ?: return
            val elapsedInWindow = elapsedRealtimeMs() - startedAtElapsedMs
            if (elapsedInWindow >= WINDOW_DURATION_MS ||
                pollsInWindow >= MAX_POLLS_PER_WINDOW ||
                totalPolls >= MAX_TOTAL_POLLS
            ) {
                if (active.rotation < MAX_ROTATIONS && totalPolls < MAX_TOTAL_POLLS) {
                    rotateCode(serverAddress, nextRotation = active.rotation + 1, carryPreviousSecret = active.secret)
                    return
                }
                scrubSavedState()
                _state.value = QuickConnectState.Expired
                return
            }

            val backoffMs =
                if (consecutiveFailures > 0) {
                    val multiplier = 1L shl consecutiveFailures.coerceAtMost(3)
                    (pollIntervalMs * multiplier).coerceAtMost(MAX_BACKOFF_MS)
                } else {
                    pollIntervalMs
                }
            delay(backoffMs)
            if (isCancelled.get() || paused) return

            pollsInWindow++
            totalPolls++
            when (val poll = quickConnectClient.pollOnce(serverAddress, active.secret)) {
                PollResult.Pending -> {
                    consecutiveFailures = 0
                    val prev = previousSecret
                    if (prev != null) {
                        if (elapsedRealtimeMs() >= previousSecretGraceUntilElapsedMs) {
                            previousSecret = null
                            previousSecretGraceUntilElapsedMs = 0L
                        } else {
                            when (quickConnectClient.pollOnce(serverAddress, prev)) {
                                PollResult.Authenticated -> {
                                    previousSecret = null
                                    previousSecretGraceUntilElapsedMs = 0L
                                    launchExchange(serverAddress, prev)
                                    return
                                }
                                PollResult.NotFound -> {
                                    previousSecret = null
                                    previousSecretGraceUntilElapsedMs = 0L
                                }
                                PollResult.DisabledOnServer -> {
                                    previousSecret = null
                                    previousSecretGraceUntilElapsedMs = 0L
                                    scrubSavedState()
                                    _state.value = QuickConnectState.DisabledOnServer
                                    return
                                }
                                PollResult.Pending,
                                is PollResult.TransientFailure,
                                -> Unit
                            }
                        }
                    }
                }
                PollResult.Authenticated -> {
                    consecutiveFailures = 0
                    launchExchange(serverAddress, active.secret)
                    return
                }
                PollResult.DisabledOnServer -> {
                    scrubSavedState()
                    _state.value = QuickConnectState.DisabledOnServer
                    return
                }
                PollResult.NotFound -> {
                    if (active.rotation < MAX_ROTATIONS && totalPolls < MAX_TOTAL_POLLS) {
                        rotateCode(serverAddress, nextRotation = active.rotation + 1, carryPreviousSecret = null)
                        return
                    }
                    scrubSavedState()
                    _state.value = QuickConnectState.Expired
                    return
                }
                is PollResult.TransientFailure -> {
                    consecutiveFailures++
                    if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                        scrubSavedState()
                        _state.value = QuickConnectState.Failed(poll.code)
                        return
                    }
                }
            }
        }
    }

    private suspend fun rotateCode(
        serverAddress: String,
        nextRotation: Int,
        carryPreviousSecret: String? = null,
    ) {
        try {
            val initiated = quickConnectClient.initiate(serverAddress)
            if (isCancelled.get() || paused) return
            val nowEpoch = clockEpochMs()
            startedAtElapsedMs = elapsedRealtimeMs()
            pollsInWindow = 0
            consecutiveFailures = 0
            previousSecret = carryPreviousSecret
            previousSecretGraceUntilElapsedMs =
                if (carryPreviousSecret != null) elapsedRealtimeMs() + PREVIOUS_SECRET_GRACE_MS else 0L
            persistSavedState(initiated.secret, initiated.code, nextRotation, nowEpoch)
            _state.value = QuickConnectState.Active(
                code = initiated.code,
                secret = initiated.secret,
                rotation = nextRotation,
                startedAtEpochMs = nowEpoch,
                rotatedNotice = true,
            )
            runPollLoop(serverAddress)
        } catch (e: CancellationException) {
            throw e
        } catch (e: OnboardingFailureException) {
            if (isCancelled.get()) return
            scrubSavedState()
            _state.value =
                if (e.code == OnboardingFailureCode.QC_DISABLED_ON_SERVER) {
                    QuickConnectState.DisabledOnServer
                } else {
                    QuickConnectState.Failed(e.code)
                }
        } catch (e: Throwable) {
            if (isCancelled.get()) return
            scrubSavedState()
            _state.value = QuickConnectState.Failed(e.toOnboardingFailureCode())
        }
    }

    private fun launchExchange(serverAddress: String, secret: String) {
        exchangeJob?.cancel()
        exchangeJob =
            scope.launch {
                try {
                    val result = quickConnectClient.authenticate(serverAddress, secret)
                    if (isCancelled.get()) return@launch
                    scrubSavedState()
                    _state.value = QuickConnectState.Authenticated(result)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: OnboardingFailureException) {
                    if (isCancelled.get()) return@launch
                    scrubSavedState()
                    _state.value = QuickConnectState.Failed(e.code)
                } catch (e: Throwable) {
                    if (isCancelled.get()) return@launch
                    scrubSavedState()
                    _state.value = QuickConnectState.Failed(e.toOnboardingFailureCode())
                }
            }
    }

    private fun persistSavedState(secret: String, code: String, rotation: Int, startedAtEpochMs: Long) {
        savedStateHandle?.let { handle ->
            handle[keySecret] = secret
            handle[keyCode] = code
            handle[keyRotation] = rotation
            handle[keyStartedAtEpochMs] = startedAtEpochMs
        }
    }

    private fun scrubSavedState() {
        savedStateHandle?.let { handle ->
            handle.remove<String>(keySecret)
            handle.remove<String>(keyCode)
            handle.remove<Int>(keyRotation)
            handle.remove<Long>(keyStartedAtEpochMs)
        }
    }

    @Singleton
    class Factory internal constructor(
        private val quickConnectClient: QuickConnectClient,
        private val elapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime,
        private val clockEpochMs: () -> Long = System::currentTimeMillis,
        private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
    ) {
        @Inject
        constructor(quickConnectClient: QuickConnectClient) : this(
            quickConnectClient = quickConnectClient,
            elapsedRealtimeMs = SystemClock::elapsedRealtime,
            clockEpochMs = System::currentTimeMillis,
            pollIntervalMs = DEFAULT_POLL_INTERVAL_MS,
        )

        fun create(
            savedStateHandle: SavedStateHandle?,
            scope: CoroutineScope,
            keyPrefix: String?,
        ): QuickConnectController =
            QuickConnectController(
                quickConnectClient = quickConnectClient,
                savedStateHandle = savedStateHandle,
                scope = scope,
                keyPrefix = keyPrefix,
                elapsedRealtimeMs = elapsedRealtimeMs,
                clockEpochMs = clockEpochMs,
                pollIntervalMs = pollIntervalMs,
            )
    }

    companion object {
        internal const val WINDOW_DURATION_MS: Long = 240_000L
        internal const val PREVIOUS_SECRET_GRACE_MS: Long = 60_000L
        internal const val DEFAULT_POLL_INTERVAL_MS: Long = 6_000L
        internal const val MAX_POLLS_PER_WINDOW: Int = 40
        internal const val MAX_ROTATIONS: Int = 2
        internal const val MAX_TOTAL_POLLS: Int = 120
        internal const val MAX_CONSECUTIVE_FAILURES: Int = 5
        internal const val MAX_BACKOFF_MS: Long = 30_000L

        internal fun sha256Hex(input: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }.take(16)
        }

        fun formatGroupedCode(raw: String): String {
            val digits = raw.filter(Char::isDigit)
            return if (digits.length == 6) "${digits.substring(0, 3)} ${digits.substring(3)}" else raw.trim()
        }

        fun formatSpacedForAccessibility(raw: String): String =
            raw.filter { it.isLetterOrDigit() }.toCharArray().joinToString(" ")
    }
}
