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
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber

/**
 * Local-only app lock backed by [BiometricPrompt] with strong biometrics and
 * device credential (PIN / pattern / password) fallback. No backend, no stored
 * secrets: the OS-managed enrollment is the passkey. The prompt both enrolls
 * (first toggle-on) and unlocks (subsequent launches) — the enrollment step is
 * really "confirm the device owner is present right now" before flipping the
 * gate on.
 *
 * Real WebAuthn passkeys via CredentialManager need a Digital-Asset-Links-
 * verified rpId, which a local-only app cannot provide; BiometricPrompt is the
 * correct system surface for this use case.
 */
@Singleton
class AppLockManager @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val appPreferences: AppPreferences,
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

    private val _lockState = MutableStateFlow(computeInitialState())
    val lockState: StateFlow<LockState> = _lockState.asStateFlow()

    fun isEnabled(): Boolean = appPreferences.getValue(appPreferences.appLockEnabled)

    fun isConfigured(): Boolean = isEnabled()

    fun canEnable(): Boolean = BiometricManager.from(appContext)
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
        if (isEnabled()) _lockState.value = LockState.LOCKED
    }

    fun markUnlocked() {
        if (_lockState.value != LockState.DISABLED) _lockState.value = LockState.UNLOCKED
    }

    suspend fun enroll(activity: FragmentActivity): EnrollResult {
        if (!canEnable()) return EnrollResult.DeviceNotSecure
        return when (val result = runPrompt(
            activity = activity,
            title = activity.getString(dev.spatialfin.R.string.app_lock_setup_title),
            subtitle = activity.getString(dev.spatialfin.R.string.app_lock_setup_subtitle),
        )) {
            PromptResult.Success -> {
                appPreferences.setValue(appPreferences.appLockEnabled, true)
                _lockState.value = LockState.UNLOCKED
                EnrollResult.Success
            }
            PromptResult.Cancelled -> EnrollResult.Cancelled
            PromptResult.NoCredential -> EnrollResult.DeviceNotSecure
            is PromptResult.Failed -> EnrollResult.Failed(result.message)
        }
    }

    suspend fun authenticate(activity: FragmentActivity): AuthResult {
        return when (val result = runPrompt(
            activity = activity,
            title = activity.getString(dev.spatialfin.R.string.app_lock_unlock_title),
            subtitle = activity.getString(dev.spatialfin.R.string.app_lock_unlock_subtitle),
        )) {
            PromptResult.Success -> {
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

    fun disable() {
        appPreferences.setValue(appPreferences.appLockEnabled, false)
        appPreferences.setValue(appPreferences.appLockCredentialId, null)
        appPreferences.setValue(appPreferences.appLockUserHandle, null)
        _lockState.value = LockState.DISABLED
    }

    fun rollbackEnable() {
        appPreferences.setValue(appPreferences.appLockEnabled, false)
        _lockState.value = LockState.DISABLED
    }

    private sealed interface PromptResult {
        data object Success : PromptResult
        data object Cancelled : PromptResult
        data object NoCredential : PromptResult
        data class Failed(val message: String) : PromptResult
    }

    private suspend fun runPrompt(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
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
                if (cont.isActive) cont.resume(PromptResult.Success)
            }
        }

        val prompt = BiometricPrompt(activity, executor, callback)
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(AUTHENTICATORS)
            .build()

        try {
            prompt.authenticate(info)
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
