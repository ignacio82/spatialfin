package dev.spatialfin.unified

import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.composable
import androidx.navigation.createGraph
import dev.spatialfin.DownloadsRoute
import dev.spatialfin.EpisodeRoute
import dev.spatialfin.HomeRoute
import dev.spatialfin.MediaRoute
import dev.spatialfin.UniversalPluginsRoute
import dev.spatialfin.downloadsTab
import dev.spatialfin.homeTab
import dev.spatialfin.mediaTab
import dev.spatialfin.shouldShowNavigationRail
import dev.spatialfin.sourcesTab
import dev.spatialfin.test.SpatialFinTestApplication
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = SpatialFinTestApplication::class)
class NavigationRailVisibilityTest {
    private val tabs = listOf(homeTab, mediaTab, downloadsTab, sourcesTab)

    private fun navController() = NavHostController(RuntimeEnvironment.getApplication()).apply {
        navigatorProvider.addNavigator(ComposeNavigator())
        graph = createGraph(startDestination = HomeRoute) {
            composable<HomeRoute> {}
            composable<MediaRoute> {}
            composable<DownloadsRoute> {}
            composable<UniversalPluginsRoute> {}
            composable<EpisodeRoute> {}
        }
    }

    private fun NavHostController.showsRail(searchExpanded: Boolean) =
        currentBackStackEntry?.destination.shouldShowNavigationRail(tabs, searchExpanded)

    @Test
    fun `returning home from a search result restores navigation`() {
        val nav = navController()
        assertTrue(nav.showsRail(searchExpanded = false))
        nav.navigate(MediaRoute)
        assertFalse(nav.showsRail(searchExpanded = true))
        nav.navigate(EpisodeRoute("episode-1"))
        assertFalse(nav.showsRail(searchExpanded = true))

        // The detail screen's Home button does not collapse the remembered search.
        nav.navigate(HomeRoute) {
            popUpTo(nav.graph.startDestinationId)
            launchSingleTop = true
        }
        assertTrue(nav.showsRail(searchExpanded = true))
    }

    @Test
    fun `back to expanded search still hides navigation until search is closed`() {
        val nav = navController()
        nav.navigate(MediaRoute)
        nav.navigate(EpisodeRoute("episode-1"))
        nav.popBackStack()

        assertFalse(nav.showsRail(searchExpanded = true))
        assertTrue(nav.showsRail(searchExpanded = false))
    }

    @Test
    fun `remembered search does not hide navigation on downloads or sources`() {
        val nav = navController()
        nav.navigate(MediaRoute)
        nav.navigate(DownloadsRoute)
        assertTrue(nav.showsRail(searchExpanded = true))
        nav.navigate(UniversalPluginsRoute)
        assertTrue(nav.showsRail(searchExpanded = true))
    }

    @Test
    fun `details and destinations outside the available tabs do not show navigation`() {
        val nav = navController()
        nav.navigate(EpisodeRoute("episode-1"))
        assertFalse(nav.showsRail(searchExpanded = false))
        nav.navigate(HomeRoute)
        assertFalse(
            nav.currentBackStackEntry?.destination.shouldShowNavigationRail(
                navigationItems = listOf(sourcesTab),
                searchExpanded = false,
            )
        )
        assertFalse(null.shouldShowNavigationRail(tabs, searchExpanded = false))
    }
}
