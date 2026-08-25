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


# Media3 FFmpeg audio extension.
# DefaultRenderersFactory instantiates the extension renderers via
# Class.forName("androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer") and swallows the
# ClassNotFoundException, so a renamed class fails *silently*: no crash, no log, just no
# AC-3 / E-AC-3 / TrueHD / DTS software decoding in release builds. Media3's own consumer
# rule is -keepclassmembers, which preserves the constructor but still lets R8 rename the
# class (verified: it became androidx.media3.decoder.ffmpeg.b), so the name has to be
# pinned here. Narrow on purpose — the class and its one reflective constructor, nothing
# else; the decoder implementation behind it stays fully optimizable.
-keep class androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer {
    <init>(android.os.Handler, androidx.media3.exoplayer.audio.AudioRendererEventListener, androidx.media3.exoplayer.audio.AudioSink);
}
