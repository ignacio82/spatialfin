import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.aboutlibraries.android)
    alias(libs.plugins.compose.stability.analyzer)
    alias(libs.plugins.androidx.baselineprofile)
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
            val customStore =
                (project.findProperty("SPATIALFIN_KEYSTORE") as String?
                        ?: localProperties.getProperty("SPATIALFIN_KEYSTORE"))
                    ?.let { file(it) } ?: System.getenv("SPATIALFIN_KEYSTORE")?.let { file(it) }
            val debugStore = file("${System.getProperty("user.home")}/.android/debug.keystore")
            
            storeFile = if (customStore?.exists() == true) customStore else debugStore
            storePassword =
                project.findProperty("SPATIALFIN_KEYSTORE_PASSWORD") as String?
                    ?: localProperties.getProperty("SPATIALFIN_KEYSTORE_PASSWORD")
                    ?: System.getenv("SPATIALFIN_KEYSTORE_PASSWORD")
                    ?: "android"
            keyAlias =
                project.findProperty("SPATIALFIN_KEY_ALIAS") as String?
                    ?: localProperties.getProperty("SPATIALFIN_KEY_ALIAS")
                    ?: System.getenv("SPATIALFIN_KEY_ALIAS")
                    ?: "androiddebugkey"
            keyPassword =
                project.findProperty("SPATIALFIN_KEY_PASSWORD") as String?
                    ?: localProperties.getProperty("SPATIALFIN_KEY_PASSWORD")
                    ?: System.getenv("SPATIALFIN_KEY_PASSWORD")
                    ?: "android"
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
            // Optimized builds are validated through the release-derived staging variant on
            // Galaxy XR. Keep the XR bridge rules below until that smoke test is retired.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("release")
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
        create("libre") { dimension = "variant" }
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

    lint {
        // Gate CI on *new* lint errors. Pre-existing issues are captured in the
        // committed baseline so the gate lands without a cleanup prerequisite;
        // burn the baseline down over time. Warnings stay non-fatal.
        baseline = file("lint-baseline.xml")
        abortOnError = true
        warningsAsErrors = false
        // Release is optimized, so keep release-specific checks enabled.
        checkReleaseBuilds = true
    }
}

composeCompiler {
    reportsDestination.set(layout.buildDirectory.dir("compose_compiler"))
    metricsDestination.set(layout.buildDirectory.dir("compose_compiler"))
}

baselineProfile {
    mergeIntoMain = true
    saveInSrc = true
    automaticGenerationDuringBuild = false
}

dependencies {
    // Shared modules
    implementation(project(":core"))
    implementation(project(":core:ui"))
    implementation(project(":data"))
    implementation(project(":fcast"))
    implementation(project(":fcast:session-ui"))
    implementation(project(":settings"))
    implementation(project(":setup"))
    implementation(project(":plugins"))
    implementation(project(":sendspin"))
    implementation(project(":modes:film"))
    implementation(project(":modes:music"))
    implementation(project(":modes:audio"))
    implementation(project(":shell:tv"))
    implementation(project(":shell:beam"))
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
    implementation(libs.androidx.profileinstaller)

    // Compose
    implementation(libs.androidx.compose.foundation)
    implementation(libs.kotlinx.collections.immutable)
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
    // Required on R8's analysis classpath for Jetpack XR alpha05+; the device supplies it.
    compileOnly(libs.android.extensions.xr)

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
    implementation(libs.androidx.media3.exoplayer)
    // HLS source for the FCast inbound receiver: split-A/V transcodes the audio to an
    // HLS (master.m3u8 + TS) stream when the receiver chain can't render the source codec
    // (e.g. TrueHD on a DD+ soundbar). Without this, DefaultMediaSourceFactory can't build
    // an HlsMediaSource and falls back to ProgressiveMediaSource → the .m3u8 hits
    // UnrecognizedInputFormatException → groups=0 → silent. (player/local already has it.)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.exoplayer.dash)
    implementation(libs.androidx.media3.ui)
    // System MediaSession for Music Assistant playback — lock-screen / shade
    // notification + Bluetooth/headset transport, bridged to MA via a
    // SimpleBasePlayer (MaMediaPlayer) hosted by MaMediaSessionService.
    implementation(libs.androidx.media3.session)
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
    baselineProfile(project(":baselineprofile"))

    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)
    testImplementation(libs.turbine)
}
