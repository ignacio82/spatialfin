package dev.spatialfin.unified.applock

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.jdtech.jellyfin.security.ContentKeyManager
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import javax.crypto.Cipher
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Local-only app lock. Two authentication backends:
 *  - [AppLockMode.Biometric] — BiometricPrompt with strong biometrics and
 *    device credential (OS PIN/pattern/password) fallback. The OS is the
 *    source of truth for enrollment; we just track that the user opted in.
 *  - [AppLockMode.Pin] — a SpatialFin-managed numeric PIN via
 *    [PinCredentialStore], with configurable wipe-on-failure policy so a
 *    forgotten PIN can destroy local data rather than leaving it recoverable.
 *
 * No backend, no synced secrets. Real WebAuthn passkeys via CredentialManager
 * would need a Digital-Asset-Links-verified rpId, which a local-only app
 * cannot provide; BiometricPrompt is the correct system surface here.
 */
@Singleton
class AppLockManager @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val appPreferences: AppPreferences,
    private val pinStore: PinCredentialStore,
    private val failureCounter: AppLockFailureCounter,
    private val wiper: AppDataWiper,
    private val contentKeyManager: ContentKeyManager,
) {
    enum class LockState { DISABLED, LOCKED, UNLOCKED }

    sealed interface EnrollResult {
        data object Success : EnrollResult
        data object DeviceNotSecure : EnrollResult
        data object Cancelled : EnrollResult
        data class Failed(val message: String) : EnrollResult
    }

    sealed interface AuthResult {
        data object Success : AuthResult
        data object Cancelled : AuthResult
        data class Failed(val message: String) : AuthResult
    }

    sealed interface PinAuthResult {
        data object Success : PinAuthResult
        data class Failed(val remaining: Int) : PinAuthResult
        data object Wiped : PinAuthResult
    }

    private val _lockState = MutableStateFlow(computeInitialState())
    val lockState: StateFlow<LockState> = _lockState.asStateFlow()

    fun mode(): AppLockMode =
        AppLockMode.fromKey(appPreferences.getValue(appPreferences.appLockMode))

    fun isEnabled(): Boolean = mode() != AppLockMode.Off

    fun isConfigured(): Boolean = when (mode()) {
        AppLockMode.Off -> false
        AppLockMode.Biometric -> appPreferences.getValue(appPreferences.appLockEnabled)
        AppLockMode.Pin -> pinStore.isConfigured()
    }

    fun canUseBiometric(): Boolean = BiometricManager.from(appContext)
        .canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

    /** Activity hook: re-evaluate the pref on resume without changing UNLOCKED state. */
    fun refreshState() {
        val on = isEnabled()
        val current = _lockState.value
        _lockState.value = when {
            !on -> LockState.DISABLED
            current == LockState.DISABLED -> LockState.LOCKED
            else -> current
        }
    }

    fun onAppBackgrounded() {
        if (isEnabled()) {
            _lockState.value = LockState.LOCKED
            // Zero the cached DEK so an attacker who gains code-exec after
            // backgrounding can't pull plaintext from process memory.
            contentKeyManager.lock()
        }
    }

    fun markUnlocked() {
        if (_lockState.value != LockState.DISABLED) _lockState.value = LockState.UNLOCKED
    }

    // region Biometric enrollment / unlock
    suspend fun enroll(activity: FragmentActivity): EnrollResult {
        if (!canUseBiometric()) return EnrollResult.DeviceNotSecure
        // If content encryption is on, transition the DEK wrap to Biometric.
        // We need the user to authenticate the ENCRYPT step via a
        // CryptoObject-bound BiometricPrompt; otherwise the unwrapping side
        // would fail on first playback.
        val wrapHandle: ContentKeyManager.BiometricWrapHandle? =
            if (appPreferences.getValue(appPreferences.contentEncryptionEnabled) &&
                contentKeyManager.hasStoredDek()
            ) {
                runCatching { contentKeyManager.initBiometricWrapCipher() }
                    .onFailure { Timber.w(it, "AppLock: initBiometricWrapCipher failed") }
                    .getOrNull()
            } else null

        val crypto = wrapHandle?.let { BiometricPrompt.CryptoObject(it.cipher) }
        val result = runPrompt(
            activity = activity,
            title = activity.getString(dev.spatialfin.R.string.app_lock_setup_title),
            subtitle = activity.getString(dev.spatialfin.R.string.app_lock_setup_subtitle),
            crypto = crypto,
        )
        return when (result) {
            is PromptResult.Success -> {
                if (wrapHandle != null) {
                    val rekeyed = contentKeyManager.rekeyToBiometric(wrapHandle)
                    if (!rekeyed) {
                        return EnrollResult.Failed(
                            activity.getString(dev.spatialfin.R.string.app_lock_unlock_failed)
                        )
                    }
                }
                appPreferences.setValue(appPreferences.appLockMode, AppLockMode.Biometric.backendKey)
                appPreferences.setValue(appPreferences.appLockEnabled, true)
                pinStore.clear()
                failureCounter.reset()
                _lockState.value = LockState.UNLOCKED
                EnrollResult.Success
            }
            PromptResult.Cancelled -> EnrollResult.Cancelled
            PromptResult.NoCredential -> EnrollResult.DeviceNotSecure
            is PromptResult.Failed -> EnrollResult.Failed(result.message)
        }
    }

    suspend fun authenticate(activity: FragmentActivity): AuthResult {
        val crypto = if (appPreferences.getValue(appPreferences.contentEncryptionEnabled)) {
            runCatching { contentKeyManager.initBiometricUnwrapCipher() }
                .getOrNull()
                ?.let { BiometricPrompt.CryptoObject(it) }
        } else null
        val result = runPrompt(
            activity = activity,
            title = activity.getString(dev.spatialfin.R.string.app_lock_unlock_title),
            subtitle = activity.getString(dev.spatialfin.R.string.app_lock_unlock_subtitle),
            crypto = crypto,
        )
        return when (result) {
            is PromptResult.Success -> {
                // If we issued a CryptoObject, the returned cipher is auth-valid.
                // Use it once to unwrap the DEK so encrypted downloads play.
                val authedCipher = result.cipher
                if (authedCipher != null) {
                    when (val unlock = contentKeyManager.unlockWithCipher(authedCipher)) {
                        is ContentKeyManager.UnlockResult.Success -> Unit
                        is ContentKeyManager.UnlockResult.Failed -> {
                            Timber.w("AppLock: DEK unwrap failed: %s", unlock.reason)
                        }
                    }
                }
                failureCounter.reset()
                markUnlocked()
                AuthResult.Success
            }
            PromptResult.Cancelled -> AuthResult.Cancelled
            PromptResult.NoCredential -> AuthResult.Failed(
                activity.getString(dev.spatialfin.R.string.app_lock_no_credential)
            )
            is PromptResult.Failed -> AuthResult.Failed(result.message)
        }
    }
    // endregion

    // region PIN setup / unlock
    /** Store a new PIN and flip the mode on. Returns false if the PIN is invalid. */
    suspend fun setPin(newPin: String): Boolean = withContext(Dispatchers.Default) {
        when (pinStore.setPin(newPin)) {
            PinCredentialStore.SetResult.Success -> {
                // If content encryption is on, transition the DEK wrap to PIN.
                // This is fast (PBKDF2 is O(100ms)) and non-interactive — no
                // BiometricPrompt needed.
                if (appPreferences.getValue(appPreferences.contentEncryptionEnabled) &&
                    contentKeyManager.hasStoredDek()
                ) {
                    val ok = contentKeyManager.rekeyToNonBiometric(
                        ContentKeyManager.Mode.Pin(newPin)
                    )
                    if (!ok) {
                        Timber.w("AppLock: setPin rekey to PIN mode failed; keeping prior wrap")
                    }
                }
                appPreferences.setValue(appPreferences.appLockMode, AppLockMode.Pin.backendKey)
                appPreferences.setValue(appPreferences.appLockEnabled, true)
                failureCounter.reset()
                _lockState.value = LockState.UNLOCKED
                true
            }
            else -> false
        }
    }

    /**
     * Verify a PIN. On success: reset counter and unlock.
     * On failure: bump counter, then either return remaining attempts or wipe.
     * Runs the KDF off the main thread — caller can safely invoke from Compose.
     */
    suspend fun verifyPin(candidate: String): PinAuthResult = withContext(Dispatchers.Default) {
        val ok = pinStore.verify(candidate)
        if (ok) {
            // Unlock the DEK so encrypted downloads become playable.
            if (appPreferences.getValue(appPreferences.contentEncryptionEnabled) &&
                contentKeyManager.hasStoredDek()
            ) {
                when (val u = contentKeyManager.unlockWithPin(candidate)) {
                    is ContentKeyManager.UnlockResult.Success -> Unit
                    is ContentKeyManager.UnlockResult.Failed -> {
                        Timber.w("AppLock: PIN verified but DEK unlock failed: %s", u.reason)
                    }
                }
            }
            failureCounter.reset()
            markUnlocked()
            return@withContext PinAuthResult.Success
        }
        val attempts = failureCounter.bump()
        val max = appPreferences.getValue(appPreferences.appLockMaxAttempts)
            .coerceIn(1, MAX_PIN_MAX_ATTEMPTS)
        val wipePolicy = appPreferences.getValue(appPreferences.appLockWipeOnFail)
        if (wipePolicy && attempts >= max) {
            Timber.w("AppLock: PIN failure %d >= %d, wiping", attempts, max)
            wiper.wipeEverything()
            PinAuthResult.Wiped
        } else {
            PinAuthResult.Failed(remaining = (max - attempts).coerceAtLeast(0))
        }
    }

    /** User-initiated wipe from the "forgot PIN" flow. */
    suspend fun wipeNow() {
        Timber.w("AppLock: user-initiated wipe")
        wiper.wipeEverything()
    }
    // endregion

    fun disable() {
        // If content encryption is on, we need an unlocked DEK to rekey back
        // to Off mode. For Biometric mode that's only the case if the user
        // unlocked this session. For Pin mode the user must have verified
        // their PIN. If neither holds, we leave encryption enabled and the
        // caller's UI should instruct the user to unlock first.
        if (appPreferences.getValue(appPreferences.contentEncryptionEnabled) &&
            contentKeyManager.hasStoredDek()
        ) {
            val rekeyed = contentKeyManager.rekeyToNonBiometric(ContentKeyManager.Mode.Off)
            if (!rekeyed) {
                Timber.w("AppLock: disable() could not rekey DEK to Off — keeping lock on")
                return
            }
        }
        appPreferences.setValue(appPreferences.appLockMode, AppLockMode.Off.backendKey)
        appPreferences.setValue(appPreferences.appLockEnabled, false)
        appPreferences.setValue(appPreferences.appLockCredentialId, null)
        appPreferences.setValue(appPreferences.appLockUserHandle, null)
        pinStore.clear()
        failureCounter.reset()
        _lockState.value = LockState.DISABLED
    }

    fun rollbackEnable() {
        appPreferences.setValue(appPreferences.appLockMode, AppLockMode.Off.backendKey)
        appPreferences.setValue(appPreferences.appLockEnabled, false)
        _lockState.value = LockState.DISABLED
    }

    fun failureCount(): Int = failureCounter.get()
    fun maxAttempts(): Int =
        appPreferences.getValue(appPreferences.appLockMaxAttempts)
            .coerceIn(1, MAX_PIN_MAX_ATTEMPTS)
    fun wipeOnFailEnabled(): Boolean =
        appPreferences.getValue(appPreferences.appLockWipeOnFail)

    private sealed interface PromptResult {
        data class Success(val cipher: Cipher?) : PromptResult
        data object Cancelled : PromptResult
        data object NoCredential : PromptResult
        data class Failed(val message: String) : PromptResult
    }

    private suspend fun runPrompt(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        crypto: BiometricPrompt.CryptoObject? = null,
    ): PromptResult = suspendCancellableCoroutine { cont ->
        val executor = ContextCompat.getMainExecutor(activity)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (!cont.isActive) return
                val result = when (errorCode) {
                    BiometricPrompt.ERROR_USER_CANCELED,
                    BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                    BiometricPrompt.ERROR_CANCELED -> PromptResult.Cancelled
                    BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL,
                    BiometricPrompt.ERROR_NO_BIOMETRICS -> PromptResult.NoCredential
                    else -> {
                        Timber.w("AppLock: biometric error %d: %s", errorCode, errString)
                        PromptResult.Failed(errString.toString())
                    }
                }
                cont.resume(result)
            }

            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                if (cont.isActive) cont.resume(PromptResult.Success(result.cryptoObject?.cipher))
            }
        }

        val prompt = BiometricPrompt(activity, executor, callback)
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(
                if (crypto != null) {
                    // Keystore keys tied to BIOMETRIC_STRONG reject DEVICE_CREDENTIAL-
                    // only unlocks. Restrict to biometrics when a CryptoObject is
                    // involved so init/doFinal succeed.
                    BiometricManager.Authenticators.BIOMETRIC_STRONG
                } else {
                    AUTHENTICATORS
                }
            )
            .apply { if (crypto != null) setNegativeButtonText("Cancel") }
            .build()

        try {
            if (crypto != null) prompt.authenticate(info, crypto) else prompt.authenticate(info)
        } catch (e: Exception) {
            Timber.w(e, "AppLock: failed to launch biometric prompt")
            if (cont.isActive) cont.resume(PromptResult.Failed(e.message ?: "Unknown error"))
        }
    }

    private fun computeInitialState(): LockState =
        if (isEnabled()) LockState.LOCKED else LockState.DISABLED

    companion object {
        private const val AUTHENTICATORS =
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL

        const val MAX_PIN_MAX_ATTEMPTS = 3

        fun from(context: Context): AppLockManager =
            EntryPointAccessors.fromApplication(
                context.applicationContext,
                AppLockManagerEntryPoint::class.java,
            ).appLockManager()
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface AppLockManagerEntryPoint {
    fun appLockManager(): AppLockManager
}
