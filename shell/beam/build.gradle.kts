plugins {
    alias(libs.plugins.android.library)
    id("spatialfin.android.library")
    id("spatialfin.android.compose")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.compose.stability.analyzer)
}

android {
    namespace = "dev.spatialfin.beam"
}

dependencies {
    // Feature + foundation modules the Beam (phone) shell hosts.
    implementation(projects.core)
    implementation(projects.core.ui)
    implementation(projects.data)
    implementation(projects.settings)
    implementation(projects.setup)
    implementation(projects.modes.film)
    implementation(projects.modes.music)
    implementation(projects.modes.audio)
    implementation(projects.plugins)
    implementation(projects.sendspin)
    implementation(projects.fcast)
    // FCast session/cast UI (CastSessionManager, LocalFCastSession, pickers) —
    // the Beam nav root's cast button + session surfaces resolve from here.
    implementation(projects.fcast.sessionUi)
    // player:beam api-exposes :player:xr (single dex-merge path) — that's how the
    // Beam nav root reaches the moved HomeVoiceController + player.xr.voice types.
    implementation(projects.player.beam)
    implementation(libs.timber)

    implementation(libs.androidx.core)
    implementation(libs.androidx.work)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)
    implementation(libs.androidx.compose.material.icons.extended)

    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.window)

    // Companion QR scanning (CameraX + MLKit, same as :setup).
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode.scanning)

    implementation(libs.coil.compose)
    implementation(libs.okhttp)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.jellyfin.core)
    implementation(libs.kotlinx.collections.immutable)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit4)
}
