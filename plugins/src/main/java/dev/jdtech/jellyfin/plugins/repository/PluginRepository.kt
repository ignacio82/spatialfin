package dev.jdtech.jellyfin.plugins.repository

import android.content.Context
import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.plugins.model.PluginConfig
import dev.jdtech.jellyfin.plugins.model.PluginHomeRow
import dev.jdtech.jellyfin.session.ActiveSessionBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PluginRepository @Inject constructor(
    private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val jellyfinApi: JellyfinApi,
    private val activeSessionBus: ActiveSessionBus,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        coerceInputValues = true
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _settingsChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val settingsChanges = _settingsChanges.asSharedFlow()

    init {
        // A user switch re-scopes the plugin directory; re-emit so observers
        // (PluginSettingsViewModel / HomeViewModel) reload the new user's set.
        activeSessionBus.events
            .onEach { _settingsChanges.tryEmit(Unit) }
            .launchIn(scope)
    }

    private fun rootDir(): File = File(context.filesDir, "universal_plugins")

    /** Per-Jellyfin-user scope; falls back to "default" before any user logs in. */
    private fun currentScope(): String = jellyfinApi.userId?.toString() ?: "default"

    /**
     * The plugin directory for [scope] (defaults to the current Jellyfin user).
     * Each user keeps an independent installed-plugin set + settings under
     * `universal_plugins/<scope>/`. An explicit [scope] lets the companion-sync
     * worker target a user that isn't the live session user yet.
     */
    private fun pluginsDir(scope: String = currentScope()): File {
        val scoped = File(rootDir(), scope)
        if (!scoped.exists()) scoped.mkdirs()
        migrateLegacyLayoutIfNeeded(scoped)
        return scoped
    }

    /**
     * One-time, non-destructive migration of the old device-global layout
     * (`universal_plugins/<pluginId>/`) into the current user's scope. Legacy
     * plugin dirs are distinguished from scope dirs by a top-level manifest.json.
     * Guarded by a `.migrated` marker so it runs at most once.
     */
    @Synchronized
    private fun migrateLegacyLayoutIfNeeded(targetScope: File) {
        val root = rootDir()
        val marker = File(root, ".migrated")
        if (marker.exists()) return
        val legacyPluginDirs = root.listFiles()
            ?.filter { it.isDirectory && File(it, "manifest.json").exists() }
            .orEmpty()
        legacyPluginDirs.forEach { legacy ->
            val dest = File(targetScope, legacy.name)
            if (!dest.exists()) {
                runCatching {
                    legacy.copyRecursively(dest, overwrite = false)
                    legacy.deleteRecursively()
                }.onFailure { Timber.e(it, "Failed to migrate legacy plugin ${legacy.name}") }
            }
        }
        runCatching { marker.createNewFile() }
    }

    /**
     * Downloads and installs a plugin from a manifest URL. When [scopeOverride]
     * is set the plugin is installed into that user's scope instead of the live
     * session user's (used by the companion-sync worker).
     */
    suspend fun installPlugin(manifestUrl: String, scopeOverride: String? = null): Result<PluginConfig> {
        return withContext(Dispatchers.IO) {
            try {
                // Capture the scope once so a user switch mid-install can't split
                // this plugin's files across two user directories.
                val targetDir = pluginsDir(scopeOverride ?: currentScope())
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
                
                val pluginDir = File(targetDir, pluginId)
                pluginDir.mkdirs()
                
                File(pluginDir, "manifest.json").writeText(manifestJson)
                File(pluginDir, "script.js").writeText(scriptContent)
                recordInstalledManifestUrl(targetDir, manifestUrl)

                android.util.Log.e("CRITICAL_ERROR", "Step 6: Plugin files saved")
                Result.success(config)
            } catch (e: Exception) {
                android.util.Log.e("CRITICAL_ERROR", "Installation failed FATALLY", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Manifest URLs already installed in [scopeOverride] (or the current user).
     * Lets the companion-sync worker skip re-downloading plugins every sync.
     */
    fun installedManifestUrls(scopeOverride: String? = null): Set<String> {
        val file = File(pluginsDir(scopeOverride ?: currentScope()), INSTALLED_MANIFESTS_FILE)
        if (!file.exists()) return emptySet()
        return runCatching { json.decodeFromString<Set<String>>(file.readText()) }.getOrElse { emptySet() }
    }

    private fun recordInstalledManifestUrl(scopeDir: File, manifestUrl: String) {
        val file = File(scopeDir, INSTALLED_MANIFESTS_FILE)
        val current = runCatching {
            if (file.exists()) json.decodeFromString<Set<String>>(file.readText()) else emptySet()
        }.getOrElse { emptySet() }
        if (manifestUrl in current) return
        runCatching { file.writeText(json.encodeToString(current + manifestUrl)) }
            .onFailure { Timber.e(it, "Failed to record installed manifest URL") }
    }

    fun getInstalledPlugins(): List<PluginConfig> {
        return pluginsDir().listFiles()?.mapNotNull { dir ->
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
        val pluginDir = File(pluginsDir(), pluginId)
        return if (pluginDir.exists()) {
            pluginDir.deleteRecursively()
        } else false
    }

    fun getPluginScript(pluginId: String?): String? {
        if (pluginId == null) return null
        val scriptFile = File(pluginsDir(), "$pluginId/script.js")
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
        val settingsFile = File(pluginsDir(), "$pluginId/settings.json")
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
        val pluginDir = File(pluginsDir(), pluginId)
        pluginDir.mkdirs()
        val settings = getPluginSettings(pluginId).toMutableMap()
        settings[key] = value
        File(pluginDir, "settings.json").writeText(json.encodeToString(settings))
        _settingsChanges.tryEmit(Unit)
    }

    fun isPluginHomeRowEnabled(pluginId: String, rowId: String, defaultEnabled: Boolean = true): Boolean {
        val key = homeRowEnabledKey(rowId)
        return getPluginSettings(pluginId)[key]?.toBooleanStrictOrNull() ?: defaultEnabled
    }

    fun updatePluginHomeRowEnabled(pluginId: String, rowId: String, enabled: Boolean) {
        updatePluginSetting(pluginId, homeRowEnabledKey(rowId), enabled.toString())
    }

    fun homeRowEnabledKey(rowId: String): String = "homeRow.$rowId.enabled"

    fun getPluginHomeRows(plugin: PluginConfig): List<PluginHomeRow> {
        val pluginId = plugin.id
        val builtInRows = plugin.homeRows.ifEmpty {
            listOf(PluginHomeRow(id = "home", name = plugin.name ?: "Home", type = "home"))
        }
        if (pluginId == null) return builtInRows
        return builtInRows + getCustomPluginHomeRows(pluginId)
    }

    fun getCustomPluginHomeRows(pluginId: String): List<PluginHomeRow> {
        val raw = getPluginSettings(pluginId)[CUSTOM_HOME_ROWS_KEY].orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            json.decodeFromString<List<PluginHomeRow>>(raw)
        }.getOrElse {
            android.util.Log.e("PluginRepository", "Failed to parse custom home rows for $pluginId", it)
            emptyList()
        }
    }

    fun addCustomPluginHomeRow(
        pluginId: String,
        templateId: String,
        name: String,
        options: Map<String, String>
    ): Boolean {
        val plugin = getInstalledPlugins().find { it.id == pluginId } ?: return false
        val template = plugin.homeRowTemplates.find { it.id == templateId } ?: return false
        val trimmedName = name.trim().ifBlank {
            val primaryValue = template.fields.firstOrNull()?.id?.let { options[it] }.orEmpty()
            listOfNotNull(template.namePrefix, primaryValue.ifBlank { template.name }).joinToString("").trim()
        }
        val row = PluginHomeRow(
            id = "custom_${template.id}_${System.currentTimeMillis()}",
            name = trimmedName,
            description = template.description,
            type = template.type,
            defaultEnabled = true,
            options = JsonObject(options.mapValues { JsonPrimitive(it.value) })
        )
        val rows = getCustomPluginHomeRows(pluginId) + row
        updatePluginSetting(pluginId, CUSTOM_HOME_ROWS_KEY, json.encodeToString(rows))
        return true
    }

    fun deleteCustomPluginHomeRow(pluginId: String, rowId: String) {
        val rows = getCustomPluginHomeRows(pluginId).filterNot { it.id == rowId }
        updatePluginSetting(pluginId, CUSTOM_HOME_ROWS_KEY, json.encodeToString(rows))
    }

    fun isCustomHomeRow(rowId: String): Boolean = rowId.startsWith("custom_")

    companion object {
        private const val CUSTOM_HOME_ROWS_KEY = "homeRows.custom"
        private const val INSTALLED_MANIFESTS_FILE = "installed_manifests.json"
    }

    private fun downloadString(url: String): String {
        val request = Request.Builder().url(url).build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Unexpected code $response")
            return response.body?.string() ?: throw IOException("Empty body")
        }
    }
}
