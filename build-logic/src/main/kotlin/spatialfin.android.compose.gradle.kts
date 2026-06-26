import com.android.build.api.dsl.LibraryExtension
import org.jetbrains.kotlin.compose.compiler.gradle.ComposeCompilerGradlePluginExtension

// Compose configuration shared by every Compose-bearing SpatialFin module.
//
// Apply alongside spatialfin.android.library:
//
//     plugins {
//         alias(libs.plugins.android.library)
//         id("spatialfin.android.library")
//         id("spatialfin.android.compose")
//     }
//
// It applies the Kotlin Compose compiler plugin, turns on android
// buildFeatures.compose, and points the compiler reports/metrics at the same
// build directory every module used by hand. The compose-stability-analyzer
// plugin and the actual Compose dependencies stay in the module scripts (the
// former is a JitPack artifact and the catalog isn't reachable here).

plugins {
    id("org.jetbrains.kotlin.plugin.compose")
}

pluginManager.withPlugin("com.android.library") {
    extensions.configure<LibraryExtension> {
        buildFeatures { compose = true }
    }
}

extensions.configure<ComposeCompilerGradlePluginExtension> {
    reportsDestination.set(layout.buildDirectory.dir("compose_compiler"))
    metricsDestination.set(layout.buildDirectory.dir("compose_compiler"))
}
