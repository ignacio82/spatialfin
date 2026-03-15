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
    }

    signingConfigs {
        create("release") {
            storeFile =
                (project.findProperty("SPATIALFIN_KEYSTORE") as String? ?: localProperties.getProperty("SPATIALFIN_KEYSTORE"))?.let { file(it) }
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
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
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
            applicationIdSuffix = ".staging"
        }
    }

    flavorDimensions += "variant"
    productFlavors {
        create("libre") {
            dimension = "variant"
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
            include("arm64-v8a")
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
    implementation(project(":player:xr"))

    implementation(libs.androidx.core)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.window)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.work)
    implementation(libs.androidx.hilt.work)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)
    implementation(libs.androidx.xr.runtime)
    implementation(libs.androidx.xr.scenecore)
    implementation(libs.androidx.xr.compose)
    implementation(libs.androidx.xr.compose.material3)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.paging.compose)
    implementation(libs.jellyfin.core)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.aboutlibraries.compose.m3)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.coil.network.cache.control)
    implementation(libs.coil.svg)
    implementation(libs.timber)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
