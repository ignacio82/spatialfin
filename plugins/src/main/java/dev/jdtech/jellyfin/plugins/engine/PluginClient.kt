package dev.jdtech.jellyfin.plugins.engine

import dev.jdtech.jellyfin.plugins.repository.PluginRepository
import dev.jdtech.jellyfin.plugins.model.PluginConfig
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PluginClient @Inject constructor(
    private val engine: PluginEngine,
    private val repository: PluginRepository
) {
    private val json = Json { encodeDefaults = true }

    /**
     * Initializes and executes a plugin's script.
     */
    suspend fun runPlugin(pluginId: String): Result<PluginRuntime> {
        Timber.d("Running plugin: %s", pluginId)
        val rawScript = repository.getPluginScript(pluginId) ?: return Result.failure(Exception("Plugin script not found"))

        val script = rawScript

        val manifest = repository.getInstalledPlugins().find { it.id == pluginId }

        var runtime: PluginRuntime? = null
        return try {
            val created = engine.createRuntime(
                userScope = repository.currentScope(),
                pluginId = pluginId,
            )
            runtime = created
            created.evaluate(script)
            Timber.d("Script evaluated for %s", pluginId)

            // Call source.enable if it exists!
            val configJson = if (manifest != null) {
                json.encodeToString(manifest)
            } else "{}"

            val settingsJson = json.encodeToString(buildSettings(pluginId, manifest))

            created.evaluate("""
                try {
                    if(typeof source.enable === 'function') {
                        source.enable($configJson, $settingsJson, '');
                    }
                } catch(e) {
                    bridge.toast("Enable failed: " + e.message);
                    console.log("SOURCE_ENABLE_ERROR: " + e.message + "\n" + e.stack);
                }
            """.trimIndent())

            Result.success(created)
        } catch (e: kotlinx.coroutines.CancellationException) {
            runCatching { runtime?.close() }
            throw e
        } catch (e: Exception) {
            runCatching { runtime?.close() }
            Timber.d(e, "Exception during plugin execution for %s", pluginId)
            Result.failure(e)
        }
    }

    private fun buildSettings(pluginId: String, manifest: PluginConfig?): JsonObject {
        if (manifest == null) return JsonObject(emptyMap())
        val savedSettings = repository.getPluginSettings(pluginId)

        return buildJsonObject {
            savedSettings.forEach { (key, value) ->
                put(key, JsonPrimitive(value))
            }
            manifest.settings.forEach { setting ->
                val key = setting.variable ?: return@forEach
                val value = savedSettings[key] ?: setting.default
                when (setting.type?.lowercase()) {
                    "boolean" -> put(key, JsonPrimitive(value.toBooleanOrFalse()))
                    "integer", "int" -> put(key, JsonPrimitive(value?.toIntOrNull() ?: 0))
                    "number", "float", "double" -> put(key, JsonPrimitive(value?.toDoubleOrNull() ?: 0.0))
                    else -> put(key, JsonPrimitive(value ?: ""))
                }
            }
        }
    }

    private fun String?.toBooleanOrFalse(): Boolean =
        this?.equals("true", ignoreCase = true) == true
}
