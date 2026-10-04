package dev.spatialfin

import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy

internal fun NavDestination?.shouldShowNavigationRail(
    navigationItems: List<TabBarItem>,
    searchExpanded: Boolean,
): Boolean {
    // Search remains expanded when opening a result so Back can restore it.
    // Only hide navigation while that search screen is actually being shown.
    val isSearchVisible = searchExpanded && this?.hasRoute(MediaRoute::class) == true
    return navigationItems.any { item ->
        this?.hierarchy?.any { it.hasRoute(item.route::class) } == true
    } && !isSearchVisible
}
