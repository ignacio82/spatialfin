plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.ksp)
}

android {
    namespace = "dev.jdtech.jellyfin.data"
    compileSdk = Versions.COMPILE_SDK
    buildToolsVersion = Versions.BUILD_TOOLS

    defaultConfig {
        minSdk = Versions.MIN_SDK

        buildConfigField("int", "VERSION_CODE", Versions.APP_CODE.toString())
        buildConfigField("String", "VERSION_NAME", "\"${Versions.APP_NAME}\"")

        consumerProguardFile("proguard-rules.pro")

        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
            arg("room.generateKotlin", "true")
        }
    }

    buildTypes {
        named("release") { isMinifyEnabled = false }
        register("staging") { initWith(getByName("release")) }
    }

    compileOptions {
        sourceCompatibility = Versions.JAVA
        targetCompatibility = Versions.JAVA
    }

    buildFeatures { buildConfig = true }

    testOptions {
        // Robolectric shadows read real Android resources; include them so robolectric.properties
        // / AndroidManifest stubs resolve during off-device JVM tests.
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(projects.settings)
    implementation(libs.androidx.paging)
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    implementation(libs.jellyfin.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.smbj)
    implementation(libs.jcifs.ng) {
        exclude(group = "org.bouncycastle", module = "bcprov-jdk15on")
    }
    implementation(libs.bcprov.jdk18on)
    implementation(libs.nfs4j)
    implementation(libs.jmdns)
    implementation(libs.timber)
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)
    testImplementation(libs.turbine)
}
