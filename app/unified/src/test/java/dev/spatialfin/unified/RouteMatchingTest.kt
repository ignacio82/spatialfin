package dev.spatialfin.unified

import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import dev.spatialfin.HomeRoute
import dev.spatialfin.MediaRoute
import dev.spatialfin.DownloadsRoute
import dev.spatialfin.UniversalPluginsRoute
import dev.spatialfin.homeTab
import dev.spatialfin.mediaTab
import dev.spatialfin.downloadsTab
import dev.spatialfin.sourcesTab
import kotlin.reflect.KClass
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.createGraph
import dev.spatialfin.MovieRoute
import org.junit.Assert.assertFalse
import dev.spatialfin.test.SpatialFinTestApplication
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = SpatialFinTestApplication::class)
class RouteMatchingTest {
    @Test
    fun testNavigationRouteMatching() {
        val context = RuntimeEnvironment.getApplication()
        val navController = NavHostController(context).apply {
            navigatorProvider.addNavigator(ComposeNavigator())
            graph = createGraph(startDestination = HomeRoute) {
                composable<HomeRoute> {}
                composable<MediaRoute> {}
                composable<DownloadsRoute> {}
                composable<UniversalPluginsRoute> {}
                composable<MovieRoute> {}
            }
        }

        val items = listOf(homeTab, mediaTab, downloadsTab, sourcesTab)

        // Initial destination is HomeRoute
        val currentDestHome = navController.currentBackStackEntry?.destination
        assertNotNull(currentDestHome)
        assertTrue(
            "Current destination should match HomeRoute",
            currentDestHome?.hierarchy?.any { it.hasRoute(HomeRoute::class) } == true
        )
        assertTrue(
            "Current destination should match homeTab",
            currentDestHome?.hierarchy?.any { it.hasRoute(homeTab.route::class) } == true
        )
        assertFalse(
            "Current destination should not match mediaTab",
            currentDestHome?.hierarchy?.any { it.hasRoute(mediaTab.route::class) } == true
        )

        // Test tab selection on Home
        assertTrue(
            "homeTab should be selected",
            currentDestHome?.hierarchy?.any { it.hasRoute(homeTab.route::class) } == true
        )
        assertFalse(
            "mediaTab should not be selected",
            currentDestHome?.hierarchy?.any { it.hasRoute(mediaTab.route::class) } == true
        )

        val showBarOnHome = items.any { item ->
            currentDestHome?.hierarchy?.any { it.hasRoute(item.route::class) } == true
        }
        assertTrue("showBottomBar should be true on Home", showBarOnHome)

        // Navigate to MediaRoute
        navController.navigate(MediaRoute)
        val currentDestMedia = navController.currentBackStackEntry?.destination
        assertTrue(
            "Current destination should match mediaTab",
            currentDestMedia?.hierarchy?.any { it.hasRoute(mediaTab.route::class) } == true
        )
        assertFalse(
            "homeTab should not be selected on MediaRoute",
            currentDestMedia?.hierarchy?.any { it.hasRoute(homeTab.route::class) } == true
        )

        val showBarOnMedia = items.any { item ->
            currentDestMedia?.hierarchy?.any { it.hasRoute(item.route::class) } == true
        }
        assertTrue("showBottomBar should be true on Media", showBarOnMedia)

        // Navigate to MovieRoute (detail screen)
        navController.navigate(MovieRoute("12345"))
        val currentDestMovie = navController.currentBackStackEntry?.destination
        val showBarOnMovie = items.any { item ->
            currentDestMovie?.hierarchy?.any { it.hasRoute(item.route::class) } == true
        }
        assertFalse("showBottomBar should be false on MovieRoute", showBarOnMovie)
        assertFalse(
            "homeTab should not be selected on MovieRoute",
            currentDestMovie?.hierarchy?.any { it.hasRoute(homeTab.route::class) } == true
        )
        assertFalse(
            "mediaTab should not be selected on MovieRoute",
            currentDestMovie?.hierarchy?.any { it.hasRoute(mediaTab.route::class) } == true
        )
    }
}
