import com.android.build.api.dsl.LibraryExtension

// Shared Android-library configuration for every SpatialFin library module.
//
// Apply it *alongside* the AGP library plugin in a module's plugins {} block:
//
//     plugins {
//         alias(libs.plugins.android.library)
//         id("spatialfin.android.library")
//         // ...module-specific plugins (ksp, hilt, compose, ...)
//     }
//
// It fills in only the android {} block that was previously copy-pasted into
// every module (compileSdk / buildTools / minSdk / buildTypes / compileOptions),
// so each module's build script keeps just its namespace + dependencies and any
// genuinely module-specific config (flavors, buildFeatures, testOptions).
//
// Dependencies stay in the module scripts because the typesafe `libs` catalog
// accessor is not available inside precompiled convention plugins.

pluginManager.withPlugin("com.android.library") {
    extensions.configure<LibraryExtension> {
        compileSdk = SpatialfinSdk.COMPILE_SDK
        buildToolsVersion = SpatialfinSdk.BUILD_TOOLS

        defaultConfig {
            minSdk = SpatialfinSdk.MIN_SDK
        }

        buildTypes {
            named("release") { isMinifyEnabled = false }
            register("staging") { initWith(getByName("release")) }
        }

        compileOptions {
            sourceCompatibility = SpatialfinSdk.JAVA
            targetCompatibility = SpatialfinSdk.JAVA
        }
    }
}
