package dev.jdtech.jellyfin.plugins.engine

import dev.jdtech.jellyfin.plugins.repository.PluginRepository
import dev.jdtech.jellyfin.plugins.model.PluginConfig
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
        android.util.Log.e("PluginClient", "Running plugin: $pluginId")
        val rawScript = repository.getPluginScript(pluginId) ?: return Result.failure(Exception("Plugin script not found"))
        
        val script = rawScript
        
        val manifest = repository.getInstalledPlugins().find { it.id == pluginId }
        
        return try {
            val runtime = engine.createRuntime()
            runtime.evaluate(script)
            android.util.Log.e("PluginClient", "Script evaluated for $pluginId")
            
            // Call source.enable if it exists!
            val configJson = if (manifest != null) {
                json.encodeToString(manifest)
            } else "{}"
            
            val settingsJson = json.encodeToString(buildSettings(pluginId, manifest))
            
            runtime.evaluate("""
                try {
                    if(typeof source.enable === 'function') {
                        source.enable($configJson, $settingsJson, '');
                    }
                } catch(e) {
                    bridge.toast("Enable failed: " + e.message);
                    console.log("SOURCE_ENABLE_ERROR: " + e.message + "\n" + e.stack);
                }
            """.trimIndent())
            
            Result.success(runtime)
        } catch (e: Exception) {
            android.util.Log.e("PluginClient", "Exception during plugin execution", e)
            Result.failure(e)
        }
    }

    private fun buildSettings(pluginId: String, manifest: PluginConfig?): JsonObject {
        if (manifest == null) return JsonObject(emptyMap())
        val savedSettings = repository.getPluginSettings(pluginId)

        return buildJsonObject {
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
