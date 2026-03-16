enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "SpatialFin"

include(":app:xr")
include(":core")
include(":data")
include(":player:core")
include(":player:local")
include(":player:session")
include(":player:xr")
include(":setup")
include(":modes:film")
include(":settings")

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}
