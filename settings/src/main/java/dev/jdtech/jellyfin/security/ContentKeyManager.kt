package dev.jdtech.jellyfin.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.InvalidKeyException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.UnrecoverableKeyException
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
 * The DEK is cached in process memory as a [ByteArray] after unlock and zeroed
 * on [lock]. Callers always receive defensive copies (`copyOf()`).
 */
class ContentKeyManager(
    private val appContext: Context,
    private val isAppLockEnabled: () -> Boolean = { false },
    private val isDekResetPending: () -> Boolean = { false },
) {
    enum class StoredDekMode {
        NONE,
        OFF,
        BIOMETRIC,
        PIN,
    }

    sealed class Mode {
        data object Off : Mode()
        data object Biometric : Mode()
        data class Pin(val pin: String) : Mode()
    }

    sealed interface BiometricUnwrapResult {
        data class Ready(val cipher: Cipher) : BiometricUnwrapResult
        data object NotBiometric : BiometricUnwrapResult
        data object KeyInvalidated : BiometricUnwrapResult
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

    data class BiometricWrapHandle(val cipher: Cipher, val iv: ByteArray)

    class PreparedDekWrap(
        val wrappedBytes: ByteArray,
        val sha256Digest: String,
        val dekCopy: ByteArray,
    ) {
        fun wipe() {
            dekCopy.fill(0)
        }
    }

    @Volatile private var cachedDek: ByteArray? = null

    private val dekFile: File
        get() = File(appContext.filesDir, DEK_FILENAME)

    /** True if a wrapped DEK exists on disk regardless of mode. */
    fun hasStoredDek(): Boolean = dekFile.exists()

    @Synchronized
    fun storedMode(): StoredDekMode =
        when (currentStoredMode()) {
            null -> StoredDekMode.NONE
            MODE_TAG_OFF -> StoredDekMode.OFF
            MODE_TAG_BIOMETRIC -> StoredDekMode.BIOMETRIC
            MODE_TAG_PIN -> StoredDekMode.PIN
            else -> StoredDekMode.NONE
        }

    @Synchronized
    fun storedDekSha256Digest(): String? {
        val f = dekFile
        if (!f.exists()) return null
        return runCatching { sha256Hex(f.readBytes()) }.getOrNull()
    }

    @Synchronized
    fun hasValidBiometricKey(): Boolean {
        val key = runCatching { getExistingKeystoreKey(authRequired = true) }.getOrNull() ?: return false
        return try {
            val cipher = Cipher.getInstance(GCM_TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Installs an ephemeral DEK received over Binder IPC in `:xrplayer`.
     * Stores an internal defensive copy; caller is responsible for zeroing its buffer.
     */
    @Synchronized
    fun installEphemeralDek(dek: ByteArray) {
        cachedDek?.fill(0)
        cachedDek = dek.copyOf()
    }

    /** Plaintext DEK defensive copy if unlocked or in Off mode (while App Lock is disabled), else null. */
    @Synchronized
    fun getDekOrNull(): ByteArray? {
        if (isDekResetPending()) return null
        cachedDek?.let { return it.copyOf() }
        if (currentStoredMode() == MODE_TAG_OFF && !isAppLockEnabled()) {
            return runCatching { unwrapOff().also { cachedDek = it }.copyOf() }.getOrNull()
        }
        return null
    }

    /**
     * Bootstrapping convenience: in Off mode (when App Lock is disabled), creates or unwraps an
     * Off-mode DEK. Whenever App Lock is enabled or the DEK is Biometric/PIN wrapped, requires
     * an already-unlocked cached DEK and throws otherwise.
     */
    @Synchronized
    fun getOrCreateDek(): ByteArray {
        if (isDekResetPending()) {
            throw IllegalStateException("Content key reset is pending; unlock recovery required")
        }
        cachedDek?.let { return it.copyOf() }
        val storedMode = currentStoredMode()
        if (storedMode == null) {
            if (isAppLockEnabled()) {
                throw IllegalStateException("Content key missing while App Lock is enabled")
            }
            val dek = randomDek()
            writeOffWrap(dek)
            cachedDek = dek
            Timber.i("ContentKeyManager: created new DEK in Off mode")
            return dek.copyOf()
        }
        if (storedMode == MODE_TAG_OFF && !isAppLockEnabled()) {
            return unwrapOff().also { cachedDek = it }.copyOf()
        }
        throw IllegalStateException(
            "Content key is locked (mode tag=$storedMode, appLock=${isAppLockEnabled()}); unlock required",
        )
    }

    /** Unlock using a BiometricPrompt-issued Cipher; caches and returns a defensive DEK copy. */
    @Synchronized
    fun unlockWithCipher(cipher: Cipher): UnlockResult {
        val blob = readBiometricWrapOrNull()
            ?: return UnlockResult.Failed(UnlockFailure.NoMaterial)
        return try {
            val dek = cipher.doFinal(blob.ciphertextWithTag)
            cachedDek?.fill(0)
            cachedDek = dek
            UnlockResult.Success(dek.copyOf())
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
            cachedDek?.fill(0)
            cachedDek = dek
            UnlockResult.Success(dek.copyOf())
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
     * Build a [Cipher] suitable for BiometricPrompt CryptoObject DECRYPT.
     * Never auto-creates a missing Keystore key — returns [BiometricUnwrapResult.KeyInvalidated]
     * if the key is missing or permanently invalidated.
     */
    @Synchronized
    fun initBiometricUnwrapCipher(): BiometricUnwrapResult {
        if (storedMode() != StoredDekMode.BIOMETRIC) {
            return BiometricUnwrapResult.NotBiometric
        }
        val blob = readBiometricWrapOrNull() ?: return BiometricUnwrapResult.KeyInvalidated
        val key =
            try {
                getExistingKeystoreKey(authRequired = true)
            } catch (e: Exception) {
                Timber.w(e, "ContentKeyManager: failed loading biometric KEK")
                return BiometricUnwrapResult.KeyInvalidated
            } ?: return BiometricUnwrapResult.KeyInvalidated

        return try {
            val cipher = Cipher.getInstance(GCM_TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, blob.iv))
            BiometricUnwrapResult.Ready(cipher)
        } catch (e: KeyPermanentlyInvalidatedException) {
            Timber.w(e, "ContentKeyManager: biometric KEK permanently invalidated")
            BiometricUnwrapResult.KeyInvalidated
        } catch (e: UnrecoverableKeyException) {
            Timber.w(e, "ContentKeyManager: biometric KEK unrecoverable")
            BiometricUnwrapResult.KeyInvalidated
        } catch (e: InvalidKeyException) {
            Timber.w(e, "ContentKeyManager: biometric KEK invalid")
            BiometricUnwrapResult.KeyInvalidated
        } catch (e: Exception) {
            Timber.w(e, "ContentKeyManager: initBiometricUnwrapCipher failed")
            BiometricUnwrapResult.KeyInvalidated
        }
    }

    /**
     * Build a [Cipher] for BiometricPrompt CryptoObject ENCRYPT using a KeyStore-generated IV
     * (no caller-supplied `GCMParameterSpec`). Does NOT rotate an existing `KEK_ALIAS_AUTH`
     * unless [rotate] is explicitly true.
     */
    @Synchronized
    fun initBiometricWrapCipher(rotate: Boolean = false): BiometricWrapHandle {
        ensureKeystoreKey(authRequired = true, rotate = rotate)
        val key = getExistingKeystoreKey(authRequired = true)
            ?: error("Biometric Keystore key missing after generation")
        val cipher = Cipher.getInstance(GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = requireNotNull(cipher.iv) { "KeyStore GCM cipher did not generate an IV" }
        require(iv.size == GCM_IV_BYTES) { "Unexpected GCM IV length: ${iv.size}" }
        return BiometricWrapHandle(cipher = cipher, iv = iv)
    }

    // region Two-phase PreparedDekWrap API

    @Synchronized
    fun prepareRekeyToBiometric(handle: BiometricWrapHandle): PreparedDekWrap? {
        val dek = resolveDekForAuthenticatedRekey() ?: run {
            Timber.w("ContentKeyManager: prepareRekeyToBiometric refused — DEK unavailable")
            return null
        }
        return try {
            val ct = handle.cipher.doFinal(dek)
            val wrapped = byteArrayOf(MODE_TAG_BIOMETRIC) + handle.iv + ct
            PreparedDekWrap(
                wrappedBytes = wrapped,
                sha256Digest = sha256Hex(wrapped),
                dekCopy = dek,
            )
        } catch (e: Exception) {
            dek.fill(0)
            Timber.e(e, "ContentKeyManager: prepareRekeyToBiometric failed")
            null
        }
    }

    @Synchronized
    fun prepareRekeyToPin(pin: String): PreparedDekWrap? {
        val dek = resolveDekForAuthenticatedRekey() ?: run {
            Timber.w("ContentKeyManager: prepareRekeyToPin refused — DEK unavailable")
            return null
        }
        return try {
            val wrapped = buildPinWrappedBytes(dek, pin)
            PreparedDekWrap(
                wrappedBytes = wrapped,
                sha256Digest = sha256Hex(wrapped),
                dekCopy = dek,
            )
        } catch (e: Exception) {
            dek.fill(0)
            Timber.e(e, "ContentKeyManager: prepareRekeyToPin failed")
            null
        }
    }

    @Synchronized
    fun prepareRekeyToOff(): PreparedDekWrap? {
        val dek = resolveDekForAuthenticatedRekey() ?: run {
            Timber.w("ContentKeyManager: prepareRekeyToOff refused — DEK unavailable")
            return null
        }
        return try {
            val wrapped = buildOffWrappedBytes(dek)
            PreparedDekWrap(
                wrappedBytes = wrapped,
                sha256Digest = sha256Hex(wrapped),
                dekCopy = dek,
            )
        } catch (e: Exception) {
            dek.fill(0)
            Timber.e(e, "ContentKeyManager: prepareRekeyToOff failed")
            null
        }
    }

    @Synchronized
    fun commitPreparedDek(prepared: PreparedDekWrap): Boolean {
        return try {
            atomicWrite(prepared.wrappedBytes)
            cachedDek?.fill(0)
            cachedDek = prepared.dekCopy.copyOf()
            true
        } catch (e: Exception) {
            Timber.e(e, "ContentKeyManager: commitPreparedDek failed")
            false
        } finally {
            prepared.wipe()
        }
    }

    // endregion

    /**
     * Transition on-disk wrapping to Off or Pin via two-phase prepare+commit.
     * Superseded Keystore aliases are NOT deleted here; caller invokes
     * [deleteSupersededKeystoreKey] only after durable preference commits succeed.
     */
    @Synchronized
    fun rekeyToNonBiometric(newMode: Mode): Boolean {
        require(newMode !is Mode.Biometric) { "Use rekeyToBiometric for Biometric mode" }
        val prepared =
            when (newMode) {
                Mode.Off -> prepareRekeyToOff()
                is Mode.Pin -> prepareRekeyToPin(newMode.pin)
                Mode.Biometric -> error("unreachable")
            } ?: return false
        return commitPreparedDek(prepared).also { ok ->
            if (ok) Timber.i("ContentKeyManager: rekeyed to %s", newMode.javaClass.simpleName)
        }
    }

    /**
     * Transition on-disk wrapping to Biometric via two-phase prepare+commit.
     */
    @Synchronized
    fun rekeyToBiometric(handle: BiometricWrapHandle): Boolean {
        val prepared = prepareRekeyToBiometric(handle) ?: return false
        return commitPreparedDek(prepared).also { ok ->
            if (ok) Timber.i("ContentKeyManager: rekeyed to Biometric")
        }
    }

    /**
     * Generate a fresh DEK and wrap it with an authenticated Biometric cipher during key-invalidation recovery.
     */
    @Synchronized
    fun resetToBiometric(handle: BiometricWrapHandle): Boolean {
        val dek = randomDek()
        return try {
            val ct = handle.cipher.doFinal(dek)
            val wrapped = byteArrayOf(MODE_TAG_BIOMETRIC) + handle.iv + ct
            atomicWrite(wrapped)
            cachedDek?.fill(0)
            cachedDek = dek.copyOf()
            Timber.i("ContentKeyManager: reset DEK to Biometric")
            true
        } catch (e: Exception) {
            Timber.e(e, "ContentKeyManager: resetToBiometric failed")
            false
        } finally {
            dek.fill(0)
        }
    }

    /**
     * Generate a fresh DEK and wrap it with [pin] during PIN key-invalidation recovery.
     */
    @Synchronized
    fun resetToPin(pin: String): Boolean {
        val dek = randomDek()
        return try {
            val wrapped = buildPinWrappedBytes(dek, pin)
            atomicWrite(wrapped)
            cachedDek?.fill(0)
            cachedDek = dek.copyOf()
            Timber.i("ContentKeyManager: reset DEK to Pin")
            true
        } catch (e: Exception) {
            Timber.e(e, "ContentKeyManager: resetToPin failed")
            false
        } finally {
            dek.fill(0)
        }
    }

    /**
     * Delete Keystore KEK aliases superseded by [targetMode]. Must only be invoked AFTER
     * both `commitPreparedDek` and durable preference Commit 2 succeed.
     */
    @Synchronized
    fun deleteSupersededKeystoreKey(targetMode: Mode) {
        when (targetMode) {
            Mode.Biometric -> deleteAlias(KEK_ALIAS_OFF)
            is Mode.Pin -> {
                deleteAlias(KEK_ALIAS_OFF)
                deleteAlias(KEK_ALIAS_AUTH)
            }
            Mode.Off -> deleteAlias(KEK_ALIAS_AUTH)
        }
    }

    /**
     * Used solely inside authenticated rekey paths (`prepareRekeyTo*`, `rekeyTo*`).
     * Allows unwrapping `MODE_TAG_OFF` even while [isAppLockEnabled] is true so legacy OFF-wrapped
     * DEKs can be rekeyed without data loss, while [getDekOrNull]/[getOrCreateDek] remain blocked.
     */
    private fun unwrapOffDekForAuthenticatedRekey(): ByteArray? =
        runCatching { unwrapOff() }
            .onFailure { Timber.w(it, "ContentKeyManager: unwrapOffDekForAuthenticatedRekey failed") }
            .getOrNull()

    private fun resolveDekForAuthenticatedRekey(): ByteArray? {
        cachedDek?.let { return it.copyOf() }
        return when (storedMode()) {
            StoredDekMode.OFF -> unwrapOffDekForAuthenticatedRekey()
            StoredDekMode.NONE -> randomDek()
            StoredDekMode.BIOMETRIC, StoredDekMode.PIN -> null
        }
    }

    // region Wrap/unwrap helpers

    private data class WrappedBlob(
        val iv: ByteArray,
        val ciphertextWithTag: ByteArray,
        val salt: ByteArray = EMPTY_BYTES,
        val modeTag: Byte = MODE_TAG_OFF,
    )

    private fun currentStoredMode(): Byte? {
        val f = dekFile
        if (!f.exists()) return null
        return runCatching {
            f.inputStream().use { stream ->
                val b = stream.read()
                if (b < 0) null else b.toByte()
            }
        }.getOrNull()
    }

    private fun unwrapOff(): ByteArray {
        val blob = readPrefixedBlob(expectedMode = MODE_TAG_OFF)
            ?: throw IllegalStateException("No Off-mode wrapped DEK on disk")
        val key = getExistingKeystoreKey(authRequired = false)
            ?: throw IllegalStateException("Off-mode Keystore key unavailable")
        val cipher = Cipher.getInstance(GCM_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, blob.iv))
        return cipher.doFinal(blob.ciphertextWithTag)
    }

    private fun readBiometricWrapOrNull(): WrappedBlob? =
        readPrefixedBlob(expectedMode = MODE_TAG_BIOMETRIC)

    private fun readPinWrapOrNull(): WrappedBlob? =
        readPrefixedBlob(expectedMode = MODE_TAG_PIN)

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

    private fun buildOffWrappedBytes(dek: ByteArray): ByteArray {
        ensureKeystoreKey(authRequired = false, rotate = false)
        val key = getExistingKeystoreKey(authRequired = false)
            ?: error("Off Keystore key missing after generation")
        val cipher = Cipher.getInstance(GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = requireNotNull(cipher.iv) { "KeyStore GCM cipher did not generate an IV" }
        require(iv.size == GCM_IV_BYTES) { "Unexpected GCM IV length: ${iv.size}" }
        val ct = cipher.doFinal(dek)
        return byteArrayOf(MODE_TAG_OFF) + iv + ct
    }

    private fun buildPinWrappedBytes(dek: ByteArray, pin: String): ByteArray {
        val salt = randomBytes(PBKDF2_SALT_BYTES)
        val kek = deriveKekFromPin(pin, salt)
        val iv = randomBytes(GCM_IV_BYTES)
        val cipher = Cipher.getInstance(GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, kek, GCMParameterSpec(GCM_TAG_BITS, iv))
        val ct = cipher.doFinal(dek)
        return byteArrayOf(MODE_TAG_PIN) + salt + iv + ct
    }

    private fun writeOffWrap(dek: ByteArray) {
        atomicWrite(buildOffWrappedBytes(dek))
    }

    private fun atomicWrite(bytes: ByteArray) {
        val f = dekFile
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, "$DEK_FILENAME.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }
        try {
            Files.move(
                tmp.toPath(),
                f.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING)
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
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

    private fun getExistingKeystoreKey(authRequired: Boolean): SecretKey? {
        val alias = if (authRequired) KEK_ALIAS_AUTH else KEK_ALIAS_OFF
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (!ks.containsAlias(alias)) return null
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

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return buildString(digest.size * 2) {
            for (b in digest) {
                append("%02x".format(b.toInt() and 0xff))
            }
        }
    }

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
