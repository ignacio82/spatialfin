import org.gradle.api.JavaVersion

// SDK / toolchain constants used by the convention plugins.
//
// This is a deliberate, minimal mirror of the SDK fields in
// buildSrc/src/main/kotlin/Versions.kt. Versions.kt has to stay in buildSrc so
// the application/module build scripts can keep reading Versions.APP_CODE,
// Versions.TARGET_SDK, etc. directly; an included build's classes are not on the
// consuming scripts' classpath, so the few values the convention plugins need
// are duplicated here. Keep the two in sync (they change ~once per SDK bump).
object SpatialfinSdk {
    const val COMPILE_SDK = 37
    const val MIN_SDK = 31
    const val BUILD_TOOLS = "37.0.0"

    val JAVA: JavaVersion = JavaVersion.VERSION_21
}
