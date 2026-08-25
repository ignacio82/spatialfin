plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "dev.jdtech.jellyfin.player.core"
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
    implementation(projects.data)
    implementation(projects.settings)
    implementation(projects.fcast)
    implementation(libs.timber)

    // Media3
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.dash)
    implementation(libs.androidx.media3.exoplayer.hls)
    // Jellyfin's FFmpeg audio-decoder extension. It lives here, not in :player:beam, because
    // this is where the capability probe (FfmpegAudioDecoders) lives — and because it was never
    // beam-only in practice: :player:tv and :app:unified both depend on :player:beam, so the
    // native library and FfmpegAudioRenderer were already packaged into every APK and picked up
    // reflectively by every EXTENSION_RENDERER_MODE_ON renderers factory.
    implementation(libs.media3.ffmpeg.decoder)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
}

dependencies { testImplementation("junit:junit:4.13.2") }
