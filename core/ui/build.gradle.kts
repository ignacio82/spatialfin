plugins {
    alias(libs.plugins.android.library)
    id("spatialfin.android.library")
    id("spatialfin.android.compose")
}

android {
    namespace = "dev.spatialfin.core.ui"
}

dependencies {
    // Shared UI foundation: SpatialFin theme/spacings/shapes, the cross-feature
    // Compose components (ErrorDialog, BaseDialog, XrConfirmDialog,
    // FinishSetupCard), and the presentation utils (safe padding, parallax,
    // offline-mode CompositionLocal, adaptive grid cells). Depends on :core for
    // CoreR and the base color/spacing tokens it builds on.
    implementation(projects.core)

    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.window)

    // XR spatial dialog + spatial theme surfaces used by the shared dialogs/theme.
    implementation(libs.androidx.xr.compose)
    implementation(libs.androidx.xr.compose.material3)

    implementation(libs.timber)
}
