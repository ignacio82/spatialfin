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
