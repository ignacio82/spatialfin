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

pluginManagement {
    includeBuild("build-logic")
    repositories {
        mavenCentral()
        gradlePluginPortal()
        google()
        maven { url = uri("https://jitpack.io") }
    }
}
