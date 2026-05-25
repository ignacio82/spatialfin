# XR system-extension callbacks cross a vendor bridge that still crashes in
# optimized alpha15 builds (see GEMINI.md "Build Quirks" / "R8 / ProGuard").
# Retain this platform type coverage for future minification retries, which
# must pass on-device Galaxy XR startup before release optimization is enabled.
-keep class com.android.extensions.xr.** { *; }
-keep interface com.android.extensions.xr.** { *; }
