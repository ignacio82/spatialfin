plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.compose.stability.analyzer)
}

android {
    namespace = "dev.spatialfin.companion.wear"
    compileSdk = Versions.COMPILE_SDK
    buildToolsVersion = Versions.BUILD_TOOLS

    defaultConfig {
        applicationId = "dev.spatialfin"
        minSdk = 33
        targetSdk = Versions.TARGET_SDK
        versionCode = Versions.APP_CODE + 2_000_000
        versionName = Versions.APP_NAME

        missingDimensionStrategy("variant", "libre")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Resolution order must match :app:unified via SpatialfinSigning.kt. The Wearable
    // Data Layer only links apps sharing an applicationId *and* a signing certificate.
    val releaseSigning = resolveSpatialfinReleaseSigning(project)

    signingConfigs {
        if (releaseSigning != null) {
            create("release") {
                storeFile = releaseSigning.storeFile
                storePassword = releaseSigning.storePassword
                keyAlias = releaseSigning.keyAlias
                keyPassword = releaseSigning.keyPassword
            }
        }
    }

    buildTypes {
        debug { applicationIdSuffix = ".debug" }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig =
                if (releaseSigning != null) {
                    signingConfigs.getByName("release")
                } else {
                    signingConfigs.getByName("debug")
                }
        }
        register("staging") {
            initWith(getByName("release"))
            applicationIdSuffix = ".staging"
        }
    }

    compileOptions {
        sourceCompatibility = Versions.JAVA
        targetCompatibility = Versions.JAVA
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

composeCompiler {
    reportsDestination.set(layout.buildDirectory.dir("compose_compiler"))
    metricsDestination.set(layout.buildDirectory.dir("compose_compiler"))
}

dependencies {
    implementation(projects.companion.protocol)
    implementation(projects.fcast)

    // Wear Compose
    implementation(libs.androidx.wear.compose.material3)
    implementation(libs.androidx.wear.compose.foundation)
    implementation(libs.androidx.wear.compose.navigation)

    // Wearable Data Layer
    implementation(libs.play.services.wearable)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.kotlinx.coroutines.guava)

    // Wear OS Tiles & ProtoLayout
    implementation(libs.androidx.protolayout)
    implementation(libs.androidx.protolayout.material3)
    implementation(libs.androidx.tiles)

    // Complications & Ongoing Activities
    implementation(libs.androidx.watchface.complications.data.source.ktx)
    implementation(libs.androidx.wear.ongoing)
    implementation(libs.androidx.wear)

    // Media3, for Split-A/V audio rendering on the watch. No MediaSession: the sink is
    // driven by FCast ingress, not by a system transport control surface.
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)

    // Poster art for Continue Watching. The now-playing hero still arrives as a
    // Data Layer Asset (the watch cannot resolve a Jellyfin image URL on the
    // Bluetooth-only path); Next Up carries a real primaryImageUrl, which is
    // loadable whenever the watch has its own route to the server.
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // OkHttp & Serialization
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.timber)

    testImplementation(libs.junit4)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
}
