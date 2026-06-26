plugins {
    alias(libs.plugins.android.library)
    id("spatialfin.android.library")
    id("spatialfin.android.compose")
    alias(libs.plugins.ksp)
    alias(libs.plugins.compose.stability.analyzer)
}

android {
    namespace = "dev.jdtech.jellyfin.film"
}

dependencies {
    implementation(projects.core)
    implementation(projects.core.ui)
    implementation(projects.data)
    implementation(projects.sendspin)
    implementation(projects.settings)
    implementation(projects.setup)
    implementation(projects.player.core)
    implementation(projects.plugins)
    implementation(libs.timber)

    implementation(libs.androidx.core)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.paging)
    implementation(libs.androidx.paging.compose)
    implementation(libs.androidx.window)
    implementation(libs.androidx.xr.compose)
    implementation(libs.coil.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.jellyfin.core)
    implementation(libs.kotlinx.collections.immutable)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)
    testImplementation(libs.turbine)
}
