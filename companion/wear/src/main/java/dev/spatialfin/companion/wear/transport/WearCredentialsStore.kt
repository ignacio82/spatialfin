package dev.spatialfin.companion.wear.transport

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.spatialfin.companion.protocol.WearCredentials
import dev.spatialfin.companion.protocol.WearProtocolCodec
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * Caches the credential bundle the host pushes over the Data Layer so standalone mode has something
 * to authenticate with.
 *
 * The blob holds a Jellyfin access token, so it is sealed with AES-GCM under a hardware-backed
 * AndroidKeyStore key rather than sitting in plaintext preferences. This is what
 * `EncryptedSharedPreferences` does internally; doing it directly avoids pulling in the deprecated
 * `androidx.security:security-crypto` artifact, and matches how `:settings`' `ContentKeyManager`
 * already handles key material in this repo.
 */
@Singleton
class WearCredentialsStore @Inject constructor(@ApplicationContext private val context: Context) {

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences("spatialfin_wear_credentials", Context.MODE_PRIVATE)
    }

    private val _credentials = MutableStateFlow<WearCredentials?>(null)

    /**
     * Observable so the UI leaves the "connect your phone once" state the moment a bundle lands.
     */
    val credentials: StateFlow<WearCredentials?> = _credentials.asStateFlow()

    init {
        _credentials.value = loadCredentials()
    }

    // The credential bundle contains the phone's device ID for target discovery.
    // Authenticating as that ID would make the watch impersonate its host session.
    val watchDeviceId: String by lazy {
        prefs.getString(KEY_DEVICE_ID, null)
            ?: UUID.randomUUID().toString().also {
                prefs.edit().putString(KEY_DEVICE_ID, it).apply()
            }
    }

    var selectedDeviceId: String?
        get() = prefs.getString("selected_device_id", null)
        set(value) {
            prefs.edit().putString("selected_device_id", value).apply()
        }

    fun saveCredentials(credentials: WearCredentials) {
        val old = _credentials.value
        if (old?.serverId != credentials.serverId || old?.userId != credentials.userId)
            selectedDeviceId = null
        _credentials.value = credentials
        runCatching {
            val json =
                WearProtocolCodec.json.encodeToString(WearCredentials.serializer(), credentials)
            val cipher =
                Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
            val ciphertext = cipher.doFinal(json.toByteArray())
            prefs
                .edit()
                .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .putString(KEY_CIPHERTEXT, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
                .apply()
            Timber.i(
                "WearCredentialsStore: sealed credentials for server %s",
                credentials.serverName,
            )
        }
            .onFailure { Timber.e(it, "WearCredentialsStore: failed to seal credentials") }
    }

    fun clearCredentials() {
        selectedDeviceId = null
        _credentials.value = null
        prefs.edit().remove(KEY_CIPHERTEXT).remove(KEY_IV).apply()
    }

    private fun loadCredentials(): WearCredentials? {
        val ivRaw = prefs.getString(KEY_IV, null) ?: return null
        val ctRaw = prefs.getString(KEY_CIPHERTEXT, null) ?: return null
        return runCatching {
            val iv = Base64.decode(ivRaw, Base64.NO_WRAP)
            val ciphertext = Base64.decode(ctRaw, Base64.NO_WRAP)
            val cipher =
                Cipher.getInstance(TRANSFORMATION).apply {
                    init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
                }
            val json = cipher.doFinal(ciphertext).decodeToString()
            WearProtocolCodec.json.decodeFromString(WearCredentials.serializer(), json)
        }
            .onFailure {
                // A rotated or invalidated Keystore key makes the blob permanently unreadable;
                // drop it so the watch asks for a fresh push instead of retrying forever.
                Timber.w(it, "WearCredentialsStore: cached credentials unreadable, discarding")
                prefs.edit().remove(KEY_CIPHERTEXT).remove(KEY_IV).apply()
            }
            .getOrNull()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let {
            return it.secretKey
        }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "spatialfin_wear_credentials"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val KEY_CIPHERTEXT = "wear_credentials_ct"
        const val KEY_IV = "wear_credentials_iv"
        const val KEY_DEVICE_ID = "wear_device_id"
    }
}
