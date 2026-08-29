package dev.jdtech.jellyfin.film.presentation.home

import dev.jdtech.jellyfin.models.Server
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the caching rules that used to live in a companion-object field, where
 * none of this could be exercised without standing up a ViewModel — the two
 * minute window in particular was untestable because it read the wall clock.
 */
class HomeStateCacheTest {
    private var clock = 1_000_000L
    private val cache = HomeStateCache { clock }

    private val serverA = "server-a"
    private val serverB = "server-b"
    private val userA: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val userB: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")

    private fun state(name: String) = HomeState(
            server = Server(
                id = name,
                name = name,
                currentServerAddressId = null,
                currentUserId = null,
            )
        )

    @Test
    fun `an empty cache returns nothing and is not fresh`() {
        assertNull(cache.get(serverA, userA))
        assertFalse(cache.isFresh(serverA, userA))
    }

    @Test
    fun `a stored state comes back for the same server and user`() {
        cache.put(serverA, userA, state("first"))

        assertEquals("first", cache.get(serverA, userA)?.server?.name)
        assertTrue(cache.isFresh(serverA, userA))
    }

    @Test
    fun `another server does not see the cache`() {
        cache.put(serverA, userA, state("first"))

        assertNull(cache.get(serverB, userA))
        assertFalse(cache.isFresh(serverB, userA))
    }

    @Test
    fun `another user on the same server does not see the cache`() {
        cache.put(serverA, userA, state("first"))

        assertNull(cache.get(serverA, userB))
        assertFalse(cache.isFresh(serverA, userB))
    }

    @Test
    fun `a null server and user is a key like any other`() {
        cache.put(null, null, state("onboarding"))

        assertEquals("onboarding", cache.get(null, null)?.server?.name)
        assertNull(cache.get(serverA, userA))
    }

    @Test
    fun `an entry goes stale after two minutes but is still served`() {
        cache.put(serverA, userA, state("first"))

        clock += 2 * 60 * 1000L - 1
        assertTrue("just inside the window", cache.isFresh(serverA, userA))

        clock += 1
        assertFalse("exactly at the window", cache.isFresh(serverA, userA))
        // Still returned: the caller paints it, then refreshes. Dropping it here
        // would put a spinner back on every tab switch.
        assertEquals("first", cache.get(serverA, userA)?.server?.name)
    }

    @Test
    fun `invalidate drops the entry entirely`() {
        cache.put(serverA, userA, state("first"))

        cache.invalidate()

        assertNull(cache.get(serverA, userA))
        assertFalse(cache.isFresh(serverA, userA))
    }

    @Test
    fun `a later put replaces the entry and restarts the window`() {
        cache.put(serverA, userA, state("first"))
        clock += 2 * 60 * 1000L
        assertFalse(cache.isFresh(serverA, userA))

        cache.put(serverA, userA, state("second"))

        assertEquals("second", cache.get(serverA, userA)?.server?.name)
        assertTrue(cache.isFresh(serverA, userA))
    }

    @Test
    fun `switching session replaces rather than accumulates`() {
        cache.put(serverA, userA, state("first"))
        cache.put(serverB, userB, state("second"))

        assertNull(cache.get(serverA, userA))
        assertEquals("second", cache.get(serverB, userB)?.server?.name)
    }
}
