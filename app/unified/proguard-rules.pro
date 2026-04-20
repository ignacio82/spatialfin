# XR system-extension callbacks cross a vendor bridge that R8 cannot safely
# optimize. Keeping the full surface prevents AbstractMethodError at runtime
# (see GEMINI.md "Build Quirks" / "R8 / ProGuard"). These rules must stay even
# while isMinifyEnabled = false so that flipping minify back on (e.g. for a TV
# release-only minified build) does not silently break XR.
-keep class com.android.extensions.xr.** { *; }
-keep interface com.android.extensions.xr.** { *; }
