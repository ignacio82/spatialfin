# XR system-extension callbacks cross a vendor bridge that still crashes in
# optimized alpha15 builds (see GEMINI.md "Build Quirks" / "R8 / ProGuard").
# Retain this platform type coverage for future minification retries, which
# must pass on-device Galaxy XR startup before release optimization is enabled.
-keep class com.android.extensions.xr.** { *; }

# Prevent Jetpack XR LifecycleOwners (e.g., StubProcessLifecycleOwner) from being
# prematurely garbage collected when R8 optimizes away fields that hold strong references.
-keep class androidx.xr.runtime.** { *; }
-keep class androidx.lifecycle.** { *; }

# ML Kit Barcode Scanning & Google Play Services Dynamite Loader
# Prevents R8 from stripping or mangling reflection / AIDL interfaces when
# communicating with unbundled Play Services barcode scanning models.
-keep class com.google.mlkit.** { *; }
-keep interface com.google.mlkit.** { *; }
-keep class com.google.android.gms.vision.** { *; }
-keep interface com.google.android.gms.vision.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_barcode.** { *; }
-keep interface com.google.android.gms.internal.mlkit_vision_barcode.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_barcode_bundled.** { *; }
-keep class com.google.android.gms.dynamic.** { *; }
-keep interface com.google.android.gms.dynamic.** { *; }
-keep class com.google.android.gms.dynamite.** { *; }
-keep interface com.google.android.gms.dynamite.** { *; }
-keep class com.google.android.gms.tasks.** { *; }
-keep interface com.google.android.gms.tasks.** { *; }
-keep class com.google.android.gms.common.** { *; }
-keep interface com.google.android.gms.common.** { *; }
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.**

# ZXing QR Code Core
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

