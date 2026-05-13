package dev.spatialfin.fcast.session

import android.content.Context
import dev.jdtech.jellyfin.fcast.sender.FCastReceiver
import dev.spatialfin.test.SpatialFinTestApplication
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Robolectric-backed round-trip tests for [RememberedReceiversStore]. The store wraps
 * SharedPreferences so we need a real Context — Robolectric supplies one. Each test
 * uses a fresh in-memory SharedPreferences via Robolectric's default Application.
 *
 * `application = SpatialFinTestApplication::class` is critical: without it Robolectric
 * instantiates the real `UnifiedApplication` (manifest-declared, `@HiltAndroidApp`-annotated),
 * which eagerly resolves Hilt's graph, which calls the Jellyfin SDK's `androidDevice(context)`
 * helper, which force-unwraps `Settings.Secure.ANDROID_ID`. That field is null in Robolectric
 * by default — NPE. See [SpatialFinTestApplication]'s KDoc for the full explanation.
 *
 * What's important here: the schema additions (audioLatencyMs / audioLatencyCalibratedAtMs)
 * are additive and backwards-compatible. Older releases wrote blobs without those fields —
 * those must still decode cleanly after an upgrade. The first test pins that contract.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = SpatialFinTestApplication::class)
class RememberedReceiversStoreTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun newStore(): RememberedReceiversStore {
        // Each instance binds to the same shared prefs (Robolectric resets across tests).
        return RememberedReceiversStore(context)
    }

    private fun receiver(host: String, port: Int = 46899, name: String = "Tv"): FCastReceiver =
        FCastReceiver(host = host, port = port, name = name, source = FCastReceiver.Source.Manual)

    @Test fun `empty store returns empty list`() = runTest {
        assertEquals(emptyList<RememberedReceiver>(), newStore().load())
    }

    @Test fun `upsert and load round-trip without latency`() = runTest {
        val store = newStore()
        store.upsert(receiver("10.0.0.1"), lastSeenMs = 1000L)
        val loaded = store.load()
        assertEquals(1, loaded.size)
        assertEquals("10.0.0.1", loaded[0].host)
        assertEquals(1000L, loaded[0].lastSeenMs)
        assertNull(loaded[0].audioLatencyMs)
        assertNull(loaded[0].audioLatencyCalibratedAtMs)
    }

    @Test fun `setAudioLatency persists and is reflected on next load`() = runTest {
        val store = newStore()
        store.upsert(receiver("10.0.0.1"))
        store.setAudioLatency(host = "10.0.0.1", port = 46899, audioLatencyMs = 73)
        val loaded = store.load().single()
        assertEquals(73, loaded.audioLatencyMs)
        assertNotNull(loaded.audioLatencyCalibratedAtMs)
    }

    @Test fun `setAudioLatency null clears both latency fields`() = runTest {
        val store = newStore()
        store.upsert(receiver("10.0.0.1"))
        store.setAudioLatency("10.0.0.1", 46899, 73)
        store.setAudioLatency("10.0.0.1", 46899, null)
        val loaded = store.load().single()
        assertNull(loaded.audioLatencyMs)
        assertNull(loaded.audioLatencyCalibratedAtMs)
    }

    @Test fun `setAudioLatency clamps negatives to zero`() = runTest {
        val store = newStore()
        store.upsert(receiver("10.0.0.1"))
        store.setAudioLatency("10.0.0.1", 46899, -10)
        assertEquals(0, store.load().single().audioLatencyMs)
    }

    @Test fun `upsert preserves prior calibrated latency on mDNS refresh`() = runTest {
        val store = newStore()
        store.upsert(receiver("10.0.0.1"), lastSeenMs = 1_000L)
        store.setAudioLatency("10.0.0.1", 46899, 99)
        // Simulate a later mDNS hit re-upserting the same host:port. The store should
        // bump lastSeenMs but keep the calibration we measured earlier.
        store.upsert(receiver("10.0.0.1", name = "Tv (renamed)"), lastSeenMs = 2_000L)

        val loaded = store.load().single()
        assertEquals("Tv (renamed)", loaded.name)
        assertEquals(2_000L, loaded.lastSeenMs)
        assertEquals(99, loaded.audioLatencyMs)
    }

    @Test fun `setAudioLatency on unknown receiver is a no-op`() = runTest {
        val store = newStore()
        // Nothing remembered yet — calling setAudioLatency must NOT silently insert a row,
        // because we'd end up with a "ghost" calibration not tied to a real receiver.
        store.setAudioLatency("10.0.0.99", 46899, 50)
        assertEquals(emptyList<RememberedReceiver>(), store.load())
    }

    @Test fun `legacy blob without audioLatency fields decodes cleanly`() = runTest {
        // Hand-craft a legacy JSON blob (the schema before audioLatencyMs was added) and
        // write it directly to SharedPreferences, then load. This protects users who upgrade
        // from a build that wrote the old schema.
        val legacyJson = """[{"host":"10.0.0.1","port":46899,"name":"Tv","lastSeenMs":1000}]"""
        context.getSharedPreferences("fcast_remembered_receivers", Context.MODE_PRIVATE)
            .edit()
            .putString("receivers", legacyJson)
            .commit()

        val loaded = newStore().load()
        assertEquals(1, loaded.size)
        assertEquals("10.0.0.1", loaded[0].host)
        assertNull(loaded[0].audioLatencyMs)
        assertNull(loaded[0].audioLatencyCalibratedAtMs)
    }

    @Test fun `forget removes the entry along with its calibration`() = runTest {
        val store = newStore()
        store.upsert(receiver("10.0.0.1"))
        store.setAudioLatency("10.0.0.1", 46899, 73)
        store.forget("10.0.0.1", 46899)
        assertEquals(emptyList<RememberedReceiver>(), store.load())
    }

    // --- PR 6: 90-day stale pruning ---

    @Test fun `pruneStale drops uncalibrated entries older than 90 days`() = runTest {
        val store = newStore()
        val now = 100_000_000_000L
        // 100 days old, uncalibrated. Should be dropped.
        store.upsert(receiver("10.0.0.1"), lastSeenMs = now - 100L * 24 * 60 * 60 * 1000)
        // 30 days old, uncalibrated. Should be kept.
        store.upsert(receiver("10.0.0.2"), lastSeenMs = now - 30L * 24 * 60 * 60 * 1000)

        val pruned = store.pruneStale(now = now)
        assertEquals(1, pruned)
        val remaining = store.load()
        assertEquals(1, remaining.size)
        assertEquals("10.0.0.2", remaining[0].host)
    }

    @Test fun `pruneStale keeps calibrated entries regardless of age`() = runTest {
        val store = newStore()
        val now = 100_000_000_000L
        // Calibrated but ancient — kept because the user invested in measuring it.
        store.upsert(receiver("10.0.0.5"), lastSeenMs = now - 365L * 24 * 60 * 60 * 1000)
        store.setAudioLatency("10.0.0.5", 46899, 73)
        // Uncalibrated and ancient — dropped.
        store.upsert(receiver("10.0.0.6"), lastSeenMs = now - 365L * 24 * 60 * 60 * 1000)

        val pruned = store.pruneStale(now = now)
        assertEquals(1, pruned)
        val remaining = store.load()
        assertEquals(1, remaining.size)
        assertEquals("10.0.0.5", remaining[0].host)
        assertEquals(73, remaining[0].audioLatencyMs)
    }

    @Test fun `pruneStale on empty store is a no-op`() = runTest {
        val store = newStore()
        assertEquals(0, store.pruneStale())
    }

    @Test fun `pruneStale on store with only fresh entries is a no-op`() = runTest {
        val store = newStore()
        store.upsert(receiver("10.0.0.7"))
        val pruned = store.pruneStale()
        assertEquals(0, pruned)
        assertEquals(1, store.load().size)
    }
}
