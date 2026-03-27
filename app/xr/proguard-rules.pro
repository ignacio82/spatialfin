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

# smbj (Samba)
-keep class com.hierynomus.smbj.** { *; }
-keep class com.hierynomus.mssmb2.** { *; }
-keep class com.hierynomus.msdtyp.** { *; }
-keep class com.hierynomus.msfscc.** { *; }
-keep class com.hierynomus.protocol.commons.** { *; }
-dontwarn com.hierynomus.smbj.**
-dontwarn com.hierynomus.protocol.commons.**

# nfs4j (NFS)
-keep class org.dcache.nfs.** { *; }
-dontwarn org.dcache.nfs.**

# BouncyCastle (used by smbj for some auth types)
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# ASN.1 (dependency of smbj)
-keep class com.beanit.jasn1.** { *; }
-dontwarn com.beanit.jasn1.**

