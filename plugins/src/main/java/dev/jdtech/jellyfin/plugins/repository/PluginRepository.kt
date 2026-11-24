package dev.jdtech.jellyfin.plugins.repository

import android.app.Application
import android.content.Context
import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.network.UrlTargetPolicy
import dev.jdtech.jellyfin.network.newBuilderWithSsrfGuard
import dev.jdtech.jellyfin.plugins.model.PluginConfig
import dev.jdtech.jellyfin.plugins.model.PluginHomeRow
import dev.jdtech.jellyfin.session.ActiveSessionBus
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber

@Singleton
class PluginRepository @Inject constructor(
    private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val jellyfinApi: JellyfinApi,
    private val activeSessionBus: ActiveSessionBus,
    private val appPreferences: AppPreferences,
    private val database: ServerDatabaseDao,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        coerceInputValues = true
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val fsLock = Any()

    internal var isMainProcessProvider: () -> Boolean = {
        runCatching { Application.getProcessName() == context.packageName }.getOrDefault(true)
    }

    private val strictHttpsClient: OkHttpClient by lazy {
        okHttpClient
            .newBuilderWithSsrfGuard(
                policy = UrlTargetPolicy.PUBLIC_OR_LAN,
                allowCrossOriginRedirects = false,
            )
            .build()
    }

    private val _settingsChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val settingsChanges = _settingsChanges.asSharedFlow()

    init {
        activeSessionBus.events
            .onEach { _settingsChanges.tryEmit(Unit) }
            .launchIn(scope)
    }

    fun notifySettingsChanged() {
        _settingsChanges.tryEmit(Unit)
    }

    private fun rootDir(): File = File(context.filesDir, "universal_plugins")

    /**
     * Read-only per-Jellyfin-user scope. Never mutates [jellyfinApi.userId].
     * Defaults to `"default"` whenever there is no authenticated user token.
     */
    internal fun currentScope(): String {
        jellyfinApi.userId?.toString()?.takeIf { SAFE_ID_REGEX.matches(it) }?.let { return it }
        val serverId = runCatching { appPreferences.getValue(appPreferences.currentServer) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() } ?: return "default"
        val dbUser = runCatching {
            runBlocking(Dispatchers.IO) {
                database.getServerCurrentUser(serverId)
            }
        }.getOrNull()
        val dbToken = dbUser?.accessToken
        val dbUserId = dbUser?.id?.toString()?.takeIf { SAFE_ID_REGEX.matches(it) }
        if (dbUserId != null && !dbToken.isNullOrBlank()) {
            return dbUserId
        }
        return "default"
    }

    /**
     * Migrates installed plugin directories and settings from [oldUserId] to [newUserId]
     * during legacy `srv-*` / user UUID remapping (Step `A1c`).
     */
    fun migrateUserScope(oldUserId: String, newUserId: String) {
        val validOld = runCatching { validateScope(oldUserId) }.getOrNull() ?: return
        val validNew = runCatching { validateScope(newUserId) }.getOrNull() ?: return
        if (validOld == validNew) return
        val changed = synchronized(fsLock) {
            val root = rootDir().canonicalFile
            val oldDir = File(root, validOld).canonicalFile
            val newDir = File(root, validNew).canonicalFile
            if (oldDir.parentFile != root || newDir.parentFile != root) return@synchronized false
            if (!oldDir.exists() || !oldDir.isDirectory) return@synchronized false
            if (!newDir.exists()) newDir.mkdirs()
            oldDir.listFiles()?.forEach { child ->
                val target = File(newDir, child.name)
                if (!target.exists()) {
                    runCatching { child.copyRecursively(target, overwrite = false) }
                        .onFailure { Timber.w(it, "PluginRepository: failed migrating %s", child.name) }
                }
            }
            oldDir.deleteRecursively()
            true
        }
        if (changed) {
            notifySettingsChanged()
        }
    }

    /**
     * Removes all installed plugin files and settings scoped to [userId].
     */
    fun clearUserScope(userId: String) {
        val validScope = runCatching { validateScope(userId) }.getOrNull() ?: return
        val deleted = synchronized(fsLock) {
            val root = rootDir().canonicalFile
            val scopeDir = File(root, validScope).canonicalFile
            if (scopeDir.parentFile != root) return@synchronized false
            if (scopeDir.exists()) scopeDir.deleteRecursively() else false
        }
        if (deleted) {
            notifySettingsChanged()
        }
    }

    private fun validateScope(scope: String): String {
        val trimmed = scope.trim()
        require(SAFE_ID_REGEX.matches(trimmed) && !trimmed.contains("..")) {
            "Invalid plugin scope: $scope"
        }
        return trimmed
    }

    internal fun validatePluginId(pluginId: String?): String {
        val trimmed = pluginId?.trim().orEmpty()
        require(trimmed.isNotEmpty() && SAFE_ID_REGEX.matches(trimmed) && !trimmed.contains("..")) {
            "Invalid plugin ID: $pluginId"
        }
        return trimmed
    }

    internal fun resolvePluginDir(scopeDir: File, pluginId: String): File {
        val validId = validatePluginId(pluginId)
        val canonicalScope = scopeDir.canonicalFile
        val pluginDir = File(canonicalScope, validId).canonicalFile
        require(pluginDir.parentFile == canonicalScope) {
            "Plugin path escapes scope directory: $validId"
        }
        return pluginDir
    }

    internal fun pluginsDir(scope: String = currentScope()): File {
        val validScope = validateScope(scope)
        val root = rootDir().canonicalFile
        val scoped = File(root, validScope).canonicalFile
        require(scoped.parentFile == root) { "Scope directory escapes plugin root" }
        if (isMainProcessProvider.invoke()) {
            synchronized(fsLock) {
                if (!scoped.exists()) scoped.mkdirs()
                migrateLegacyLayoutIfNeededLocked(scoped)
            }
        }
        return scoped
    }

    private fun migrateLegacyLayoutIfNeededLocked(targetScope: File) {
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
                }.onFailure { Timber.w(it, "Failed to migrate legacy plugin %s", legacy.name) }
            }
        }
        runCatching { marker.createNewFile() }
    }

    /**
     * Downloads and installs a plugin from a manifest URL. When [requireHttps] is true,
     * both manifest and resolved script URLs must use HTTPS, redirects are restricted to
     * same-origin, and existing plugins cannot be overwritten from a different or ambiguous origin.
     */
    suspend fun installPlugin(
        manifestUrl: String,
        scopeOverride: String? = null,
        requireHttps: Boolean = false,
    ): Result<PluginConfig> {
        return withContext(Dispatchers.IO) {
            try {
                val targetScope = validateScope(scopeOverride ?: currentScope())
                val targetDir = pluginsDir(targetScope)

                val parsedManifestUrl = manifestUrl.trim().toHttpUrlOrNull()
                    ?: throw IOException("Invalid plugin manifest URL")
                if (requireHttps && !parsedManifestUrl.isHttps) {
                    Timber.w("pluginBlockedInsecureOrigin manifestUrl=%s", manifestUrl.substringBefore('?'))
                    throw SecurityException("Plugin manifest URL must use HTTPS")
                }

                Timber.d("Downloading plugin manifest from %s", manifestUrl.substringBefore('?'))
                val (manifestJson, finalManifestUrl) = downloadString(parsedManifestUrl, requireHttps)
                if (requireHttps && !finalManifestUrl.isHttps) {
                    Timber.w("pluginBlockedInsecureOrigin finalManifestUrl=%s", finalManifestUrl.toString().substringBefore('?'))
                    throw SecurityException("Plugin manifest redirected to non-HTTPS URL")
                }
                val manifestOrigin = "${finalManifestUrl.scheme}://${finalManifestUrl.host}:${finalManifestUrl.port}"

                val config = json.decodeFromString<PluginConfig>(manifestJson)
                val pluginId = validatePluginId(config.id)
                val scriptUrlStr = config.scriptUrl?.trim()?.takeIf { it.isNotEmpty() }
                    ?: throw IOException("Missing scriptUrl in manifest")

                val resolvedScriptUrl = finalManifestUrl.resolve(scriptUrlStr)
                    ?: scriptUrlStr.toHttpUrlOrNull()
                    ?: throw IOException("Invalid scriptUrl in manifest")

                if (requireHttps && !resolvedScriptUrl.isHttps) {
                    Timber.w(
                        "pluginBlockedInsecureOrigin pluginId=%s scriptUrl=%s",
                        pluginId,
                        resolvedScriptUrl.toString().substringBefore('?'),
                    )
                    throw SecurityException("Plugin scriptUrl must use HTTPS")
                }

                Timber.d("Downloading plugin script for %s from %s", pluginId, resolvedScriptUrl.toString().substringBefore('?'))
                val (scriptContent, finalScriptUrl) = downloadString(resolvedScriptUrl, requireHttps)
                if (requireHttps && !finalScriptUrl.isHttps) {
                    Timber.w(
                        "pluginBlockedInsecureOrigin pluginId=%s finalScriptUrl=%s",
                        pluginId,
                        finalScriptUrl.toString().substringBefore('?'),
                    )
                    throw SecurityException("Plugin script redirected to non-HTTPS URL")
                }

                synchronized(fsLock) {
                    if (!targetDir.exists()) targetDir.mkdirs()
                    val pluginDir = resolvePluginDir(targetDir, pluginId)
                    val existingManifest = File(pluginDir, "manifest.json")
                    val originFile = File(pluginDir, MANIFEST_ORIGIN_FILE)
                    if (requireHttps && existingManifest.exists()) {
                        val existingOrigin = if (originFile.exists()) {
                            runCatching { originFile.readText().trim() }.getOrNull().orEmpty()
                        } else {
                            ""
                        }
                        if (existingOrigin.isEmpty() || existingOrigin != manifestOrigin) {
                            Timber.w(
                                "pluginBlockedInsecureOrigin pluginId=%s existingOrigin=%s newOrigin=%s",
                                pluginId,
                                existingOrigin.ifEmpty { "<missing>" },
                                manifestOrigin,
                            )
                            throw SecurityException(
                                "Cannot overwrite existing plugin '$pluginId' from mismatched or unverified origin",
                            )
                        }
                    }

                    pluginDir.mkdirs()
                    File(pluginDir, "manifest.json").writeText(manifestJson)
                    File(pluginDir, "script.js").writeText(scriptContent)
                    originFile.writeText(manifestOrigin)
                    recordInstalledManifestUrlLocked(targetDir, manifestUrl)
                }

                notifySettingsChanged()
                Timber.d("Plugin %s installed cleanly", pluginId)
                Result.success(config)
            } catch (e: Exception) {
                Timber.d(e, "Plugin installation failed for %s", manifestUrl.substringBefore('?'))
                Result.failure(e)
            }
        }
    }

    fun installedManifestUrls(scopeOverride: String? = null): Set<String> {
        val file = File(pluginsDir(scopeOverride ?: currentScope()), INSTALLED_MANIFESTS_FILE)
        if (!file.exists()) return emptySet()
        return runCatching { json.decodeFromString<Set<String>>(file.readText()) }.getOrElse { emptySet() }
    }

    private fun recordInstalledManifestUrlLocked(scopeDir: File, manifestUrl: String) {
        val file = File(scopeDir, INSTALLED_MANIFESTS_FILE)
        val current = runCatching {
            if (file.exists()) json.decodeFromString<Set<String>>(file.readText()) else emptySet()
        }.getOrElse { emptySet() }
        if (manifestUrl in current) return
        runCatching { file.writeText(json.encodeToString(current + manifestUrl)) }
            .onFailure { Timber.w(it, "Failed to record installed manifest URL") }
    }

    fun getInstalledPlugins(): List<PluginConfig> {
        return pluginsDir().listFiles()?.mapNotNull { dir ->
            val manifestFile = File(dir, "manifest.json")
            if (manifestFile.exists()) {
                try {
                    json.decodeFromString<PluginConfig>(manifestFile.readText())
                } catch (e: Exception) {
                    Timber.d(e, "Failed to parse cached manifest in %s", dir.name)
                    null
                }
            } else {
                Timber.d("Manifest missing in %s", dir.name)
                null
            }
        } ?: emptyList()
    }

    fun uninstallPlugin(pluginId: String): Boolean {
        val deleted = synchronized(fsLock) {
            val pluginDir = runCatching { resolvePluginDir(pluginsDir(), pluginId) }.getOrNull()
                ?: return false
            if (pluginDir.exists()) pluginDir.deleteRecursively() else false
        }
        if (deleted) {
            notifySettingsChanged()
        }
        return deleted
    }

    fun getPluginScript(pluginId: String?): String? {
        if (pluginId == null) return null
        val pluginDir = runCatching { resolvePluginDir(pluginsDir(), pluginId) }.getOrNull()
            ?: return null
        val scriptFile = File(pluginDir, "script.js")
        if (scriptFile.exists()) return scriptFile.readText()

        Timber.d("Plugin script missing for %s", pluginId)
        return null
    }

    fun getPluginSettings(pluginId: String?): Map<String, String> {
        if (pluginId == null) return emptyMap()
        val pluginDir = runCatching { resolvePluginDir(pluginsDir(), pluginId) }.getOrNull()
            ?: return emptyMap()
        val manifest = getInstalledPlugins().find { it.id == pluginId }
        val defaults = manifest?.settings
            ?.mapNotNull { setting ->
                val key = setting.variable ?: return@mapNotNull null
                key to (setting.default ?: "")
            }
            ?.toMap()
            ?: emptyMap()
        val settingsFile = File(pluginDir, "settings.json")
        if (!settingsFile.exists()) return defaults

        val saved = runCatching {
            json.decodeFromString<Map<String, String>>(settingsFile.readText())
        }.getOrElse {
            Timber.d(it, "Failed to parse settings for %s", pluginId)
            emptyMap()
        }
        return defaults + saved
    }

    fun updatePluginSetting(pluginId: String, key: String, value: String) {
        synchronized(fsLock) {
            val pluginDir = resolvePluginDir(pluginsDir(), pluginId)
            pluginDir.mkdirs()
            val settings = getPluginSettings(pluginId).toMutableMap()
            settings[key] = value
            File(pluginDir, "settings.json").writeText(json.encodeToString(settings))
        }
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
            Timber.d(it, "Failed to parse custom home rows for %s", pluginId)
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
        val rowId = "custom_${templateId}_${System.currentTimeMillis()}"
        val row = PluginHomeRow(
            id = rowId,
            name = trimmedName.ifBlank { template.name },
            type = template.type,
            defaultEnabled = true,
            options = JsonObject(options.mapValues { JsonPrimitive(it.value) }),
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

    private fun downloadString(url: HttpUrl, requireHttps: Boolean): Pair<String, HttpUrl> {
        val client = if (requireHttps) strictHttpsClient else okHttpClient
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Unexpected HTTP ${response.code}")
            }
            val contentLength = response.body.contentLength()
            if (contentLength > MAX_PLUGIN_DOWNLOAD_BYTES) {
                throw IOException("Plugin download exceeds $MAX_PLUGIN_DOWNLOAD_BYTES byte cap")
            }
            val bytes = response.body.byteStream().use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    total += n
                    if (total > MAX_PLUGIN_DOWNLOAD_BYTES) {
                        throw IOException("Plugin download exceeds $MAX_PLUGIN_DOWNLOAD_BYTES byte cap")
                    }
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
            val finalUrl = runCatching { response.request.url }.getOrDefault(url)
            return String(bytes, Charsets.UTF_8) to finalUrl
        }
    }

    companion object {
        private val SAFE_ID_REGEX = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
        private const val INSTALLED_MANIFESTS_FILE = ".installed_manifests.json"
        private const val MANIFEST_ORIGIN_FILE = ".manifest_origin"
        private const val CUSTOM_HOME_ROWS_KEY = "custom_home_rows"
        const val MAX_PLUGIN_DOWNLOAD_BYTES: Long = 2L * 1024L * 1024L
    }
}
