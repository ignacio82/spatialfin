plugins {
    alias(libs.plugins.android.library)
    id("spatialfin.android.library")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "dev.spatialfin.companion.host"

    flavorDimensions += "variant"
    productFlavors { register("libre") }
}

dependencies {
    implementation(projects.companion.protocol)
    implementation(projects.core)
    implementation(projects.player.session)
    implementation(projects.player.core)
    implementation(projects.core.ui)
    implementation(projects.data)
    implementation(projects.fcast)
    implementation(projects.settings)

    implementation(libs.play.services.wearable)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.timber)

    testImplementation(libs.junit4)
}
