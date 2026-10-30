package dev.jdtech.jellyfin.network

import java.security.MessageDigest
import java.security.Provider
import java.security.Security
import jcifs.smb.NtlmUtil
import jcifs.util.Crypto
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SmbCryptoTest {

    @Test
    fun testMd4DigestResolvesAndComputesKnownVector() {
        // Known vector: MD4("abc") = a448017aaf21d8525fc10ae87aa6729d
        val provider = BouncyCastleProvider()
        val md = MessageDigest.getInstance("MD4", provider)
        assertNotNull(md)
        val digest = md.digest("abc".toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        assertEquals("a448017aaf21d8525fc10ae87aa6729d", hex)
    }

    @Test
    fun testJcifsCryptoGetMD4WithProviderInstance() {
        val provider = BouncyCastleProvider()
        // If not already initialized, initProvider sets it; if already initialized, it won't throw because of our bridge
        runCatching { Crypto.initProvider(provider) }

        val md = Crypto.getMD4()
        assertNotNull(md)
        val digest = md.digest("abc".toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        assertEquals("a448017aaf21d8525fc10ae87aa6729d", hex)
    }

    @Test
    fun testNtlmNtHashComputation() {
        val hash = NtlmUtil.getNTHash("password")
        assertNotNull(hash)
        val hex = hash.joinToString("") { "%02x".format(it) }
        // Known test vector for NTLM hash of "password": 8846f7eaee8fb117ad06bdd830b7586c
        assertEquals("8846f7eaee8fb117ad06bdd830b7586c", hex)
    }

    @Test
    fun testPlatformBCConflictDoesNotBreakProviderInstance() {
        // Register a dummy platform "BC" provider without MD4, exactly as Android does out-of-the-box
        val dummyPlatformBC = object : Provider("BC", 1.0, "Platform BC without MD4") {}
        Security.removeProvider("BC")
        Security.addProvider(dummyPlatformBC)
        try {
            // Asking Security for "BC" by name fails with NoSuchAlgorithmException
            assertThrows(java.security.NoSuchAlgorithmException::class.java) {
                MessageDigest.getInstance("MD4", "BC")
            }

            // But passing BouncyCastleProvider instance directly succeeds without modifying Security
            val fullBc = BouncyCastleProvider()
            val md = MessageDigest.getInstance("MD4", fullBc)
            val digest = md.digest("abc".toByteArray(Charsets.UTF_8))
            val hex = digest.joinToString("") { "%02x".format(it) }
            assertEquals("a448017aaf21d8525fc10ae87aa6729d", hex)
        } finally {
            Security.removeProvider("BC")
        }
    }

    @Test
    fun testSmbCryptoBridgeInitializesSuccessfully() {
        SmbCryptoBridge.ensureInitialized()
        val md = Crypto.getMD4()
        assertNotNull(md)
        val digest = md.digest("abc".toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        assertEquals("a448017aaf21d8525fc10ae87aa6729d", hex)
    }

    @Test
    fun testSmbFileClientInstantiatesAndEnsuresCryptoInitialized() {
        val client = SmbFileClient()
        assertNotNull(client)
        val md = Crypto.getMD4()
        assertNotNull(md)
    }
}
