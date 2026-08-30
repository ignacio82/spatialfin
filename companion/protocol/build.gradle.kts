plugins {
    alias(libs.plugins.kotlin.serialization)
    kotlin("jvm")
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit4)
}
