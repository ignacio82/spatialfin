plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.version.catalog.update)
    alias(libs.plugins.kotlin.parcelize) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.aboutlibraries) apply false
    alias(libs.plugins.aboutlibraries.android) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.ktfmt) apply false
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://download.dcache.org/nexus/repository/public/") }
    }
}

subprojects {
    apply(plugin = "com.ncorti.ktfmt.gradle")

    configure<com.ncorti.ktfmt.gradle.KtfmtExtension> {
        kotlinLangStyle()
    }
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}