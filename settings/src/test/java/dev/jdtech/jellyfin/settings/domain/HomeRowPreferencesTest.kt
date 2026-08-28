package dev.jdtech.jellyfin.settings.domain

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Exercises [HomeRowPreferences] against an in-memory [SharedPreferences] that
 * notifies listeners on `apply()`, the way the platform does.
 */
class HomeRowPreferencesTest {
    private lateinit var prefs: FakeSharedPreferences
    private lateinit var homeRows: HomeRowPreferences

    private val music = HomeRowIds.latest("11111111-1111-1111-1111-111111111111")
    private val movies = HomeRowIds.latest("22222222-2222-2222-2222-222222222222")

    private val onScreen =
        listOf(HomeRowIds.SUGGESTIONS, HomeRowIds.CONTINUE_WATCHING, HomeRowIds.NEXT_UP, music, movies)

    @Before
    fun setUp() {
        prefs = FakeSharedPreferences()
        homeRows = HomeRowPreferences(AppPreferences(prefs))
    }

    @Test
    fun `hiding a latest row hides it and persists`() {
        homeRows.setVisible(music, false)

        assertFalse(homeRows.isVisible(music))
        assertTrue(homeRows.isVisible(movies))
        assertEquals(listOf(HomeRowIds.SUGGESTIONS, HomeRowIds.CONTINUE_WATCHING, HomeRowIds.NEXT_UP, movies),
            homeRows.layout.value.arrange(onScreen) { it })
        // Survives a process restart: a fresh instance reads the same blob.
        assertFalse(HomeRowPreferences(AppPreferences(prefs)).isVisible(music))
    }

    @Test
    fun `moving a latest row up reorders it and persists`() {
        homeRows.move(movies, onScreen, up = true)

        assertEquals(
            listOf(HomeRowIds.SUGGESTIONS, HomeRowIds.CONTINUE_WATCHING, HomeRowIds.NEXT_UP, movies, music),
            homeRows.layout.value.arrange(onScreen) { it },
        )
        assertEquals(
            listOf(HomeRowIds.SUGGESTIONS, HomeRowIds.CONTINUE_WATCHING, HomeRowIds.NEXT_UP, movies, music),
            HomeRowPreferences(AppPreferences(prefs)).layout.value.order,
        )
    }

    @Test
    fun `hiding then showing a latest row brings it back`() {
        homeRows.setVisible(music, false)
        homeRows.setVisible(music, true)

        assertTrue(homeRows.isVisible(music))
    }

    @Test
    fun `the layout flow emits on every change`() {
        val seen = mutableListOf<HomeRowLayout>()
        seen += homeRows.layout.value

        homeRows.setVisible(music, false)
        seen += homeRows.layout.value
        homeRows.move(movies, onScreen, up = true)
        seen += homeRows.layout.value

        assertEquals(3, seen.distinct().size)
    }

    @Test
    fun `toggling ALL_LATEST on unhides individually hidden latest rows`() {
        homeRows.setVisible(music, false)
        assertFalse(homeRows.isVisible(music))

        homeRows.setVisible(HomeRowIds.ALL_LATEST, true)
        assertTrue(homeRows.isVisible(HomeRowIds.ALL_LATEST))
        assertTrue(homeRows.isVisible(music))
        assertTrue(homeRows.isVisible(movies))
    }

    @Test
    fun `toggling ALL_LATEST off hides all latest rows`() {
        homeRows.setVisible(HomeRowIds.ALL_LATEST, false)
        assertFalse(homeRows.isVisible(HomeRowIds.ALL_LATEST))
        assertFalse(homeRows.isVisible(music))
        assertFalse(homeRows.isVisible(movies))
    }

    @Test
    fun `network share visibility syncs with AppPreferences and layout`() {
        val shareRow = HomeRowIds.networkShare("smb1")
        assertTrue(homeRows.isVisible(shareRow))

        homeRows.setVisible(shareRow, false)
        assertFalse(homeRows.isVisible(shareRow))
        assertFalse(AppPreferences(prefs).isNetworkShareHomeVisible("smb1"))

        homeRows.setVisible(shareRow, true)
        assertTrue(homeRows.isVisible(shareRow))
        assertTrue(AppPreferences(prefs).isNetworkShareHomeVisible("smb1"))
    }

    @Test
    fun `offline and plugin rows hide and unhide cleanly`() {
        val offlineRow = HomeRowIds.offline("movies")
        val pluginRow = HomeRowIds.plugin("my_plugin", "home")

        homeRows.setVisible(offlineRow, false)
        assertFalse(homeRows.isVisible(offlineRow))

        homeRows.setVisible(pluginRow, false)
        assertFalse(homeRows.isVisible(pluginRow))

        val restorable = homeRows.layout.value.restorableHiddenRows()
        val restorableIds = restorable.map { it.first }.toSet()
        assertTrue(restorableIds.contains(offlineRow))
        assertTrue(restorableIds.contains(pluginRow))

        homeRows.setVisible(offlineRow, true)
        assertTrue(homeRows.isVisible(offlineRow))

        homeRows.setVisible(pluginRow, true)
        assertTrue(homeRows.isVisible(pluginRow))
    }
}

private class FakeSharedPreferences : SharedPreferences {
    private val values = mutableMapOf<String, Any?>()
    private val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()

    override fun getAll(): MutableMap<String, *> = values.toMutableMap()

    override fun getString(key: String?, defValue: String?) = values[key] as? String ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?) =
        values[key] as? MutableSet<String> ?: defValues

    override fun getInt(key: String?, defValue: Int) = values[key] as? Int ?: defValue

    override fun getLong(key: String?, defValue: Long) = values[key] as? Long ?: defValue

    override fun getFloat(key: String?, defValue: Float) = values[key] as? Float ?: defValue

    override fun getBoolean(key: String?, defValue: Boolean) = values[key] as? Boolean ?: defValue

    override fun contains(key: String?) = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) {
        listener?.let { listeners += it }
    }

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) {
        listener?.let { listeners -= it }
    }

    private inner class Editor : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()

        override fun putString(key: String, value: String?) = apply { pending[key] = value }

        override fun putStringSet(key: String, values: MutableSet<String>?) =
            apply { pending[key] = values }

        override fun putInt(key: String, value: Int) = apply { pending[key] = value }

        override fun putLong(key: String, value: Long) = apply { pending[key] = value }

        override fun putFloat(key: String, value: Float) = apply { pending[key] = value }

        override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }

        override fun remove(key: String) = apply { pending[key] = null }

        override fun clear() = apply { values.keys.forEach { pending[it] = null } }

        override fun commit(): Boolean {
            pending.forEach { (key, value) ->
                if (value == null) values.remove(key) else values[key] = value
                listeners.toList().forEach { it.onSharedPreferenceChanged(this@FakeSharedPreferences, key) }
            }
            pending.clear()
            return true
        }

        override fun apply() {
            commit()
        }
    }
}
