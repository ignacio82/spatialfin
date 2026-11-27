package dev.jdtech.jellyfin.settings.domain

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Base64
import java.util.UUID
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber

/**
 * Main-process encrypted credential store for paired `spatialfin-avr` rooms and the device's
 * persistent `client_id`.
 *
 * Uses a dedicated non-auth-bound `AndroidKeyStore` AES-256-GCM alias (`spatialfin_theater_avr_tokens_v1`)
 * completely decoupled from `ContentKeyManager`'s download DEK so App Lock toggles or biometric
 * changes never silently brick room tokens, while still zeroing everything on [wipeAll].
 */
@Singleton
class TheaterAvrTokenStore internal constructor(
    private val prefs: SharedPreferences,
    private val crypto: TokenCrypto,
    private val clockMs: () -> Long,
) {

    @Inject
    constructor(@ApplicationContext context: Context) : this(
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
        crypto = AndroidKeyStoreTokenCrypto(KEYSTORE_ALIAS),
        clockMs = System::currentTimeMillis,
    )

    data class StoredAvrToken(
        val serverId: String,
        val avrToken: String,
        val host: String,
        val port: Int,
        val useTls: Boolean,
        val pairedAtMs: Long,
    )

    internal interface TokenCrypto {
        fun encrypt(plaintext: ByteArray, aad: ByteArray): ByteArray
        fun decrypt(blob: ByteArray, aad: ByteArray): ByteArray
        fun deleteKey()
    }

    private val lock = Any()

    fun getOrCreateClientId(): String = synchronized(lock) {
        prefs.getString(KEY_CLIENT_ID, null)?.takeIf { it.isNotBlank() }
            ?: "client-${UUID.randomUUID()}".also { generated ->
                prefs.edit().putString(KEY_CLIENT_ID, generated).apply()
            }
    }

    fun saveToken(
        serverId: String,
        avrToken: String,
        host: String,
        port: Int,
        useTls: Boolean,
    ): StoredAvrToken = synchronized(lock) {
        val normalizedId = normalizeServerId(serverId)
        require(normalizedId.isNotEmpty()) { "serverId must not be blank" }
        require(avrToken.isNotBlank()) { "avrToken must not be blank" }
        val record = StoredAvrToken(
            serverId = normalizedId,
            avrToken = avrToken,
            host = host,
            port = port,
            useTls = useTls,
            pairedAtMs = clockMs(),
        )
        val payloadJson = JSONObject()
            .put(FIELD_SERVER_ID, record.serverId)
            .put(FIELD_AVR_TOKEN, record.avrToken)
            .put(FIELD_HOST, record.host)
            .put(FIELD_PORT, record.port)
            .put(FIELD_USE_TLS, record.useTls)
            .put(FIELD_PAIRED_AT_MS, record.pairedAtMs)
            .toString()
        val encrypted = crypto.encrypt(
            plaintext = payloadJson.toByteArray(Charsets.UTF_8),
            aad = normalizedId.toByteArray(Charsets.UTF_8),
        )
        val encoded = Base64.getEncoder().encodeToString(encrypted)
        prefs.edit().putString(tokenPrefKey(normalizedId), encoded).apply()
        record
    }

    fun getToken(serverId: String): StoredAvrToken? = synchronized(lock) {
        val normalizedId = normalizeServerId(serverId)
        if (normalizedId.isEmpty()) return@synchronized null
        val prefKey = tokenPrefKey(normalizedId)
        val encoded = prefs.getString(prefKey, null) ?: return@synchronized null
        return@synchronized try {
            val blob = Base64.getDecoder().decode(encoded)
            val plaintext = crypto.decrypt(
                blob = blob,
                aad = normalizedId.toByteArray(Charsets.UTF_8),
            )
            val obj = JSONObject(String(plaintext, Charsets.UTF_8))
            StoredAvrToken(
                serverId = obj.getString(FIELD_SERVER_ID),
                avrToken = obj.getString(FIELD_AVR_TOKEN),
                host = obj.getString(FIELD_HOST),
                port = obj.getInt(FIELD_PORT),
                useTls = obj.optBoolean(FIELD_USE_TLS, false),
                pairedAtMs = obj.optLong(FIELD_PAIRED_AT_MS, 0L),
            )
        } catch (e: AEADBadTagException) {
            Timber.tag(TAG).w(e, "Theater AVR token AAD/tag check failed for %s; purging stale record", normalizedId)
            prefs.edit().remove(prefKey).apply()
            null
        } catch (e: Exception) {
            when (e) {
                is GeneralSecurityException, is IllegalArgumentException, is JSONException -> {
                    Timber.tag(TAG).w(e, "Theater AVR token unreadable for %s; purging stale record", normalizedId)
                    prefs.edit().remove(prefKey).apply()
                    null
                }
                else -> throw e
            }
        }
    }

    fun hasToken(serverId: String): Boolean = getToken(serverId) != null

    fun listServerIds(): List<String> = synchronized(lock) {
        prefs.all.keys
            .filter { it.startsWith(TOKEN_KEY_PREFIX) }
            .map { it.removePrefix(TOKEN_KEY_PREFIX) }
            .sorted()
    }

    fun removeToken(serverId: String) = synchronized(lock) {
        val normalizedId = normalizeServerId(serverId)
        if (normalizedId.isNotEmpty()) {
            prefs.edit().remove(tokenPrefKey(normalizedId)).apply()
        }
    }

    fun wipeAll() = synchronized(lock) {
        prefs.edit().clear().apply()
        crypto.deleteKey()
    }

    private fun normalizeServerId(serverId: String): String =
        serverId.trim().removePrefix(RECEIVER_ID_PREFIX).substringBefore("::")

    private fun tokenPrefKey(normalizedServerId: String): String =
        "$TOKEN_KEY_PREFIX$normalizedServerId"

    private class AndroidKeyStoreTokenCrypto(private val alias: String) : TokenCrypto {
        private val keyLock = Any()

        override fun encrypt(plaintext: ByteArray, aad: ByteArray): ByteArray = synchronized(keyLock) {
            val key = getOrCreateKey()
            val cipher = Cipher.getInstance(GCM_TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.updateAAD(aad)
            val ciphertext = cipher.doFinal(plaintext)
            val iv = cipher.iv
            require(iv.size == GCM_IV_BYTES) { "Unexpected GCM IV length ${iv.size}" }
            ByteArray(GCM_IV_BYTES + ciphertext.size).also { out ->
                System.arraycopy(iv, 0, out, 0, GCM_IV_BYTES)
                System.arraycopy(ciphertext, 0, out, GCM_IV_BYTES, ciphertext.size)
            }
        }

        override fun decrypt(blob: ByteArray, aad: ByteArray): ByteArray = synchronized(keyLock) {
            if (blob.size <= GCM_IV_BYTES) {
                throw IllegalArgumentException("Encrypted token blob truncated (${blob.size} bytes)")
            }
            val key = getExistingKey()
                ?: throw AEADBadTagException("Missing Keystore key for Theater AVR tokens")
            val iv = blob.copyOfRange(0, GCM_IV_BYTES)
            val ciphertext = blob.copyOfRange(GCM_IV_BYTES, blob.size)
            val cipher = Cipher.getInstance(GCM_TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(aad)
            cipher.doFinal(ciphertext)
        }

        override fun deleteKey() = synchronized(keyLock) {
            runCatching {
                val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                if (ks.containsAlias(alias)) ks.deleteEntry(alias)
            }
            Unit
        }

        private fun getExistingKey(): SecretKey? {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (!ks.containsAlias(alias)) return null
            return ks.getKey(alias, null) as? SecretKey
        }

        private fun getOrCreateKey(): SecretKey {
            getExistingKey()?.let { return it }
            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val spec = KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
            keyGenerator.init(spec)
            return keyGenerator.generateKey()
        }
    }

    companion object {
        private const val TAG = "TheaterAvrTokenStore"
        private const val PREFS_NAME = "spatialfin_theater_avr_tokens"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEYSTORE_ALIAS = "spatialfin_theater_avr_tokens_v1"
        private const val GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128

        private const val KEY_CLIENT_ID = "client_id"
        private const val TOKEN_KEY_PREFIX = "avr_token_"
        private const val RECEIVER_ID_PREFIX = "SendspinAvr:"

        private const val FIELD_SERVER_ID = "server_id"
        private const val FIELD_AVR_TOKEN = "avr_token"
        private const val FIELD_HOST = "host"
        private const val FIELD_PORT = "port"
        private const val FIELD_USE_TLS = "use_tls"
        private const val FIELD_PAIRED_AT_MS = "paired_at_ms"
    }
}
