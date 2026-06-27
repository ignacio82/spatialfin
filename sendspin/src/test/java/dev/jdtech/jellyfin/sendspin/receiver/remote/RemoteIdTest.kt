package dev.jdtech.jellyfin.sendspin.receiver.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteIdTest {
    @Test
    fun `parses a clean 26-char id`() {
        val id = RemoteId.parse("PGSVXKGZJCFA6MOH4UPBH5Q9HY")
        assertEquals("PGSVXKGZJCFA6MOH4UPBH5Q9HY", id?.rawId)
    }

    @Test
    fun `strips cosmetic hyphens and whitespace and upper-cases`() {
        val id = RemoteId.parse("  pgsvx-kgzjc-fa6mo-h4upb-h5q9h-y  ")
        assertEquals("PGSVXKGZJCFA6MOH4UPBH5Q9HY", id?.rawId)
    }

    @Test
    fun `rejects ids of the wrong length`() {
        assertNull(RemoteId.parse("PGSVXKGZJCFA6MOH4UPBH5Q9H")) // 25
        assertNull(RemoteId.parse("PGSVXKGZJCFA6MOH4UPBH5Q9HYZ")) // 27
        assertNull(RemoteId.parse(""))
    }

    @Test
    fun `rejects non-alphanumeric characters`() {
        // 26 chars but contains a disallowed symbol.
        assertNull(RemoteId.parse("PGSVXKGZJCFA6MOH4UPBH5Q9H!"))
    }

    @Test
    fun `rejects null input`() {
        assertNull(RemoteId.parse(null))
        assertFalse(RemoteId.isValid(null))
    }

    @Test
    fun `isValid mirrors parse`() {
        assertTrue(RemoteId.isValid("pgsvx-kgzjc-fa6mo-h4upb-h5q9h-y"))
        assertFalse(RemoteId.isValid("not-an-id"))
    }

    @Test
    fun `constructor rejects an already-invalid raw id`() {
        assertThrows(IllegalArgumentException::class.java) {
            RemoteId("lowercase-and-too-short")
        }
    }
}
