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
    // Google libwebrtc prebuilt (org.webrtc.*) for off-LAN Music Assistant remote
    // access: the SendSpin protocol is tunnelled over a WebRTC data channel via an
    // in-process loopback relay (see receiver/remote/). 16 KB page-size aligned.
    implementation(libs.stream.webrtc.android)
    implementation(libs.timber)

    testImplementation(libs.junit4)
}
