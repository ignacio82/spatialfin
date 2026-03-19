# Android XR SDK extensions - provided at runtime by the XR environment
# We MUST keep the classes and their members to prevent R8 from misoptimizing the shim calls,
# which leads to "java.lang.AbstractMethodError: abstract method 'void com.android.extensions.xr.function.Consumer.accept(java.lang.Object)'"
-keep class com.android.extensions.xr.** { *; }
-keep interface com.android.extensions.xr.** { *; }
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
