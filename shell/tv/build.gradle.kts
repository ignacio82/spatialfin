plugins {
    alias(libs.plugins.android.library)
    id("spatialfin.android.library")
    id("spatialfin.android.compose")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.compose.stability.analyzer)
}

android {
    namespace = "dev.spatialfin.tv"
}

dependencies {
    // Feature + foundation modules the TV shell hosts.
    implementation(projects.core)
    implementation(projects.core.ui)
    implementation(projects.data)
    implementation(projects.settings)
    implementation(projects.setup)
    implementation(projects.modes.film)
    implementation(projects.modes.music)
    implementation(projects.modes.audio)
    implementation(projects.player.tv)
    implementation(projects.plugins)
    implementation(projects.sendspin)
    implementation(libs.timber)

    implementation(libs.androidx.core)
    implementation(libs.androidx.work)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)
    implementation(libs.androidx.compose.material.icons.extended)

    // TV Material (Leanback design) — TvTheme / TV components.
    implementation(libs.androidx.tv.foundation)
    implementation(libs.androidx.tv.material)

    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.window)

    implementation(libs.coil.compose)
    // ZXing renders the TV companion-pairing QR (no MLKit on Leanback).
    implementation("com.google.zxing:core:3.5.3")

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.jellyfin.core)
    implementation(libs.kotlinx.collections.immutable)
    implementation(libs.kotlinx.serialization.json)
}
