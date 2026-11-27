package dev.spatialfin.fcast.session

import android.content.SharedPreferences
import dev.jdtech.jellyfin.cast.CastCapability
import dev.jdtech.jellyfin.cast.CastProtocol
import dev.jdtech.jellyfin.cast.CastReceiver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class RememberedReceiversStoreMigrationTest {

    private class InMemorySharedPreferences : SharedPreferences {
        private val map = linkedMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = map.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? = map[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            map[key] as? MutableSet<String> ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = map[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = map[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = map[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor()
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        private inner class Editor : SharedPreferences.Editor {
            private val pending = linkedMapOf<String, Any?>()
            override fun putString(key: String, value: String?) = apply { pending[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?) = apply { pending[key] = values }
            override fun putInt(key: String, value: Int) = apply { pending[key] = value }
            override fun putLong(key: String, value: Long) = apply { pending[key] = value }
            override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
            override fun remove(key: String) = apply { pending[key] = null }
            override fun clear() = apply { map.keys.forEach { pending[it] = null } }
            override fun commit(): Boolean {
                pending.forEach { (k, v) -> if (v == null) map.remove(k) else map[k] = v }
                pending.clear()
                return true
            }
            override fun apply() {
                commit()
            }
        }
    }

    @Test
    fun `legacy FCast JSON without id or protocol backfills FCast identity cleanly`() = runBlocking {
        val prefs = InMemorySharedPreferences()
        prefs.edit().putString(
            "receivers",
            """[{"host":"192.168.1.20","port":46899,"name":"Living Room TV","lastSeenMs":1000}]""",
        ).commit()

        val store = RememberedReceiversStore(prefsProvider = { prefs }, hasPairedAvrToken = { false })
        val loaded = store.load().single()
        assertEquals("FCast:192.168.1.20:46899", loaded.resolvedId)
        assertEquals(CastProtocol.FCast, loaded.castProtocol)
        assertFalse(loaded.useTls)
        assertNull(loaded.anchorUuid)
    }

    @Test
    fun `upsertCastReceiver updates SendspinAvr host and port in place preserving codecs and anchor`() = runBlocking {
        val prefs = InMemorySharedPreferences()
        val store = RememberedReceiversStore(prefsProvider = { prefs }, hasPairedAvrToken = { true })

        val id = "SendspinAvr:avr-550e8400e29b41d4a716446655440000"
        val caps = setOf(CastCapability.Audio, CastCapability.Volume, CastCapability.SplitAv, CastCapability.TheaterAudioV1)

        store.upsertCastReceiver(
            CastReceiver(
                id = id,
                name = "Theater AVR",
                host = "192.168.1.50",
                port = 1984,
                protocol = CastProtocol.SendspinAvr,
                capabilities = caps,
                useTls = false,
            ),
            lastSeenMs = 1_000L,
        )
        store.setSupportedAudioCodecsById(id, listOf("truehd", "eac3-joc"))
        store.setRoomAnchor(
            serverId = "avr-550e8400e29b41d4a716446655440000",
            anchorUuid = "anchor-uuid-99",
            anchorToMlpTransform = listOf(0.5f, 0f, -2.5f, 0.1f, 0f, 0f, 1f),
        )

        // DHCP renewal at a new IP and TLS enabled
        store.upsertCastReceiver(
            CastReceiver(
                id = id,
                name = "Theater AVR",
                host = "192.168.1.77",
                port = 2984,
                protocol = CastProtocol.SendspinAvr,
                capabilities = caps,
                useTls = true,
            ),
            lastSeenMs = 2_000L,
        )

        val entries = store.load()
        assertEquals(1, entries.size)
        val entry = entries.single()
        assertEquals(id, entry.resolvedId)
        assertEquals(CastProtocol.SendspinAvr, entry.castProtocol)
        assertEquals("192.168.1.77", entry.host)
        assertEquals(2984, entry.port)
        assertEquals(true, entry.useTls)
        assertEquals(listOf("truehd", "eac3-joc"), entry.supportedAudioCodecs)
        assertEquals("anchor-uuid-99", entry.anchorUuid)
        assertEquals(listOf(0.5f, 0f, -2.5f, 0.1f, 0f, 0f, 1f), entry.anchorToMlpTransform)
    }

    @Test
    fun `pruneStale exempts paired SendspinAvr rooms while dropping stale unpaired entries`() = runBlocking {
        val prefs = InMemorySharedPreferences()
        val pairedIds = setOf("SendspinAvr:avr-11111111")
        val store = RememberedReceiversStore(
            prefsProvider = { prefs },
            hasPairedAvrToken = { it in pairedIds },
        )

        store.upsertCastReceiver(
            CastReceiver(
                id = "SendspinAvr:avr-11111111",
                name = "Paired Room",
                host = "10.0.0.10",
                port = 1984,
                protocol = CastProtocol.SendspinAvr,
            ),
            lastSeenMs = 1L,
        )
        store.upsertCastReceiver(
            CastReceiver(
                id = "SendspinAvr:avr-22222222",
                name = "Unpaired Room",
                host = "10.0.0.11",
                port = 1984,
                protocol = CastProtocol.SendspinAvr,
            ),
            lastSeenMs = 1L,
        )
        store.upsertCastReceiver(
            CastReceiver(
                id = "FCast:10.0.0.12:46899",
                name = "Old TV",
                host = "10.0.0.12",
                port = 46899,
                protocol = CastProtocol.FCast,
            ),
            lastSeenMs = 1L,
        )

        val pruned = store.pruneStale(now = 100L * 24 * 60 * 60 * 1000L)
        assertEquals(2, pruned)
        val remaining = store.load()
        assertEquals(1, remaining.size)
        assertEquals("SendspinAvr:avr-11111111", remaining.single().resolvedId)
    }
}
