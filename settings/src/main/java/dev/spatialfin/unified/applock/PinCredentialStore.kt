package dev.spatialfin.unified.applock

import android.content.SharedPreferences
import android.util.Base64
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.TimeSource
import timber.log.Timber

/**
 * Local-only PIN credential used when the user picks PIN auth instead of
 * biometric. The PIN never leaves the device — we store PBKDF2-HMAC-SHA256 of
 * (pin || salt) along with a JSON blob describing the KDF parameters.
 */
@Singleton
class PinCredentialStore @Inject constructor(
    private val appPreferences: AppPreferences,
) {
    enum class SetResult { Success, TooShort, TooLong, NotDigits }

    sealed interface PrepareResult {
        data class Ready(val prepared: PreparedPin) : PrepareResult
        data class Invalid(val reason: SetResult) : PrepareResult
    }

    data class PreparedPin(
        val saltB64: String,
        val hashB64: String,
        val kdfParamsJson: String,
    )

    fun isConfigured(): Boolean =
        appPreferences.getValue(appPreferences.appLockPinHash) != null &&
            appPreferences.getValue(appPreferences.appLockPinSalt) != null

    fun preparePin(pin: String): PrepareResult {
        when (val v = validate(pin)) {
            SetResult.Success -> Unit
            else -> return PrepareResult.Invalid(v)
        }
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val params = KdfParams.current()
        val hash = derive(pin, salt, params)
        return PrepareResult.Ready(
            PreparedPin(
                saltB64 = Base64.encodeToString(salt, Base64.NO_WRAP),
                hashB64 = Base64.encodeToString(hash, Base64.NO_WRAP),
                kdfParamsJson = params.toJson(),
            )
        )
    }

    fun stagePendingPin(editor: SharedPreferences.Editor, prepared: PreparedPin) {
        editor.putString(appPreferences.appLockPendingPinSalt.backendName, prepared.saltB64)
        editor.putString(appPreferences.appLockPendingPinHash.backendName, prepared.hashB64)
        editor.putString(appPreferences.appLockPendingPinKdfParams.backendName, prepared.kdfParamsJson)
    }

    fun promotePendingPin(editor: SharedPreferences.Editor): Boolean {
        val saltB64 = appPreferences.getValue(appPreferences.appLockPendingPinSalt) ?: return false
        val hashB64 = appPreferences.getValue(appPreferences.appLockPendingPinHash) ?: return false
        val kdfParams = appPreferences.getValue(appPreferences.appLockPendingPinKdfParams)
        editor.putString(appPreferences.appLockPinSalt.backendName, saltB64)
        editor.putString(appPreferences.appLockPinHash.backendName, hashB64)
        editor.putString(appPreferences.appLockPinKdfParams.backendName, kdfParams)
        clearPendingPin(editor)
        return true
    }

    fun applyPreparedPin(editor: SharedPreferences.Editor, prepared: PreparedPin) {
        editor.putString(appPreferences.appLockPinSalt.backendName, prepared.saltB64)
        editor.putString(appPreferences.appLockPinHash.backendName, prepared.hashB64)
        editor.putString(appPreferences.appLockPinKdfParams.backendName, prepared.kdfParamsJson)
        clearPendingPin(editor)
    }

    fun clearPendingPin(editor: SharedPreferences.Editor) {
        editor.remove(appPreferences.appLockPendingPinHash.backendName)
        editor.remove(appPreferences.appLockPendingPinSalt.backendName)
        editor.remove(appPreferences.appLockPendingPinKdfParams.backendName)
    }

    fun clearInEditor(editor: SharedPreferences.Editor) {
        editor.remove(appPreferences.appLockPinHash.backendName)
        editor.remove(appPreferences.appLockPinSalt.backendName)
        editor.remove(appPreferences.appLockPinKdfParams.backendName)
        clearPendingPin(editor)
    }

    fun setPin(pin: String): SetResult {
        return when (val prepared = preparePin(pin)) {
            is PrepareResult.Invalid -> prepared.reason
            is PrepareResult.Ready -> {
                appPreferences.commitMainBatch { editor ->
                    applyPreparedPin(editor, prepared.prepared)
                }
                SetResult.Success
            }
        }
    }

    /** Constant-time verify against the stored hash. */
    fun verify(pin: String): Boolean {
        val saltB64 = appPreferences.getValue(appPreferences.appLockPinSalt) ?: return false
        val hashB64 = appPreferences.getValue(appPreferences.appLockPinHash) ?: return false
        val params = KdfParams.fromJsonOrDefault(
            appPreferences.getValue(appPreferences.appLockPinKdfParams)
        )
        val salt = runCatching { Base64.decode(saltB64, Base64.NO_WRAP) }.getOrNull() ?: return false
        val expected = runCatching { Base64.decode(hashB64, Base64.NO_WRAP) }.getOrNull() ?: return false
        val actual = derive(pin, salt, params)
        return constantTimeEquals(expected, actual)
    }

    fun clear() {
        appPreferences.commitMainBatch { editor ->
            clearInEditor(editor)
        }
    }

    private fun validate(pin: String): SetResult = when {
        pin.length < MIN_PIN_LEN -> SetResult.TooShort
        pin.length > MAX_PIN_LEN -> SetResult.TooLong
        !pin.all { it.isDigit() } -> SetResult.NotDigits
        else -> SetResult.Success
    }

    private fun derive(pin: String, salt: ByteArray, params: KdfParams): ByteArray {
        val start = TimeSource.Monotonic.markNow()
        val spec = PBEKeySpec(pin.toCharArray(), salt, params.iterations, params.hashBits)
        return try {
            val factory = SecretKeyFactory.getInstance(params.algorithm)
            factory.generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
            Timber.v("PinCredentialStore: derive took %s", start.elapsedNow().toIsoString())
        }
    }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    private data class KdfParams(
        val algorithm: String,
        val iterations: Int,
        val hashBits: Int,
    ) {
        fun toJson(): String =
            """{"algo":"$algorithm","iter":$iterations,"bits":$hashBits}"""

        companion object {
            fun current(): KdfParams = KdfParams(
                algorithm = "PBKDF2WithHmacSHA256",
                iterations = DEFAULT_ITERATIONS,
                hashBits = 256,
            )

            private val JSON_RE = Regex(
                """"algo"\s*:\s*"([^"]+)".*?"iter"\s*:\s*(\d+).*?"bits"\s*:\s*(\d+)""",
                RegexOption.DOT_MATCHES_ALL,
            )

            fun fromJsonOrDefault(json: String?): KdfParams {
                if (json.isNullOrBlank()) return current()
                val m = JSON_RE.find(json) ?: return current()
                return KdfParams(
                    algorithm = m.groupValues[1],
                    iterations = m.groupValues[2].toIntOrNull() ?: DEFAULT_ITERATIONS,
                    hashBits = m.groupValues[3].toIntOrNull() ?: 256,
                )
            }
        }
    }

    companion object {
        const val MIN_PIN_LEN = 4
        const val MAX_PIN_LEN = 8
        private const val SALT_BYTES = 16
        private const val DEFAULT_ITERATIONS = 600_000
    }
}
