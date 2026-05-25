plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose.compiler)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.compose.stability.analyzer)
}

android {
    namespace = "dev.jdtech.jellyfin.player.xr"
    compileSdk = Versions.COMPILE_SDK
    buildToolsVersion = Versions.BUILD_TOOLS

    defaultConfig { minSdk = Versions.MIN_SDK }

    buildTypes {
        named("release") { isMinifyEnabled = false }
        register("staging") { initWith(getByName("release")) }
    }

    compileOptions {
        sourceCompatibility = Versions.JAVA
        targetCompatibility = Versions.JAVA
    }

    buildFeatures { compose = true }

    testOptions { unitTests.isIncludeAndroidResources = true }
}

composeCompiler {
    reportsDestination.set(layout.buildDirectory.dir("compose_compiler"))
    metricsDestination.set(layout.buildDirectory.dir("compose_compiler"))
}

dependencies {
    implementation(libs.jellyfin.core)
    implementation(projects.core)
    implementation(projects.player.core)
    implementation(projects.player.local)
    implementation(projects.player.session)
    implementation(projects.data)
    implementation(projects.settings)
    implementation(projects.fcast)

    // Android core
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel)

    // Compose
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.kotlinx.collections.immutable)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.ui)

    // Media3
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.session)

    // XR SDK
    implementation(libs.androidx.xr.runtime)
    implementation(libs.androidx.xr.scenecore)
    implementation(libs.androidx.xr.compose)
    implementation(libs.androidx.xr.arcore)

    // Image loading (for next-episode artwork in the next-up panel)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Utils
    implementation(libs.litertlm.android)
    implementation(libs.mlkit.genai.prompt)
    implementation(libs.okhttp)
    implementation(libs.timber)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)
    testImplementation(libs.turbine)
}
