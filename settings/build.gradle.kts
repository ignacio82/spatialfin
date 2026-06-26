plugins {
    alias(libs.plugins.android.library)
    id("spatialfin.android.library")
    id("spatialfin.android.compose")
    alias(libs.plugins.ksp)
}

android {
    namespace = "dev.jdtech.jellyfin.settings"
}

dependencies {
    implementation(libs.timber)

    implementation(libs.androidx.core)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.jellyfin.core)
    implementation(libs.okhttp)

    testImplementation(libs.junit4)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
}
