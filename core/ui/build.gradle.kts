plugins {
    alias(libs.plugins.android.library)
    id("spatialfin.android.library")
    id("spatialfin.android.compose")
}

android {
    namespace = "dev.spatialfin.core.ui"

    // Compose UI tests run on the JVM under Robolectric, so they gate in CI
    // without a device; that needs the android resources on the test classpath.
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    // Shared UI foundation: SpatialFin theme/spacings/shapes, the cross-feature
    // Compose components (ErrorDialog, BaseDialog, XrConfirmDialog,
    // FinishSetupCard), and the presentation utils (safe padding, parallax,
    // offline-mode CompositionLocal, adaptive grid cells). Depends on :core for
    // CoreR and the base color/spacing tokens it builds on.
    implementation(projects.core)
    // SpatialFin* domain models (SpatialFinItem/Episode/Season/Person) that the
    // shared browse primitives render. They live in :data (same as :core uses).
    implementation(projects.data)
    // Shared on-device LLM management cards (presentation.ai) render the
    // download-manager + preference state defined in :settings.
    implementation(projects.settings)

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

    // AsyncImage for the shared browse primitives (ItemCard, ItemPoster, …).
    implementation(libs.coil.compose)

    // StateFlow appears on the CastButtonController seam's API surface.
    implementation(libs.kotlinx.coroutines.core)

    implementation(libs.timber)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
