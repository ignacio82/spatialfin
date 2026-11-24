package dev.jdtech.jellyfin.setup.lan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingSecretsTest {

    @Test
    fun `generate produces 128-bit lowercase hex pairingToken and 6-char Crockford manualCode`() {
        val s1 = PairingSecrets.generate(nowEpochMs = 1_000_000L, nowElapsedMs = 5_000L)
        val s2 = PairingSecrets.generate(nowEpochMs = 1_000_000L, nowElapsedMs = 5_000L)

        assertTrue(Regex("^[0-9a-f]{32}$").matches(s1.pairingToken))
        assertEquals(6, s1.manualCode.length)
        assertEquals(7, s1.formattedManualCode.length)
        assertEquals('-', s1.formattedManualCode[3])
        assertEquals(1_600_000L, s1.expiresAtEpochMs)
        assertEquals(605_000L, s1.idleExpiresAtElapsedMs)
        assertEquals(905_000L, s1.hardCapElapsedMs)
        assertNotEquals(s1.pairingToken, s2.pairingToken)
    }

    @Test
    fun `normalizeManualCode strips spaces and hyphens, uppercases, and rejects ambiguous glyphs`() {
        assertEquals("K7P9M2", PairingSecrets.normalizeManualCode("k7p-9m2"))
        assertEquals("K7P9M2", PairingSecrets.normalizeManualCode(" K7P 9M2 "))
        assertEquals("ABCDEF", PairingSecrets.normalizeManualCode("abc-def"))

        // Ambiguous characters (0, O, 1, I, L) must be rejected
        assertNull(PairingSecrets.normalizeManualCode("K7P-0M2"))
        assertNull(PairingSecrets.normalizeManualCode("K7P-OM2"))
        assertNull(PairingSecrets.normalizeManualCode("K7P-1M2"))
        assertNull(PairingSecrets.normalizeManualCode("K7P-IM2"))
        assertNull(PairingSecrets.normalizeManualCode("K7P-LM2"))

        // Wrong lengths and blank values rejected
        assertNull(PairingSecrets.normalizeManualCode(""))
        assertNull(PairingSecrets.normalizeManualCode(null))
        assertNull(PairingSecrets.normalizeManualCode("K7P9M"))
        assertNull(PairingSecrets.normalizeManualCode("K7P9M2X"))
    }

    @Test
    fun `isQrPairingToken recognizes 32-hex tokens only`() {
        assertTrue(PairingSecrets.isQrPairingToken("0123456789abcdef0123456789abcdef"))
        assertTrue(PairingSecrets.isQrPairingToken("0123456789ABCDEF0123456789ABCDEF"))
        assertFalse(PairingSecrets.isQrPairingToken("K7P-9M2"))
        assertFalse(PairingSecrets.isQrPairingToken("0123456789abcdef"))
        assertFalse(PairingSecrets.isQrPairingToken(null))
    }
}
