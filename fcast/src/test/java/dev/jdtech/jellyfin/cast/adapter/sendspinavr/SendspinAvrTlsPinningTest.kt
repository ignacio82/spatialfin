package dev.jdtech.jellyfin.cast.adapter.sendspinavr

import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Principal
import java.security.PublicKey
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Date
import javax.net.ssl.X509TrustManager
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SendspinAvrTlsPinningTest {

    private class FakeLeafCert(
        private val key: PublicKey,
        private val serial: BigInteger,
    ) : X509Certificate() {
        override fun getPublicKey(): PublicKey = key
        override fun getSerialNumber(): BigInteger = serial
        override fun getEncoded(): ByteArray = key.encoded
        override fun verify(key: PublicKey?) = Unit
        override fun verify(key: PublicKey?, sigProvider: String?) = Unit
        override fun toString(): String = "FakeLeafCert($serial)"
        override fun checkValidity() = Unit
        override fun checkValidity(date: Date?) = Unit
        override fun getVersion(): Int = 3
        override fun getIssuerDN(): Principal = Principal { "CN=Test" }
        override fun getSubjectDN(): Principal = Principal { "CN=Test" }
        override fun getNotBefore(): Date = Date(0L)
        override fun getNotAfter(): Date = Date(Long.MAX_VALUE)
        override fun getTBSCertificate(): ByteArray = ByteArray(0)
        override fun getSignature(): ByteArray = ByteArray(0)
        override fun getSigAlgName(): String = "SHA256withECDSA"
        override fun getSigAlgOID(): String = "1.2.840.10045.4.3.2"
        override fun getSigAlgParams(): ByteArray? = null
        override fun getIssuerUniqueID(): BooleanArray? = null
        override fun getSubjectUniqueID(): BooleanArray? = null
        override fun getKeyUsage(): BooleanArray? = null
        override fun getBasicConstraints(): Int = -1
        override fun hasUnsupportedCriticalExtension(): Boolean = false
        override fun getCriticalExtensionOIDs(): MutableSet<String> = mutableSetOf()
        override fun getNonCriticalExtensionOIDs(): MutableSet<String> = mutableSetOf()
        override fun getExtensionValue(oid: String?): ByteArray? = null
    }

    private fun fakeCertWithKey(seed: Int): X509Certificate {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(256)
        val kp = kpg.generateKeyPair()
        return FakeLeafCert(kp.public, BigInteger.valueOf(seed.toLong()))
    }

    @Test
    fun `computeLeafSpkiSha256 and computeTlsSas produce canonical URL-safe pin and 8-char uppercase hex SAS`() {
        val cert = fakeCertWithKey(1)
        val expectedDigest = MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded)
        val expectedB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(expectedDigest)

        val computed = SendspinAvrTlsPinning.computeLeafSpkiSha256(cert)
        assertEquals(expectedB64, computed)
        assertFalse(computed.contains('='))
        assertFalse(computed.contains('+'))
        assertFalse(computed.contains('/'))

        val sas = SendspinAvrTlsPinning.computeTlsSas(computed)
        assertEquals(8, sas?.length)
        assertTrue("SAS must be uppercase hex: $sas", sas!!.matches(Regex("[0-9A-F]{8}")))
    }

    @Test
    fun `pinned TrustManager accepts exact leaf SPKI match and rejects mismatched or empty chains`() {
        val goodCert = fakeCertWithKey(10)
        val evilCert = fakeCertWithKey(20)
        val pin = SendspinAvrTlsPinning.computeLeafSpkiSha256(goodCert)
        assertNotEquals(pin, SendspinAvrTlsPinning.computeLeafSpkiSha256(evilCert))

        val baseClient = OkHttpClient()
        val pinnedClient = SendspinAvrTlsPinning.createPinnedClient(baseClient, pin)
        val tm = pinnedClient.x509TrustManager as X509TrustManager

        // Exact SPKI match passes
        tm.checkServerTrusted(arrayOf(goodCert), "ECDHE_ECDSA")

        // Mismatched leaf cert throws TlsPinMismatchException wrapped in CertificateException
        val mismatchErr = runCatching {
            tm.checkServerTrusted(arrayOf(evilCert), "ECDHE_ECDSA")
        }.exceptionOrNull()
        assertTrue(
            "Expected CertificateException wrapping TlsPinMismatchException, got $mismatchErr",
            mismatchErr is CertificateException && SendspinAvrTlsPinning.isPinMismatch(mismatchErr),
        )

        // Empty chain is rejected
        val emptyErr = runCatching {
            tm.checkServerTrusted(emptyArray(), "ECDHE_ECDSA")
        }.exceptionOrNull()
        assertTrue(
            "Expected TlsPinMismatchException on empty chain, got $emptyErr",
            emptyErr is CertificateException && SendspinAvrTlsPinning.isPinMismatch(emptyErr),
        )
    }
}
