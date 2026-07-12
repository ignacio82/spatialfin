plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.androidx.baselineprofile) apply false
    alias(libs.plugins.version.catalog.update)
    alias(libs.plugins.kotlin.parcelize) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.aboutlibraries) apply false
    alias(libs.plugins.aboutlibraries.android) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.ktfmt) apply false
    alias(libs.plugins.compose.stability.analyzer) apply false
}

allprojects {
    repositories {
        maven {
            url = uri("https://storage.googleapis.com/r8-releases/raw")
            content { includeModule("com.android.tools", "r8") }
        }
        maven {
            url = uri("${rootDir}/third_party/maven")
            content {
                includeModule("com.sleepycat", "je")
                includeModule("org.dcache", "nfs4j")
                includeModule("org.dcache", "nfs4j-basic-client")
                includeModule("org.dcache", "nfs4j-core")
                includeModule("org.dcache", "oncrpc4j")
                includeModule("org.dcache", "oncrpc4j-core")
            }
        }
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
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
