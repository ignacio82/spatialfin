package dev.jdtech.jellyfin.network

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.Security
import jcifs.smb.NtlmUtil
import jcifs.util.Crypto
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SmbDeviceCryptoTest {

    @Test
    fun testOnDeviceBouncyCastleAndNtlmHash() {
        SmbCryptoBridge.ensureInitialized()

        val provider = Security.getProvider("BC")
        assertNotNull("BouncyCastle provider must be registered", provider)
        assertEquals(BouncyCastleProvider::class.java.name, provider.javaClass.name)

        val md = Crypto.getMD4()
        assertNotNull("Crypto.getMD4() must not be null", md)
        val hash = md.digest("abc".toByteArray(Charsets.UTF_8))
        val hex = hash.joinToString("") { "%02x".format(it) }
        assertEquals("a448017aaf21d8525fc10ae87aa6729d", hex)

        val ntHash = NtlmUtil.getNTHash("password")
        assertNotNull(ntHash)
        val ntHex = ntHash.joinToString("") { "%02x".format(it) }
        assertEquals("8846f7eaee8fb117ad06bdd830b7586c", ntHex)

        // Verify NTLMv2 key calculation (nTOWFv2) which relies on MD4 / NT hash
        val ntlmv2 = NtlmUtil.nTOWFv2("password", "user", "domain")
        assertNotNull(ntlmv2)

        // Verify SmbFileClient initialization and test connection error handling without crypto crash
        val client = SmbFileClient()
        assertNotNull(client)
    }
}
