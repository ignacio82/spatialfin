package dev.jdtech.jellyfin.cast.adapter.sendspinavr

import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import okhttp3.OkHttpClient

/**
 * Raised when a self-signed `spatialfin-avr` TLS server presents a leaf certificate whose
 * SubjectPublicKeyInfo (SPKI) SHA-256 fingerprint does not match the pinned value.
 */
class TlsPinMismatchException(
    val expectedSpkiSha256: String,
    val actualSpkiSha256: String?,
    message: String = "Theater AVR TLS certificate pin mismatch",
) : SSLPeerUnverifiedException(message)

/**
 * Strict leaf SubjectPublicKeyInfo (SPKI) SHA-256 pinning for self-signed `spatialfin-avr`
 * LAN TLS deployments.
 *
 * Unlike trust-all workarounds, this validates exact constant-time equality of the leaf
 * certificate's SPKI SHA-256 digest in both [X509TrustManager] and [HostnameVerifier], allowing
 * pinned rooms to survive DHCP LAN IP changes while rejecting any MITM or rotated keypair until
 * the user explicitly re-pairs.
 */
object SendspinAvrTlsPinning {

    private const val MAX_CACHED_CLIENTS = 16
    private val cachedClients = ConcurrentHashMap<Pair<Int, String>, OkHttpClient>()

    /** Computes `URL_SAFE_NO_PAD` base64url SHA-256 of [cert]'s ASN.1 DER SubjectPublicKeyInfo. */
    fun computeLeafSpkiSha256(cert: X509Certificate): String {
        val spkiBytes = cert.publicKey.encoded
        val digest = MessageDigest.getInstance("SHA-256").digest(spkiBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    /**
     * Computes the canonical 8-character uppercase hex SAS fingerprint matching `spatialfin-avr`'s
     * `compute_tls_sas` (first 4 bytes of the decoded SPKI SHA-256 digest).
     */
    fun computeTlsSas(spkiB64Url: String): String? {
        val bytes = runCatching {
            Base64.getUrlDecoder().decode(spkiB64Url.trim())
        }.getOrNull() ?: return null
        if (bytes.size < 4) return null
        return String.format(
            Locale.US,
            "%02X%02X%02X%02X",
            bytes[0].toInt() and 0xFF,
            bytes[1].toInt() and 0xFF,
            bytes[2].toInt() and 0xFF,
            bytes[3].toInt() and 0xFF,
        )
    }

    /**
     * Returns a cached [OkHttpClient] derived from [baseClient] that enforces exact leaf SPKI
     * SHA-256 equality against [expectedSpkiSha256].
     */
    fun createPinnedClient(
        baseClient: OkHttpClient,
        expectedSpkiSha256: String,
    ): OkHttpClient {
        val normalizedPin = expectedSpkiSha256.trim()
        require(normalizedPin.isNotEmpty()) { "expectedSpkiSha256 must not be blank" }
        val expectedDigest = Base64.getUrlDecoder().decode(normalizedPin)
        require(expectedDigest.size == 32) { "SPKI SHA-256 pin must decode to 32 bytes" }

        val cacheKey = System.identityHashCode(baseClient) to normalizedPin
        cachedClients[cacheKey]?.let { return it }

        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                throw CertificateException("Client certificates are not supported")
            }

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val leaf = chain?.firstOrNull()
                    ?: throw CertificateException(
                        TlsPinMismatchException(
                            expectedSpkiSha256 = normalizedPin,
                            actualSpkiSha256 = null,
                            message = "Server presented an empty certificate chain",
                        ),
                    )
                val actualDigest = MessageDigest.getInstance("SHA-256").digest(leaf.publicKey.encoded)
                if (!MessageDigest.isEqual(actualDigest, expectedDigest)) {
                    val actualB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(actualDigest)
                    throw CertificateException(
                        TlsPinMismatchException(
                            expectedSpkiSha256 = normalizedPin,
                            actualSpkiSha256 = actualB64,
                            message = "Theater AVR TLS SPKI pin mismatch (expected $normalizedPin, got $actualB64)",
                        ),
                    )
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }

        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
        }

        val hostnameVerifier = HostnameVerifier { _, session ->
            val leaf = runCatching { session.peerCertificates.firstOrNull() as? X509Certificate }
                .getOrNull() ?: return@HostnameVerifier false
            val actualDigest = MessageDigest.getInstance("SHA-256").digest(leaf.publicKey.encoded)
            MessageDigest.isEqual(actualDigest, expectedDigest)
        }

        val pinned = baseClient.newBuilder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier(hostnameVerifier)
            .build()

        if (cachedClients.size >= MAX_CACHED_CLIENTS) {
            cachedClients.clear()
        }
        cachedClients[cacheKey] = pinned
        return pinned
    }

    /**
     * Performs a handshake-only TLS probe (no HTTP request or credentials sent) to read the
     * peer leaf certificate's SPKI SHA-256 pin and 8-character SAS for manual / Android TV
     * user confirmation before pairing.
     */
    fun probeLeafCertificatePinAndSas(
        host: String,
        port: Int,
        timeoutMs: Int = 4_000,
    ): Pair<String, String> {
        var capturedLeaf: X509Certificate? = null
        val captureTm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                capturedLeaf = chain?.firstOrNull()
                    ?: throw CertificateException("Server presented no certificate")
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(captureTm), SecureRandom())
        }
        val socket = sslContext.socketFactory.createSocket() as SSLSocket
        socket.use { tlsSocket ->
            tlsSocket.soTimeout = timeoutMs
            tlsSocket.connect(InetSocketAddress(host, port), timeoutMs)
            tlsSocket.startHandshake()
            val leaf = capturedLeaf
                ?: (tlsSocket.session.peerCertificates.firstOrNull() as? X509Certificate)
                ?: throw CertificateException("No leaf X509Certificate returned by $host:$port")
            val spki = computeLeafSpkiSha256(leaf)
            val sas = computeTlsSas(spki)
                ?: throw CertificateException("Failed computing SAS for $host:$port")
            return spki to sas
        }
    }

    /** Returns true if [throwable] or any of its causes is a [TlsPinMismatchException]. */
    fun isPinMismatch(throwable: Throwable?): Boolean {
        var current = throwable
        var depth = 0
        while (current != null && depth < 16) {
            if (current is TlsPinMismatchException) return true
            current = current.cause
            depth++
        }
        return false
    }
}
