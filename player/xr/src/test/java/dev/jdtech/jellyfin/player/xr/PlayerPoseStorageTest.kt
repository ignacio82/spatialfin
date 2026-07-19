package dev.jdtech.jellyfin.player.xr

import android.content.SharedPreferences
import dev.jdtech.jellyfin.player.local.presentation.PlayerViewModel
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class PlayerPoseStorageTest {

    private val sharedPreferences = mockk<SharedPreferences>(relaxed = true)
    private val appPreferences = AppPreferences(sharedPreferences)
    private val viewModel = mockk<PlayerViewModel>(relaxed = true)

    @Before
    fun setup() {
        every { viewModel.appPreferences } returns appPreferences
    }

    @Test
    fun `loadSavedPlayerRootScale returns value from preferences`() {
        every { sharedPreferences.getFloat("pref_xr_player_panel_scale", 1.39f) } returns 1.75f

        val scale = loadSavedPlayerRootScale(viewModel)

        assertEquals(1.75f, scale, 1e-4f)
    }

    @Test
    fun `loadSavedPlayerRootScale clamps extreme values`() {
        every { sharedPreferences.getFloat("pref_xr_player_panel_scale", 1.39f) } returns 10.0f

        val scale = loadSavedPlayerRootScale(viewModel)

        assertEquals(5.0f, scale, 1e-4f)
    }

    @Test
    fun `savePlayerRootScale stores coerced value`() {
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { sharedPreferences.edit() } returns editor

        savePlayerRootScale(viewModel, 2.2f)

        verify { editor.putFloat("pref_xr_player_panel_scale", 2.2f) }
    }

    @Test
    fun `loadSavedPlayerRootDepth returns value from preferences`() {
        every { sharedPreferences.getFloat("pref_xr_player_panel_depth", 6.0f) } returns 4.5f

        val depth = loadSavedPlayerRootDepth(viewModel)

        assertEquals(4.5f, depth, 1e-4f)
    }

    @Test
    fun `savePlayerRootDepth stores coerced value`() {
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { sharedPreferences.edit() } returns editor

        savePlayerRootDepth(viewModel, 8.0f)

        verify { editor.putFloat("pref_xr_player_panel_depth", 8.0f) }
    }

    @Test
    fun `loadSavedPlayerRootPose does not overwrite saved scale`() {
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { sharedPreferences.edit() } returns editor

        loadSavedPlayerRootPose(viewModel)

        verify(exactly = 0) { editor.putFloat("pref_xr_player_panel_scale", any()) }
    }
}
