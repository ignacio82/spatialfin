import java.io.File
import java.util.Properties
import org.gradle.api.GradleException
import org.gradle.api.Project

/** Resolved release signing credentials shared by `:app:unified` and `:companion:wear`. */
data class SpatialfinReleaseSigning(
    val storeFile: File,
    val storePassword: String,
    val keyAlias: String,
    val keyPassword: String,
)

/**
 * Resolves release keystore settings from Gradle properties, root `local.properties`, or env vars.
 *
 * Relative `SPATIALFIN_KEYSTORE` paths always resolve from `rootProject` so multi-module apps
 * (`:app:unified` and `:companion:wear`) read the exact same keystore. When
 * `-PSPATIALFIN_REQUIRE_RELEASE_SIGNING=true` is passed, missing or non-existent signing config
 * fails configuration immediately instead of silently falling back to debug signing.
 */
fun resolveSpatialfinReleaseSigning(project: Project): SpatialfinReleaseSigning? {
    val localProperties = Properties()
    val localPropertiesFile = project.rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { localProperties.load(it) }
    }

    fun lookup(key: String): String? =
        (project.findProperty(key) as String?)?.takeIf { it.isNotBlank() }
            ?: localProperties.getProperty(key)?.takeIf { it.isNotBlank() }
            ?: System.getenv(key)?.takeIf { it.isNotBlank() }

    val requireReleaseSigning =
        (project.findProperty("SPATIALFIN_REQUIRE_RELEASE_SIGNING") as String?)
            ?.equals("true", ignoreCase = true) == true

    val storePath = lookup("SPATIALFIN_KEYSTORE")
    val resolvedStore =
        storePath?.let { raw ->
            val candidate = File(raw)
            if (candidate.isAbsolute) candidate else project.rootProject.file(raw)
        }

    if (requireReleaseSigning) {
        if (resolvedStore == null) {
            throw GradleException(
                "Release signing is required for ${project.path} " +
                    "(-PSPATIALFIN_REQUIRE_RELEASE_SIGNING=true), but SPATIALFIN_KEYSTORE is not set.",
            )
        }
        if (!resolvedStore.isFile) {
            throw GradleException(
                "Release signing is required for ${project.path} " +
                    "(-PSPATIALFIN_REQUIRE_RELEASE_SIGNING=true), but SPATIALFIN_KEYSTORE does not exist: ${resolvedStore.path}",
            )
        }
        val storePassword =
            lookup("SPATIALFIN_KEYSTORE_PASSWORD")
                ?: throw GradleException(
                    "Release signing is required for ${project.path} " +
                        "(-PSPATIALFIN_REQUIRE_RELEASE_SIGNING=true), but SPATIALFIN_KEYSTORE_PASSWORD is missing.",
                )
        val keyAlias =
            lookup("SPATIALFIN_KEY_ALIAS")
                ?: throw GradleException(
                    "Release signing is required for ${project.path} " +
                        "(-PSPATIALFIN_REQUIRE_RELEASE_SIGNING=true), but SPATIALFIN_KEY_ALIAS is missing.",
                )
        val keyPassword =
            lookup("SPATIALFIN_KEY_PASSWORD")
                ?: throw GradleException(
                    "Release signing is required for ${project.path} " +
                        "(-PSPATIALFIN_REQUIRE_RELEASE_SIGNING=true), but SPATIALFIN_KEY_PASSWORD is missing.",
                )
        return SpatialfinReleaseSigning(
            storeFile = resolvedStore,
            storePassword = storePassword,
            keyAlias = keyAlias,
            keyPassword = keyPassword,
        )
    }

    if (resolvedStore == null || !resolvedStore.isFile) {
        return null
    }

    return SpatialfinReleaseSigning(
        storeFile = resolvedStore,
        storePassword = lookup("SPATIALFIN_KEYSTORE_PASSWORD") ?: "android",
        keyAlias = lookup("SPATIALFIN_KEY_ALIAS") ?: "androiddebugkey",
        keyPassword = lookup("SPATIALFIN_KEY_PASSWORD") ?: "android",
    )
}
