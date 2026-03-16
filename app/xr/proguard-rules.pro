# Android XR SDK extensions are provided at runtime by the XR environment
-dontwarn com.android.extensions.xr.**

# Hilt/Dagger rules (usually handled by the plugin, but adding explicitly if needed)
-keepattributes *Annotation*
-keepattributes Signature

# WorkManager
-keep class androidx.work.WorkerParameters { *; }
-keep class androidx.work.Worker { *; }

# Coil
-keep class coil3.** { *; }
-dontwarn coil3.**

# Kotlin Serialization
-keepattributes *Annotation*, InnerClasses
-keep class kotlinx.serialization.json.** { *; }

# Timber
-dontwarn timber.log.**
-keep class timber.log.Timber { *; }
