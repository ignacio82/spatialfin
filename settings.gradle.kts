enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "SpatialFin"

include(":app:unified")
include(":core")
include(":core:ui")
include(":data")
include(":fcast")
include(":fcast:session-ui")
include(":player:core")
include(":player:local")
include(":player:session")
include(":player:xr")
include(":player:beam")
include(":player:tv")
include(":setup")
include(":modes:film")
include(":modes:music")
include(":modes:audio")
include(":shell:tv")
include(":shell:beam")
include(":settings")
include(":baselineprofile")
include(":plugins")
include(":sendspin")
include(":companion:protocol")
include(":companion:host")
include(":companion:wear")

pluginManagement {
    // Keep the compiler and keep-annotation API on the same version. The app uses one precise
    // NEVER_INLINE edge for a Java-WebSocket monitor-region verifier bug on Android XR.
    // NOTE: this version is duplicated as `r8` in gradle/libs.versions.toml and Gradle cannot
    // resolve a catalog reference this early in settings evaluation. `versionCatalogUpdate`
    // only bumps the catalog, so update BOTH or the shrinker and the keep-annotation API drift.
    buildscript {
        repositories {
            maven { url = uri("https://storage.googleapis.com/r8-releases/raw") }
        }
        dependencies {
            classpath("com.android.tools:r8:9.4.14")
        }
    }

    includeBuild("build-logic")
    repositories {
        mavenCentral()
        gradlePluginPortal()
        google()
        maven { url = uri("https://jitpack.io") }
    }
}
