package dev.jdtech.jellyfin.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import timber.log.Timber

/**
 * Manages a 32-byte device-local Data Encryption Key (DEK) that AES-CTR-
 * encrypts downloaded content.
 *
 * The DEK itself is cached in process memory as a [ByteArray] after the user
 * unlocks the app; it is zeroed on [lock] / backgrounding. Persistence on
 * disk is always in *wrapped* form, with the wrapping strategy chosen by
 * [Mode]:
 *
 * - [Mode.Off]        : Keystore AES-256-GCM, `setUserAuthenticationRequired(false)`.
 *                       Any process with our app's UID can unwrap.
 * - [Mode.Biometric]  : Keystore AES-256-GCM, `setUserAuthenticationRequired(true)`.
 *                       Unwrap requires a [Cipher] obtained from BiometricPrompt's
 *                       CryptoObject after a successful auth — nothing to do with
 *                       the OS PIN; the biometric/device-credential dialog gates it.
 * - [Mode.Pin(pin)]   : KEK derived from the SpatialFin PIN via PBKDF2-HMAC-SHA256.
 *                       Keystore is not involved, so the PIN is the only path to
 *                       the DEK. Also implies wipe-on-wrong-PIN policy (enforced
 *                       elsewhere) — a forgotten PIN ⇒ DEK unrecoverable.
 *
 * The on-disk file layout is:
 *   byte 0     : mode tag (see [MODE_TAG_OFF] / [MODE_TAG_BIOMETRIC] / [MODE_TAG_PIN])
 *   Off/Bio    : [12-byte GCM IV][32 + 16 bytes GCM ciphertext+tag]
 *   Pin        : [16-byte salt][12-byte GCM IV][32 + 16 bytes GCM ciphertext+tag]
 */
class ContentKeyManager(
    private val appContext: Context,
) {
    sealed class Mode {
        data object Off : Mode()
        data object Biometric : Mode()
        data class Pin(val pin: String) : Mode()
    }

    sealed interface UnlockFailure {
        data object NoMaterial : UnlockFailure
        data object WrongPin : UnlockFailure
        data object AuthRequired : UnlockFailure
        data class Crypto(val cause: Throwable) : UnlockFailure
    }

    sealed interface UnlockResult {
        data class Success(val dek: ByteArray) : UnlockResult
        data class Failed(val reason: UnlockFailure) : UnlockResult
    }

    @Volatile private var cachedDek: ByteArray? = null

    private val dekFile: File
        get() = File(appContext.filesDir, DEK_FILENAME)

    /** True if a wrapped DEK exists on disk regardless of mode. */
    fun hasStoredDek(): Boolean = dekFile.exists()

    /** Plaintext DEK if unlocked or in Off mode, otherwise null. */
    @Synchronized
    fun getDekOrNull(): ByteArray? {
        cachedDek?.let { return it }
        // Off mode: no user action required, we can always unwrap.
        if (currentStoredMode() == MODE_TAG_OFF) {
            return runCatching { unwrapOff().also { cachedDek = it } }.getOrNull()
        }
        return null
    }

    /**
     * Bootstrapping convenience: in Off mode, behaves like Phase 2a. In
     * Biometric/Pin mode, only returns a DEK if the user has already
     * unlocked this session — otherwise null (caller must skip encryption).
     * Never blocks or prompts.
     */
    @Synchronized
    fun getOrCreateDek(): ByteArray {
        cachedDek?.let { return it }
        val storedMode = currentStoredMode()
        if (storedMode == null) {
            // Fresh install — create Off-mode DEK. Mode transitions later re-wrap.
            val dek = randomDek()
            writeOffWrap(dek)
            cachedDek = dek
            Timber.i("ContentKeyManager: created new DEK in Off mode")
            return dek
        }
        if (storedMode == MODE_TAG_OFF) {
            return unwrapOff().also { cachedDek = it }
        }
        // Mode is Biometric/Pin but nothing unlocked. The caller shouldn't
        // encrypt in this case; throw so callers that used getOrCreateDek
        // during Phase 2 notice. ResumableDownloadWorker now uses getDekOrNull.
        throw IllegalStateException(
            "Content key is locked (mode tag=$storedMode); unlock required before use",
        )
    }

    /** Unlock using a BiometricPrompt-issued Cipher; caches and returns the DEK. */
    @Synchronized
    fun unlockWithCipher(cipher: Cipher): UnlockResult {
        val blob = readBiometricWrapOrNull()
            ?: return UnlockResult.Failed(UnlockFailure.NoMaterial)
        return try {
            val dek = cipher.doFinal(blob.ciphertextWithTag)
            cachedDek = dek
            UnlockResult.Success(dek)
        } catch (e: Exception) {
            Timber.w(e, "ContentKeyManager: biometric unlock doFinal failed")
            UnlockResult.Failed(UnlockFailure.Crypto(e))
        }
    }

    /** Unlock using the SpatialFin PIN; returns WrongPin on AEAD failure. */
    @Synchronized
    fun unlockWithPin(pin: String): UnlockResult {
        val blob = readPinWrapOrNull()
            ?: return UnlockResult.Failed(UnlockFailure.NoMaterial)
        val kek = deriveKekFromPin(pin, blob.salt)
        val cipher = Cipher.getInstance(GCM_TRANSFORMATION)
        return try {
            cipher.init(Cipher.DECRYPT_MODE, kek, GCMParameterSpec(GCM_TAG_BITS, blob.iv))
            val dek = cipher.doFinal(blob.ciphertextWithTag)
            cachedDek = dek
            UnlockResult.Success(dek)
        } catch (e: javax.crypto.AEADBadTagException) {
            UnlockResult.Failed(UnlockFailure.WrongPin)
        } catch (e: Exception) {
            Timber.w(e, "ContentKeyManager: PIN unlock failed")
            UnlockResult.Failed(UnlockFailure.Crypto(e))
        }
    }

    /** Zero the cached plaintext DEK. Called on background / logout. */
    @Synchronized
    fun lock() {
        cachedDek?.fill(0)
        cachedDek = null
    }

    /**
     * Build a [Cipher] suitable for wrapping in a BiometricPrompt.CryptoObject
     * for the DECRYPT step. Call [unlockWithCipher] after a successful auth.
     * Returns null when no biometric-wrapped DEK is on disk (i.e. the user
     * hasn't entered Biometric mode on this device yet).
     */
    @Synchronized
    fun initBiometricUnwrapCipher(): Cipher? {
        val blob = readBiometricWrapOrNull() ?: return null
        val key = getKeystoreKey(authRequired = true) ?: return null
        val cipher = Cipher.getInstance(GCM_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, blob.iv))
        return cipher
    }

    /**
     * Build a [Cipher] to wrap in BiometricPrompt.CryptoObject for the ENCRYPT
     * step of transitioning into Biometric mode. The caller authenticates,
     * then passes the same cipher back to [rekeyToBiometricWithCipher].
     *
     * Generating the Keystore key itself does not prompt — only using it
     * (doFinal) does — so this method can safely be invoked before any UI.
     */
    @Synchronized
    fun initBiometricWrapCipher(): BiometricWrapHandle {
        ensureKeystoreKey(authRequired = true, rotate = true)
        val key = getKeystoreKey(authRequired = true)
            ?: error("Biometric Keystore key missing after generation")
        val iv = randomBytes(GCM_IV_BYTES)
        val cipher = Cipher.getInstance(GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return BiometricWrapHandle(cipher = cipher, iv = iv)
    }

    data class BiometricWrapHandle(val cipher: Cipher, val iv: ByteArray)

    /**
     * Transition the on-disk wrapping to Off or Pin. For Biometric, use
     * [initBiometricWrapCipher] + [rekeyToBiometric] which handles the
     * BiometricPrompt-authenticated ENCRYPT-step correctly.
     */
    @Synchronized
    fun rekeyToNonBiometric(newMode: Mode): Boolean {
        require(newMode !is Mode.Biometric) { "Use rekeyToBiometric for Biometric mode" }
        val dek = ensureUnlockedDek() ?: run {
            Timber.w("ContentKeyManager: rekey refused — DEK not unlocked")
            return false
        }
        return runCatching {
            when (newMode) {
                Mode.Off -> writeOffWrap(dek)
                is Mode.Pin -> writePinWrap(dek, newMode.pin)
                Mode.Biometric -> error("unreachable")
            }
            cachedDek = dek
            Timber.i("ContentKeyManager: rekeyed to %s", newMode.javaClass.simpleName)
        }.onFailure { Timber.e(it, "ContentKeyManager: rekey to %s failed", newMode) }
            .isSuccess
    }

    /**
     * Complete the Off/Pin → Biometric transition using a cipher that was
     * authenticated via BiometricPrompt. [handle] must be the exact same
     * handle returned by [initBiometricWrapCipher] and passed as the
     * BiometricPrompt.CryptoObject.
     */
    @Synchronized
    fun rekeyToBiometric(handle: BiometricWrapHandle): Boolean {
        val dek = ensureUnlockedDek() ?: run {
            Timber.w("ContentKeyManager: rekey(Biometric) refused — DEK not unlocked")
            return false
        }
        return runCatching {
            val ct = handle.cipher.doFinal(dek)
            atomicWrite(byteArrayOf(MODE_TAG_BIOMETRIC) + handle.iv + ct)
            // Biometric mode must not leave a non-auth-gated KEK lying around.
            deleteAlias(KEK_ALIAS_OFF)
            cachedDek = dek
            Timber.i("ContentKeyManager: rekeyed to Biometric")
        }.onFailure { Timber.e(it, "ContentKeyManager: rekeyToBiometric failed") }
            .isSuccess
    }

    private fun ensureUnlockedDek(): ByteArray? {
        cachedDek?.let { return it }
        if (currentStoredMode() == MODE_TAG_OFF) {
            return runCatching { unwrapOff() }.getOrNull().also { if (it != null) cachedDek = it }
        }
        return null
    }

    // region Wrap/unwrap helpers

    private data class WrappedBlob(val iv: ByteArray, val ciphertextWithTag: ByteArray, val salt: ByteArray = EMPTY_BYTES, val modeTag: Byte = MODE_TAG_OFF)

    private fun currentStoredMode(): Byte? {
        val f = dekFile
        if (!f.exists()) return null
        return runCatching { f.inputStream().use { it.read().toByte() } }.getOrNull()
    }

    private fun unwrapOff(): ByteArray {
        val blob = readPrefixedBlob(expectedMode = MODE_TAG_OFF)
            ?: throw IllegalStateException("No Off-mode wrapped DEK on disk")
        val key = getKeystoreKey(authRequired = false)
            ?: throw IllegalStateException("Off-mode Keystore key unavailable")
        val cipher = Cipher.getInstance(GCM_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, blob.iv))
        return cipher.doFinal(blob.ciphertextWithTag)
    }

    private fun readBiometricWrapOrNull(): WrappedBlob? {
        return readPrefixedBlob(expectedMode = MODE_TAG_BIOMETRIC)
    }

    private fun readPinWrapOrNull(): WrappedBlob? {
        return readPrefixedBlob(expectedMode = MODE_TAG_PIN)
    }

    private fun readPrefixedBlob(expectedMode: Byte): WrappedBlob? {
        val f = dekFile
        if (!f.exists()) return null
        val bytes = f.readBytes()
        if (bytes.isEmpty() || bytes[0] != expectedMode) return null
        return when (expectedMode) {
            MODE_TAG_OFF, MODE_TAG_BIOMETRIC -> {
                if (bytes.size < 1 + GCM_IV_BYTES + DEK_BYTES + GCM_TAG_BYTES) return null
                val iv = bytes.copyOfRange(1, 1 + GCM_IV_BYTES)
                val ct = bytes.copyOfRange(1 + GCM_IV_BYTES, bytes.size)
                WrappedBlob(iv = iv, ciphertextWithTag = ct, modeTag = expectedMode)
            }
            MODE_TAG_PIN -> {
                val headerSize = 1 + PBKDF2_SALT_BYTES + GCM_IV_BYTES
                if (bytes.size < headerSize + DEK_BYTES + GCM_TAG_BYTES) return null
                val salt = bytes.copyOfRange(1, 1 + PBKDF2_SALT_BYTES)
                val iv = bytes.copyOfRange(1 + PBKDF2_SALT_BYTES, headerSize)
                val ct = bytes.copyOfRange(headerSize, bytes.size)
                WrappedBlob(iv = iv, ciphertextWithTag = ct, salt = salt, modeTag = expectedMode)
            }
            else -> null
        }
    }

    private fun writeOffWrap(dek: ByteArray) {
        // Fresh key each rotation so we never reuse a nonce with an old key.
        ensureKeystoreKey(authRequired = false, rotate = true)
        val key = getKeystoreKey(authRequired = false)
            ?: error("Off Keystore key missing after generation")
        val iv = randomBytes(GCM_IV_BYTES)
        val cipher = Cipher.getInstance(GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val ct = cipher.doFinal(dek)
        atomicWrite(byteArrayOf(MODE_TAG_OFF) + iv + ct)
        deleteAlias(KEK_ALIAS_AUTH)
    }

    private fun writePinWrap(dek: ByteArray, pin: String) {
        val salt = randomBytes(PBKDF2_SALT_BYTES)
        val kek = deriveKekFromPin(pin, salt)
        val iv = randomBytes(GCM_IV_BYTES)
        val cipher = Cipher.getInstance(GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, kek, GCMParameterSpec(GCM_TAG_BITS, iv))
        val ct = cipher.doFinal(dek)
        atomicWrite(byteArrayOf(MODE_TAG_PIN) + salt + iv + ct)
        // PIN mode intentionally deletes every Keystore alias — the PIN is the
        // only path to the DEK. If the user forgets the PIN, content is lost
        // (that is the wipe-on-forget design decision).
        deleteAlias(KEK_ALIAS_OFF)
        deleteAlias(KEK_ALIAS_AUTH)
    }

    private fun atomicWrite(bytes: ByteArray) {
        val f = dekFile
        val tmp = File(f.parent, "$DEK_FILENAME.tmp")
        tmp.writeBytes(bytes)
        if (f.exists()) f.delete()
        if (!tmp.renameTo(f)) {
            tmp.copyTo(f, overwrite = true)
            tmp.delete()
        }
    }

    // endregion

    // region Key generation

    private fun ensureKeystoreKey(authRequired: Boolean, rotate: Boolean = false) {
        val alias = if (authRequired) KEK_ALIAS_AUTH else KEK_ALIAS_OFF
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (rotate && ks.containsAlias(alias)) {
            ks.deleteEntry(alias)
        }
        if (ks.containsAlias(alias)) return
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
        if (authRequired) {
            builder.setUserAuthenticationRequired(true)
            // Per-op authentication via CryptoObject — no sliding timer.
            // On API 30+ we can set 0 seconds + authenticators.
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                builder.setUserAuthenticationParameters(
                    0,
                    KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
                )
            } else {
                @Suppress("DEPRECATION")
                builder.setUserAuthenticationValidityDurationSeconds(-1)
            }
        }
        kg.init(builder.build())
        kg.generateKey()
    }

    private fun getKeystoreKey(authRequired: Boolean): SecretKey? {
        val alias = if (authRequired) KEK_ALIAS_AUTH else KEK_ALIAS_OFF
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (!ks.containsAlias(alias)) {
            ensureKeystoreKey(authRequired = authRequired, rotate = false)
        }
        return ks.getKey(alias, null) as? SecretKey
    }

    private fun deleteAlias(alias: String) {
        runCatching {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (ks.containsAlias(alias)) ks.deleteEntry(alias)
        }
    }

    private fun deriveKekFromPin(pin: String, salt: ByteArray): SecretKey {
        val spec = PBEKeySpec(pin.toCharArray(), salt, PBKDF2_ITERATIONS, 256)
        return try {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun randomDek(): ByteArray = ByteArray(DEK_BYTES).also { SecureRandom().nextBytes(it) }
    private fun randomBytes(n: Int): ByteArray = ByteArray(n).also { SecureRandom().nextBytes(it) }

    // endregion

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEK_ALIAS_OFF = "spatialfin_content_kek"
        private const val KEK_ALIAS_AUTH = "spatialfin_content_kek_auth"
        private const val DEK_FILENAME = "content_dek.bin"
        private const val GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val GCM_TAG_BYTES = 16
        private const val GCM_IV_BYTES = 12
        private const val PBKDF2_ITERATIONS = 600_000
        private const val PBKDF2_SALT_BYTES = 16
        const val DEK_BYTES = 32
        private val EMPTY_BYTES = ByteArray(0)

        private const val MODE_TAG_OFF: Byte = 0
        private const val MODE_TAG_BIOMETRIC: Byte = 1
        private const val MODE_TAG_PIN: Byte = 2
    }
}
