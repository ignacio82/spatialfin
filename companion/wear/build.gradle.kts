import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.compose.stability.analyzer)
}

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localPropertiesFile.inputStream().use { localProperties.load(it) }
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

    // Resolution order must match :app:unified exactly. The Wearable Data Layer only
    // links apps sharing an applicationId *and* a signing certificate, so if this
    // module found the keystore by a different route than the app does, the two
    // bundles would sign with different certs and pairing would fail silently.
    val customStorePath =
        (project.findProperty("SPATIALFIN_KEYSTORE") as String?
            ?: localProperties.getProperty("SPATIALFIN_KEYSTORE"))
            ?: System.getenv("SPATIALFIN_KEYSTORE")
    val customStore = customStorePath?.let { file(it) }
    val hasCustomStore = customStore?.exists() == true

    signingConfigs {
        if (hasCustomStore) {
            create("release") {
                storeFile = customStore
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
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (hasCustomStore) {
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

    // OkHttp & Serialization
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.timber)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
}
