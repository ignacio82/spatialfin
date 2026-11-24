package dev.jdtech.jellyfin.setup.lan

import android.os.SystemClock
import java.security.SecureRandom

/**
 * Per-session pairing credentials for `LanSetupServer`:
 * - `pairingToken`: 128-bit CSPRNG lowercase hex (32 chars), embedded in receiver QR codes.
 * - `manualCode`: 6-character Crockford Base32 (`23456789ABCDEFGHJKMNPQRSTUVWXYZ`, unambiguous
 *   alphabet omitting 0/O, 1/I/L), displayed to the user as `XXX-XXX` (`formattedManualCode`).
 * - Monotonic `createdElapsedMs` governs actual expiration (`10 min` idle, `15 min` hard cap);
 *   `expiresAtEpochMs` is advisory for wire/display consumers.
 */
data class PairingSecrets(
    val pairingToken: String,
    val manualCode: String,
    val createdEpochMs: Long,
    val createdElapsedMs: Long,
    val expiresAtEpochMs: Long = createdEpochMs + IDLE_TTL_MS,
) {
    val formattedManualCode: String
        get() =
            if (manualCode.length == MANUAL_CODE_LENGTH) {
                "${manualCode.substring(0, 3)}-${manualCode.substring(3, 6)}"
            } else {
                manualCode
            }

    val idleExpiresAtElapsedMs: Long
        get() = createdElapsedMs + IDLE_TTL_MS

    val hardCapElapsedMs: Long
        get() = createdElapsedMs + HARD_CAP_TTL_MS

    fun rotateManualCode(
        nowEpochMs: Long = System.currentTimeMillis(),
        nowElapsedMs: Long = SystemClock.elapsedRealtime(),
    ): PairingSecrets =
        copy(
            manualCode = generateManualCode(),
            createdEpochMs = nowEpochMs,
            createdElapsedMs = nowElapsedMs,
            expiresAtEpochMs = nowEpochMs + IDLE_TTL_MS,
        )

    companion object {
        const val MANUAL_CODE_LENGTH: Int = 6
        const val IDLE_TTL_MS: Long = 600_000L
        const val HARD_CAP_TTL_MS: Long = 900_000L

        // Crockford Base32 excluding confusing glyphs (0/O, 1/I/L) — matches Companion tv-pairing.js
        private const val CROCKFORD_ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"
        private val HEX_TOKEN_REGEX = Regex("^[0-9a-fA-F]{32}$")
        private val secureRandom = SecureRandom()

        fun generate(
            nowEpochMs: Long = System.currentTimeMillis(),
            nowElapsedMs: Long = SystemClock.elapsedRealtime(),
        ): PairingSecrets =
            PairingSecrets(
                pairingToken = generatePairingToken(),
                manualCode = generateManualCode(),
                createdEpochMs = nowEpochMs,
                createdElapsedMs = nowElapsedMs,
                expiresAtEpochMs = nowEpochMs + IDLE_TTL_MS,
            )

        fun generatePairingToken(): String {
            val bytes = ByteArray(16)
            secureRandom.nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }

        fun generateManualCode(): String {
            val chars = CharArray(MANUAL_CODE_LENGTH)
            for (i in 0 until MANUAL_CODE_LENGTH) {
                chars[i] = CROCKFORD_ALPHABET[secureRandom.nextInt(CROCKFORD_ALPHABET.length)]
            }
            return String(chars)
        }

        fun isQrPairingToken(raw: String?): Boolean =
            raw != null && HEX_TOKEN_REGEX.matches(raw.trim())

        /**
         * Normalizes a typed 6-character Crockford Base32 pairing code (stripping spaces and
         * hyphens, uppercasing, and rejecting ambiguous/non-alphabet characters such as `0`/`O`,
         * `1`/`I`/`L`). Returns null if [raw] is null or does not normalize to 6 valid symbols.
         */
        fun normalizeManualCode(raw: String?): String? {
            if (raw.isNullOrBlank()) return null
            val cleaned =
                buildString(raw.length) {
                    for (ch in raw) {
                        if (ch == '-' || ch == ' ' || ch == '\t' || ch == '\r' || ch == '\n') continue
                        append(ch.uppercaseChar())
                    }
                }
            if (cleaned.length != MANUAL_CODE_LENGTH) return null
            for (ch in cleaned) {
                if (ch !in CROCKFORD_ALPHABET) return null
            }
            return cleaned
        }

        /**
         * Normalizes an `X-Pairing-Token` header into either a 32-char lowercase hex QR token or
         * a 6-char uppercase Crockford manual code, returning null if malformed.
         */
        fun normalizePairingCredential(raw: String?): Pair<String, Boolean>? {
            if (raw.isNullOrBlank()) return null
            val trimmed = raw.trim()
            if (HEX_TOKEN_REGEX.matches(trimmed)) {
                return trimmed.lowercase() to false
            }
            val code = normalizeManualCode(trimmed) ?: return null
            return code to true
        }
    }
}
