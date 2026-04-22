package dev.jdtech.jellyfin.player.xr.voice

/**
 * Tiny TTL + LRU cache used by the voice pipeline to suppress repeated external
 * lookups when the user asks the same question about the same scene twice
 * within a short window (e.g. "who plays X" → follow-up → same scene).
 *
 * The wrapper `Entry` holds the cached value plus its expiry so callers can
 * disambiguate a cache miss (`getOrNull` returns null) from a cached null
 * value (`entry.value == null`).
 */
internal class VoiceResultCache<V>(
    private val ttlMs: Long,
    private val maxSize: Int = 16,
    private val now: () -> Long = System::currentTimeMillis,
) {
    class Entry<V>(val value: V, val expiresAtMs: Long)

    private val map = object : LinkedHashMap<String, Entry<V>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry<V>>?) =
            size > maxSize
    }

    @Synchronized
    fun getOrNull(key: String): Entry<V>? {
        val entry = map[key] ?: return null
        if (now() > entry.expiresAtMs) {
            map.remove(key)
            return null
        }
        return entry
    }

    @Synchronized
    fun put(key: String, value: V) {
        map[key] = Entry(value, now() + ttlMs)
    }
}
