package dev.jdtech.jellyfin.network

import org.dcache.nfs.v4.CompoundBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidNfsClientTest {

    @Test
    fun `lookup chunks stay within the negotiated operation limit`() {
        val maximumOperations = 6

        val chunks = AndroidNfsClient.lookupChunks(
            "/one/two/three/four/five/six/seven/",
            maximumOperations,
        )

        assertEquals(
            listOf(
                listOf("one", "two", "three"),
                listOf("four", "five", "six"),
                listOf("seven"),
            ),
            chunks,
        )
        val compoundOperationCounts = chunks.map { components ->
            val builder = CompoundBuilder().withPutrootfh()
            components.forEach(builder::withLookup)
            builder.withGetfh().build().argarray.size + SEQUENCE_OPERATION_COUNT
        }
        assertEquals(listOf(6, 6, 4), compoundOperationCounts)
        assertTrue(compoundOperationCounts.all { it <= maximumOperations })
    }

    @Test
    fun `lookup chunks normalize export separators and preserve parent traversal`() {
        val chunks = AndroidNfsClient.lookupChunks("//exports///movies/../archive//", 5)

        assertEquals(
            listOf(
                listOf("exports", "movies"),
                listOf("..", "archive"),
            ),
            chunks,
        )
    }

    @Test
    fun `empty root export requires no lookup chunks`() {
        assertEquals(emptyList<List<String>>(), AndroidNfsClient.lookupChunks("///", 4))
    }

    @Test
    fun `lookup rejects a session too small to carry one component`() {
        assertThrows(IllegalArgumentException::class.java) {
            AndroidNfsClient.lookupChunks("movies", 3)
        }
    }

    private companion object {
        private const val SEQUENCE_OPERATION_COUNT = 1
    }
}
