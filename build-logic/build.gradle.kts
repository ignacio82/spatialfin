plugins {
    `kotlin-dsl`
}

repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    // AGP API so the convention plugins can configure the android {} extension.
    // Keep in sync with `android-plugin` in gradle/libs.versions.toml.
    //
    // This lives in an *included build* (not buildSrc) on purpose: buildSrc
    // implementation deps leak onto every project's buildscript classpath and
    // collide with the versioned `alias(libs.plugins.android.*)` applications in
    // the root/module scripts. An included build keeps these deps isolated.
    implementation("com.android.tools.build:gradle:9.2.1")

    // Kotlin Compose compiler Gradle plugin, so spatialfin.android.compose can
    // apply `org.jetbrains.kotlin.plugin.compose` and configure composeCompiler {}.
    // Keep in sync with `kotlin` in gradle/libs.versions.toml.
    implementation("org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.3.21")
}
