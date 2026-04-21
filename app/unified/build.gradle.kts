import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.aboutlibraries.android)
}

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localPropertiesFile.inputStream().use { localProperties.load(it) }
}

base.archivesName = "spatialfin"

android {
    namespace = "dev.spatialfin"
    compileSdk = Versions.COMPILE_SDK
    buildToolsVersion = Versions.BUILD_TOOLS

    defaultConfig {
        applicationId = "dev.spatialfin"
        minSdk = Versions.MIN_SDK
        targetSdk = Versions.TARGET_SDK
        versionCode = Versions.APP_CODE
        versionName = Versions.APP_NAME
        val xrSpatialFeatureRequired =
            (project.findProperty("XR_SPATIAL_FEATURE_REQUIRED") as String?) ?: "false"
        manifestPlaceholders["xrSpatialFeatureRequired"] = xrSpatialFeatureRequired
        // Default for the universal (phone / XR / Beam Pro) bundle — the `tv`
        // flavor overrides this to "true" because Google Play's Android TV
        // track rejects bundles where android.software.leanback is optional.
        manifestPlaceholders["leanbackRequired"] = "false"
    }

    signingConfigs {
        create("release") {
            storeFile =
                (project.findProperty("SPATIALFIN_KEYSTORE") as String?
                    ?: localProperties.getProperty("SPATIALFIN_KEYSTORE"))?.let { file(it) }
                    ?: System.getenv("SPATIALFIN_KEYSTORE")?.let { file(it) }
            storePassword =
                project.findProperty("SPATIALFIN_KEYSTORE_PASSWORD") as String?
                    ?: localProperties.getProperty("SPATIALFIN_KEYSTORE_PASSWORD")
                    ?: System.getenv("SPATIALFIN_KEYSTORE_PASSWORD")
            keyAlias =
                project.findProperty("SPATIALFIN_KEY_ALIAS") as String?
                    ?: localProperties.getProperty("SPATIALFIN_KEY_ALIAS")
                    ?: System.getenv("SPATIALFIN_KEY_ALIAS")
            keyPassword =
                project.findProperty("SPATIALFIN_KEY_PASSWORD") as String?
                    ?: localProperties.getProperty("SPATIALFIN_KEY_PASSWORD")
                    ?: System.getenv("SPATIALFIN_KEY_PASSWORD")
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
            // TV global-search provider authority. Must stay in sync with the
            // applicationId — two installs of the app can't share a provider
            // authority, so debug/staging/release get their own.
            resValue("string", "search_authority", "dev.spatialfin.debug.search")
        }
        release {
            // XR system-extension callbacks are still crashing in optimized builds with
            // AbstractMethodError inside com.android.extensions.xr.Consumer bridges.
            // Keep release unminified until the androidx.xr / R8 interaction is resolved.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig =
                if (signingConfigs.getByName("release").storeFile?.exists() == true) {
                    signingConfigs.getByName("release")
                } else {
                    null
                }
            resValue("string", "search_authority", "dev.spatialfin.search")
        }
        create("staging") {
            initWith(getByName("release"))
            applicationIdSuffix = ".staging"
            resValue("string", "search_authority", "dev.spatialfin.staging.search")
        }
    }

    flavorDimensions += "variant"
    productFlavors {
        create("libre") {
            dimension = "variant"
        }
        // TV-only variant for Google Play's Android TV track. Google TV
        // Streamer and other Leanback devices install this bundle; XR and
        // Beam Pro continue to receive the `libre` bundle unchanged.
        create("tv") {
            dimension = "variant"
            // Upstream library modules only publish a `libre` flavor, so resolve
            // their artifacts against that one when building the TV variant.
            matchingFallbacks += "libre"
            manifestPlaceholders["leanbackRequired"] = "true"
            // Offset keeps the TV bundle's versionCode disjoint from the
            // libre bundle's so both can coexist on one Play listing.
            versionCode = Versions.APP_CODE + 1_000_000
            versionNameSuffix = "-tv"
        }
    }

    splits {
        abi {
            // Disabled when building bundles due to AGP 8.9.0 bug:
            // https://issuetracker.google.com/issues/402800800
            val isBuildingBundle =
                gradle.startParameter.taskNames.any { it.lowercase().contains("bundle") }
            isEnable = !isBuildingBundle
            reset()
            include("arm64-v8a", "armeabi-v7a")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
        // Required by the `resValue(...)` calls in buildTypes that generate
        // `R.string.search_authority` per applicationId for the TV global-
        // search provider.
        resValues = true
    }

    testOptions { unitTests.isIncludeAndroidResources = true }

    compileOptions {
        sourceCompatibility = Versions.JAVA
        targetCompatibility = Versions.JAVA
    }

}

dependencies {
    // Shared modules
    implementation(project(":core"))
    implementation(project(":data"))
    implementation(project(":settings"))
    implementation(project(":setup"))
    implementation(project(":modes:film"))
    implementation(project(":player:core"))
    implementation(project(":player:local"))
    implementation(project(":player:session"))

    // All three player implementations
    // player:xr is exposed transitively via player:beam's api() dep (avoids a dex-merge
    // duplicate for LibassRenderer and keeps jniLibs flowing through one path).
    implementation(project(":player:tv"))
    implementation(project(":player:beam"))

    // AndroidX core
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.core)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.window)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.work)
    implementation(libs.androidx.hilt.work)

    // Compose
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)

    // TV material
    implementation(libs.androidx.tv.foundation)
    implementation(libs.androidx.tv.material)

    // XR
    implementation(libs.androidx.xr.runtime)
    implementation(libs.androidx.xr.scenecore)
    implementation(libs.androidx.xr.compose)
    implementation(libs.androidx.xr.compose.material3)

    // Camera
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // Barcode scanning (MLKit for XR/Phone, ZXing for TV)
    implementation(libs.mlkit.barcode.scanning)
    implementation("com.google.zxing:core:3.5.3")

    // Navigation & paging
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.paging.compose)
    implementation(libs.androidx.media3.ui)
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")

    // Backend
    implementation(libs.jellyfin.core)
    implementation(libs.kotlinx.serialization.json)

    // DI
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    ksp(libs.androidx.hilt.compiler)

    // Image loading & UI
    implementation(libs.aboutlibraries.compose.m3)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.coil.network.cache.control)
    implementation(libs.coil.svg)
    implementation(libs.timber)

    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)
    testImplementation(libs.turbine)
}

