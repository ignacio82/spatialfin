plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.stability.analyzer)
}

android {
    namespace = "dev.jdtech.jellyfin.fcast"
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

    testOptions { unitTests.isIncludeAndroidResources = false }
}

composeCompiler {
    reportsDestination.set(layout.buildDirectory.dir("compose_compiler"))
    metricsDestination.set(layout.buildDirectory.dir("compose_compiler"))
}

dependencies {
    implementation(libs.androidx.core)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.ui)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.jmdns)
    implementation(libs.okhttp)
    implementation(libs.timber)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
}
