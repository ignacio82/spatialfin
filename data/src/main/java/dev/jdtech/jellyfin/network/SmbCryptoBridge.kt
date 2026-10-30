package dev.jdtech.jellyfin.network

import java.security.Security
import java.util.concurrent.atomic.AtomicBoolean
import jcifs.util.Crypto
import org.bouncycastle.jce.provider.BouncyCastleProvider
import timber.log.Timber

internal object SmbCryptoBridge {
    private val initialized = AtomicBoolean(false)

    /**
     * Lazily and thread-safely initializes jcifs-ng with the full Bouncy Castle provider instance
     * and installs it into [Security] so that both instance-based resolution in jcifs-ng and any
     * named "BC" provider lookups resolve MD4 successfully on Android devices.
     */
    fun ensureInitialized() {
        if (initialized.compareAndSet(false, true)) {
            val provider = BouncyCastleProvider()
            try {
                Crypto.initProvider(provider)
            } catch (e: Exception) {
                Timber.w(e, "jcifs-ng Crypto provider initialization: %s", e.message)
            }
            try {
                // Remove the platform's stripped-down "BC" provider and insert the full
                // BouncyCastleProvider so any named "BC" or fallback algorithm lookup succeeds.
                Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
                Security.insertProviderAt(provider, 1)
            } catch (e: Exception) {
                Timber.w(e, "Failed to register BouncyCastleProvider with Security: %s", e.message)
            }
        }
    }
}
