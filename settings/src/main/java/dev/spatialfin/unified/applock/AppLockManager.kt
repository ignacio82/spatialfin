package dev.spatialfin.unified.applock

import android.content.Context
import android.security.keystore.KeyPermanentlyInvalidatedException
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
import dev.jdtech.jellyfin.settings.R
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import javax.crypto.Cipher
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Local-only app lock with two-phase digest-pinned atomic DEK enrollment and
 * crash-safe Biometric / PIN key-invalidation recovery.
 *
 * Main-process exclusive: mutating methods and cold-start reconciliation enforce
 * [AppPreferences.isMainProcess] so `:xrplayer` never mutates default preferences
 * or AndroidKeyStore KEKs.
 */
@Singleton
class AppLockManager @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val appPreferences: AppPreferences,
    private val pinStore: PinCredentialStore,
    private val failureCounter: AppLockFailureCounter,
    private val wiper: dagger.Lazy<AppDataWiper>,
    private val contentKeyManager: ContentKeyManager,
    private val encryptedDownloadsPurgeHook: dagger.Lazy<EncryptedDownloadsPurgeHook>,
    private val systemModalGraceHook: SystemModalGraceHook,
) : AppLockObserver {
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
        data object KeyInvalidated : AuthResult
        data class Failed(val message: String) : AuthResult
    }

    sealed interface PinAuthResult {
        data object Success : PinAuthResult
        data class Failed(val remaining: Int) : PinAuthResult
        data object KeyInvalidated : PinAuthResult
        data object Wiped : PinAuthResult
    }

    /** Configuration-change-surviving scope for enrollment, disable, and recovery commits. */
    val enrollmentScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _enrollmentVersion = MutableStateFlow(0L)
    override val enrollmentVersion: StateFlow<Long> = _enrollmentVersion.asStateFlow()

    private val _lockState = MutableStateFlow(computeInitialState())
    override val lockState: StateFlow<LockState> = _lockState.asStateFlow()

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

    /** Activity hook: reconcile any pending enrollment and evaluate state without unlocking. */
    fun refreshState() {
        reconcilePendingEnrollmentIfMainProcess()
        val on = isEnabled()
        val current = _lockState.value
        _lockState.value = when {
            !on -> LockState.DISABLED
            current == LockState.DISABLED -> LockState.LOCKED
            else -> current
        }
    }

    fun onAppBackgrounded() {
        if (!appPreferences.isMainProcess) return
        if (isEnabled()) {
            _lockState.value = LockState.LOCKED
            contentKeyManager.lock()
        }
    }

    fun markUnlocked() {
        check(appPreferences.isMainProcess) { "AppLockManager.markUnlocked() called outside main process" }
        if (_lockState.value != LockState.DISABLED) _lockState.value = LockState.UNLOCKED
    }

    // region Biometric enrollment / unlock

    suspend fun enroll(activity: FragmentActivity): EnrollResult {
        check(appPreferences.isMainProcess) { "AppLockManager.enroll() called outside main process" }
        if (!canUseBiometric()) return EnrollResult.DeviceNotSecure

        val wrapHandle =
            try {
                contentKeyManager.initBiometricWrapCipher(rotate = false)
            } catch (e: KeyPermanentlyInvalidatedException) {
                if (contentKeyManager.storedMode() != ContentKeyManager.StoredDekMode.BIOMETRIC) {
                    runCatching { contentKeyManager.initBiometricWrapCipher(rotate = true) }
                        .getOrElse { retryErr ->
                            Timber.w(retryErr, "AppLock: biometric wrap cipher retry failed")
                            return EnrollResult.Failed(
                                retryErr.message ?: activity.getString(R.string.app_lock_unlock_failed)
                            )
                        }
                } else {
                    Timber.w(e, "AppLock: biometric KEK invalidated during enrollment")
                    return EnrollResult.Failed(
                        activity.getString(R.string.app_lock_key_invalidated_title)
                    )
                }
            } catch (e: Exception) {
                Timber.w(e, "AppLock: initBiometricWrapCipher failed")
                return EnrollResult.Failed(
                    e.message ?: activity.getString(R.string.app_lock_unlock_failed)
                )
            }

        val result = runPrompt(
            activity = activity,
            title = activity.getString(R.string.app_lock_setup_title),
            subtitle = activity.getString(R.string.app_lock_setup_subtitle),
            crypto = BiometricPrompt.CryptoObject(wrapHandle.cipher),
        )
        return when (result) {
            is PromptResult.Success -> {
                val authedCipher = result.cipher ?: return EnrollResult.Failed(
                    activity.getString(R.string.app_lock_unlock_failed)
                )
                val authedHandle = ContentKeyManager.BiometricWrapHandle(authedCipher, wrapHandle.iv)
                val committed =
                    enrollmentScope.async(Dispatchers.IO + NonCancellable) {
                        commitBiometricEnrollment(authedHandle)
                    }.await()
                if (!committed) {
                    EnrollResult.Failed(activity.getString(R.string.app_lock_unlock_failed))
                } else {
                    EnrollResult.Success
                }
            }
            PromptResult.Cancelled -> EnrollResult.Cancelled
            PromptResult.NoCredential -> EnrollResult.DeviceNotSecure
            is PromptResult.Failed -> EnrollResult.Failed(result.message)
        }
    }

    private fun commitBiometricEnrollment(handle: ContentKeyManager.BiometricWrapHandle): Boolean {
        return appPreferences.withAppLockPolicyLock {
            val prepared = contentKeyManager.prepareRekeyToBiometric(handle) ?: return@withAppLockPolicyLock false
            try {
                val commit1 = appPreferences.commitMainBatch { editor ->
                    editor.putString(
                        appPreferences.appLockPendingMode.backendName,
                        AppLockMode.Biometric.backendKey,
                    )
                    editor.putString(
                        appPreferences.appLockPendingDekDigest.backendName,
                        prepared.sha256Digest,
                    )
                }
                if (!commit1) {
                    rollbackPendingMarkers()
                    return@withAppLockPolicyLock false
                }
                val dekCommitted = contentKeyManager.commitPreparedDek(prepared)
                if (!dekCommitted) {
                    rollbackPendingMarkers()
                    return@withAppLockPolicyLock false
                }
                val commit2 = appPreferences.commitMainBatch { editor ->
                    editor.putString(
                        appPreferences.appLockMode.backendName,
                        AppLockMode.Biometric.backendKey,
                    )
                    editor.putBoolean(appPreferences.appLockEnabled.backendName, true)
                    if (!appPreferences.contains(appPreferences.appLockWipeOnFail)) {
                        editor.putBoolean(appPreferences.appLockWipeOnFail.backendName, false)
                    }
                    if (!appPreferences.contains(appPreferences.appLockMaxAttempts)) {
                        editor.putInt(appPreferences.appLockMaxAttempts.backendName, MAX_PIN_MAX_ATTEMPTS)
                    }
                    editor.remove(appPreferences.appLockPendingMode.backendName)
                    editor.remove(appPreferences.appLockPendingDekDigest.backendName)
                    editor.putBoolean(appPreferences.appLockDekResetPending.backendName, false)
                    pinStore.clearInEditor(editor)
                }
                if (!commit2) return@withAppLockPolicyLock false
                contentKeyManager.deleteSupersededKeystoreKey(ContentKeyManager.Mode.Biometric)
                failureCounter.reset()
                _lockState.value = LockState.UNLOCKED
                _enrollmentVersion.value += 1L
                true
            } finally {
                prepared.wipe()
            }
        }
    }

    suspend fun authenticate(activity: FragmentActivity): AuthResult {
        check(appPreferences.isMainProcess) { "AppLockManager.authenticate() called outside main process" }
        if (appPreferences.getValue(appPreferences.appLockDekResetPending)) {
            return AuthResult.KeyInvalidated
        }
        return when (val stored = contentKeyManager.storedMode()) {
            ContentKeyManager.StoredDekMode.PIN -> AuthResult.KeyInvalidated
            ContentKeyManager.StoredDekMode.BIOMETRIC -> {
                when (val unwrap = contentKeyManager.initBiometricUnwrapCipher()) {
                    is ContentKeyManager.BiometricUnwrapResult.KeyInvalidated,
                    ContentKeyManager.BiometricUnwrapResult.NotBiometric -> AuthResult.KeyInvalidated
                    is ContentKeyManager.BiometricUnwrapResult.Ready -> {
                        val result = runPrompt(
                            activity = activity,
                            title = activity.getString(R.string.app_lock_unlock_title),
                            subtitle = activity.getString(R.string.app_lock_unlock_subtitle),
                            crypto = BiometricPrompt.CryptoObject(unwrap.cipher),
                        )
                        when (result) {
                            is PromptResult.Success -> {
                                val authedCipher = result.cipher ?: return AuthResult.KeyInvalidated
                                when (val unlock = contentKeyManager.unlockWithCipher(authedCipher)) {
                                    is ContentKeyManager.UnlockResult.Success -> {
                                        unlock.dek.fill(0)
                                        failureCounter.reset()
                                        markUnlocked()
                                        AuthResult.Success
                                    }
                                    is ContentKeyManager.UnlockResult.Failed -> {
                                        Timber.w("AppLock: DEK unwrap failed: %s", unlock.reason)
                                        AuthResult.KeyInvalidated
                                    }
                                }
                            }
                            PromptResult.Cancelled -> AuthResult.Cancelled
                            PromptResult.NoCredential -> AuthResult.Failed(
                                activity.getString(R.string.app_lock_no_credential)
                            )
                            is PromptResult.Failed -> AuthResult.Failed(result.message)
                        }
                    }
                }
            }
            ContentKeyManager.StoredDekMode.OFF,
            ContentKeyManager.StoredDekMode.NONE -> {
                val wrapHandle =
                    try {
                        contentKeyManager.initBiometricWrapCipher(rotate = false)
                    } catch (_: KeyPermanentlyInvalidatedException) {
                        runCatching { contentKeyManager.initBiometricWrapCipher(rotate = true) }
                            .getOrElse { err ->
                                Timber.w(err, "AppLock: biometric wrap init failed during unlock")
                                return if (stored == ContentKeyManager.StoredDekMode.OFF) {
                                    AuthResult.KeyInvalidated
                                } else {
                                    AuthResult.Failed(err.message ?: activity.getString(R.string.app_lock_unlock_failed))
                                }
                            }
                    } catch (e: Exception) {
                        Timber.w(e, "AppLock: biometric wrap init failed during unlock")
                        return if (stored == ContentKeyManager.StoredDekMode.OFF) {
                            AuthResult.KeyInvalidated
                        } else {
                            AuthResult.Failed(e.message ?: activity.getString(R.string.app_lock_unlock_failed))
                        }
                    }

                val result = runPrompt(
                    activity = activity,
                    title = activity.getString(R.string.app_lock_unlock_title),
                    subtitle = activity.getString(R.string.app_lock_unlock_subtitle),
                    crypto = BiometricPrompt.CryptoObject(wrapHandle.cipher),
                )
                when (result) {
                    is PromptResult.Success -> {
                        val authedCipher = result.cipher
                        if (authedCipher == null) {
                            return if (stored == ContentKeyManager.StoredDekMode.OFF) {
                                AuthResult.KeyInvalidated
                            } else {
                                AuthResult.Failed(activity.getString(R.string.app_lock_unlock_failed))
                            }
                        }
                        val rekeyed = withContext(Dispatchers.IO + NonCancellable) {
                            contentKeyManager.rekeyToBiometric(
                                ContentKeyManager.BiometricWrapHandle(authedCipher, wrapHandle.iv)
                            )
                        }
                        if (!rekeyed) {
                            return if (stored == ContentKeyManager.StoredDekMode.OFF) {
                                AuthResult.KeyInvalidated
                            } else {
                                AuthResult.Failed(activity.getString(R.string.app_lock_unlock_failed))
                            }
                        }
                        contentKeyManager.deleteSupersededKeystoreKey(ContentKeyManager.Mode.Biometric)
                        failureCounter.reset()
                        markUnlocked()
                        AuthResult.Success
                    }
                    PromptResult.Cancelled -> AuthResult.Cancelled
                    PromptResult.NoCredential -> AuthResult.Failed(
                        activity.getString(R.string.app_lock_no_credential)
                    )
                    is PromptResult.Failed -> AuthResult.Failed(result.message)
                }
            }
        }
    }

    /**
     * Crash-safe crypto-bound Biometric key-invalidation recovery:
     * persists `appLockDekResetPending = true` before rotating `KEK_ALIAS_AUTH`, authenticates
     * the fresh wrap cipher via BiometricPrompt, then purges encrypted downloads and resets DEK
     * inside `enrollmentScope` with `Dispatchers.IO + NonCancellable`.
     */
    suspend fun purgeEncryptedDownloadsAndResetDek(activity: FragmentActivity): AuthResult {
        check(appPreferences.isMainProcess) { "AppLockManager.purgeEncryptedDownloadsAndResetDek() called outside main process" }
        withContext(Dispatchers.IO + NonCancellable) {
            appPreferences.commitMainBatch { editor ->
                editor.putBoolean(appPreferences.appLockDekResetPending.backendName, true)
            }
        }
        val wrapHandle =
            runCatching { contentKeyManager.initBiometricWrapCipher(rotate = true) }
                .getOrElse { err ->
                    Timber.e(err, "AppLock: failed rotating biometric KEK for recovery")
                    return AuthResult.Failed(err.message ?: activity.getString(R.string.app_lock_unlock_failed))
                }
        val result = runPrompt(
            activity = activity,
            title = activity.getString(R.string.app_lock_key_invalidated_title),
            subtitle = activity.getString(R.string.app_lock_unlock_subtitle),
            crypto = BiometricPrompt.CryptoObject(wrapHandle.cipher),
        )
        return when (result) {
            is PromptResult.Success -> {
                val authedCipher = result.cipher ?: return AuthResult.Failed(
                    activity.getString(R.string.app_lock_unlock_failed)
                )
                val authedHandle = ContentKeyManager.BiometricWrapHandle(authedCipher, wrapHandle.iv)
                val ok =
                    enrollmentScope.async(Dispatchers.IO + NonCancellable) {
                        encryptedDownloadsPurgeHook.get().purgeEncryptedDownloads()
                        val resetOk = contentKeyManager.resetToBiometric(authedHandle)
                        if (!resetOk) return@async false
                        contentKeyManager.deleteSupersededKeystoreKey(ContentKeyManager.Mode.Biometric)
                        appPreferences.commitMainBatch { editor ->
                            editor.putBoolean(appPreferences.appLockDekResetPending.backendName, false)
                        }
                        failureCounter.reset()
                        markUnlocked()
                        true
                    }.await()
                if (ok) AuthResult.Success else AuthResult.Failed(activity.getString(R.string.app_lock_unlock_failed))
            }
            PromptResult.Cancelled -> AuthResult.Cancelled
            PromptResult.NoCredential -> AuthResult.Failed(activity.getString(R.string.app_lock_no_credential))
            is PromptResult.Failed -> AuthResult.Failed(result.message)
        }
    }

    // endregion

    // region PIN setup / unlock / recovery

    suspend fun setPin(newPin: String): Boolean {
        check(appPreferences.isMainProcess) { "AppLockManager.setPin() called outside main process" }
        return enrollmentScope.async(Dispatchers.IO + NonCancellable) {
            val preparedPin =
                when (val prep = pinStore.preparePin(newPin)) {
                    is PinCredentialStore.PrepareResult.Ready -> prep.prepared
                    is PinCredentialStore.PrepareResult.Invalid -> return@async false
                }
            appPreferences.withAppLockPolicyLock {
                val preparedDek = contentKeyManager.prepareRekeyToPin(newPin) ?: return@withAppLockPolicyLock false
                try {
                    val commit1 = appPreferences.commitMainBatch { editor ->
                        editor.putString(
                            appPreferences.appLockPendingMode.backendName,
                            AppLockMode.Pin.backendKey,
                        )
                        editor.putString(
                            appPreferences.appLockPendingDekDigest.backendName,
                            preparedDek.sha256Digest,
                        )
                        pinStore.stagePendingPin(editor, preparedPin)
                    }
                    if (!commit1) {
                        rollbackPendingMarkers()
                        return@withAppLockPolicyLock false
                    }
                    val dekCommitted = contentKeyManager.commitPreparedDek(preparedDek)
                    if (!dekCommitted) {
                        rollbackPendingMarkers()
                        return@withAppLockPolicyLock false
                    }
                    val commit2 = appPreferences.commitMainBatch { editor ->
                        editor.putString(
                            appPreferences.appLockMode.backendName,
                            AppLockMode.Pin.backendKey,
                        )
                        editor.putBoolean(appPreferences.appLockEnabled.backendName, true)
                        if (!appPreferences.contains(appPreferences.appLockWipeOnFail)) {
                            editor.putBoolean(appPreferences.appLockWipeOnFail.backendName, false)
                        }
                        if (!appPreferences.contains(appPreferences.appLockMaxAttempts)) {
                            editor.putInt(appPreferences.appLockMaxAttempts.backendName, MAX_PIN_MAX_ATTEMPTS)
                        }
                        editor.remove(appPreferences.appLockPendingMode.backendName)
                        editor.remove(appPreferences.appLockPendingDekDigest.backendName)
                        editor.putBoolean(appPreferences.appLockDekResetPending.backendName, false)
                        pinStore.applyPreparedPin(editor, preparedPin)
                    }
                    if (!commit2) return@withAppLockPolicyLock false
                    contentKeyManager.deleteSupersededKeystoreKey(ContentKeyManager.Mode.Pin(newPin))
                    failureCounter.reset()
                    _lockState.value = LockState.UNLOCKED
                    _enrollmentVersion.value += 1L
                    true
                } finally {
                    preparedDek.wipe()
                }
            }
        }.await()
    }

    suspend fun verifyPin(candidate: String): PinAuthResult = withContext(Dispatchers.Default) {
        check(appPreferences.isMainProcess) { "AppLockManager.verifyPin() called outside main process" }
        val ok = pinStore.verify(candidate)
        if (!ok) {
            val attempts = failureCounter.bump()
            val max = appPreferences.getValue(appPreferences.appLockMaxAttempts)
                .coerceIn(1, MAX_PIN_MAX_ATTEMPTS)
            val wipePolicy = appPreferences.getValue(appPreferences.appLockWipeOnFail)
            return@withContext if (wipePolicy && attempts >= max) {
                Timber.w("AppLock: PIN failure %d >= %d, wiping", attempts, max)
                wiper.get().wipeEverything()
                PinAuthResult.Wiped
            } else {
                PinAuthResult.Failed(remaining = (max - attempts).coerceAtLeast(0))
            }
        }

        if (appPreferences.getValue(appPreferences.appLockDekResetPending)) {
            return@withContext PinAuthResult.KeyInvalidated
        }

        when (contentKeyManager.storedMode()) {
            ContentKeyManager.StoredDekMode.PIN -> {
                when (val u = contentKeyManager.unlockWithPin(candidate)) {
                    is ContentKeyManager.UnlockResult.Success -> {
                        u.dek.fill(0)
                        failureCounter.reset()
                        markUnlocked()
                        PinAuthResult.Success
                    }
                    is ContentKeyManager.UnlockResult.Failed -> {
                        Timber.w("AppLock: PIN verified but DEK unlock failed: %s", u.reason)
                        PinAuthResult.KeyInvalidated
                    }
                }
            }
            ContentKeyManager.StoredDekMode.OFF,
            ContentKeyManager.StoredDekMode.NONE -> {
                val rekeyed = withContext(Dispatchers.IO + NonCancellable) {
                    contentKeyManager.rekeyToNonBiometric(ContentKeyManager.Mode.Pin(candidate))
                }
                if (rekeyed) {
                    contentKeyManager.deleteSupersededKeystoreKey(ContentKeyManager.Mode.Pin(candidate))
                    failureCounter.reset()
                    markUnlocked()
                    PinAuthResult.Success
                } else {
                    PinAuthResult.KeyInvalidated
                }
            }
            ContentKeyManager.StoredDekMode.BIOMETRIC -> PinAuthResult.KeyInvalidated
        }
    }

    suspend fun purgeEncryptedDownloadsAndResetPinDek(verifiedPin: String): Boolean {
        check(appPreferences.isMainProcess) { "AppLockManager.purgeEncryptedDownloadsAndResetPinDek() called outside main process" }
        return enrollmentScope.async(Dispatchers.IO + NonCancellable) {
            appPreferences.commitMainBatch { editor ->
                editor.putBoolean(appPreferences.appLockDekResetPending.backendName, true)
            }
            if (!pinStore.verify(verifiedPin)) return@async false
            encryptedDownloadsPurgeHook.get().purgeEncryptedDownloads()
            val resetOk = contentKeyManager.resetToPin(verifiedPin)
            if (!resetOk) return@async false
            contentKeyManager.deleteSupersededKeystoreKey(ContentKeyManager.Mode.Pin(verifiedPin))
            appPreferences.commitMainBatch { editor ->
                editor.putBoolean(appPreferences.appLockDekResetPending.backendName, false)
            }
            failureCounter.reset()
            markUnlocked()
            true
        }.await()
    }

    /** User-initiated wipe from the "forgot PIN" flow. */
    suspend fun wipeNow() {
        check(appPreferences.isMainProcess) { "AppLockManager.wipeNow() called outside main process" }
        Timber.w("AppLock: user-initiated wipe")
        wiper.get().wipeEverything()
    }

    // endregion

    suspend fun disable(): Boolean {
        check(appPreferences.isMainProcess) { "AppLockManager.disable() called outside main process" }
        return enrollmentScope.async(Dispatchers.IO + NonCancellable) {
            appPreferences.withAppLockPolicyLock {
                val stored = contentKeyManager.storedMode()
                if (contentKeyManager.hasStoredDek() && stored != ContentKeyManager.StoredDekMode.OFF) {
                    val preparedDek = contentKeyManager.prepareRekeyToOff() ?: run {
                        Timber.w("AppLock: disable() could not prepare Off DEK — keeping lock on")
                        return@withAppLockPolicyLock false
                    }
                    try {
                        val commit1 = appPreferences.commitMainBatch { editor ->
                            editor.putString(
                                appPreferences.appLockPendingMode.backendName,
                                AppLockMode.Off.backendKey,
                            )
                            editor.putString(
                                appPreferences.appLockPendingDekDigest.backendName,
                                preparedDek.sha256Digest,
                            )
                        }
                        if (!commit1) {
                            rollbackPendingMarkers()
                            return@withAppLockPolicyLock false
                        }
                        val dekCommitted = contentKeyManager.commitPreparedDek(preparedDek)
                        if (!dekCommitted) {
                            rollbackPendingMarkers()
                            return@withAppLockPolicyLock false
                        }
                    } finally {
                        preparedDek.wipe()
                    }
                }
                val commit2 = appPreferences.commitMainBatch { editor ->
                    editor.putString(
                        appPreferences.appLockMode.backendName,
                        AppLockMode.Off.backendKey,
                    )
                    editor.putBoolean(appPreferences.appLockEnabled.backendName, false)
                    editor.remove(appPreferences.appLockCredentialId.backendName)
                    editor.remove(appPreferences.appLockUserHandle.backendName)
                    editor.remove(appPreferences.appLockPendingMode.backendName)
                    editor.remove(appPreferences.appLockPendingDekDigest.backendName)
                    editor.putBoolean(appPreferences.appLockDekResetPending.backendName, false)
                    pinStore.clearInEditor(editor)
                }
                if (!commit2) return@withAppLockPolicyLock false
                contentKeyManager.deleteSupersededKeystoreKey(ContentKeyManager.Mode.Off)
                failureCounter.reset()
                _lockState.value = LockState.DISABLED
                _enrollmentVersion.value += 1L
                true
            }
        }.await()
    }

    fun failureCount(): Int = failureCounter.get()
    fun maxAttempts(): Int =
        appPreferences.getValue(appPreferences.appLockMaxAttempts)
            .coerceIn(1, MAX_PIN_MAX_ATTEMPTS)
    fun wipeOnFailEnabled(): Boolean =
        appPreferences.getValue(appPreferences.appLockWipeOnFail)

    private fun rollbackPendingMarkers() {
        appPreferences.commitMainBatch { editor ->
            editor.remove(appPreferences.appLockPendingMode.backendName)
            editor.remove(appPreferences.appLockPendingDekDigest.backendName)
            pinStore.clearPendingPin(editor)
        }
    }

    private fun reconcilePendingEnrollmentIfMainProcess() {
        if (!appPreferences.isMainProcess) return
        appPreferences.withAppLockPolicyLock {
            val pendingMode = appPreferences.getValue(appPreferences.appLockPendingMode) ?: return@withAppLockPolicyLock
            val pendingDigest = appPreferences.getValue(appPreferences.appLockPendingDekDigest)
            val actualDigest = contentKeyManager.storedDekSha256Digest()

            val digestMatches = !pendingDigest.isNullOrBlank() && actualDigest == pendingDigest
            when {
                digestMatches && pendingMode == AppLockMode.Biometric.backendKey &&
                    contentKeyManager.hasValidBiometricKey() -> {
                    val promoted = appPreferences.commitMainBatch { editor ->
                        editor.putString(
                            appPreferences.appLockMode.backendName,
                            AppLockMode.Biometric.backendKey,
                        )
                        editor.putBoolean(appPreferences.appLockEnabled.backendName, true)
                        if (!appPreferences.contains(appPreferences.appLockWipeOnFail)) {
                            editor.putBoolean(appPreferences.appLockWipeOnFail.backendName, false)
                        }
                        if (!appPreferences.contains(appPreferences.appLockMaxAttempts)) {
                            editor.putInt(appPreferences.appLockMaxAttempts.backendName, MAX_PIN_MAX_ATTEMPTS)
                        }
                        editor.remove(appPreferences.appLockPendingMode.backendName)
                        editor.remove(appPreferences.appLockPendingDekDigest.backendName)
                        pinStore.clearInEditor(editor)
                    }
                    if (promoted) {
                        failureCounter.reset()
                        contentKeyManager.deleteSupersededKeystoreKey(ContentKeyManager.Mode.Biometric)
                        _enrollmentVersion.value += 1L
                    }
                }
                digestMatches && pendingMode == AppLockMode.Pin.backendKey &&
                    appPreferences.getValue(appPreferences.appLockPendingPinHash) != null &&
                    appPreferences.getValue(appPreferences.appLockPendingPinSalt) != null -> {
                    val promoted = appPreferences.commitMainBatch { editor ->
                        if (pinStore.promotePendingPin(editor)) {
                            editor.putString(
                                appPreferences.appLockMode.backendName,
                                AppLockMode.Pin.backendKey,
                            )
                            editor.putBoolean(appPreferences.appLockEnabled.backendName, true)
                            if (!appPreferences.contains(appPreferences.appLockWipeOnFail)) {
                                editor.putBoolean(appPreferences.appLockWipeOnFail.backendName, false)
                            }
                            if (!appPreferences.contains(appPreferences.appLockMaxAttempts)) {
                                editor.putInt(appPreferences.appLockMaxAttempts.backendName, MAX_PIN_MAX_ATTEMPTS)
                            }
                            editor.remove(appPreferences.appLockPendingMode.backendName)
                            editor.remove(appPreferences.appLockPendingDekDigest.backendName)
                        }
                    }
                    if (promoted) {
                        failureCounter.reset()
                        contentKeyManager.deleteSupersededKeystoreKey(ContentKeyManager.Mode.Pin(""))
                        _enrollmentVersion.value += 1L
                    }
                }
                else -> {
                    // Fail closed on digest mismatch, invalid KEK/PIN, or pending "off".
                    rollbackPendingMarkers()
                }
            }
        }
    }

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
    ): PromptResult {
        val trackGrace = _lockState.value != LockState.LOCKED
        if (trackGrace) {
            systemModalGraceHook.setSystemModalActive(true)
        }
        return try {
            suspendCancellableCoroutine { cont ->
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
                cont.invokeOnCancellation {
                    executor.execute { prompt.cancelAuthentication() }
                }
                val info = BiometricPrompt.PromptInfo.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setAllowedAuthenticators(AUTHENTICATORS)
                    .build()

                try {
                    if (crypto != null) prompt.authenticate(info, crypto) else prompt.authenticate(info)
                } catch (e: Exception) {
                    Timber.w(e, "AppLock: failed to launch biometric prompt")
                    if (cont.isActive) cont.resume(PromptResult.Failed(e.message ?: "Unknown error"))
                }
            }
        } finally {
            if (trackGrace) {
                systemModalGraceHook.setSystemModalActive(false)
            }
        }
    }

    private fun computeInitialState(): LockState {
        reconcilePendingEnrollmentIfMainProcess()
        return if (isEnabled()) LockState.LOCKED else LockState.DISABLED
    }

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
