enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "SpatialFin"

include(":app:xr")
include(":app:beam")
include(":app:tv")
include(":core")
include(":data")
include(":player:core")
include(":player:local")
include(":player:session")
include(":player:xr")
include(":player:beam")
include(":player:tv")
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
