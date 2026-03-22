# Hilt/Dagger rules
-keepattributes *Annotation*
-keepattributes Signature

# WorkManager
-keep class androidx.work.WorkerParameters { *; }
-keep class androidx.work.Worker { *; }

# Coil
-keep class coil3.** { *; }
-dontwarn coil3.**

# Timber
-dontwarn timber.log.**
-keep class timber.log.Timber { *; }
