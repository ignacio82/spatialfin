# Wear OS Proguard rules
-keep class dev.spatialfin.companion.wear.** { *; }
-keep class dev.spatialfin.companion.protocol.** { *; }

# Media3 keep rules
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Protobuf / ProtoLayout keep rules
-keep class androidx.wear.protolayout.** { *; }
-keep class androidx.wear.tiles.** { *; }
