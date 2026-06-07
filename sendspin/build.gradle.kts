plugins { alias(libs.plugins.android.library) }

android {
    namespace = "dev.jdtech.jellyfin.sendspin"
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
}

dependencies {
    implementation(project(":fcast")) // Depends on fcast for the cast/ abstraction layer
    implementation(libs.androidx.core)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.jmdns)
    implementation(libs.java.websocket)
    implementation(libs.moshi.kotlin)
    implementation(libs.okhttp)
    implementation(libs.org.json)
    implementation(libs.sendspin.jvm)
    implementation(libs.timber)
}
