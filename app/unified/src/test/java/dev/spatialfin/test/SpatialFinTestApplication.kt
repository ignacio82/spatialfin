package dev.spatialfin.test

import android.app.Application

/**
 * Minimal Robolectric stand-in for [dev.spatialfin.unified.UnifiedApplication]. Tests that
 * don't need Hilt should pin this via `@Config(application = SpatialFinTestApplication::class)`
 * so Robolectric instantiates a plain [Application] instead of the `@HiltAndroidApp`-annotated
 * `UnifiedApplication`.
 *
 * Why this exists: instantiating `UnifiedApplication` triggers `Hilt_UnifiedApplication.onCreate`,
 * which eagerly resolves every `@Inject` field on the Application. One of those (transitively)
 * builds a `JellyfinApi`, which calls the Jellyfin SDK's `androidDevice(context)`. That helper
 * read-and-force-unwraps `Settings.Secure.ANDROID_ID`. In Robolectric `ANDROID_ID` is null by
 * default, so the unwrap throws NPE before the test body ever runs.
 *
 * Seeding ANDROID_ID in `@Before` is too late — the Application is constructed by the
 * Robolectric runner *before* `@Before` fires. Swapping in a stub Application sidesteps the
 * problem entirely without compromising what the test actually exercises (SharedPreferences
 * round-trips, in this case).
 *
 * Future Hilt-using tests should not use this stub; they should use `@HiltAndroidTest` +
 * `HiltAndroidRule` and either swap `JellyfinApi` with a fake via a test module, OR seed
 * `ShadowSettings.Secure` in a custom test runner.
 */
class SpatialFinTestApplication : Application()
