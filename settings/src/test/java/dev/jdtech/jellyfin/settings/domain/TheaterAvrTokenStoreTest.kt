package dev.jdtech.jellyfin.settings.domain

import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TheaterAvrTokenStoreTest {

    private class InMemoryAesGcmCrypto : TheaterAvrTokenStore.TokenCrypto {
        private val random = SecureRandom()
        var key: SecretKey? = generateKey()
        var deletedCount = 0

        override fun encrypt(plaintext: ByteArray, aad: ByteArray): ByteArray {
            val activeKey = key ?: generateKey().also { key = it }
            val iv = ByteArray(12).also(random::nextBytes)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, activeKey, GCMParameterSpec(128, iv))
            cipher.updateAAD(aad)
            val ciphertext = cipher.doFinal(plaintext)
            return ByteArray(12 + ciphertext.size).also { out ->
                System.arraycopy(iv, 0, out, 0, 12)
                System.arraycopy(ciphertext, 0, out, 12, ciphertext.size)
            }
        }

        override fun decrypt(blob: ByteArray, aad: ByteArray): ByteArray {
            val activeKey = key ?: throw AEADBadTagException("Key deleted")
            val iv = blob.copyOfRange(0, 12)
            val ciphertext = blob.copyOfRange(12, blob.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, activeKey, GCMParameterSpec(128, iv))
            cipher.updateAAD(aad)
            return cipher.doFinal(ciphertext)
        }

        override fun deleteKey() {
            key = null
            deletedCount++
        }

        private fun generateKey(): SecretKey =
            KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    }

    @Test
    fun `save and retrieve token round-trips and normalizes SendspinAvr prefix`() {
        val prefs = FakeSharedPreferences()
        val crypto = InMemoryAesGcmCrypto()
        var nowMs = 1_700_000_000_000L
        val store = TheaterAvrTokenStore(prefs, crypto) { nowMs }

        val saved = store.saveToken(
            serverId = "SendspinAvr:avr-550e8400e29b41d4a716446655440000",
            avrToken = "savr_tok_secret",
            host = "192.168.1.50",
            port = 1984,
            useTls = true,
        )

        assertEquals("avr-550e8400e29b41d4a716446655440000", saved.serverId)
        assertEquals(nowMs, saved.pairedAtMs)
        assertTrue(store.hasToken("avr-550e8400e29b41d4a716446655440000"))
        assertTrue(store.hasToken("SendspinAvr:avr-550e8400e29b41d4a716446655440000"))

        val loaded = store.getToken("avr-550e8400e29b41d4a716446655440000")
        assertEquals(saved, loaded)
        assertEquals(listOf("avr-550e8400e29b41d4a716446655440000"), store.listServerIds())
    }

    @Test
    fun `tampered ciphertext triggers AEADBadTagException and clears only corrupted record`() {
        val prefs = FakeSharedPreferences()
        val crypto = InMemoryAesGcmCrypto()
        val store = TheaterAvrTokenStore(prefs, crypto) { 123L }

        store.saveToken("avr-11111111", "tok-1", "10.0.0.10", 1984, false)
        store.saveToken("avr-22222222", "tok-2", "10.0.0.11", 1984, false)

        // Swap ciphertext under avr-11111111 with avr-22222222's blob (AAD mismatch!).
        val blobForTwo = prefs.getString("avr_token_avr-22222222", null)!!
        prefs.edit().putString("avr_token_avr-11111111", blobForTwo).commit()

        assertNull(store.getToken("avr-11111111"))
        assertFalse(prefs.contains("avr_token_avr-11111111"))
        assertNotNull(store.getToken("avr-22222222"))
        assertEquals("tok-2", store.getToken("avr-22222222")?.avrToken)
    }

    @Test
    fun `client_id persists across reads and wipeAll rotates client_id and deletes key`() {
        val prefs = FakeSharedPreferences()
        val crypto = InMemoryAesGcmCrypto()
        val store = TheaterAvrTokenStore(prefs, crypto) { 999L }

        val firstClientId = store.getOrCreateClientId()
        assertTrue(firstClientId.startsWith("client-"))
        assertEquals(firstClientId, store.getOrCreateClientId())

        store.saveToken("avr-aaaaaaaa", "tok-a", "192.168.1.99", 1984, false)
        store.wipeAll()

        assertEquals(1, crypto.deletedCount)
        assertFalse(store.hasToken("avr-aaaaaaaa"))
        assertTrue(store.listServerIds().isEmpty())
        val rotatedClientId = store.getOrCreateClientId()
        assertNotEquals(firstClientId, rotatedClientId)
    }

    @Test
    fun `removeToken removes single server without touching other rooms`() {
        val prefs = FakeSharedPreferences()
        val crypto = InMemoryAesGcmCrypto()
        val store = TheaterAvrTokenStore(prefs, crypto) { 555L }

        store.saveToken("avr-aaaaaaaa", "tok-a", "192.168.1.10", 1984, false)
        store.saveToken("avr-bbbbbbbb", "tok-b", "192.168.1.11", 1984, true)
        store.removeToken("SendspinAvr:avr-aaaaaaaa")

        assertNull(store.getToken("avr-aaaaaaaa"))
        assertEquals("tok-b", store.getToken("avr-bbbbbbbb")?.avrToken)
    }
}
