package dev.jdtech.jellyfin.api

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AcceptLanguageHeaderTest {

    @Test
    fun `formats language and country tag with quality fallback`() {
        val header = buildAcceptLanguageHeader(Locale.US)
        assertEquals("en-US, en;q=0.9", header)
    }

    @Test
    fun `formats spanish spain locale`() {
        val header = buildAcceptLanguageHeader(Locale.forLanguageTag("es-ES"))
        assertEquals("es-ES, es;q=0.9", header)
    }

    @Test
    fun `formats single language without region`() {
        val header = buildAcceptLanguageHeader(Locale.FRENCH)
        assertEquals("fr", header)
    }

    @Test
    fun `returns null for undefined or empty locale tag`() {
        val header = buildAcceptLanguageHeader(Locale.ROOT)
        assertNull(header)
    }
}
