import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose.compiler)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localPropertiesFile.inputStream().use { localProperties.load(it) }
}

base.archivesName = "fin-player-tv"

android {
    namespace = "dev.spatialfin.tv"
    compileSdk = Versions.COMPILE_SDK
    buildToolsVersion = Versions.BUILD_TOOLS

    defaultConfig {
        applicationId = "dev.spatialfin.player"
        minSdk = Versions.MIN_SDK
        targetSdk = Versions.TARGET_SDK
        versionCode = Versions.APP_CODE + 1000
        versionName = Versions.APP_NAME
    }

    signingConfigs {
        create("release") {
            storeFile =
                (
                    project.findProperty("FIN_PLAYER_KEYSTORE") as String?
                        ?: localProperties.getProperty("FIN_PLAYER_KEYSTORE")
                        ?: project.findProperty("SPATIALFIN_KEYSTORE") as String?
                        ?: localProperties.getProperty("SPATIALFIN_KEYSTORE")
                )?.let { file(it) }
                    ?: System.getenv("FIN_PLAYER_KEYSTORE")?.let { file(it) }
                    ?: System.getenv("SPATIALFIN_KEYSTORE")?.let { file(it) }
            storePassword =
                project.findProperty("FIN_PLAYER_KEYSTORE_PASSWORD") as String?
                    ?: localProperties.getProperty("FIN_PLAYER_KEYSTORE_PASSWORD")
                    ?: project.findProperty("SPATIALFIN_KEYSTORE_PASSWORD") as String?
                    ?: localProperties.getProperty("SPATIALFIN_KEYSTORE_PASSWORD")
                    ?: System.getenv("FIN_PLAYER_KEYSTORE_PASSWORD")
                    ?: System.getenv("SPATIALFIN_KEYSTORE_PASSWORD")
            keyAlias =
                project.findProperty("FIN_PLAYER_KEY_ALIAS") as String?
                    ?: localProperties.getProperty("FIN_PLAYER_KEY_ALIAS")
                    ?: project.findProperty("SPATIALFIN_KEY_ALIAS") as String?
                    ?: localProperties.getProperty("SPATIALFIN_KEY_ALIAS")
                    ?: System.getenv("FIN_PLAYER_KEY_ALIAS")
                    ?: System.getenv("SPATIALFIN_KEY_ALIAS")
            keyPassword =
                project.findProperty("FIN_PLAYER_KEY_PASSWORD") as String?
                    ?: localProperties.getProperty("FIN_PLAYER_KEY_PASSWORD")
                    ?: project.findProperty("SPATIALFIN_KEY_PASSWORD") as String?
                    ?: localProperties.getProperty("SPATIALFIN_KEY_PASSWORD")
                    ?: System.getenv("FIN_PLAYER_KEY_PASSWORD")
                    ?: System.getenv("SPATIALFIN_KEY_PASSWORD")
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".tv.debug"
            isDebuggable = true
        }
        release {
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
        }
        create("staging") {
            initWith(getByName("release"))
            applicationIdSuffix = ".tv.staging"
        }
    }

    flavorDimensions += "variant"
    productFlavors {
        create("libre") {
            dimension = "variant"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = Versions.JAVA
        targetCompatibility = Versions.JAVA
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":data"))
    implementation(project(":settings"))
    implementation(project(":setup"))
    implementation(project(":modes:film"))
    implementation(project(":player:core"))
    implementation(project(":player:local"))
    implementation(project(":player:session"))
    implementation(project(":player:tv"))

    implementation(libs.androidx.core)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.work)
    implementation(libs.androidx.hilt.work)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.tv.material)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.paging.compose)
    implementation(libs.jellyfin.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.hilt.android)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.coil.network.cache.control)
    implementation(libs.coil.svg)
    implementation(libs.timber)
    implementation("com.google.zxing:core:3.5.3")

    ksp(libs.hilt.compiler)
    ksp(libs.androidx.hilt.compiler)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
