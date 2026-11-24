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

    @Test
    fun `hiding suggestions row hides it and persists in layout and preferences`() {
        assertTrue(homeRows.isVisible(HomeRowIds.SUGGESTIONS))

        homeRows.setVisible(HomeRowIds.SUGGESTIONS, false)

        assertFalse(homeRows.isVisible(HomeRowIds.SUGGESTIONS))
        assertFalse(AppPreferences(prefs).getValue(AppPreferences(prefs).homeSuggestions))
        // Verify it is recorded in the layout's hidden set as well
        assertTrue(homeRows.layout.value.hidden.contains(HomeRowIds.SUGGESTIONS))
        // Survives a fresh instance / restart: reads both boolean and storedHidden
        val restarted = HomeRowPreferences(AppPreferences(prefs))
        assertFalse(restarted.isVisible(HomeRowIds.SUGGESTIONS))
        assertTrue(restarted.layout.value.hidden.contains(HomeRowIds.SUGGESTIONS))
    }

    @Test
    fun `unhiding suggestions row restores visibility and persists`() {
        homeRows.setVisible(HomeRowIds.SUGGESTIONS, false)
        assertFalse(homeRows.isVisible(HomeRowIds.SUGGESTIONS))

        homeRows.setVisible(HomeRowIds.SUGGESTIONS, true)
        assertTrue(homeRows.isVisible(HomeRowIds.SUGGESTIONS))
        assertTrue(AppPreferences(prefs).getValue(AppPreferences(prefs).homeSuggestions))
        assertFalse(homeRows.layout.value.hidden.contains(HomeRowIds.SUGGESTIONS))

        val restarted = HomeRowPreferences(AppPreferences(prefs))
        assertTrue(restarted.isVisible(HomeRowIds.SUGGESTIONS))
        assertFalse(restarted.layout.value.hidden.contains(HomeRowIds.SUGGESTIONS))
    }

    @Test
    fun `toggling home suggestions via shared preferences syncs layout hidden set`() {
        val appPrefs = AppPreferences(prefs)
        assertTrue(homeRows.isVisible(HomeRowIds.SUGGESTIONS))

        appPrefs.setValue(appPrefs.homeSuggestions, false)
        assertFalse(homeRows.isVisible(HomeRowIds.SUGGESTIONS))
        assertTrue(homeRows.layout.value.hidden.contains(HomeRowIds.SUGGESTIONS))

        appPrefs.setValue(appPrefs.homeSuggestions, true)
        assertTrue(homeRows.isVisible(HomeRowIds.SUGGESTIONS))
        assertFalse(homeRows.layout.value.hidden.contains(HomeRowIds.SUGGESTIONS))
    }

    @Test
    fun `companion sync does not overwrite home row visibility`() {
        val appPrefs = AppPreferences(prefs)
        homeRows.setVisible(HomeRowIds.SUGGESTIONS, false)
        assertFalse(homeRows.isVisible(HomeRowIds.SUGGESTIONS))

        // When companion sync provides home_suggestions = "true", it must not be applied
        val applied = appPrefs.applyCompanionPreference("home_suggestions", "true")
        assertFalse(applied)
        assertFalse(homeRows.isVisible(HomeRowIds.SUGGESTIONS))
        assertFalse(appPrefs.getValue(appPrefs.homeSuggestions))
    }
}
