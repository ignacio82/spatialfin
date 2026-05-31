package dev.jdtech.jellyfin.plugins.repository

import android.content.Context
import dev.jdtech.jellyfin.plugins.model.PluginConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PluginRepository @Inject constructor(
    private val context: Context,
    private val okHttpClient: OkHttpClient
) {
    private val json = Json { 
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        coerceInputValues = true
    }
    private val pluginsDir = File(context.filesDir, "universal_plugins")

    init {
        if (!pluginsDir.exists()) {
            pluginsDir.mkdirs()
        }
    }

    /**
     * Downloads and installs a plugin from a manifest URL.
     */
    suspend fun installPlugin(manifestUrl: String): Result<PluginConfig> {
        return withContext(Dispatchers.IO) {
            try {
                android.util.Log.e("CRITICAL_ERROR", "Step 1: Downloading manifest from $manifestUrl")
                val manifestJson = downloadString(manifestUrl)
                android.util.Log.e("CRITICAL_ERROR", "Step 2: Manifest downloaded, length: ${manifestJson.length}")
                
                val config = try {
                    json.decodeFromString<PluginConfig>(manifestJson)
                } catch (e: Exception) {
                    android.util.Log.e("CRITICAL_ERROR", "Step 2b: JSON parse failed", e)
                    throw e
                }
                
                android.util.Log.e("CRITICAL_ERROR", "Step 3: Config parsed, id: ${config.id}")
                
                val pluginId = config.id ?: "unknown"
                val scriptUrlStr = config.scriptUrl ?: throw Exception("Missing scriptUrl in manifest")
                
                // Resolve relative script URL
                val scriptUrl = try {
                    manifestUrl.toHttpUrl().resolve(scriptUrlStr)?.toString() ?: scriptUrlStr
                } catch (e: Exception) {
                    scriptUrlStr
                }

                android.util.Log.e("CRITICAL_ERROR", "Step 4: Downloading script from $scriptUrl")
                val scriptContent = downloadString(scriptUrl)
                android.util.Log.e("CRITICAL_ERROR", "Step 5: Script downloaded, length: ${scriptContent.length}")
                
                val pluginDir = File(pluginsDir, pluginId)
                pluginDir.mkdirs()
                
                File(pluginDir, "manifest.json").writeText(manifestJson)
                File(pluginDir, "script.js").writeText(scriptContent)
                
                android.util.Log.e("CRITICAL_ERROR", "Step 6: Plugin files saved")
                Result.success(config)
            } catch (e: Exception) {
                android.util.Log.e("CRITICAL_ERROR", "Installation failed FATALLY", e)
                Result.failure(e)
            }
        }
    }

    fun getInstalledPlugins(): List<PluginConfig> {
        return pluginsDir.listFiles()?.mapNotNull { dir ->
            val manifestFile = File(dir, "manifest.json")
            if (manifestFile.exists()) {
                try {
                    json.decodeFromString<PluginConfig>(manifestFile.readText())
                } catch (e: Exception) {
                    android.util.Log.e("CRITICAL_ERROR", "Failed to parse cached manifest in ${dir.name}", e)
                    null
                }
            } else {
                android.util.Log.e("CRITICAL_ERROR", "Manifest missing in ${dir.name}")
                null
            }
        } ?: emptyList()
    }

    fun uninstallPlugin(pluginId: String): Boolean {
        val pluginDir = File(pluginsDir, pluginId)
        return if (pluginDir.exists()) {
            pluginDir.deleteRecursively()
        } else false
    }

    fun getPluginScript(pluginId: String?): String? {
        if (pluginId == null) return null
        val scriptFile = File(pluginsDir, "$pluginId/script.js")
        if (scriptFile.exists()) return scriptFile.readText()

        android.util.Log.e("PluginRepository", "Plugin script missing for $pluginId")
        return null
    }

    fun getPluginSettings(pluginId: String?): Map<String, String> {
        if (pluginId == null) return emptyMap()
        val manifest = getInstalledPlugins().find { it.id == pluginId }
        val defaults = manifest?.settings
            ?.mapNotNull { setting ->
                val key = setting.variable ?: return@mapNotNull null
                key to (setting.default ?: "")
            }
            ?.toMap()
            ?: emptyMap()
        val settingsFile = File(pluginsDir, "$pluginId/settings.json")
        if (!settingsFile.exists()) return defaults

        val saved = runCatching {
            json.decodeFromString<Map<String, String>>(settingsFile.readText())
        }.getOrElse {
            android.util.Log.e("PluginRepository", "Failed to parse settings for $pluginId", it)
            emptyMap()
        }
        return defaults + saved
    }

    fun updatePluginSetting(pluginId: String, key: String, value: String) {
        val pluginDir = File(pluginsDir, pluginId)
        pluginDir.mkdirs()
        val settings = getPluginSettings(pluginId).toMutableMap()
        settings[key] = value
        File(pluginDir, "settings.json").writeText(json.encodeToString(settings))
    }

    private fun downloadString(url: String): String {
        val request = Request.Builder().url(url).build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Unexpected code $response")
            return response.body?.string() ?: throw IOException("Empty body")
        }
    }
}
